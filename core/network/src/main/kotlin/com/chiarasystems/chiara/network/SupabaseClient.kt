package com.chiarasystems.chiara.network

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Backend coordinates (BuildConfig). The anon key is public by design: RLS and the `clinical_rpc` functions protect data. */
data class SupabaseConfig(val url: String, val anonKey: String) {
    val baseUrl: String = url.trimEnd('/')
}

enum class CachePolicy {
    None,

    /** Store each successful response; serve the last one when offline. */
    FallbackToDisk,
}

data class Session(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch millis. */
    val expiresAtMillis: Long,
    val userId: String,
    val email: String?,
)

/** JWT claims injected by the custom access token hook (`app_metadata`). */
data class Claims(val clinicId: String? = null, val clinicRole: String? = null)

/**
 * Minimal Supabase client: GoTrue (email/password) + PostgREST RPC. Port of iOS `SupabaseClient`.
 *
 * Rules inherited from the backend:
 * - Everything goes through RPC; `authenticated` has no direct table access (only `profiles` / `clinics` at sign-in).
 * - The client never sends `clinic_id`; the server reads it from the JWT.
 * - Refresh tokens rotate on every use, so refreshes are serialized.
 */
class SupabaseClient(
    private val config: SupabaseConfig,
    private val secureStore: SecureStore,
    private val cache: ResponseCache,
    http: OkHttpClient = OkHttpClient(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val http: OkHttpClient = http.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS) // iOS: timeoutIntervalForRequest = 20
        .build()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> get() = _session.asStateFlow()

    private val _isServingCache = MutableStateFlow(false)

    /** True while the data on screen came from the offline cache. */
    val isServingCache: StateFlow<Boolean> get() = _isServingCache.asStateFlow()

    /** Called when the refresh token is rejected (the user must sign in again). */
    @Volatile var onSessionLost: (() -> Unit)? = null

    private val refreshLock = Mutex()

    // --- Auth (GoTrue) -------------------------------------------------------------------------------------------

    suspend fun signIn(email: String, password: String): Session {
        val body = JsonObject(mapOf("email" to JsonPrimitive(email), "password" to JsonPrimitive(password)))
        return adopt(authRequest("token?grant_type=password", body))
    }

    suspend fun restore(refreshToken: String): Session {
        val body = JsonObject(mapOf("refresh_token" to JsonPrimitive(refreshToken)))
        return adopt(authRequest("token?grant_type=refresh_token", body))
    }

    suspend fun recoverPassword(email: String) {
        authRequest("recover", JsonObject(mapOf("email" to JsonPrimitive(email))))
    }

    suspend fun signOut() {
        _session.value?.accessToken?.let { token ->
            val req = Request.Builder().url("${config.baseUrl}/auth/v1/logout")
                .post(ByteArray(0).toRequestBody())
                .header("apikey", config.anonKey)
                .header("Authorization", "Bearer $token")
                .build()
            runCatching { send(req).close() } // errors ignored, like iOS
        }
        clearLocalState()
    }

    fun clearLocalState() {
        _session.value = null
        _isServingCache.value = false
        cache.clear()
        secureStore.remove(SecureStore.REFRESH_TOKEN)
    }

    private suspend fun authRequest(path: String, body: JsonObject): String {
        val req = Request.Builder().url("${config.baseUrl}/auth/v1/$path")
            .post(body.toString().toRequestBody(JSON))
            .header("apikey", config.anonKey)
            .header("Content-Type", "application/json")
            .build()
        send(req).use { response ->
            val text = response.body?.string().orEmpty()
            val status = response.code
            if (status !in 200..299) {
                if (status == 400 || status == 401 || status == 422) throw ApiError.InvalidCredentials
                throw ApiError.Server(status, message(text) ?: "Error $status.")
            }
            return text
        }
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresIn: Double,
        val user: User,
    ) {
        @Serializable data class User(val id: String, val email: String? = null)
    }

    private fun adopt(text: String): Session {
        val token = runCatching { LENIENT.decodeFromString<TokenResponse>(text) }.getOrNull()
            ?: throw ApiError.Unreadable("sesión")
        val s = Session(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAtMillis = nowMillis() + (token.expiresIn * 1000).toLong(),
            userId = token.user.id,
            email = token.user.email,
        )
        _session.value = s
        // Only the refresh token is persisted. The access token stays in memory.
        secureStore.set(s.refreshToken, SecureStore.REFRESH_TOKEN)
        return s
    }

    /**
     * Serialized refresh: GoTrue rotates the refresh token on each use, so two concurrent refreshes would invalidate each
     * other and log the user out. [staleAccessToken] is the token the caller found stale: if the session already holds
     * a different one by the time the lock is free, somebody else refreshed and there is nothing to do.
     */
    private suspend fun refreshSession(staleAccessToken: String?) {
        refreshLock.withLock {
            val current = _session.value
            if (current != null && current.accessToken != staleAccessToken) return
            val refresh = current?.refreshToken ?: secureStore.get(SecureStore.REFRESH_TOKEN) ?: throw ApiError.NotSignedIn
            try {
                restore(refresh) // adopt() persists the rotated refresh token immediately
            } catch (e: ApiError.InvalidCredentials) {
                clearLocalState()
                onSessionLost?.invoke()
                throw ApiError.SessionExpired
            }
        }
    }

    /** JWT claims (`app_metadata.clinic_id` / `clinic_role`); the signature is not checked, they are informative only. */
    val claims: Claims
        get() {
            val token = _session.value?.accessToken ?: return Claims()
            val parts = token.split(".")
            if (parts.size < 2) return Claims()
            return runCatching {
                val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
                val meta = LENIENT.parseToJsonElement(payload).jsonObject["app_metadata"]?.jsonObject
                Claims(
                    clinicId = meta?.get("clinic_id")?.jsonPrimitive?.contentOrNull,
                    clinicRole = meta?.get("clinic_role")?.jsonPrimitive?.contentOrNull,
                )
            }.getOrDefault(Claims())
        }

    // --- PostgREST -----------------------------------------------------------------------------------------------

    /**
     * Calls a `security definer` RPC and returns the raw JSON body. Null params are sent as explicit `null`; keys are
     * sorted so the cache key is stable.
     */
    suspend fun rpc(name: String, params: Map<String, JsonElement?> = emptyMap(), cache: CachePolicy = CachePolicy.None): String {
        val body = JsonObject(params.toSortedMap().mapValues { it.value ?: JsonNull }).toString()
        val cacheKey = "${claims.clinicId ?: "-"}|$name|$body"
        try {
            val text = authorized("rest/v1/rpc/$name", "POST", body)
            if (cache == CachePolicy.FallbackToDisk) this.cache.store(text.toByteArray(Charsets.UTF_8), cacheKey)
            _isServingCache.value = false
            return text
        } catch (e: ApiError) {
            if (e.isConnectivity && cache == CachePolicy.FallbackToDisk) {
                this.cache.load(cacheKey)?.let {
                    _isServingCache.value = true
                    return String(it, Charsets.UTF_8)
                }
            }
            throw e
        }
    }

    /** Direct read of a table or view (only used for `profiles` / `clinics` at sign-in). */
    suspend fun get(path: String): String = authorized("rest/v1/$path", "GET", null)

    /** Decodes a response with the app's lenient JSON; any failure is "unreadable". */
    inline fun <reified T> decode(text: String): T =
        try { DEFAULT_JSON.decodeFromString<T>(text) } catch (e: Exception) {
            throw ApiError.Unreadable(T::class.simpleName ?: "?")
        }

    private suspend fun authorized(path: String, method: String, body: String?, retrying: Boolean = false): String {
        if (_session.value == null) throw ApiError.NotSignedIn
        _session.value?.let { s ->
            if (s.expiresAtMillis - nowMillis() < 60_000) refreshSession(s.accessToken)
        }
        val token = _session.value?.accessToken ?: throw ApiError.NotSignedIn

        val req = Request.Builder().url("${config.baseUrl}/$path")
            .method(method, body?.toRequestBody(JSON))
            .header("apikey", config.anonKey)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build()

        send(req).use { response ->
            val text = response.body?.string().orEmpty()
            val status = response.code
            if (status == 401 && !retrying) {
                refreshSession(token)
                return authorized(path, method, body, retrying = true)
            }
            if (status !in 200..299) {
                if (status == 401) { onSessionLost?.invoke(); throw ApiError.SessionExpired }
                throw ApiError.Server(status, message(text) ?: "Error $status.")
            }
            return text
        }
    }

    private suspend fun send(req: Request): Response = try {
        http.newCall(req).await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: UnknownHostException) { throw ApiError.Offline
    } catch (e: ConnectException) { throw ApiError.Offline
    } catch (e: NoRouteToHostException) { throw ApiError.Offline
    } catch (e: SocketTimeoutException) { throw ApiError.Offline // timeouts count as offline, like iOS
    } catch (e: java.io.InterruptedIOException) { throw ApiError.Offline
    } catch (e: SocketException) { throw ApiError.Offline
    } catch (e: IOException) { throw ApiError.Server(0, e.message ?: "I/O error")
    }

    /** PostgREST: `{"message": …}`; GoTrue: `{"msg": …}` / `{"error_description": …}`. */
    private fun message(text: String): String? = runCatching {
        val o = LENIENT.parseToJsonElement(text).jsonObject
        (o["message"] ?: o["msg"] ?: o["error_description"])?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    companion object {
        private val JSON = "application/json".toMediaType()
        private val LENIENT = Json { ignoreUnknownKeys = true }

        /** App-wide lenient decoding: unknown keys ignored, nulls coerced to defaults (like iOS's tolerant decoders). */
        @PublishedApi internal val DEFAULT_JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

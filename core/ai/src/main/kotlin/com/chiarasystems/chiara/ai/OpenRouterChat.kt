package com.chiarasystems.chiara.ai

import com.chiarasystems.chiara.network.ApiError
import com.chiarasystems.chiara.network.SupabaseClient
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed class ChatError(message: String) : Exception(message) {
    /** This user has no OpenRouter key or no LLM configured on the server. */
    data object NotAvailable : ChatError("not available")
    /** The provider answered with this HTTP status, after the retries and the fallback models. */
    data class Rejected(val status: Int) : ChatError("rejected $status")
    /** The answer could not be read, or had no choice in it. */
    data object Unreadable : ChatError("unreadable")
}

/**
 * The signed-in user's AI settings from the server (`get_my_ai_config`): their own OpenRouter key and, per role, the
 * provider's URL and models (first = primary, rest = fallbacks). Kept in memory only: the key never reaches the cache.
 */
@Serializable
data class UserAIConfig(val apiKey: String? = null, val roles: List<Role>? = null) {
    @Serializable
    data class Role(val role: String, val url: String, val models: List<String> = emptyList())

    fun role(name: String): Role? = roles?.firstOrNull { it.role == name }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        suspend fun load(client: SupabaseClient): UserAIConfig {
            val text = client.rpc("get_my_ai_config")
            // The server answers snake_case; accept both.
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw ApiError.Unreadable("UserAIConfig")
            val key = (obj["apiKey"] ?: obj["api_key"])?.jsonPrimitive?.contentOrNull
            val roles = (obj["roles"] as? JsonArray)?.mapNotNull { r ->
                val o = r as? JsonObject ?: return@mapNotNull null
                Role(
                    role = o["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    models = (o["models"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                )
            }
            return UserAIConfig(key, roles)
        }
    }
}

/** One HTTP exchange; replaced in tests. */
class HttpReply(val status: Int, val body: ByteArray, val retryAfter: String? = null) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

fun interface HttpSender {
    /** Posts [body] as JSON with a bearer [key] to [url]; throws [SocketTimeoutException] when the call times out. */
    suspend fun post(url: String, key: String, body: String, timeoutSeconds: Long): HttpReply
}

class OkHttpSender(private val http: OkHttpClient = OkHttpClient()) : HttpSender {
    override suspend fun post(url: String, key: String, body: String, timeoutSeconds: Long): HttpReply =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val call = http.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).readTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
                .newCall(
                    Request.Builder().url(url).header("Authorization", "Bearer $key")
                        .post(body.toRequestBody("application/json".toMediaType())).build(),
                )
            call.execute().use { r -> HttpReply(r.code, r.body?.bytes() ?: ByteArray(0), r.header("Retry-After")) }
        }
}

/**
 * Chat completions through OpenRouter with the server's LLM role (`get_my_ai_config`), sent the way the desktop app sends
 * its report calls: same body, same retries, same fallback from one model to the next. The only addition is the privacy
 * request: zero data retention and no data collection.
 */
class OpenRouterChat(
    private val config: suspend () -> UserAIConfig,
    private val sender: HttpSender = OkHttpSender(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    data class Target(val url: String, val key: String, /** The primary model first, then its fallbacks. */ val models: List<String>)
    data class Completion(val text: String, /** The model that answered. */ val model: String)

    /** The URL, key and models of the `llm` role. */
    suspend fun target(): Target {
        val cfg = try { config() } catch (e: CancellationException) { throw e } catch (e: ApiError) {
            if (e.isConnectivity) throw e else throw ChatError.NotAvailable
        } catch (e: Exception) { throw ChatError.NotAvailable }
        val key = cfg.apiKey?.takeIf { it.isNotEmpty() } ?: throw ChatError.NotAvailable
        val role = cfg.role("llm")?.takeIf { it.models.isNotEmpty() } ?: throw ChatError.NotAvailable
        return Target(endpoint(role.url), key, role.models)
    }

    /** One completion: each model in turn, each retried while its answer is transient. */
    suspend fun complete(system: String, user: String, maxTokens: Int, target: Target): Completion {
        var key = target.key
        var reloadedKey = false
        var lastStatus = 0
        var index = 0
        while (index < target.models.size) {
            val model = target.models[index]
            val isLast = index == target.models.size - 1
            val body = body(model, system, user, maxTokens)
            var attempt = 0
            var moveOn = false
            while (attempt < attempts(isLast)) {
                currentCoroutineContext().ensureActive()
                val reply = try {
                    sender.post(target.url, key, body, 600)
                } catch (e: SocketTimeoutException) {
                    // A model too slow to answer: the next one may not be.
                    lastStatus = 408; moveOn = true; break
                }
                val status = reply.status
                if (status == 200) return completion(reply.text, model)
                lastStatus = status
                // The key may have been rotated since it was read: read it again once and repeat.
                if (status == 401 && !reloadedKey) {
                    reloadedKey = true
                    val fresh = runCatching { target() }.getOrNull()
                    if (fresh != null && fresh.key != key) { key = fresh.key; continue }
                }
                if (status in TRANSIENT && attempt + 1 < attempts(isLast)) {
                    attempt++
                    sleep(delayMillis(attempt, reply.retryAfter))
                    continue
                }
                moveOn = shouldTryNextModel(status)
                break
            }
            if (!moveOn || isLast) throw ChatError.Rejected(lastStatus)
            index++
        }
        throw ChatError.Rejected(lastStatus)
    }

    companion object {
        /** Rate limits and a provider momentarily down: worth the same request again (`is_transient_status`). */
        val TRANSIENT = setOf(408, 425, 429, 502, 503, 504)

        /** Answers that are the model's or provider's fault, so another model may work. 401/402/403 are the key's. */
        fun shouldTryNextModel(status: Int) = status in TRANSIENT || status in setOf(400, 404, 413, 422, 500)

        /** Attempts per model: two while a fallback remains, four on the last one. */
        fun attempts(isLast: Boolean) = if (isLast) 4 else 2

        /** Wait before retry [n] (1-based): the provider's `Retry-After` seconds if given, else 1, 2, 4… s, max 20 s. */
        fun delayMillis(n: Int, retryAfter: String?): Long {
            val seconds = retryAfter?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
            return (seconds?.coerceAtMost(20) ?: (1 shl maxOf(n - 1, 0)).coerceAtMost(20)) * 1000L
        }

        /** The base URL loses a trailing `/` and `/v1`, and gets `/v1/chat/completions`. */
        fun endpoint(base: String): String {
            var b = base.trim()
            while (b.endsWith("/")) b = b.dropLast(1)
            if (b.endsWith("/v1")) b = b.dropLast(3)
            while (b.endsWith("/")) b = b.dropLast(1)
            return "$b/v1/chat/completions"
        }

        fun body(model: String, system: String, user: String, maxTokens: Int): String = buildJsonObject {
            put("messages", JsonArray(listOf(
                buildJsonObject { put("role", "system"); put("content", system) },
                buildJsonObject { put("role", "user"); put("content", user) },
            )))
            put("temperature", 0.0)
            put("repeat_penalty", 1.1)
            put("max_tokens", maxTokens)
            put("chat_template_kwargs", buildJsonObject { put("enable_thinking", false) })
            put("usage", buildJsonObject { put("include", true) })
            put("reasoning", buildJsonObject { put("enabled", false) })
            put("model", model)
            put("provider", buildJsonObject { put("zdr", true); put("data_collection", "deny") })
        }.toString()

        /** The text of the first choice; a reply cut by `max_tokens` loses its unfinished last sentence. */
        fun completion(json: String, model: String): Completion {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: throw ChatError.Unreadable
            val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: throw ChatError.Unreadable
            var text = (choice["message"] as? JsonObject)?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "length") text = ReportText.trimUnfinishedTail(text)
            return Completion(text, root["model"]?.jsonPrimitive?.contentOrNull ?: model)
        }
    }
}

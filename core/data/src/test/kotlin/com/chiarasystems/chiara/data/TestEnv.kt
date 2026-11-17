package com.chiarasystems.chiara.data

import com.chiarasystems.chiara.network.FileResponseCache
import com.chiarasystems.chiara.network.InMemorySecureStore
import com.chiarasystems.chiara.network.SupabaseClient
import com.chiarasystems.chiara.network.SupabaseConfig
import java.io.File
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** A fake Supabase: routes by path, records every request (path + body), can be switched offline. */
class TestEnv {
    class Call(val path: String, val body: String)

    val server = MockWebServer()
    val store = InMemorySecureStore()
    val calls = CopyOnWriteArrayList<Call>()
    @Volatile var offline = false
    private val routes = mutableMapOf<String, (String) -> MockResponse>()
    private val cacheDir = File.createTempFile("cache", "").also { it.delete() }

    private fun jwt(clinic: String?) = "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(
        (if (clinic != null) """{"sub":"u1","app_metadata":{"clinic_id":"$clinic","clinic_role":"doctor"}}""" else """{"sub":"u1"}""")
            .toByteArray(),
    ) + ".s"

    fun tokenBody(clinic: String? = "clinic-1", n: Int = 1) =
        """{"access_token":"${jwt(clinic)}","refresh_token":"refresh-$n","expires_in":3600,"user":{"id":"u1","email":"doc@clinic.es"}}"""

    /** Route by path prefix; the first registered prefix that matches wins. */
    fun route(prefix: String, handler: (body: String) -> MockResponse) { routes[prefix] = handler }
    fun json(prefix: String, body: String, code: Int = 200) = route(prefix) { MockResponse().setResponseCode(code).setBody(body) }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                calls += Call(path, body)
                val handler = routes.entries.firstOrNull { path.startsWith(it.key) }?.value
                return handler?.invoke(body) ?: MockResponse().setResponseCode(404).setBody("""{"message":"no route $path"}""")
            }
        }
        server.start()
    }

    val client: SupabaseClient by lazy {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            if (offline) throw UnknownHostException("offline")
            chain.proceed(chain.request())
        }).build()
        SupabaseClient(SupabaseConfig(server.url("/").toString(), "anon"), store, FileResponseCache(cacheDir), http)
    }

    suspend fun signedIn(clinic: String? = "clinic-1"): SupabaseClient {
        json("/auth/v1/token", tokenBody(clinic))
        client.signIn("doc@clinic.es", "pw")
        return client
    }

    fun callsTo(prefix: String) = calls.filter { it.path.startsWith(prefix) }

    fun close() {
        server.shutdown()
        cacheDir.deleteRecursively()
    }
}

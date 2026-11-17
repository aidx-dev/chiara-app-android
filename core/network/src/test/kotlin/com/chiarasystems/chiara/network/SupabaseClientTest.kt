package com.chiarasystems.chiara.network

import java.io.File
import java.net.UnknownHostException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SupabaseClientTest {
    private lateinit var server: MockWebServer
    private val store = InMemorySecureStore()
    private lateinit var cacheDir: File
    private lateinit var cache: FileResponseCache
    @Volatile private var offline = false
    private var clock = 1_000_000L

    /** Unsigned JWT whose payload carries the clinic claims, like the demo backend. */
    private fun jwt(n: Int) = "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(
        """{"sub":"u1","n":$n,"app_metadata":{"clinic_id":"clinic-1","clinic_role":"doctor"}}""".toByteArray(),
    ) + ".s"

    private fun token(n: Int, expiresIn: Int = 3600) =
        """{"access_token":"${jwt(n)}","refresh_token":"refresh-$n","expires_in":$expiresIn,"user":{"id":"u1","email":"doc@clinic.es"}}"""

    private fun client(): SupabaseClient {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            if (offline) throw UnknownHostException("offline")
            chain.proceed(chain.request())
        }).build()
        return SupabaseClient(SupabaseConfig(server.url("/").toString(), "anon-key"), store, cache, http) { clock }
    }

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        cacheDir = File.createTempFile("cache", "").also { it.delete() }
        cache = FileResponseCache(cacheDir)
    }

    @After fun tearDown() {
        server.shutdown()
        cacheDir.deleteRecursively()
    }

    @Test fun signInAdoptsTheSessionAndPersistsOnlyTheRefreshToken() = runBlocking {
        server.enqueue(MockResponse().setBody(token(1)))
        val c = client()
        val s = c.signIn("doc@clinic.es", "secret")
        assertEquals("u1", s.userId)
        assertEquals("refresh-1", store.get(SecureStore.REFRESH_TOKEN))
        assertEquals(Claims("clinic-1", "doctor"), c.claims)
        val req = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=password", req.path)
        assertEquals("anon-key", req.getHeader("apikey"))
        assertEquals("""{"email":"doc@clinic.es","password":"secret"}""", req.body.readUtf8())
    }

    @Test fun badCredentialsMapFrom400_401_422() = runBlocking {
        for (status in listOf(400, 401, 422)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error_description":"nope"}"""))
            try { client().signIn("a@b.c", "x"); fail() } catch (e: ApiError.InvalidCredentials) { /* ok */ }
        }
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"msg":"boom"}"""))
        try { client().signIn("a@b.c", "x"); fail() } catch (e: ApiError.Server) {
            assertEquals(500, e.status); assertEquals("boom", e.serverMessage)
        }
    }

    @Test fun rpcSendsHeadersSortedKeysAndExplicitNulls() = runBlocking {
        server.enqueue(MockResponse().setBody(token(1)))
        server.enqueue(MockResponse().setBody("""[{"id":"1"}]"""))
        val c = client()
        c.signIn("a@b.c", "x"); server.takeRequest()
        val out = c.rpc("list_patients", mapOf("p_search" to null, "p_page" to JsonPrimitive(1)))
        assertEquals("""[{"id":"1"}]""", out)
        val req = server.takeRequest()
        assertEquals("/rest/v1/rpc/list_patients", req.path)
        assertEquals("Bearer ${jwt(1)}", req.getHeader("Authorization"))
        assertEquals("""{"p_page":1,"p_search":null}""", req.body.readUtf8())
    }

    @Test fun anExpiringTokenIsRefreshedBeforeTheCall() = runBlocking {
        server.enqueue(MockResponse().setBody(token(1, expiresIn = 30))) // < 60 s left
        server.enqueue(MockResponse().setBody(token(2)))
        server.enqueue(MockResponse().setBody("[]"))
        val c = client()
        c.signIn("a@b.c", "x"); server.takeRequest()
        c.rpc("x")
        assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest().path)
        assertEquals("Bearer ${jwt(2)}", server.takeRequest().getHeader("Authorization"))
        assertEquals("refresh-2", store.get(SecureStore.REFRESH_TOKEN))
    }

    @Test fun a401RefreshesOnceAndRetries() = runBlocking {
        server.enqueue(MockResponse().setBody(token(1)))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setBody(token(2)))
        server.enqueue(MockResponse().setBody("[1]"))
        val c = client()
        c.signIn("a@b.c", "x"); server.takeRequest()
        assertEquals("[1]", c.rpc("x"))
        assertEquals(4, server.requestCount)
    }

    @Test fun aRejectedRefreshTokenEndsTheSession() = runBlocking {
        server.enqueue(MockResponse().setBody(token(1)))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error_description":"Invalid Refresh Token"}"""))
        val c = client()
        var lost = false
        c.onSessionLost = { lost = true }
        c.signIn("a@b.c", "x")
        try { c.rpc("x"); fail() } catch (e: ApiError.SessionExpired) { /* ok */ }
        assertTrue(lost)
        assertNull(c.session.value)
        assertNull(store.get(SecureStore.REFRESH_TOKEN))
    }

    @Test fun concurrentCallsShareOneRefresh() = runBlocking {
        server.enqueue(MockResponse().setBody(token(1, expiresIn = 30)))
        val refreshes = java.util.concurrent.atomic.AtomicInteger()
        val c = client()
        c.signIn("a@b.c", "x")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.contains("refresh_token")) {
                    Thread.sleep(150) // slow refresh: the others must wait for it, not start their own
                    MockResponse().setBody(token(2 + refreshes.getAndIncrement()))
                } else MockResponse().setBody("[]")
        }
        (1..5).map { async(Dispatchers.Default) { c.rpc("x") } }.awaitAll()
        assertEquals(1, refreshes.get())
    }

    @Test fun offlineServesTheLastCachedResponseOnlyWhenAsked() = runBlocking {
        server.enqueue(MockResponse().setBody(token(1)))
        server.enqueue(MockResponse().setBody("""["fresh"]"""))
        val c = client()
        c.signIn("a@b.c", "x")
        assertEquals("""["fresh"]""", c.rpc("list_patients", cache = CachePolicy.FallbackToDisk))
        assertFalse(c.isServingCache.value)

        offline = true
        assertEquals("""["fresh"]""", c.rpc("list_patients", cache = CachePolicy.FallbackToDisk))
        assertTrue(c.isServingCache.value)
        try { c.rpc("list_patients"); fail() } catch (e: ApiError.Offline) { /* no cache policy: surfaced */ }
        try { c.rpc("other", cache = CachePolicy.FallbackToDisk); fail() } catch (e: ApiError.Offline) { /* never cached */ }
    }

    @Test fun serverErrorsAreNeverAnsweredFromTheCache() = runBlocking {
        server.enqueue(MockResponse().setBody(token(1)))
        server.enqueue(MockResponse().setBody("""["fresh"]"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"db down"}"""))
        val c = client()
        c.signIn("a@b.c", "x")
        c.rpc("l", cache = CachePolicy.FallbackToDisk)
        try { c.rpc("l", cache = CachePolicy.FallbackToDisk); fail() } catch (e: ApiError.Server) {
            assertEquals("db down", e.serverMessage)
        }
    }

    @Test fun signOutClearsEverything() = runBlocking {
        server.enqueue(MockResponse().setBody(token(1)))
        server.enqueue(MockResponse().setBody("""["x"]"""))
        server.enqueue(MockResponse().setResponseCode(204))
        val c = client()
        c.signIn("a@b.c", "x")
        c.rpc("l", cache = CachePolicy.FallbackToDisk)
        c.signOut()
        assertNull(c.session.value)
        assertNull(store.get(SecureStore.REFRESH_TOKEN))
        assertNull(cache.load("clinic-1|l|{}"))
        try { c.rpc("l"); fail() } catch (e: ApiError.NotSignedIn) { /* ok */ }
    }

    @Test fun decodeWrapsFailuresAsUnreadable() {
        val c = client()
        @kotlinx.serialization.Serializable data class Row(val id: String)
        assertEquals(Row("1"), c.decode<Row>("""{"id":"1","extra":true}"""))
        try { c.decode<Row>("not json"); fail() } catch (e: ApiError.Unreadable) { assertEquals("Row", e.what) }
        assertNotNull(JsonNull)
    }
}

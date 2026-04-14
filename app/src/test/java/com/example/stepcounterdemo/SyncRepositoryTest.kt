package com.example.stepcounterdemo

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

class SyncRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: SyncRepository

    /** JWT sub extractor that works in JVM unit tests (no android.util.Base64). */
    private val jvmJwtExtractor = SyncRepository.JwtSubExtractor { token ->
        val part = token.split(".").getOrNull(1)
            ?: throw IllegalArgumentException("Invalid JWT")
        val padded = part + "=".repeat((4 - part.length % 4) % 4)
        val bytes = Base64.getDecoder().decode(padded.replace('-', '+').replace('_', '/'))
        JSONObject(String(bytes, Charsets.UTF_8)).getString("sub")
    }

    /** Builds a fake JWT with the given sub claim. */
    private fun fakeJwt(sub: String): String {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"$sub"}""".toByteArray())
        return "header.$payload.sig"
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = SyncRepository(jwtSubExtractor = jvmJwtExtractor)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `authenticate returns Success with token and humanId on HTTP 200`() {
        val jwt = fakeJwt("client-abc-123")
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"token":"$jwt"}"""))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val result = repo.authenticate(baseUrl, "user", "pass", "device-guid-1")

        assertTrue(result is SyncRepository.AuthResult.Success)
        val success = result as SyncRepository.AuthResult.Success
        assertEquals(jwt, success.token)
        assertEquals("client-abc-123", success.humanId)
    }

    @Test
    fun `authenticate sends correct request body`() {
        val jwt = fakeJwt("any-sub")
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"token":"$jwt"}"""))

        val baseUrl = server.url("/").toString().trimEnd('/')
        repo.authenticate(baseUrl, "myuser", "mypass", "dev-guid-xyz")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/devices/auth", recorded.path)
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("myuser", body.getString("username"))
        assertEquals("mypass", body.getString("password"))
        assertEquals("dev-guid-xyz", body.getString("device_guid"))
        assertEquals("stepcounter", body.getString("device_type"))
    }

    @Test
    fun `authenticate returns Failure on non-200 response`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val result = repo.authenticate(baseUrl, "bad", "creds", "dev")

        assertTrue(result is SyncRepository.AuthResult.Failure)
        assertTrue((result as SyncRepository.AuthResult.Failure).message.contains("401"))
    }

    @Test
    fun `authenticate returns Failure on network error`() {
        server.shutdown() // force connection refused

        val result = repo.authenticate("http://localhost:1", "u", "p", "d")

        assertTrue(result is SyncRepository.AuthResult.Failure)
    }

    @Test
    fun `submitBatch returns Success with accepted count on HTTP 200`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"accepted":["guid1","guid2"],"rejected":[]}"""))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val records = listOf(
            com.example.stepcounterdemo.data.HourlyStepEntity(488000L, 120),
            com.example.stepcounterdemo.data.HourlyStepEntity(488001L, 95)
        )
        val result = repo.submitBatch(baseUrl, "tok", "human-1", "dev-1", records)

        assertTrue(result is SyncRepository.BatchResult.Success)
        assertEquals(2, (result as SyncRepository.BatchResult.Success).accepted)
    }

    @Test
    fun `submitBatch sends correct Authorization header and JSON body`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"accepted":["g1"],"rejected":[]}"""))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val hourKey = 488000L
        val records = listOf(com.example.stepcounterdemo.data.HourlyStepEntity(hourKey, 50))
        repo.submitBatch(baseUrl, "my-token", "human-uuid", "device-uuid", records)

        val recorded = server.takeRequest()
        assertEquals("Bearer my-token", recorded.getHeader("Authorization"))
        assertEquals("/api/devices/measurements/stepcounter", recorded.path)
        val body = JSONObject(recorded.body.readUtf8())
        val m = body.getJSONArray("measurements").getJSONObject(0)
        assertEquals("human-uuid", m.getString("humanId"))
        assertEquals(hourKey * 3_600_000L, m.getLong("startTime"))
        assertEquals((hourKey + 1) * 3_600_000L, m.getLong("endTime"))
        assertEquals(50, m.getInt("stepCount"))
    }

    @Test
    fun `submitBatch produces deterministic guid for same deviceGuid and hourKey`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"accepted":["g"],"rejected":[]}"""))
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"accepted":["g"],"rejected":[]}"""))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val records = listOf(com.example.stepcounterdemo.data.HourlyStepEntity(488000L, 42))
        repo.submitBatch(baseUrl, "tok", "human", "device-xyz", records)
        repo.submitBatch(baseUrl, "tok", "human", "device-xyz", records)

        val guid1 = JSONObject(server.takeRequest().body.readUtf8())
            .getJSONArray("measurements").getJSONObject(0).getString("guid")
        val guid2 = JSONObject(server.takeRequest().body.readUtf8())
            .getJSONArray("measurements").getJSONObject(0).getString("guid")
        assertEquals(guid1, guid2)
    }

    @Test
    fun `submitBatch returns Unauthorized on HTTP 401`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val result = repo.submitBatch(baseUrl, "expired", "h", "d",
            listOf(com.example.stepcounterdemo.data.HourlyStepEntity(1L, 10)))

        assertEquals(SyncRepository.BatchResult.Unauthorized, result)
    }

    @Test
    fun `submitBatch returns Failure on server error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val result = repo.submitBatch(baseUrl, "tok", "h", "d",
            listOf(com.example.stepcounterdemo.data.HourlyStepEntity(1L, 10)))

        assertTrue(result is SyncRepository.BatchResult.Failure)
        assertTrue((result as SyncRepository.BatchResult.Failure).message.contains("500"))
    }
}

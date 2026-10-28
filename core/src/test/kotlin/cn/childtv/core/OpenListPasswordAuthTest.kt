package cn.childtv.core

import com.google.gson.JsonParser
import kotlin.test.*

class OpenListPasswordAuthTest {
    private val list = HttpResponse(200, emptyMap(), """{"code":200,"data":{"content":[],"total":0}}""".toByteArray())
    private fun token(value: String) = HttpResponse(200, emptyMap(), """{"code":200,"data":{"token":"$value"}}""".toByteArray())
    @Test fun `password login exchanges credentials and keeps stable client id`() {
        val requests = mutableListOf<HttpRequest>()
        val transport = object : HttpTransport { override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return if (request.uri.path.endsWith("/login")) token("session-token") else list
        } }
        val adapter = OpenListAdapter("http://example.test", transport = transport, username = "parent", password = "private-password", clientId = "stable-device")
        assertTrue(adapter.testConnection()); adapter.listFiles("/child")
        assertEquals(1, requests.count { it.uri.path.endsWith("/login") })
        val body = JsonParser.parseString(requests.first().body!!.toString(Charsets.UTF_8)).asJsonObject
        assertEquals("parent", body["username"].asString); assertEquals("private-password", body["password"].asString)
        assertTrue(requests.all { it.headers["Client-Id"] == "stable-device" })
        assertTrue(requests.drop(1).all { it.headers["Authorization"] == "session-token" && !it.body!!.toString(Charsets.UTF_8).contains("private-password") })
    }
    @Test fun `wrong password fails without file request or leaked body`() {
        val requests = mutableListOf<HttpRequest>()
        val transport = object : HttpTransport { override fun execute(request: HttpRequest): HttpResponse { requests += request; return HttpResponse(200, emptyMap(), """{"code":400,"message":"private-password"}""".toByteArray()) } }
        val error = assertFailsWith<SourceException> { OpenListAdapter("http://example.test", transport = transport, username = "parent", password = "private-password").testConnection() }
        assertEquals(SourceFailure.AUTHENTICATION, error.reason); assertEquals(1, requests.size); assertTrue(requests.single().uri.path.endsWith("/login")); assertFalse(error.message!!.contains("private-password"))
    }
    @Test fun `token mode never logs in even when forbidden`() {
        val requests = mutableListOf<HttpRequest>()
        val transport = object : HttpTransport { override fun execute(request: HttpRequest): HttpResponse { requests += request; return HttpResponse(403, emptyMap(), ByteArray(0)) } }
        assertFailsWith<SourceException> { OpenListAdapter("http://example.test", "existing-token", transport = transport).testConnection() }
        assertEquals(1, requests.size); assertTrue(requests.single().uri.path.endsWith("/list")); assertEquals("existing-token", requests.single().headers["Authorization"])
    }
    @Test fun `expired token retries login once and repeated 401 is bounded`() {
        var logins = 0; var files = 0
        val transport = object : HttpTransport { override fun execute(request: HttpRequest): HttpResponse = if (request.uri.path.endsWith("/login")) { logins++; token("session-$logins") } else { files++; HttpResponse(200, emptyMap(), """{"code":401}""".toByteArray()) } }
        val error = assertFailsWith<SourceException> { OpenListAdapter("http://example.test", transport = transport, username = "parent", password = "private-password").testConnection() }
        assertEquals(401, error.status); assertEquals(2, logins); assertEquals(2, files)
    }
    @Test fun `password mode does not relogin for forbidden directory`() {
        var logins = 0; var files = 0
        val transport = object : HttpTransport { override fun execute(request: HttpRequest): HttpResponse = if (request.uri.path.endsWith("/login")) { logins++; token("session") } else { files++; HttpResponse(200, emptyMap(), """{"code":403}""".toByteArray()) } }
        assertFailsWith<SourceException> { OpenListAdapter("http://example.test", transport = transport, username = "parent", password = "private-password").testConnection() }
        assertEquals(1, logins); assertEquals(1, files)
    }
    @Test fun `raw video requests never receive source credentials or session headers`() {
        val requests = mutableListOf<HttpRequest>()
        val transport = object : HttpTransport { override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return when { request.uri.path.endsWith("/login") -> token("session-token"); request.uri.path.endsWith("/get") -> HttpResponse(200, emptyMap(), """{"code":200,"data":{"raw_url":"http://video.test/clip"}}""".toByteArray()); else -> HttpResponse(206, mapOf("Content-Range" to "bytes 0-3/100"), byteArrayOf(1,2,3,4)) }
        } }
        OpenListAdapter("http://example.test", transport = transport, username = "parent", password = "private-password").openRange("/clip", 0, 4)
        assertTrue(requests.first().uri.path.endsWith("/login")); assertTrue(requests.last().headers.keys.none { it.equals("Authorization", true) || it.equals("Client-Id", true) }); assertNull(requests.last().body)
    }
    @Test fun `invalid header token never appears in validation error and unicode password remains JSON`() {
        val transport = object : HttpTransport { override fun execute(request: HttpRequest) = list }
        val sentinel = "sensitive-sentinel\nnext-line"
        val failure = assertFailsWith<IllegalArgumentException> { OpenListAdapter("http://example.test", sentinel, transport = transport).testConnection() }
        assertFalse(failure.message.orEmpty().contains("sensitive-sentinel"))
        val unicode = "私密密码"
        val exchange = object : HttpTransport { override fun execute(request: HttpRequest): HttpResponse = if (request.uri.path.endsWith("/login")) { assertEquals(unicode, JsonParser.parseString(request.body!!.toString(Charsets.UTF_8)).asJsonObject["password"].asString); token("safe-token") } else list }
        assertTrue(OpenListAdapter("http://example.test", transport = exchange, username = "家长", password = unicode).testConnection())
    }

}

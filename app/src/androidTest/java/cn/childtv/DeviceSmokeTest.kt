package cn.childtv

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** All seeded records and secrets belong to an isolated, deleted fixture database. */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @Test fun roomLoginCsrfSourceScanAndQuotaUseOneDurableStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "instrumented-${UUID.randomUUID()}.db"
        val credentialNamespace = "$databaseName.credentials"
        val graph = AppGraph(context, databaseName, credentialNamespace)
        val fixture = object : NanoHTTPD(18181) {
            override fun serve(session: IHTTPSession): Response {
                if (session.headers["authorization"] != "instrumented-fixture-source-secret") return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{}")
                return when (session.uri) {
                    "/api/fs/list" -> newFixedLengthResponse(Response.Status.OK, "application/json", JSONObject().put("code", 200).put("data", JSONObject().put("total", 1).put("content", JSONArray().put(JSONObject().put("name", "Fixture.S01E01.mp4").put("size", 256000).put("is_dir", false)))).toString())
                    "/api/fs/get" -> {
                        val raw = mutableMapOf<String, String>(); session.parseBody(raw)
                        if (JSONObject(raw["postData"].orEmpty()).getString("path").endsWith(".nfo")) newFixedLengthResponse(Response.Status.OK, "application/json", "{\"code\":404}")
                        else newFixedLengthResponse(Response.Status.OK, "application/json", "{\"code\":200,\"data\":{\"size\":256000,\"raw_url\":\"http://127.0.0.1:18181/fixture.mp4\"}}")
                    }
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{}")
                }
            }
        }
        val server = AdminHttpServer(context, graph, 18180)
        val client = OkHttpClient()
        var sourceId: String? = null
        var cookie = ""
        var csrf = ""
        fun request(method: String, path: String, body: JSONObject? = null, authenticated: Boolean = false, origin: String = "http://127.0.0.1:18180", includeCsrf: Boolean = true): Pair<Int, String> {
            val builder = Request.Builder().url("http://127.0.0.1:18180/api/v1/$path").header("Origin", origin)
            if (authenticated) { builder.header("Cookie", cookie); if (includeCsrf) builder.header("X-CSRF-Token", csrf) }
            builder.method(method, if (method == "GET") null else (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
            client.newCall(builder.build()).execute().use { response ->
                response.header("Set-Cookie")?.let { cookie = it.substringBefore(';') }
                return response.code to response.body!!.string()
            }
        }
        try {
            fixture.start(); server.start()
            val index = client.newCall(Request.Builder().url("http://127.0.0.1:18180/").build()).execute().use { response -> assertEquals(200, response.code); response.body!!.string() }
            assertTrue("Bundled login HTML must be served", index.contains("<html"))
            val script = Regex("<script[^>]*src=\"([^\"]+)\"").find(index)?.groupValues?.get(1) ?: throw AssertionError("Bundled script reference is missing")
            client.newCall(Request.Builder().url("http://127.0.0.1:18180$script").build()).execute().use { response -> assertEquals(200, response.code); assertTrue(response.body!!.string().length > 100) }
            java.net.Socket("127.0.0.1", 18180).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write(("POST /api/v1/restore HTTP/1.1\r\nHost: 127.0.0.1:18180\r\nOrigin: http://127.0.0.1:18180\r\nContent-Type: application/json\r\nContent-Length: 75497472\r\nConnection: close\r\n\r\n").toByteArray())
                val line = socket.getInputStream().bufferedReader().readLine()
                assertTrue("Anonymous large body must be rejected before reading: $line", line.contains("401"))
            }
            graph.store.setSetting("adminPassword", graph.security.hashPassword("instrumented-admin-password"))
            assertEquals(401, request("GET", "library").first)
            assertEquals(401, request("POST", "auth/login", JSONObject().put("password", "wrong")).first)
            val login = request("POST", "auth/login", JSONObject().put("password", "instrumented-admin-password"))
            assertEquals(200, login.first); csrf = JSONObject(login.second).getString("csrfToken")
            assertEquals(403, request("PUT", "policy", JSONObject().put("defaultMinutes", 60), true, includeCsrf = false).first)
            assertEquals(403, request("PUT", "policy", JSONObject().put("defaultMinutes", 60), true, origin = "http://cross-site.invalid").first)
            val policyUpdate = request("PUT", "policy", JSONObject().put("defaultMinutes", 60), true)
            assertEquals(policyUpdate.second, 200, policyUpdate.first)
            val created = request("POST", "sources", JSONObject().put("name", "Instrumented fixture").put("type", "openlist").put("endpoint", "http://127.0.0.1:18181").put("secret", "instrumented-fixture-source-secret").put("roots", JSONArray(listOf("/"))), true)
            assertEquals(created.second, 200, created.first); assertFalse(created.second.contains("instrumented-fixture-source-secret")); sourceId = JSONObject(created.second).getString("id")
            val deadline = System.currentTimeMillis() + 15000
            while (graph.store.list("scans").any { it.optString("status") == "running" } && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertEquals("completed", graph.store.list("scans").single().getString("status"))
            val episode = graph.store.list("library").single()
            val id = episode.getString("id")
            graph.policy.checkpoint(JSONObject().put("id", "fixture-checkpoint").put("episodeId", id).put("title", episode.getString("title")).put("seriesTitle", episode.getString("seriesTitle")).put("libraryName", episode.getString("libraryName")).put("date", graph.policy.today().toString()).put("startedAt", java.time.Instant.now().toString()).put("durationMillis", 1500).put("seconds", 1).put("kind", "normal"), null)
            assertEquals(1, graph.policy.state().watchedSeconds)
            val edited = request("PATCH", "library/$id", JSONObject().put("title", "手工确认标题").put("seriesTitle", "手工动画").put("libraryName", "Instrumented fixture").put("seasonNumber", 1).put("episodeNumber", 1).put("synopsis", "设备烟测人工资料").put("lockedFields", JSONArray(listOf("title", "seriesTitle", "seasonNumber", "episodeNumber"))), true)
            assertEquals(edited.second, 200, edited.first)
            assertEquals(200, request("POST", "tags", JSONObject().put("key", "设备路线").put("values", JSONArray(listOf("测试"))), true).first)
            val tagPayload = JSONObject().put("episodeIds", JSONArray(listOf(id))).put("key", "设备路线").put("values", JSONArray(listOf("测试"))).put("operation", "append")
            val preview = request("POST", "library/tags/preview", tagPayload, true)
            assertEquals(preview.second, 200, preview.first); assertEquals(1, JSONObject(preview.second).getInt("affectedCount"))
            assertEquals(200, request("POST", "library/tags", tagPayload.put("confirmed", true), true).first)
            val filters = java.net.URLEncoder.encode(JSONObject().put("设备路线", JSONArray(listOf("测试"))).toString(), "UTF-8")
            val aggregate = request("GET", "analytics?dimension=tag&tags=$filters", authenticated = true)
            assertEquals(aggregate.second, 200, aggregate.first); assertEquals(1, JSONObject(aggregate.second).getLong("totalSeconds"))
            val dayDetail = request("GET", "analytics/day?date=${graph.policy.today()}", authenticated = true)
            assertEquals(dayDetail.second, 200, dayDetail.first)
            assertFalse(JSONObject(dayDetail.second).getJSONArray("entries").getJSONObject(0).getString("seriesTitle").matches(Regex("[0-9a-f-]{36}")))
            val library = request("GET", "library", authenticated = true)
            assertEquals(200, library.first); assertEquals(id, JSONArray(library.second).getJSONObject(0).getString("id"))
            graph.store.database.close()
            val reopened = LocalStore(context, databaseName)
            try { assertEquals(id, reopened.list("library").single().getString("id")); assertEquals(1, PolicyRepository(reopened).state().watchedSeconds) } finally { reopened.database.close() }
        } finally {
            server.stop(); fixture.stop(); graph.store.database.close(); context.deleteDatabase(databaseName)
            context.deleteSharedPreferences(credentialNamespace)
        }
    }
    @Test fun televisionEntryAndRemoteNavigationRemainReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg("cn.childtv")), 10000))
        device.findObject(By.textContains("开始吧"))?.click() // dismiss first-day choice, if present
        val calendar = device.wait(Until.findObject(By.text("观看日历")), 5000)
        assertNotNull(calendar); calendar.click()
        device.pressDPadDown(); device.pressDPadRight(); device.pressDPadCenter()
        assertTrue(device.wait(Until.hasObject(By.pkg("cn.childtv")), 3000))
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.textContains("小约动画")), 3000))
    }
}

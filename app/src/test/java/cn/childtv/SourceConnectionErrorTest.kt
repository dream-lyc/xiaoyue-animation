package cn.childtv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SourceConnectionErrorTest {
    @get:Rule val worker = WorkerThreadRule()
    private lateinit var context: Context
    private lateinit var graph: AppGraph
    private lateinit var upstream: LocalHttpFixture
    private lateinit var admin: AdminHttpServer
    private lateinit var login: AdminSecurity.Session
    private lateinit var databaseName: String
    private var port = 0
    private var responseCode = 401
    private var selectedOnly = false
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext(); databaseName = "connection-${UUID.randomUUID()}.db"
        graph = AppGraph(context, databaseName, "$databaseName.credentials")
        upstream = LocalHttpFixture { _, _, body, _ ->
            if (selectedOnly && body.optString("path") == "/allowed") JSONObject().put("code", 200).put("data", JSONObject().put("content", JSONArray()).put("total", 0))
            else JSONObject().put("code", responseCode).put("message", "upstream-private-body-do-not-return")
        }
        graph.store.put("sources", JSONObject().put("id", "source").put("name", "源").put("type", "openlist").put("endpoint", "http://127.0.0.1:${upstream.port}").put("roots", JSONArray()).put("credentialConfigured", false))
        login = requireNotNull(graph.security.login("long-test-password", graph.security.hashPassword("long-test-password")))
        port = ServerSocket(0).use { it.localPort }; admin = AdminHttpServer(context, graph, port).also { it.start() }
    }
    @After fun cleanup() { admin.stop(); upstream.close(); graph.store.database.close(); context.deleteDatabase(databaseName); context.deleteSharedPreferences("$databaseName.credentials") }
    private fun testConnection(): Pair<Int, JSONObject> {
        val request = Request.Builder().url("http://127.0.0.1:$port/api/v1/sources/source/test").header("Origin", "http://127.0.0.1:$port").header("Cookie", "childtv_session=${login.token}").header("X-CSRF-Token", login.csrfToken).post("{}".toRequestBody("application/json".toMediaType())).build()
        return OkHttpClient().newCall(request).execute().use { it.code to JSONObject(it.body!!.string()) }
    }
    @Test fun `upstream authentication failure is actionable and does not expire admin login`() {
        val (status, body) = testConnection()
        assertEquals(body.toString(), 502, status)
        assertEquals("SOURCE_AUTHENTICATION", body.getString("code")); assertTrue(body.getString("message").contains("令牌"))
        assertEquals(401, body.getInt("upstreamStatus")); assertFalse(body.toString().contains("upstream-private-body"))
        assertNotNull(graph.security.session(login.token))
    }
    @Test fun `upstream missing directory and server failures are distinct`() {
        responseCode = 404; val missing = testConnection()
        assertEquals(502, missing.first); assertEquals("SOURCE_NOT_FOUND", missing.second.getString("code")); assertTrue(missing.second.getString("message").contains("目录"))
        responseCode = 503; val offline = testConnection()
        assertEquals(502, offline.first); assertEquals("SOURCE_NETWORK", offline.second.getString("code")); assertFalse(offline.second.toString().contains("upstream-private-body"))
    }
    @Test fun `connection test uses selected directory instead of inaccessible root`() {
        selectedOnly = true
        graph.store.put("sources", graph.store.get("sources", "source")!!.put("roots", JSONArray(listOf("/allowed"))))
        val result = testConnection(); assertEquals(result.second.toString(), 200, result.first)
    }
}

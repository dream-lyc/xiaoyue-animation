package cn.childtv

import android.app.*
import android.content.*
import android.os.*
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.concurrent.Executors

class AppGraph(context: Context, databaseName: String = "child-tv.db", credentialNamespace: String = "encrypted-credentials") {
    val store = LocalStore(context.applicationContext, databaseName)
    val vault = CredentialVault(context.applicationContext, credentialNamespace)
    val artwork by lazy { ArtworkRepository(store, sources::cacheFirstFrame) }
    val policy = PolicyRepository(store)
    @Volatile var parent = cn.childtv.core.ParentAuthorization()
    val security = AdminSecurity { SystemClock.elapsedRealtime() }
    val playbackEpoch = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile var stopPlayback: (() -> Unit)? = null
    val sources = SourceRepository(store, vault).also { it.recover() }
    val api = AdminApi(store, vault, sources) { playbackEpoch.incrementAndGet(); parent = cn.childtv.core.ParentAuthorization(); security.revokeAll(); Handler(Looper.getMainLooper()).post { stopPlayback?.invoke() } }
    companion object {
        @Volatile private var instance: AppGraph? = null
        fun get(context: Context): AppGraph = instance ?: synchronized(this) { instance ?: AppGraph(context).also { instance = it } }
    }
}
class AdminService : Service() {
    private var server: AdminHttpServer? = null
    private val serverLock = Any()
    @Volatile private var destroyed = false
    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("service-status", MODE_PRIVATE).edit().putBoolean("running", false).putString("state", "starting").remove("error").apply()
        val channel = NotificationChannel("administration", "局域网管理", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "administration").setContentTitle("小约动画管理服务运行中").setContentText("仅在可信家庭局域网使用；HTTP 未加密").setSmallIcon(android.R.drawable.ic_menu_manage).setContentIntent(pending).build())
        val starter = Executors.newSingleThreadExecutor()
        starter.execute {
            try {
                val started = AdminHttpServer(this, AppGraph.get(this)).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                synchronized(serverLock) { if (destroyed) started.stop() else { server = started; getSharedPreferences("service-status", MODE_PRIVATE).edit().putBoolean("running", true).putString("state", "running").remove("error").apply() } }
            }
            catch (e: Exception) { getSharedPreferences("service-status", MODE_PRIVATE).edit().putBoolean("running", false).putString("state", "failed").putString("error", "管理服务启动失败（${e.javaClass.simpleName}），请检查 8080 端口是否被占用").apply(); stopSelf() }
        }
        starter.shutdown()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { destroyed = true; synchronized(serverLock) { server?.stop(); server = null }; getSharedPreferences("service-status", MODE_PRIVATE).edit().putBoolean("running", false).putString("state", "stopped").apply(); super.onDestroy() }
}
class AdminHttpServer(private val context: Context, private val graph: AppGraph, private val adminPort: Int = 8080) : NanoHTTPD(adminPort) {
    override fun serve(session: IHTTPSession): Response { return try {
        val host = session.headers["host"].orEmpty()
        val allowed = localHosts()
        if (host !in allowed) throw ApiFailure(403, "Host 不受信任，请使用电视显示的局域网地址")
        val path = session.uri
        if (!path.startsWith("/api/")) return staticFile(path)
        val method = session.method.name
        val mutation = method !in listOf("GET", "HEAD")
        val token = session.headers["cookie"].orEmpty().split(';').map { it.trim() }.firstOrNull { it.startsWith("childtv_session=") }?.substringAfter('=')
        val origin = session.headers["origin"]
        if (origin != null && origin != "http://$host") throw ApiFailure(403, "跨站请求被拒绝")
        when (path) {
            "/api/v1/auth/login" -> if (method != "POST" || origin != "http://$host") throw ApiFailure(403, "登录需要同源请求")
            "/api/v1/auth/session" -> if (method != "GET") throw ApiFailure(405, "不支持的方法")
            else -> if (!graph.security.authorize(token, session.headers["x-csrf-token"], origin, host, mutation)) throw ApiFailure(if (graph.security.session(token) == null) 401 else 403, "请登录或刷新验证信息")
        }
        val body = if (mutation) {
            val length = session.headers["content-length"]?.let { it.toLongOrNull() ?: throw ApiFailure(400, "Content-Length 无效") } ?: 0
            if (length < 0) throw ApiFailure(400, "Content-Length 无效")
            val limit = when (path) { "/api/v1/auth/login" -> 8192L; "/api/v1/restore" -> 72L * 1024 * 1024; else -> 1024L * 1024 }
            if (length > limit || session.headers.containsKey("transfer-encoding")) throw ApiFailure(413, "请求过大")
            if (length > 0 && session.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") throw ApiFailure(415, "接口仅接受 JSON 请求")
            val bytes = ByteArray(length.toInt()); var offset = 0; val readStarted = SystemClock.elapsedRealtime()
            while (offset < bytes.size) { if (SystemClock.elapsedRealtime() - readStarted > 30_000) throw ApiFailure(408, "请求读取超时"); val count = session.inputStream.read(bytes, offset, bytes.size - offset); if (count <= 0) throw ApiFailure(400, "请求正文不完整"); offset += count }
            JSONObject(String(bytes, Charsets.UTF_8).ifBlank { "{}" })
        } else JSONObject()
        if (path !in setOf("/api/v1/auth/login", "/api/v1/auth/session") && !graph.security.authorize(token, session.headers["x-csrf-token"], origin, host, mutation)) throw ApiFailure(401, "管理会话已过期")
        when (path) {
            "/api/v1/auth/login" -> {
                if (method != "POST" || origin != "http://$host") throw ApiFailure(403, "登录需要同源请求")
                val verifier = graph.store.getSetting("adminPassword", "")
                if (verifier.isEmpty()) throw ApiFailure(403, "请先在电视端设置管理员密码")
                val login = graph.security.login(body.optString("password"), verifier) ?: throw ApiFailure(401, "密码错误或尝试过多，请稍后重试")
                json(JSONObject().put("authenticated", true).put("csrfToken", login.csrfToken)).also { it.addHeader("Set-Cookie", "childtv_session=${login.token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=3600") }
            }
            "/api/v1/auth/session" -> {
                val auth = graph.security.session(token)
                json(JSONObject().put("authenticated", auth != null).also { if (auth != null) it.put("csrfToken", auth.csrfToken) })
            }
            else -> {
                if (path == "/api/v1/auth/logout") { graph.security.logout(token); json(JSONObject()).also { it.addHeader("Set-Cookie", "childtv_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0") } }
                else if (method == "GET" && path.startsWith("/api/v1/artwork/")) {
                    val episode = graph.store.get("library", path.substringAfterLast('/')) ?: throw ApiFailure(404, "封面不存在")
                    val cached = graph.artwork.cacheEpisode(episode)
                    val file = graph.artwork.fileFor(cached) ?: throw ApiFailure(404, "封面不可用，请稍后重试")
                    newChunkedResponse(Response.Status.OK, graph.artwork.mediaType(file), file.inputStream()).apply { headers() }
                } else json(graph.api.route(method, path.removePrefix("/api/v1/"), body, session.parameters.mapValues { it.value.firstOrNull().orEmpty() }))
            }
        }
    } catch (e: cn.childtv.core.SourceException) { val (status, body) = SourceErrors.response(e); json(body, status) }
      catch (e: ApiFailure) { json(JSONObject().put("message", e.message), e.status) }
      catch (e: IllegalArgumentException) { json(JSONObject().put("message", e.message ?: "参数无效"), 400) }
      catch (e: org.json.JSONException) { json(JSONObject().put("message", "JSON 字段不完整"), 400) }
      catch (e: Exception) { json(JSONObject().put("message", "操作失败，现有数据保留。请检查电视连接状态"), 500) }
    }
    private fun localHosts(): Set<String> = (NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { it.inetAddresses.toList() }.filter { !it.isLoopbackAddress && it is java.net.Inet4Address }.map { "${it.hostAddress}:$adminPort" } + "127.0.0.1:$adminPort").toSet()
    private fun json(value: Any, status: Int = 200): Response = newFixedLengthResponse(Response.Status.lookup(status) ?: object : Response.IStatus { override fun getRequestStatus() = status; override fun getDescription() = "$status Bad Gateway" }, "application/json; charset=utf-8", value.toString()).apply { headers(); if (status >= 400) closeConnection(true) }
    private fun Response.headers() { addHeader("Cache-Control", "no-store"); addHeader("X-Content-Type-Options", "nosniff"); addHeader("X-Frame-Options", "DENY"); addHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' https: data:; frame-ancestors 'none'; connect-src 'self'") }
    private fun staticFile(path: String): Response {
        if (path.contains("..") || path.contains('\\')) throw ApiFailure(400, "路径无效")
        val file = if (path == "/" || !path.substringAfterLast('/').contains('.')) "index.html" else path.removePrefix("/")
        val mime = when (file.substringAfterLast('.')) { "html" -> "text/html"; "js" -> "application/javascript"; "css" -> "text/css"; "svg" -> "image/svg+xml"; else -> "application/octet-stream" }
        return try { newChunkedResponse(Response.Status.OK, mime, context.assets.open("admin/$file")).apply { headers() } }
        catch (e: java.io.IOException) { newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "管理界面未打包，请构建 web-admin 后重新生成 APK") }
    }
}

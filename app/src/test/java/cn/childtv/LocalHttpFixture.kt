package cn.childtv

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import org.json.JSONObject

data class FixtureResponse(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())
/** A real loopback HTTP peer with bounded request bodies and byte-correct UTF-8. */
class LocalHttpFixture(private val handler: (String, String, JSONObject, Map<String, String>) -> Any) : Closeable {
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    private val thread = Thread({
        while (!socket.isClosed) {
            try {
                socket.accept().use { client ->
                    client.soTimeout = 10000
                    val input = client.getInputStream().buffered()
                    fun line(): String? {
                        val bytes = java.io.ByteArrayOutputStream()
                        while (true) { val c = input.read(); if (c < 0) return if (bytes.size() == 0) null else bytes.toString("US-ASCII"); if (c == 10) return bytes.toString("US-ASCII").trimEnd('\r'); bytes.write(c) }
                    }
                    val first = line()?.split(' ') ?: return@use
                    val headers = linkedMapOf<String, String>()
                    while (true) { val header = line() ?: break; if (header.isEmpty()) break; headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim() }
                    val length = headers["content-length"]?.toInt() ?: 0; require(length in 0..1_048_576)
                    val bodyBytes = ByteArray(length); var read = 0
                    while (read < length) { val n = input.read(bodyBytes, read, length - read); if (n < 0) break; read += n }
                    val body = if (read == 0) JSONObject() else JSONObject(String(bodyBytes, 0, read, Charsets.UTF_8))
                    val value = handler(first[0], first[1].substringBefore('?'), body, headers)
                    val response = value as? FixtureResponse ?: FixtureResponse(200, value.toString().toByteArray(), mapOf("Content-Type" to "application/json"))
                    client.getOutputStream().apply {
                        write("HTTP/1.1 ${response.status} Response\r\nContent-Length: ${response.body.size}\r\nConnection: close\r\n".toByteArray())
                        response.headers.forEach { (key, entry) -> write("$key: $entry\r\n".toByteArray()) }; write("\r\n".toByteArray()); write(response.body); flush()
                    }
                }
            } catch (failure: Exception) {
                if (!socket.isClosed) failure.printStackTrace()
            }
        }
    }, "source-http-fixture").apply { isDaemon = true; start() }
    override fun close() { socket.close(); thread.join(1000) }
}

package cn.childtv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.childtv.core.*
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class FrameArtworkDeviceTest {
    @Test fun missingArtworkExtractsAnActualFrameThroughBoundedHttpRanges() {
        val clip = InstrumentationRegistry.getInstrumentation().context.assets.open("playback-fixture.mp4").use { it.readBytes() }
        val server = object : NanoHTTPD(18184) {
            override fun serve(session: IHTTPSession): Response {
                val range = session.headers["range"]?.removePrefix("bytes=")?.split('-') ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Range required")
                val start = range[0].toInt(); val end = range[1].toInt()
                return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "video/mp4", ByteArrayInputStream(clip, start, end - start + 1), (end - start + 1).toLong()).also { it.addHeader("Content-Range", "bytes $start-$end/${clip.size}") }
            }
        }
        val read = AtomicLong()
        val client = OkHttpClient()
        val adapter = object : SourceAdapter {
            override fun openRange(path: String, offset: Long, length: Int): ByteArray {
                assertTrue("Each frame read is bounded", length <= 1_048_576)
                val request = Request.Builder().url("http://127.0.0.1:18184/clip.mp4").header("Range", "bytes=$offset-${offset + length - 1}").build()
                return client.newCall(request).execute().use { response -> assertEquals(206, response.code); response.body!!.bytes().also { read.addAndGet(it.size.toLong()) } }
            }
            override fun stat(path: String) = SourceFile(path, size = clip.size.toLong())
            override fun resolvePlayback(path: String) = PlaybackLocation(URI("http://127.0.0.1:18184/clip.mp4"))
            override fun testConnection() = true
            override fun listFiles(path: String, page: String?) = DirectoryPage(emptyList())
            override fun listDirectories(path: String, page: String?) = DirectoryPage(emptyList())
        }
        try {
            server.start()
            val file = SourceFile("/clip.mp4", size = clip.size.toLong())
            val image = FrameArtworkExtractor().extract(adapter, file)
            assertNotNull("Actual Android retriever should extract the fixture frame", image)
            assertTrue(image!!.isNotEmpty()); assertTrue(read.get() in 1..8L * 1024 * 1024)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(image, 0, image.size)
            assertNotNull(bitmap); assertTrue(bitmap!!.width > 0 && bitmap.height > 0); bitmap.recycle()
            read.set(0)
            assertNull("Insufficient read budget must fall back safely", FrameArtworkExtractor(byteBudget = 128).extract(adapter, file))
            assertTrue(read.get() <= 128)
        } finally { server.stop() }
    }
}

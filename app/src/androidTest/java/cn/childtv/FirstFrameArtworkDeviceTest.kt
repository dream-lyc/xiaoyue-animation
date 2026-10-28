package cn.childtv

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.childtv.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FirstFrameArtworkDeviceTest {
    private fun clip() = InstrumentationRegistry.getInstrumentation().context.assets.open("cover-first-frame.mp4").use { it.readBytes() }
    private fun adapter(bytes: ByteArray) = object : SourceAdapter {
        override fun openRange(path: String, offset: Long, length: Int): ByteArray { assertTrue(length <= 1_048_576); return bytes.copyOfRange(offset.toInt(), offset.toInt() + length) }
        override fun stat(path: String) = SourceFile(path, size = bytes.size.toLong())
        override fun resolvePlayback(path: String) = PlaybackLocation(URI("http://fixture.invalid/clip.mp4"))
        override fun testConnection() = true
        override fun listFiles(path: String, page: String?) = DirectoryPage(emptyList())
        override fun listDirectories(path: String, page: String?) = DirectoryPage(emptyList())
    }
    @Test fun defaultExtractionUsesTheActualRedFirstFrameRatherThanBlueOneSecondFrame() {
        val bytes = clip(); val extracted = FrameArtworkExtractor().extract(adapter(bytes), SourceFile("/clip.mp4", size = bytes.size.toLong()))
        assertNotNull(extracted)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(extracted!!, 0, extracted.size))
        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2); bitmap.recycle()
        assertTrue("Default cover must be the red first frame", Color.red(pixel) > 180 && Color.blue(pixel) < 80)
    }
    @Test fun unmatchedAvailableEpisodeAlsoCachesAVideoFrame() {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "first-frame-${UUID.randomUUID()}.db"; val store = LocalStore(context, name)
        try {
            val row = JSONObject().put("id", "episode").put("title", "未匹配视频").put("seriesTitle", "动画").put("libraryName", "测试").put("seasonNumber", 1).put("episodeNumber", 1).put("status", "unmatched").put("available", true).put("lockedFields", JSONArray()).put("tags", JSONObject())
            store.put("library", row)
            val bytes = clip(); val cached = FrameArtworkExtractor().cacheIfMissing(store, row, adapter(bytes), SourceFile("/clip.mp4", size = bytes.size.toLong()))
            assertNotNull("Metadata matching is not required for frame artwork", ArtworkRepository(store).fileFor(cached))
            assertEquals("video_frame", cached.getString("artworkOrigin"))
        } finally { store.database.close(); context.deleteDatabase(name); store.artworkDirectory.deleteRecursively() }
    }
}

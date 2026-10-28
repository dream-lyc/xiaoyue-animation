package cn.childtv

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import cn.childtv.core.SourceAdapter
import cn.childtv.core.SourceFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Best-effort frame fallback. Reads bounded ranges, never stages a complete remote video. */
class FrameArtworkExtractor(private val byteBudget: Long = 8L * 1024 * 1024, private val requestBudget: Int = 64) {
    fun extract(adapter: SourceAdapter, file: SourceFile, positionMs: Long = 0): ByteArray? {
        if (file.size <= 0 || file.directory || byteBudget <= 0 || requestBudget <= 0) return null
        var readBytes = 0L; var requests = 0; var closed = false
        val started = android.os.SystemClock.elapsedRealtime()
        val random = object : MediaDataSource() {
            @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (closed || position < 0 || position >= file.size) return -1
                if (size == 0) return 0
                if (android.os.SystemClock.elapsedRealtime() - started > 10_000 || requests >= requestBudget || readBytes >= byteBudget) throw IOException("截帧读取预算耗尽")
                val length = minOf(size.toLong(), 1_048_576L, file.size - position, byteBudget - readBytes).toInt()
                val bytes = adapter.openRange(file.path, position, length)
                if (bytes.isEmpty() || bytes.size > length) throw IOException("范围读取响应无效")
                bytes.copyInto(buffer, offset); readBytes += bytes.size; requests++; return bytes.size
            }
            override fun getSize(): Long = file.size
            @Synchronized override fun close() { closed = true }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(random)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            if (width <= 0 || height <= 0 || width.toLong() * height > 8_000_000) return null
            val time = positionMs.coerceAtLeast(0) * 1000
            val frame = if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, 1280, 720) else retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)
            frame ?: return null
            val factor = minOf(1.0, 1280.0 / frame.width, 720.0 / frame.height)
            val scaled = if (factor < 1) Bitmap.createScaledBitmap(frame, (frame.width * factor).toInt().coerceAtLeast(1), (frame.height * factor).toInt().coerceAtLeast(1), true) else frame
            try { ByteArrayOutputStream().use { output -> check(scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)); output.toByteArray().takeIf { it.size <= 4 * 1024 * 1024 } } }
            finally { if (scaled !== frame) scaled.recycle(); frame.recycle() }
        } catch (_: Exception) { null }
        finally { try { retriever.release() } catch (_: Exception) {}; random.close() }
    }
    fun cacheIfMissing(store: LocalStore, episode: JSONObject, adapter: SourceAdapter, file: SourceFile, positionMs: Long = 0, stillCurrent: () -> Boolean = { true }, frameDecoder: ((SourceAdapter, SourceFile, Long) -> ByteArray?)? = null): JSONObject {
        val epoch = store.catalogEpoch.get()
        val artwork = ArtworkRepository(store)
        if (artwork.fileFor(episode) != null) return episode
        val signature = artwork.signature(episode)
        val bytes = (frameDecoder?.invoke(adapter, file, positionMs) ?: if (frameDecoder == null) extract(adapter, file, positionMs) else null) ?: return episode
        return try { store.transaction { val latest = store.get("library", episode.getString("id")) ?: return@transaction episode; if (!stillCurrent() || store.catalogEpoch.get() != epoch || artwork.signature(latest) != signature || artwork.fileFor(latest) != null) latest else artwork.cacheBytes(episode.getString("id"), bytes, "video_frame") } } catch (_: Exception) { episode }
    }
}

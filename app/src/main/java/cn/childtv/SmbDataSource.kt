package cn.childtv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import cn.childtv.core.RandomAccessReader
import org.json.JSONObject
import java.io.EOFException

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SmbDataSource(private val sources: SourceRepository, private val episode: JSONObject) : BaseDataSource(true) {
    private var reader: RandomAccessReader? = null
    private var position = 0L
    private var remaining = 0L
    private var currentUri: Uri? = null
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val opened = sources.openSmb(episode)
        if (dataSpec.position > opened.size) { opened.close(); throw EOFException("播放位置超出资源范围") }
        reader = opened; position = dataSpec.position; currentUri = dataSpec.uri
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) opened.size - position else minOf(dataSpec.length, opened.size - position)
        transferStarted(dataSpec); return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val size = minOf(length.toLong(), remaining, 1_048_576L).toInt()
        val read = requireNotNull(reader).read(position, buffer, offset, size)
        if (read <= 0) throw EOFException("SMB 资源提前结束")
        position += read; remaining -= read; bytesTransferred(read); return read
    }
    override fun getUri(): Uri? = currentUri
    override fun close() { val opened = reader; reader = null; currentUri = null; if (opened != null) try { opened.close() } finally { transferEnded() } }
}

package se.r56.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** Random access reader over something on an SMB share. */
interface RandomReader {
    val length: Long

    /** Reads up to len bytes at pos, returns the number of bytes read or -1 at end. */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int
    fun close()
}

/** A plain file on a share. */
class SmbFileReader(private val src: Source, private val path: String) : RandomReader {
    private var file: SmbFile = Smb.openFile(src, path)
    override val length: Long = file.fileInformation.standardInformation.endOfFile

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        return try {
            file.read(buf, pos, off, len)
        } catch (e: Exception) {
            try {
                file.close()
            } catch (_: Exception) {
            }
            Smb.reset(src)
            file = Smb.openFile(src, path)
            file.read(buf, pos, off, len)
        }
    }

    override fun close() {
        try {
            file.close()
        } catch (_: Exception) {
        }
    }
}

/** The video inside a multi-volume RAR set, presented as one continuous file. */
class RarReader(
    private val src: Source,
    private val dir: String,
    res: RarArchive.Result
) : RandomReader {
    private val segs = res.segments
    override val length: Long = res.totalSize
    private var curVol: String? = null
    private var cur: SmbFile? = null

    private fun volPath(vol: String) = if (dir.isEmpty()) vol else "$dir/$vol"

    private fun find(pos: Long): RarArchive.Segment {
        var lo = 0
        var hi = segs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (segs[mid].start <= pos) lo = mid else hi = mid - 1
        }
        return segs[lo]
    }

    private fun closeCurrent() {
        try {
            cur?.close()
        } catch (_: Exception) {
        }
        cur = null
        curVol = null
    }

    private fun fileFor(vol: String): SmbFile {
        if (vol != curVol || cur == null) {
            closeCurrent()
            cur = Smb.openFile(src, volPath(vol))
            curVol = vol
        }
        return cur!!
    }

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        val seg = find(pos)
        val inSeg = pos - seg.start
        val n = minOf(len.toLong(), seg.size - inSeg).toInt()
        if (n <= 0) return -1
        val filePos = seg.offset + inSeg
        return try {
            fileFor(seg.volume).read(buf, filePos, off, n)
        } catch (e: Exception) {
            closeCurrent()
            Smb.reset(src)
            fileFor(seg.volume).read(buf, filePos, off, n)
        }
    }

    override fun close() = closeCurrent()
}

object RarSupport {
    private val cache = HashMap<String, RarArchive.Result>()

    private class Adapter(private val r: RandomReader) : RarArchive.Reader {
        override fun length(): Long = r.length
        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int = r.read(pos, buf, off, len)
        override fun close() = r.close()
    }

    /** Finds all volumes of the set, parses the headers and caches the byte-range map. */
    fun resolve(src: Source, firstPath: String): RarArchive.Result {
        val key = "${src.id}|$firstPath"
        synchronized(cache) { cache[key]?.let { return it } }
        val dir = firstPath.substringBeforeLast('/', "")
        val first = firstPath.substringAfterLast('/')
        val names = Smb.list(src, dir).map { it.name }
        val vols = RarArchive.volumeNames(first, names)
        val res = RarArchive.index(vols, RarArchive.Opener { vol ->
            Adapter(SmbFileReader(src, if (dir.isEmpty()) vol else "$dir/$vol"))
        })
        synchronized(cache) { cache[key] = res }
        return res
    }

    fun reader(src: Source, firstPath: String): RandomReader =
        RarReader(src, firstPath.substringBeforeLast('/', ""), resolve(src, firstPath))
}

/** ExoPlayer data source for r56smb:// and r56rar:// URIs (authority = source id, path = path in share). */
class SmbDataSource : BaseDataSource(true) {
    private var uri: Uri? = null
    private var reader: RandomReader? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    private val buf = ByteArray(BUF)
    private var bufStart = -1L
    private var bufLen = 0

    override fun open(dataSpec: DataSpec): Long {
        val u = dataSpec.uri
        uri = u
        transferInitializing(dataSpec)
        try {
            val id = u.authority ?: throw IOException("Missing source")
            val src = Store.source(id) ?: throw IOException("Unknown source")
            val segs = u.pathSegments
            val isRar = u.scheme == SCHEME_RAR
            // r56rar URIs carry the inner file name as an extra last segment (helps ExoPlayer pick the extractor)
            val path = (if (isRar) segs.dropLast(1) else segs).joinToString("/")
            val r: RandomReader = if (isRar) RarSupport.reader(src, path) else SmbFileReader(src, path)
            reader = r
            if (dataSpec.position > r.length) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            position = dataSpec.position
            val avail = r.length - position
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, avail) else avail
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: e.toString(), e)
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val r = reader ?: return C.RESULT_END_OF_INPUT
        if (position < bufStart || position >= bufStart + bufLen) {
            if (!fill(r, position)) return C.RESULT_END_OF_INPUT
        }
        val inBuf = (position - bufStart).toInt()
        val n = minOf(length.toLong(), (bufLen - inBuf).toLong(), remaining).toInt()
        System.arraycopy(buf, inBuf, buffer, offset, n)
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    private fun fill(r: RandomReader, pos: Long): Boolean {
        var total = 0
        try {
            while (total < BUF) {
                val n = r.read(pos + total, buf, total, minOf(BUF - total, CHUNK))
                if (n <= 0) break
                total += n
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: e.toString(), e)
        }
        bufStart = if (total > 0) pos else -1L
        bufLen = total
        return total > 0
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            reader?.close()
        } finally {
            reader = null
            uri = null
            bufStart = -1L
            bufLen = 0
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }

    companion object {
        const val SCHEME_SMB = "r56smb"
        const val SCHEME_RAR = "r56rar"
        private const val BUF = 512 * 1024
        private const val CHUNK = 256 * 1024

        fun buildUri(scheme: String, sourceId: String, path: String): Uri {
            val b = Uri.Builder().scheme(scheme).authority(sourceId)
            path.split('/').filter { it.isNotEmpty() }.forEach { b.appendPath(it) }
            return b.build()
        }
    }
}

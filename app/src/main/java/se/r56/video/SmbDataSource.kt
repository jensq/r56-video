package se.r56.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

/** The video inside a multi-volume RAR set, presented as one continuous file. Safe for concurrent reads. */
class RarReader(
    private val src: Source,
    private val dir: String,
    res: RarArchive.Result
) : RandomReader {
    private val segs = res.segments
    override val length: Long = res.totalSize
    private val files = HashMap<String, SmbFile>()

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

    @Synchronized
    private fun fileFor(vol: String): SmbFile {
        files[vol]?.let { return it }
        if (files.size >= 4) closeAllLocked() // keep only a few volumes open while playing through the set
        val f = Smb.openFile(src, volPath(vol))
        files[vol] = f
        return f
    }

    @Synchronized
    private fun drop(vol: String, f: SmbFile) {
        if (files[vol] === f) files.remove(vol)
        try {
            f.close()
        } catch (_: Exception) {
        }
    }

    private fun closeAllLocked() {
        for (f in files.values) {
            try {
                f.close()
            } catch (_: Exception) {
            }
        }
        files.clear()
    }

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        val seg = find(pos)
        val inSeg = pos - seg.start
        val n = minOf(len.toLong(), seg.size - inSeg).toInt()
        if (n <= 0) return -1
        val filePos = seg.offset + inSeg
        val f = fileFor(seg.volume)
        return try {
            f.read(buf, filePos, off, n)
        } catch (e: Exception) {
            drop(seg.volume, f)
            Smb.reset(src)
            fileFor(seg.volume).read(buf, filePos, off, n)
        }
    }

    @Synchronized
    override fun close() = closeAllLocked()
}

private const val PR_BLOCK = 256 * 1024
private const val PR_MIN_SIZE = 8L * 1024 * 1024

/**
 * Reads several blocks of the file at the same time on background threads and hands them out in order.
 * One SMB request at a time is limited by network round-trip time (bad on wifi); several in flight is not.
 */
class ParallelReader(
    private val base: RandomReader,
    private val workers: Int,
    private val windowBlocks: Int
) : RandomReader {
    private class Block(val data: ByteArray, val len: Int)

    override val length: Long = base.length
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val done = HashMap<Long, Block>()
    private var gen = 0
    private var startPos = 0L
    private var nextIdx = 0L
    private var consumeIdx = 0L
    private var expected = -1L
    private var error: Throwable? = null
    private var closed = false
    private var threads: List<Thread> = emptyList()

    /** Called with the lock held: (re)start reading at pos. */
    private fun restart(pos: Long) {
        gen++
        done.clear()
        startPos = pos
        nextIdx = 0
        consumeIdx = 0
        expected = pos
        error = null
        if (threads.isEmpty()) {
            threads = (0 until workers).map { i ->
                Thread({ work() }, "r56-read-$i").also {
                    it.isDaemon = true
                    it.start()
                }
            }
        }
        cond.signalAll()
    }

    private fun work() {
        while (true) {
            var idx = 0L
            var g = 0
            var pos = 0L
            var want = 0
            lock.lock()
            try {
                while (true) {
                    if (closed) return
                    val p = startPos + nextIdx * PR_BLOCK
                    if (error == null && p < length && nextIdx - consumeIdx < windowBlocks) break
                    cond.await(300, TimeUnit.MILLISECONDS)
                }
                idx = nextIdx++
                g = gen
                pos = startPos + idx * PR_BLOCK
                want = minOf(PR_BLOCK.toLong(), length - pos).toInt()
            } finally {
                lock.unlock()
            }
            try {
                val data = ByteArray(want)
                var got = 0
                while (got < want) {
                    val n = base.read(pos + got, data, got, want - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < want) throw IOException("Unexpected end of file")
                lock.withLock {
                    if (g == gen) {
                        done[idx] = Block(data, want)
                        cond.signalAll()
                    }
                }
            } catch (e: Throwable) {
                lock.withLock {
                    if (g == gen && !closed) {
                        error = e
                        cond.signalAll()
                    }
                }
            }
        }
    }

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        return lock.withLock {
            if (closed) return@withLock -1
            if (pos != expected) restart(pos)
            while (true) {
                val err = error
                if (err != null) throw IOException(err.message ?: err.toString(), err)
                if (closed) return@withLock -1
                val blk = done[consumeIdx]
                if (blk != null) {
                    val blockStart = startPos + consumeIdx * PR_BLOCK
                    val inBlk = (pos - blockStart).toInt()
                    val n = minOf(len, blk.len - inBlk)
                    System.arraycopy(blk.data, inBlk, buf, off, n)
                    expected = pos + n
                    if (inBlk + n >= blk.len) {
                        done.remove(consumeIdx)
                        consumeIdx++
                        cond.signalAll()
                    }
                    return@withLock n
                }
                try {
                    cond.await(300, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            -1
        }
    }

    override fun close() {
        val ts: List<Thread>
        lock.withLock {
            closed = true
            done.clear()
            cond.signalAll()
            ts = threads
        }
        for (t in ts) {
            try {
                t.join(3000)
            } catch (_: InterruptedException) {
            }
        }
        base.close()
    }
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
            val base: RandomReader = if (isRar) RarSupport.reader(src, path) else SmbFileReader(src, path)
            val big = Store.bigBuffer
            val r: RandomReader = if (base.length > PR_MIN_SIZE) {
                ParallelReader(base, if (big) 5 else 3, if (big) 64 else 32)
            } else {
                base
            }
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

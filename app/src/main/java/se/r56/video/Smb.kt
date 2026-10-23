package se.r56.video

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.EnumSet
import java.util.concurrent.TimeUnit

typealias SmbFile = com.hierynomus.smbj.share.File

data class Entry(val name: String, val isDir: Boolean, val size: Long)

/**
 * Thin wrapper around smbj with one cached connection per server/share/user.
 *
 * Robustness: all calls for one server run one at a time, a failed call closes the whole TCP connection
 * (not just the share) before retrying, and if that fails too the connection is retried once more with only
 * the older SMB 2.1 / 3.0 dialects. Buffer sizes are kept small so every request costs a single SMB credit.
 */
object Smb {
    private fun buildClient(compat: Boolean): SMBClient {
        val b = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .withReadBufferSize(if (compat) 64 * 1024 else 256 * 1024)
            .withWriteBufferSize(64 * 1024)
            .withTransactBufferSize(64 * 1024)
            .withDfsEnabled(false)
        if (compat) b.withDialects(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_3_0)
        return SMBClient(b.build())
    }

    private val client: SMBClient by lazy { buildClient(false) }
    private val compatClient: SMBClient by lazy { buildClient(true) }

    private class Link(val conn: Connection, val share: DiskShare)

    private val links = HashMap<String, Link>()
    private val compatKeys = HashSet<String>()
    private val locks = HashMap<String, Any>()

    /** Android ships a stripped "BC" provider; replace it with the full one smbj needs (MD4 etc). */
    fun initSecurity() {
        try {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    private fun lockFor(src: Source): Any = locks.getOrPut(src.connKey) { Any() }

    @Synchronized
    private fun share(src: Source): DiskShare {
        val key = src.connKey
        val existing = links[key]
        if (existing != null && existing.share.isConnected) return existing.share
        if (existing != null) closeLink(links.remove(key))

        val conn = (if (compatKeys.contains(key)) compatClient else client).connect(src.host)
        try {
            val auth = if (src.user.isBlank()) {
                AuthenticationContext.guest()
            } else {
                AuthenticationContext(src.user, src.pass.toCharArray(), src.domain)
            }
            val session = conn.authenticate(auth)
            val sh = session.connectShare(src.share) as DiskShare
            links[key] = Link(conn, sh)
            return sh
        } catch (e: Exception) {
            try {
                conn.close(true)
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private fun closeLink(l: Link?) {
        if (l == null) return
        try {
            l.share.close()
        } catch (_: Exception) {
        }
        try {
            l.conn.close(true) // drop the TCP connection so the next attempt starts from scratch
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun reset(src: Source) {
        closeLink(links.remove(src.connKey))
    }

    @Synchronized
    private fun useCompat(src: Source) {
        compatKeys.add(src.connKey)
    }

    /** Runs block on a connected share. On failure: fresh connection, then fresh connection with older dialects. */
    fun <T> withShare(src: Source, block: (DiskShare) -> T): T {
        synchronized(lockFor(src)) {
            try {
                return block(share(src))
            } catch (e: Exception) {
                reset(src)
            }
            try {
                return block(share(src))
            } catch (e: Exception) {
                reset(src)
                useCompat(src)
            }
            return block(share(src))
        }
    }

    fun list(src: Source, path: String): List<Entry> = withShare(src) { sh ->
        sh.list(smbPath(path)).mapNotNull { f ->
            val n = f.fileName
            if (n == "." || n == ".." || n.startsWith(".") || n.startsWith("$") || n.startsWith("@") || n == "lost+found") {
                null
            } else {
                val isDir = EnumWithValue.EnumUtils.isSet(f.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                Entry(n, isDir, f.endOfFile)
            }
        }
    }

    private fun smbPath(p: String) = p.replace('/', '\\')

    fun openFile(src: Source, path: String): SmbFile = withShare(src) { sh ->
        sh.openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
    }
}

package se.r56.video

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.EnumSet
import java.util.concurrent.TimeUnit

typealias SmbFile = com.hierynomus.smbj.share.File

data class Entry(val name: String, val isDir: Boolean, val size: Long)

/** Thin wrapper around smbj with one cached connection per server/share/user. */
object Smb {
    private val client: SMBClient by lazy {
        SMBClient(
            SmbConfig.builder()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(60, TimeUnit.SECONDS)
                .build()
        )
    }
    private val shares = HashMap<String, DiskShare>()

    /** Android ships a stripped "BC" provider; replace it with the full one smbj needs (MD4 etc). */
    fun initSecurity() {
        try {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    private fun share(src: Source): DiskShare {
        val key = src.connKey
        val existing = shares[key]
        if (existing != null && existing.isConnected) return existing
        if (existing != null) {
            try {
                existing.close()
            } catch (_: Exception) {
            }
            shares.remove(key)
        }
        val conn = client.connect(src.host)
        val auth = if (src.user.isBlank()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(src.user, src.pass.toCharArray(), src.domain)
        }
        val session = conn.authenticate(auth)
        val sh = session.connectShare(src.share) as DiskShare
        shares[key] = sh
        return sh
    }

    @Synchronized
    fun reset(src: Source) {
        val old = shares.remove(src.connKey)
        if (old != null) {
            try {
                old.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Runs block on a connected share; on failure reconnects once and retries. */
    fun <T> withShare(src: Source, block: (DiskShare) -> T): T {
        return try {
            block(share(src))
        } catch (e: Exception) {
            reset(src)
            block(share(src))
        }
    }

    private fun smbPath(p: String) = p.replace('/', '\\')

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

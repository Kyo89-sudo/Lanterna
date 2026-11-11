package com.kyo.lanterna

import android.net.Uri
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class SmbEntry(val name: String, val path: String, val isDir: Boolean, val size: Long) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
}

val VIDEO_EXT = setOf(
    "mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "mts", "webm",
    "wmv", "flv", "mpg", "mpeg", "vob", "3gp", "ogv"
)
val SUB_EXT = setOf("srt", "ass", "ssa", "vtt")

/** URI interni: lanternasmb://<idServer>/percorso/nel/share/file.mkv */
object SmbUri {
    const val SCHEME = "lanternasmb"

    fun build(serverId: String, path: String): Uri =
        Uri.Builder().scheme(SCHEME).authority(serverId).path("/" + path.trim('/')).build()

    fun resolve(uri: Uri): Pair<ServerConfig, String> {
        val s = Store.server(uri.authority ?: "") ?: throw IOException("Server non trovato")
        return s to (uri.path ?: "").trim('/')
    }
}

object SmbManager {

    /** Thread che leggono i blocchi in parallelo (più richieste SMB in volo = meno attese). */
    val pool: ExecutorService = Executors.newFixedThreadPool(8, object : java.util.concurrent.ThreadFactory {
        private val n = AtomicInteger()
        override fun newThread(r: Runnable) = Thread(r, "smb-read-${n.incrementAndGet()}").apply { isDaemon = true }
    })

    /** Limita le letture contemporanee al valore scelto nelle impostazioni (2/4/6/8). */
    @Volatile private var limiterSize = 0
    @Volatile private var limiterSem = Semaphore(4)

    @Synchronized
    fun limiter(): Semaphore {
        val n = Store.parallel
        if (n != limiterSize) {
            limiterSem = Semaphore(n)
            limiterSize = n
        }
        return limiterSem
    }

    private val client: SMBClient by lazy {
        SMBClient(
            SmbConfig.builder()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(60, TimeUnit.SECONDS)
                .withReadBufferSize(4 * 1024 * 1024)
                .withSecurityProvider(BCSecurityProvider())
                .build()
        )
    }

    private val shares = HashMap<String, DiskShare>()

    @Synchronized
    private fun share(s: ServerConfig): DiskShare {
        shares[s.id]?.let { sh ->
            if (runCatching { sh.isConnected }.getOrDefault(false)) return sh
            runCatching { sh.close() }
            shares.remove(s.id)
        }
        val conn = if (s.port > 0) client.connect(s.host, s.port) else client.connect(s.host)
        val auth = if (s.user.isBlank()) AuthenticationContext.guest()
        else AuthenticationContext(s.user, s.password.toCharArray(), s.domain.ifBlank { null })
        val session = conn.authenticate(auth)
        val sh = session.connectShare(s.share) as? DiskShare
            ?: throw IOException("'${s.share}' non è una cartella condivisa")
        shares[s.id] = sh
        return sh
    }

    @Synchronized
    fun invalidate(id: String) {
        shares.remove(id)?.let { runCatching { it.close() } }
    }

    /** Esegue l'operazione; se la connessione è caduta riconnette e riprova una volta. */
    private fun <T> withShare(s: ServerConfig, block: (DiskShare) -> T): T {
        try {
            return block(share(s))
        } catch (e: Exception) {
            invalidate(s.id)
            try {
                return block(share(s))
            } catch (e2: Exception) {
                throw if (e2 is IOException) e2 else IOException(e2.message ?: e2.javaClass.simpleName, e2)
            }
        }
    }

    private fun smbPath(path: String) = path.trim('/').replace('/', '\\')

    fun list(s: ServerConfig, path: String): List<SmbEntry> = withShare(s) { sh ->
        sh.list(smbPath(path))
            .filter { it.fileName != "." && it.fileName != ".." }
            .filter { (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value) == 0L }
            .map {
                val isDir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                SmbEntry(it.fileName, (path.trim('/') + "/" + it.fileName).trim('/'), isDir, it.endOfFile)
            }
            .sortedWith(compareBy<SmbEntry>({ !it.isDir }, { it.name.lowercase() }))
    }

    fun openFile(s: ServerConfig, path: String): File = withShare(s) { sh ->
        sh.openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
    }

    fun fileSize(f: File): Long = f.getFileInformation(FileStandardInformation::class.java).endOfFile
}

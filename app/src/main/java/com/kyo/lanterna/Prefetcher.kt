package com.kyo.lanterna

import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

object SmbStats {
    /** Byte totali letti dal server (per calcolare la velocità nel pannello info). */
    val bytesRead = AtomicLong()
}

/**
 * Legge il file in blocchi grandi, in anticipo e in parallelo.
 * Il player chiede pochi KB alla volta: invece di fare una richiesta di rete
 * per ognuna, li prende dalla RAM. La rete lavora sempre "davanti" al player.
 */
/** Legge `length` byte dal file a `fileOffset` dentro `buf` da `bufOffset`; ritorna i byte letti o -1. */
fun interface ChunkReader {
    fun read(buf: ByteArray, fileOffset: Long, bufOffset: Int, length: Int): Int
}

class Prefetcher(
    private val file: ChunkReader,
    start: Long,
    length: Long,
    private val chunkSize: Int,
    private val maxChunks: Int,
    private val pool: ExecutorService
) : Closeable {

    companion object {
        const val CHUNK = 2 * 1024 * 1024
        private val active: MutableSet<Prefetcher> = ConcurrentHashMap.newKeySet()
        fun totalAheadBytes(): Long = active.sumOf { it.ahead.get() }
    }

    private val end = start + length
    private var nextOffset = start
    private val pending = ArrayDeque<Future<ByteArray>>()
    private var cur: ByteArray? = null
    private var curPos = 0
    private val ahead = AtomicLong()
    @Volatile private var closed = false

    // Avvio rapido: i primi blocchi sono piccoli (256 KB) e raddoppiano fino a chunkSize,
    // così dopo un'apertura o un salto il player riceve dati in pochi decimi di secondo.
    // NB: va dichiarato PRIMA del blocco init, che chiama fill().
    private var nextLen = minOf(256 * 1024, chunkSize).coerceAtLeast(64 * 1024)

    init {
        active.add(this)
        fill()
    }

    private fun fill() {
        while (!closed && pending.size < maxChunks && nextOffset < end) {
            val off = nextOffset
            val len = minOf(nextLen.toLong(), end - off).toInt()
            if (len <= 0) break
            nextLen = minOf(nextLen * 2, chunkSize)
            nextOffset += len
            pending.addLast(pool.submit(Callable { readChunk(off, len) }))
        }
    }

    private fun readChunk(off: Long, len: Int): ByteArray {
        val buf = ByteArray(len)
        var done = 0
        var emptyReads = 0
        while (done < len) {
            if (closed) throw InterruptedIOException("chiuso")
            // richieste da max 512 KB: dopo un salto le letture vecchie si fermano subito
            val n = file.read(buf, off + done, done, minOf(len - done, 512 * 1024))
            if (n <= 0) {
                // Il blocco sta tutto dentro il file: una risposta vuota non è la fine del file.
                // Riprova lo stesso punto; mai restituire un blocco con un buco in mezzo.
                if (++emptyReads >= 5) throw IOException("lettura SMB incompleta a ${off + done}")
                Thread.sleep(100L * emptyReads)
                continue
            }
            emptyReads = 0
            done += n
        }
        SmbStats.bytesRead.addAndGet(done.toLong())
        if (!closed) ahead.addAndGet(done.toLong())
        return buf
    }

    /** Ritorna i byte copiati, oppure -1 a fine file. */
    fun read(target: ByteArray, offset: Int, length: Int): Int {
        var c = cur
        if (c == null || curPos >= c.size) {
            val f = pending.removeFirstOrNull() ?: return -1
            c = try {
                f.get()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("interrotto")
            } catch (e: ExecutionException) {
                val cause = e.cause
                throw if (cause is IOException) cause else IOException(cause?.message ?: "errore lettura SMB", cause)
            }
            ahead.addAndGet(-c.size.toLong())
            cur = c
            curPos = 0
            fill()
            if (c.isEmpty()) return -1
        }
        val n = minOf(length, c.size - curPos)
        System.arraycopy(c, curPos, target, offset, n)
        curPos += n
        return n
    }

    override fun close() {
        closed = true
        active.remove(this)
        pending.forEach { it.cancel(false) }
        pending.clear()
        cur = null
    }
}

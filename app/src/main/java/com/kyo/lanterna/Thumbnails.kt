package com.kyo.lanterna

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.hierynomus.smbj.share.File
import java.util.concurrent.Executors

/** Sorgente per MediaMetadataRetriever che legge da SMB con una piccola cache a blocchi. */
class SmbMediaDataSource(private val file: File, private val fileSize: Long) : MediaDataSource() {
    private val block = 256 * 1024
    private val cache = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > 24
    }

    override fun getSize(): Long = fileSize

    override fun close() {}

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= fileSize || size <= 0) return -1
        var done = 0
        while (done < size && position + done < fileSize) {
            val p = position + done
            val idx = p / block
            val data = cache[idx] ?: loadBlock(idx).also { cache[idx] = it }
            val inBlock = (p - idx * block).toInt()
            if (inBlock >= data.size) break
            val n = minOf(size - done, data.size - inBlock)
            System.arraycopy(data, inBlock, buffer, offset + done, n)
            done += n
        }
        return if (done == 0) -1 else done
    }

    private fun loadBlock(idx: Long): ByteArray {
        val start = idx * block
        val len = minOf(block.toLong(), fileSize - start).toInt()
        val buf = ByteArray(len)
        var done = 0
        while (done < len) {
            val n = file.read(buf, start + done, done, len - done)
            if (n <= 0) break
            done += n
        }
        return if (done == len) buf else buf.copyOf(done)
    }
}

/**
 * Miniature per l'anteprima sulla barra del tempo: un fotogramma ogni 10 secondi,
 * calcolato solo quando serve e tenuto in cache.
 */
class ThumbnailLoader(
    private val server: ServerConfig,
    private val path: String,
    private val onReady: (Long, Bitmap) -> Unit
) {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<Long, Bitmap>(60)
    @Volatile private var wanted = -1L
    @Volatile private var released = false
    private var retriever: MediaMetadataRetriever? = null
    private var file: File? = null
    private var failed = false

    private fun bucket(posMs: Long) = (posMs / 10_000L) * 10_000L

    fun request(posMs: Long) {
        if (released) return
        val b = bucket(posMs)
        cache.get(b)?.let { onReady(b, it); return }
        wanted = b
        exec.execute {
            if (released || wanted != b) return@execute
            val bmp = cache.get(b) ?: frame(b)?.also { cache.put(b, it) } ?: return@execute
            main.post { if (!released && wanted == b) onReady(b, bmp) }
        }
    }

    private fun open(): MediaMetadataRetriever {
        val f = SmbManager.openFile(server, path)
        file = f
        val r = MediaMetadataRetriever()
        r.setDataSource(SmbMediaDataSource(f, SmbManager.fileSize(f)))
        return r
    }

    private fun frame(posMs: Long): Bitmap? {
        if (failed) return null
        val r = retriever ?: try {
            open().also { retriever = it }
        } catch (e: Exception) {
            failed = true
            return null
        }
        return try {
            val us = posMs * 1000L
            if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
            } else {
                r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                    val h = (320f * it.height / it.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(it, 320, h, true)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun release() {
        released = true
        exec.execute {
            runCatching { retriever?.release() }
            runCatching { file?.close() }
        }
        exec.shutdown()
    }
}

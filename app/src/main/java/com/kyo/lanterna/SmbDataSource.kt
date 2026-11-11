package com.kyo.lanterna

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.smbj.share.File
import java.io.IOException

/** Sorgente dati ExoPlayer che legge da SMB passando per il Prefetcher. */
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }

    private var uri: Uri? = null
    private var file: File? = null
    private var prefetcher: Prefetcher? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val (server, path) = try {
            SmbUri.resolve(dataSpec.uri)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        }

        val f = try {
            SmbManager.openFile(server, path)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        file = f

        val size = try {
            f.getFileInformation(FileStandardInformation::class.java).endOfFile
        } catch (e: Exception) {
            throw DataSourceException(IOException(e.message, e), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }

        if (dataSpec.position > size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        val left = size - dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, left) else left

        val preset = Store.bufferPreset()
        val chunks = ((preset.readAheadMb.toLong() * 1024 * 1024) / Prefetcher.CHUNK).toInt().coerceAtLeast(2)
        prefetcher = Prefetcher({ b, o, bo, l -> f.read(b, o, bo, l) }, dataSpec.position, bytesRemaining, Prefetcher.CHUNK, chunks, SmbManager.pool, SmbManager.limiter())

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val p = prefetcher ?: return C.RESULT_END_OF_INPUT
        val n = p.read(buffer, offset, minOf(length.toLong(), bytesRemaining).toInt())
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        prefetcher?.close()
        prefetcher = null
        file?.let { f -> runCatching { f.close() } }
        file = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

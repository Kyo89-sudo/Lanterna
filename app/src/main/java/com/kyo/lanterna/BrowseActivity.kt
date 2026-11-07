package com.kyo.lanterna

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import java.util.concurrent.Executors

class BrowseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERVER = "server"
        const val EXTRA_PATH = "path"
        private val io = Executors.newSingleThreadExecutor()
    }

    private lateinit var server: ServerConfig
    private var path = ""
    private val adapter = RowAdapter(::onClick, ::onOptions)
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private var entries: List<SmbEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        server = Store.server(intent.getStringExtra(EXTRA_SERVER) ?: "") ?: run { finish(); return }
        path = intent.getStringExtra(EXTRA_PATH) ?: ""

        findViewById<TextView>(R.id.header).text =
            if (path.isEmpty()) server.name.ifBlank { server.host } else path.substringAfterLast('/')
        findViewById<TextView>(R.id.path).text =
            "\\\\${server.host}\\${server.share}\\" + path.replace('/', '\\')

        empty = findViewById(R.id.empty)
        empty.setOnClickListener { load() }
        recycler = findViewById(R.id.list)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        load()
    }

    override fun onRestart() {
        super.onRestart()
        // aggiorna le indicazioni "ripresa da" dopo aver guardato un video
        if (entries.isNotEmpty()) show(entries, focusFirst = false)
    }

    private fun load() {
        empty.text = "Caricamento…"
        empty.visibility = View.VISIBLE
        empty.isFocusable = false
        io.execute {
            val r = runCatching { SmbManager.list(server, path) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                r.onSuccess { show(it, focusFirst = true) }
                r.onFailure {
                    adapter.rows = emptyList()
                    empty.text = "Errore: ${it.message ?: it.javaClass.simpleName}\n\nPremi OK per riprovare"
                    empty.isFocusable = true
                    empty.requestFocus()
                }
            }
        }
    }

    private fun show(all: List<SmbEntry>, focusFirst: Boolean) {
        entries = all
        val dirs = all.filter { it.isDir }
        val videos = all.filter { !it.isDir && it.ext in VIDEO_EXT }
        adapter.rows = dirs.map { Row(it.name, "", "📁", it) } + videos.map { v ->
            val r = Store.resume(key(v))
            val extra = if (r > 0) "  ·  ripresa da ${formatTime(r)}" else ""
            val subs = if (sidecarSubs(v).isNotEmpty()) "  ·  sottotitoli esterni" else ""
            Row(v.name, formatSize(v.size) + subs + extra, if (r > 0) "◐" else "▶", v)
        }
        if (adapter.rows.isEmpty()) {
            empty.text = "Nessuna cartella o video qui"
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
            if (focusFirst) recycler.post { recycler.getChildAt(0)?.requestFocus() }
        }
    }

    private fun key(e: SmbEntry) = SmbUri.build(server.id, e.path).toString()

    private fun sidecarSubs(video: SmbEntry): List<SmbEntry> {
        val base = video.name.substringBeforeLast('.')
        return entries.filter { !it.isDir && it.ext in SUB_EXT && it.name.startsWith(base, ignoreCase = true) }
    }

    private fun onClick(row: Row) {
        val e = row.tag as? SmbEntry ?: return
        if (e.isDir) {
            startActivity(
                Intent(this, BrowseActivity::class.java)
                    .putExtra(EXTRA_SERVER, server.id)
                    .putExtra(EXTRA_PATH, e.path)
            )
            return
        }
        val r = Store.resume(key(e))
        if (r > 0) {
            AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(arrayOf("Riprendi da ${formatTime(r)}", "Dall'inizio")) { _, which ->
                    play(e, if (which == 0) r else 0L)
                }
                .show()
        } else play(e, 0L)
    }

    private fun onOptions(row: Row) {
        val e = row.tag as? SmbEntry ?: return
        if (e.isDir) return
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(arrayOf("Riproduci dall'inizio", "Test velocità SMB su questo file")) { _, which ->
                if (which == 0) play(e, 0L) else speedTest(e)
            }
            .show()
    }

    private fun play(e: SmbEntry, start: Long) {
        val base = e.name.substringBeforeLast('.')
        val subs = sidecarSubs(e)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URI, key(e))
                .putExtra(PlayerActivity.EXTRA_TITLE, e.name)
                .putExtra(PlayerActivity.EXTRA_START, start)
                .putStringArrayListExtra(PlayerActivity.EXTRA_SUB_URIS, ArrayList(subs.map { key(it) }))
                .putStringArrayListExtra(PlayerActivity.EXTRA_SUB_LABELS, ArrayList(subs.map {
                    it.name.substring(minOf(base.length, it.name.length)).substringBeforeLast('.').trim('.', ' ', '_', '-')
                }))
        )
    }

    private fun speedTest(e: SmbEntry) {
        val dlg = AlertDialog.Builder(this)
            .setTitle("Test velocità SMB")
            .setMessage("Lettura in corso (max 15 secondi)…")
            .setNegativeButton("Chiudi", null)
            .show()
        io.execute {
            val msg = try {
                val f = SmbManager.openFile(server, e.path)
                val size = f.getFileInformation(FileStandardInformation::class.java).endOfFile
                val len = minOf(size, 512L * 1024 * 1024)
                val p = Prefetcher({ b, o, bo, l -> f.read(b, o, bo, l) }, 0, len, Prefetcher.CHUNK, 16, SmbManager.pool)
                val buf = ByteArray(256 * 1024)
                var total = 0L
                val t0 = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - t0 < 15_000) {
                    val n = p.read(buf, 0, buf.size)
                    if (n < 0) break
                    total += n
                }
                val sec = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1) / 1000.0
                p.close()
                runCatching { f.close() }
                val mbps = total * 8 / 1_000_000.0 / sec
                "Letti ${formatSize(total)} in %.1f s\n\nVelocità: %.1f Mbps\n\n".format(sec, mbps) +
                    "Riferimento: 1080p H.265/AV1 ≈ 3–15 Mbps, 4K remux ≈ 40–100 Mbps."
            } catch (ex: Exception) {
                "Errore: ${ex.message ?: ex.javaClass.simpleName}"
            }
            runOnUiThread { if (dlg.isShowing) dlg.setMessage(msg) }
        }
    }
}

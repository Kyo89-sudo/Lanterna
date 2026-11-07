package com.kyo.lanterna

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private val adapter = RowAdapter(::onClick, ::onOptions)
    private lateinit var recycler: RecyclerView

    private object AddServer
    private object Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        findViewById<TextView>(R.id.header).text = getString(R.string.app_name)
        findViewById<TextView>(R.id.path).text = "Server SMB  ·  build " +
            packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<View>(R.id.empty).visibility = View.GONE
        recycler = findViewById(R.id.list)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val first = adapter.rows.isEmpty()
        adapter.rows = Store.servers().map {
            Row(it.name.ifBlank { it.host }, "\\\\${it.host}\\${it.share}" + if (it.user.isNotBlank()) "  ·  ${it.user}" else "", "🖧", it)
        } + Row("Aggiungi server", "Premi OK  ·  tieni premuto OK o ≡ su un server per modificarlo", "＋", AddServer) +
            Row("Impostazioni buffer", BUFFER_PRESETS[Store.bufferPresetIndex].label, "⚙", Settings)
        if (first) recycler.post { recycler.getChildAt(0)?.requestFocus() }
    }

    private fun onClick(row: Row) {
        when (val t = row.tag) {
            is ServerConfig -> startActivity(
                Intent(this, BrowseActivity::class.java)
                    .putExtra(BrowseActivity.EXTRA_SERVER, t.id)
                    .putExtra(BrowseActivity.EXTRA_PATH, "")
            )
            AddServer -> editServer(null)
            Settings -> chooseBuffer()
        }
    }

    private fun onOptions(row: Row) {
        val s = row.tag as? ServerConfig ?: return
        AlertDialog.Builder(this)
            .setTitle(row.title)
            .setItems(arrayOf("Modifica", "Elimina")) { _, which ->
                if (which == 0) editServer(s)
                else AlertDialog.Builder(this)
                    .setMessage("Eliminare il server \"${row.title}\"?")
                    .setPositiveButton("Elimina") { _, _ -> Store.deleteServer(s.id); refresh() }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
            .show()
    }

    private fun editServer(existing: ServerConfig?) {
        val v = layoutInflater.inflate(R.layout.dialog_server, null)
        val name = v.findViewById<EditText>(R.id.f_name)
        val host = v.findViewById<EditText>(R.id.f_host)
        val share = v.findViewById<EditText>(R.id.f_share)
        val user = v.findViewById<EditText>(R.id.f_user)
        val pass = v.findViewById<EditText>(R.id.f_pass)
        val domain = v.findViewById<EditText>(R.id.f_domain)
        existing?.let {
            name.setText(it.name)
            host.setText(if (it.port > 0) "${it.host}:${it.port}" else it.host)
            share.setText(it.share)
            user.setText(it.user)
            pass.setText(it.password)
            domain.setText(it.domain)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nuovo server SMB" else "Modifica server")
            .setView(v)
            .setPositiveButton("Salva") { _, _ ->
                val hostText = host.text.toString().trim().removePrefix("\\\\").removePrefix("smb://").trim('/', '\\')
                val h = hostText.substringBefore(':')
                val port = hostText.substringAfter(':', "").toIntOrNull() ?: 0
                val sh = share.text.toString().trim().trim('/', '\\')
                if (h.isEmpty() || sh.isEmpty()) {
                    Toast.makeText(this, "Indirizzo e cartella condivisa sono obbligatori", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                Store.saveServer(
                    ServerConfig(
                        id = existing?.id ?: "s${System.currentTimeMillis()}",
                        name = name.text.toString().trim(),
                        host = h,
                        port = port,
                        share = sh,
                        user = user.text.toString().trim(),
                        password = pass.text.toString(),
                        domain = domain.text.toString().trim()
                    )
                )
                refresh()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun chooseBuffer() {
        AlertDialog.Builder(this)
            .setTitle("Dimensione buffer")
            .setSingleChoiceItems(BUFFER_PRESETS.map { it.label }.toTypedArray(), Store.bufferPresetIndex) { d, which ->
                Store.bufferPresetIndex = which
                d.dismiss()
                refresh()
            }
            .show()
    }
}

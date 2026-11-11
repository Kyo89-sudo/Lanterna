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
import java.util.Calendar
import java.util.concurrent.Executors

/** Base comune: applica il tema (chiaro / scuro / automatico). */
abstract class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Store.init(this)
        Themes.apply()
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        Themes.apply()
    }
}

class MainActivity : BaseActivity() {

    private object AddServer

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var continueAdapter: CardAdapter
    private lateinit var serverAdapter: CardAdapter
    private lateinit var settingsAdapter: RowAdapter
    private lateinit var navHome: TextView
    private lateinit var navSettings: TextView
    private lateinit var homePage: View
    private lateinit var settingsPage: View
    private lateinit var continueLabel: View
    private lateinit var continueList: RecyclerView
    private lateinit var serverList: RecyclerView
    private lateinit var settingsList: RecyclerView
    private lateinit var greeting: TextView
    private lateinit var updateBanner: TextView
    private var firstFocusDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        navHome = findViewById(R.id.nav_home)
        navSettings = findViewById(R.id.nav_settings)
        homePage = findViewById(R.id.home_page)
        settingsPage = findViewById(R.id.settings_page)
        continueLabel = findViewById(R.id.continue_label)
        continueList = findViewById(R.id.continue_list)
        serverList = findViewById(R.id.server_list)
        settingsList = findViewById(R.id.settings_list)
        greeting = findViewById(R.id.greeting)
        updateBanner = findViewById(R.id.update_banner)
        findViewById<TextView>(R.id.version).text = "build ${Updater.currentBuild(this)}"

        continueAdapter = CardAdapter(::onContinueClick, ::onContinueOptions)
        serverAdapter = CardAdapter(::onServerClick, ::onServerOptions)
        settingsAdapter = RowAdapter(::onSettingClick)

        continueList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        continueList.adapter = continueAdapter
        serverList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        serverList.adapter = serverAdapter
        settingsList.layoutManager = LinearLayoutManager(this)
        settingsList.adapter = settingsAdapter

        navHome.setOnClickListener { showPage(0) }
        navSettings.setOnClickListener { showPage(1) }
        navHome.setOnFocusChangeListener { _, f -> if (f) showPage(0) }
        navSettings.setOnFocusChangeListener { _, f -> if (f) showPage(1) }
        updateBanner.setOnClickListener { startUpdate() }
        showPage(0)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        checkUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun showPage(p: Int) {
        homePage.visibility = if (p == 0) View.VISIBLE else View.GONE
        settingsPage.visibility = if (p == 1) View.VISIBLE else View.GONE
        navHome.isSelected = p == 0
        navSettings.isSelected = p == 1
    }

    private fun refresh() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greeting.text = when (h) {
            in 5..11 -> "Buongiorno"
            in 12..17 -> "Buon pomeriggio"
            else -> "Buonasera"
        }

        val history = Store.history().filter { item ->
            runCatching { SmbUri.resolve(android.net.Uri.parse(item.key)) }.isSuccess
        }
        continueAdapter.cards = history.map { item ->
            val pct = if (item.durMs > 0) (item.posMs * 100 / item.durMs).toInt() else -1
            val sub = if (item.durMs > 0) "Mancano ${formatTime(item.durMs - item.posMs)}" else "Ripresa da ${formatTime(item.posMs)}"
            Card(item.title.substringBeforeLast('.'), sub, R.drawable.ic_video, item, pct)
        }
        val hasContinue = history.isNotEmpty()
        continueLabel.visibility = if (hasContinue) View.VISIBLE else View.GONE
        continueList.visibility = if (hasContinue) View.VISIBLE else View.GONE

        serverAdapter.cards = Store.servers().map {
            Card(it.name.ifBlank { it.host }, "\\\\${it.host}\\${it.share}", R.drawable.ic_server, it)
        } + Card("Aggiungi o trova in rete", "Cerca i PC con cartelle condivise", R.drawable.ic_add, AddServer)

        settingsAdapter.rows = listOf(
            Row("Tema", Themes.LABELS[Store.theme], R.drawable.ic_theme, "theme", accent = true),
            Row("Buffer", BUFFER_PRESETS[Store.bufferPresetIndex].label.replace(Regex("\\s+"), " "), R.drawable.ic_speed, "buffer", accent = true),
            Row("Letture parallele dal server", "${Store.parallel} connessioni  ·  prova con il test velocità", R.drawable.ic_server, "parallel", accent = true),
            Row("Adatta frequenza del TV al film", if (Store.frameRateMatch) "Sì (es. 23,976 Hz per i film)" else "No", R.drawable.ic_aspect, "fps", accent = true),
            Row("Anteprima miniature sulla barra del tempo", if (Store.thumbnails) "Sì" else "No", R.drawable.ic_video, "thumbs", accent = true),
            Row("Aggiornamenti", "Versione installata: build ${Updater.currentBuild(this)}  ·  premi OK per controllare", R.drawable.ic_update, "update", accent = true)
        )
        showUpdateBanner()

        if (!firstFocusDone) {
            firstFocusDone = true
            val target = if (hasContinue) continueList else serverList
            target.post { target.getChildAt(0)?.requestFocus() }
        }
    }

    // ---------------- continua a guardare ----------------

    private fun onContinueClick(c: Card) {
        val item = c.tag as? HistoryItem ?: return
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URI, item.key)
                .putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                .putExtra(PlayerActivity.EXTRA_START, item.posMs)
        )
    }

    private fun onContinueOptions(c: Card) {
        val item = c.tag as? HistoryItem ?: return
        AlertDialog.Builder(this)
            .setTitle(c.title)
            .setItems(arrayOf("Riproduci dall'inizio", "Segna come visto", "Rimuovi da Continua a guardare")) { _, w ->
                when (w) {
                    0 -> startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_URI, item.key)
                            .putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                            .putExtra(PlayerActivity.EXTRA_START, 0L)
                    )
                    1 -> {
                        Store.clearProgress(item.key); Store.setWatched(item.key, true); refresh()
                    }
                    2 -> {
                        Store.clearProgress(item.key); refresh()
                    }
                }
            }
            .show()
    }

    // ---------------- server ----------------

    private fun onServerClick(c: Card) {
        when (val t = c.tag) {
            is ServerConfig -> startActivity(
                Intent(this, BrowseActivity::class.java)
                    .putExtra(BrowseActivity.EXTRA_SERVER, t.id)
                    .putExtra(BrowseActivity.EXTRA_PATH, "")
            )
            AddServer -> AlertDialog.Builder(this)
                .setTitle("Aggiungi server")
                .setItems(arrayOf("Cerca nella rete", "Inserisci a mano")) { _, w ->
                    if (w == 0) discover() else editServer(null)
                }
                .show()
        }
    }

    private fun onServerOptions(c: Card) {
        val s = c.tag as? ServerConfig ?: return
        AlertDialog.Builder(this)
            .setTitle(c.title)
            .setItems(arrayOf("Modifica", "Elimina")) { _, which ->
                if (which == 0) editServer(s)
                else AlertDialog.Builder(this)
                    .setMessage("Eliminare il server \"${c.title}\"?")
                    .setPositiveButton("Elimina") { _, _ -> Store.deleteServer(s.id); refresh() }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
            .show()
    }

    private fun discover() {
        val dlg = AlertDialog.Builder(this)
            .setTitle("Ricerca nella rete")
            .setMessage("Cerco i PC con cartelle condivise… 0%")
            .setNegativeButton("Annulla", null)
            .show()
        io.execute {
            val result = runCatching {
                Discovery.scan(this) { pct ->
                    runOnUiThread { if (dlg.isShowing) dlg.setMessage("Cerco i PC con cartelle condivise… $pct%") }
                }
            }
            runOnUiThread {
                if (isFinishing || !dlg.isShowing) return@runOnUiThread
                dlg.dismiss()
                result.onFailure {
                    Toast.makeText(this, "Ricerca non riuscita: ${it.message}", Toast.LENGTH_LONG).show()
                    editServer(null)
                }
                result.onSuccess { hosts ->
                    if (hosts.isEmpty()) {
                        AlertDialog.Builder(this)
                            .setTitle("Nessun PC trovato")
                            .setMessage("Controlla che il PC sia acceso, nella stessa rete e con la cartella condivisa.")
                            .setPositiveButton("Inserisci a mano") { _, _ -> editServer(null) }
                            .setNegativeButton("Chiudi", null)
                            .show()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("PC trovati")
                            .setItems(hosts.map { "${it.name}     ${it.ip}" }.toTypedArray()) { _, w ->
                                editServer(null, hosts[w].ip, hosts[w].name)
                            }
                            .show()
                    }
                }
            }
        }
    }

    private fun editServer(existing: ServerConfig?, hostHint: String? = null, nameHint: String? = null) {
        val v = layoutInflater.inflate(R.layout.dialog_server, null)
        val name = v.findViewById<EditText>(R.id.f_name)
        val host = v.findViewById<EditText>(R.id.f_host)
        val share = v.findViewById<EditText>(R.id.f_share)
        val user = v.findViewById<EditText>(R.id.f_user)
        val pass = v.findViewById<EditText>(R.id.f_pass)
        val domain = v.findViewById<EditText>(R.id.f_domain)
        if (existing != null) {
            name.setText(existing.name)
            host.setText(if (existing.port > 0) "${existing.host}:${existing.port}" else existing.host)
            share.setText(existing.share)
            user.setText(existing.user)
            pass.setText(existing.password)
            domain.setText(existing.domain)
        } else {
            hostHint?.let { host.setText(it) }
            nameHint?.let { name.setText(it) }
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

    // ---------------- impostazioni ----------------

    private fun choose(title: String, options: List<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), current) { d, w ->
                d.dismiss()
                onPick(w)
                refresh()
            }
            .show()
    }

    private fun onSettingClick(r: Row) {
        when (r.tag) {
            "theme" -> choose("Tema", Themes.LABELS, Store.theme) {
                Store.theme = it
                Themes.apply()
            }
            "buffer" -> choose("Buffer", BUFFER_PRESETS.map { it.label }, Store.bufferPresetIndex) {
                Store.bufferPresetIndex = it
            }
            "parallel" -> choose(
                "Letture parallele",
                PARALLEL_OPTIONS.map { "$it connessioni" },
                PARALLEL_OPTIONS.indexOf(Store.parallel).coerceAtLeast(0)
            ) { Store.parallel = PARALLEL_OPTIONS[it] }
            "fps" -> choose("Adatta frequenza del TV", listOf("Sì", "No"), if (Store.frameRateMatch) 0 else 1) {
                Store.frameRateMatch = it == 0
            }
            "thumbs" -> choose("Anteprima miniature", listOf("Sì", "No"), if (Store.thumbnails) 0 else 1) {
                Store.thumbnails = it == 0
            }
            "update" -> checkUpdates(force = true, interactive = true)
        }
    }

    // ---------------- aggiornamenti ----------------

    private fun checkUpdates(force: Boolean = false, interactive: Boolean = false) {
        if (!force && System.currentTimeMillis() - Store.lastUpdateCheck < 6 * 3600_000L) {
            showUpdateBanner()
            return
        }
        if (interactive) Toast.makeText(this, "Controllo aggiornamenti…", Toast.LENGTH_SHORT).show()
        io.execute {
            val r = runCatching { Updater.latestBuild() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                r.onSuccess { latest ->
                    Store.latestBuild = latest
                    Store.lastUpdateCheck = System.currentTimeMillis()
                    showUpdateBanner()
                    if (interactive) {
                        if (latest > Updater.currentBuild(this)) startUpdate()
                        else Toast.makeText(this, "Hai già l'ultima versione", Toast.LENGTH_LONG).show()
                    }
                }
                r.onFailure {
                    if (interactive) Toast.makeText(this, "Controllo non riuscito: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showUpdateBanner() {
        val latest = Store.latestBuild
        if (latest > Updater.currentBuild(this)) {
            updateBanner.text = "Aggiornamento disponibile: build $latest   ·   premi OK per installarlo"
            updateBanner.visibility = View.VISIBLE
        } else updateBanner.visibility = View.GONE
    }

    private fun startUpdate() {
        if (!Updater.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle("Permesso necessario")
                .setMessage(
                    "Per aggiornarsi da sola, Lanterna deve poter installare app.\n\n" +
                        "Se il pulsante non funziona: Impostazioni › La mia Fire TV › Opzioni sviluppatore › " +
                        "Installa app sconosciute › Lanterna."
                )
                .setPositiveButton("Apri impostazioni") { _, _ ->
                    if (!Updater.openInstallPermission(this))
                        Toast.makeText(this, "Apri le impostazioni a mano", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Annulla", null)
                .show()
            return
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Aggiornamento")
            .setMessage("Download in corso… 0%")
            .setCancelable(false)
            .show()
        io.execute {
            val r = runCatching {
                Updater.download(this) { pct -> runOnUiThread { dlg.setMessage("Download in corso… $pct%") } }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                dlg.dismiss()
                r.onSuccess { runCatching { Updater.install(this, it) }.onFailure { e ->
                    Toast.makeText(this, "Installazione non avviata: ${e.message}", Toast.LENGTH_LONG).show()
                } }
                r.onFailure { Toast.makeText(this, "Download non riuscito: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}

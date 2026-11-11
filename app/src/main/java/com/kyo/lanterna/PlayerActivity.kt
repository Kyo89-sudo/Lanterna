package com.kyo.lanterna

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.TimeBar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START = "start"
        private val RESIZE_MODES = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        )
        private const val CUES_MIME = "application/x-media3-cues"
    }

    private class Episode(val entry: SmbEntry, val key: String, val subs: List<SmbEntry>)

    private lateinit var player: ExoPlayer
    private lateinit var renderers: DelayRenderersFactory
    private lateinit var playerView: PlayerView
    private lateinit var controls: View
    private lateinit var titleView: TextView
    private lateinit var pathView: TextView
    private lateinit var badges: TextView
    private lateinit var timeBar: DefaultTimeBar
    private lateinit var posView: TextView
    private lateinit var remainingView: TextView
    private lateinit var thumbBox: View
    private lateinit var thumbImage: ImageView
    private lateinit var thumbTime: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnRew: ImageButton
    private lateinit var btnFwd: ImageButton
    private lateinit var btnAudio: TextView
    private lateinit var btnSubs: TextView
    private lateinit var btnChapters: TextView
    private lateinit var btnAspect: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var panel: View
    private lateinit var panelTitle: TextView
    private lateinit var panelList: RecyclerView
    private lateinit var nextCard: View
    private lateinit var nextLabel: TextView
    private lateinit var nextTitle: TextView
    private lateinit var nextPlay: TextView
    private lateinit var nextCancel: TextView
    private lateinit var osd: TextView
    private lateinit var stats: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val panelAdapter = PanelAdapter()
    private var panelBuilder: (() -> List<PanelItem>)? = null
    private var panelReturnFocus: View? = null

    private lateinit var server: ServerConfig
    private var folder = ""
    private var folderKey = ""
    private var playlist: List<Episode> = emptyList()
    private var index = 0
    private var uriKey = ""
    private var title = ""
    private var chapters: List<Chapter> = emptyList()
    private var mkvFps = 0f
    private var generation = 0
    private var prefsApplied = false
    private var fpsMatched = false
    private var thumbs: ThumbnailLoader? = null
    private var scrubbing = false
    private var speedIdx = 1
    private val speeds = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    private var seekTarget = -1L
    private var nextCountdown = 0

    private var videoDecoder = "-"
    private var audioDecoder = "-"
    private var lastBytes = 0L
    private var lastTime = 0L
    private var rebuffers = 0
    private var lastRebufferAt = ""

    // ---------------- avvio ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        Store.init(this)
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        bindViews()

        val uriStr = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        val resolved = try {
            SmbUri.resolve(Uri.parse(uriStr))
        } catch (e: Exception) {
            finish(); return
        }
        server = resolved.first
        val path = resolved.second
        folder = path.substringBeforeLast('/', "")
        folderKey = SmbUri.build(server.id, folder).toString()
        val start = intent.getLongExtra(EXTRA_START, 0L)

        player = buildPlayer()
        playerView.player = player
        playerView.useController = false
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        playerView.resizeMode = RESIZE_MODES[Store.resizeMode]
        applySubtitleStyle()
        setupControls()

        val current = SmbEntry(path.substringAfterLast('/'), path, false, 0)
        playlist = listOf(Episode(current, uriStr, emptyList()))
        showOsd(intent.getStringExtra(EXTRA_TITLE) ?: current.name, 3000)

        // elenco della cartella: episodi (precedente/successivo) e sottotitoli esterni
        io.execute {
            val list = runCatching { SmbManager.list(server, folder) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (list != null) buildPlaylist(list, path)
                playIndex(index, start)
            }
        }
        handler.post(saveTick)
    }

    private fun bindViews() {
        playerView = findViewById(R.id.player_view)
        controls = findViewById(R.id.controls)
        titleView = findViewById(R.id.title)
        pathView = findViewById(R.id.path_line)
        badges = findViewById(R.id.badges)
        timeBar = findViewById(R.id.time_bar)
        posView = findViewById(R.id.pos)
        remainingView = findViewById(R.id.remaining)
        thumbBox = findViewById(R.id.thumb_box)
        thumbImage = findViewById(R.id.thumb)
        thumbTime = findViewById(R.id.thumb_time)
        btnPlay = findViewById(R.id.btn_play)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        btnRew = findViewById(R.id.btn_rew)
        btnFwd = findViewById(R.id.btn_fwd)
        btnAudio = findViewById(R.id.btn_audio)
        btnSubs = findViewById(R.id.btn_subs)
        btnChapters = findViewById(R.id.btn_chapters)
        btnAspect = findViewById(R.id.btn_aspect)
        btnSpeed = findViewById(R.id.btn_speed)
        panel = findViewById(R.id.panel)
        panelTitle = findViewById(R.id.panel_title)
        panelList = findViewById(R.id.panel_list)
        nextCard = findViewById(R.id.next_card)
        nextLabel = findViewById(R.id.next_label)
        nextTitle = findViewById(R.id.next_title)
        nextPlay = findViewById(R.id.next_play)
        nextCancel = findViewById(R.id.next_cancel)
        osd = findViewById(R.id.osd)
        stats = findViewById(R.id.stats)
    }

    private fun buildPlaylist(list: List<SmbEntry>, path: String) {
        val videos = list.filter { !it.isDir && it.ext in VIDEO_EXT }
        if (videos.isEmpty()) return
        playlist = videos.map { v ->
            val base = v.name.substringBeforeLast('.')
            Episode(
                v,
                SmbUri.build(server.id, v.path).toString(),
                list.filter { !it.isDir && it.ext in SUB_EXT && it.name.startsWith(base, ignoreCase = true) }
            )
        }
        index = playlist.indexOfFirst { it.entry.path == path }.coerceAtLeast(0)
    }

    private fun buildPlayer(): ExoPlayer {
        val preset = Store.bufferPreset()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 600_000, 5_000, 8_000)
            .setTargetBufferBytes(preset.playerMb * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(20_000, false)
            .build()

        // Decoder hardware sempre per primi (AV1/HEVC sulla Fire TV).
        val codecSelector = MediaCodecSelector { mimeType, secure, tunneling ->
            MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
                .sortedByDescending { it.hardwareAccelerated }
        }
        renderers = DelayRenderersFactory(this).apply {
            setMediaCodecSelector(codecSelector)
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        }

        val trackSelector = DefaultTrackSelector(this)
        trackSelector.setParameters(
            trackSelector.buildUponParameters().setPreferredAudioLanguage(Locale.getDefault().language)
        )

        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val sources = DefaultMediaSourceFactory(SmbDataSource.Factory(), extractors)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(10))

        val p = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .build()

        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long
            ) {
                videoDecoder = decoderName
                updateBadges()
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long
            ) {
                audioDecoder = decoderName
            }
        })

        p.addListener(object : Player.Listener {
            private var wasReady = false
            private var seeking = false

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) seeking = true
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        wasReady = true
                        seeking = false
                    }
                    Player.STATE_BUFFERING -> if (wasReady && !seeking) {
                        rebuffers++
                        lastRebufferAt = formatTime(player.currentPosition)
                    }
                    Player.STATE_ENDED -> onEnded()
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                btnPlay.contentDescription = if (isPlaying) "Pausa" else "Riproduci"
            }

            override fun onTracksChanged(tracks: Tracks) {
                applySeriesPrefs()
                updateBadges()
                maybeMatchFrameRate()
                if (panel.visibility == View.VISIBLE) refreshPanel(focusedPanelPos())
            }

            override fun onPlayerError(error: PlaybackException) = showError(error)
        })
        return p
    }

    private fun buildMediaItem(ep: Episode): MediaItem {
        val base = ep.entry.name.substringBeforeLast('.')
        val subs = ep.subs.map { s ->
            val label = s.name.substring(minOf(base.length, s.name.length)).substringBeforeLast('.').trim('.', ' ', '_', '-')
            val mime = when (s.ext) {
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "vtt" -> MimeTypes.TEXT_VTT
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            val lang = label.split('.', ' ', '_', '-').firstOrNull { it.length in 2..3 }
            MediaItem.SubtitleConfiguration.Builder(SmbUri.build(server.id, s.path))
                .setMimeType(mime)
                .setLanguage(lang)
                .setLabel(if (label.isBlank()) "Esterni" else "Esterni ($label)")
                .build()
        }
        return MediaItem.Builder().setUri(ep.key).setSubtitleConfigurations(subs).build()
    }

    /** Avvia l'episodio i della cartella. */
    private fun playIndex(i: Int, startMs: Long) {
        if (i !in playlist.indices) return
        if (uriKey.isNotEmpty()) saveProgress()
        index = i
        val ep = playlist[i]
        uriKey = ep.key
        title = ep.entry.name
        generation++
        prefsApplied = false
        fpsMatched = false
        chapters = emptyList()
        mkvFps = 0f
        rebuffers = 0
        lastRebufferAt = ""
        seekTarget = -1L
        hideNextCard()
        thumbs?.release()
        thumbs = null

        val prefs = Store.seriesPrefs(folderKey)
        renderers.subDelayUs = prefs.subDelayMs * 1000L
        renderers.audioDelayUs = prefs.audioDelayMs * 1000L

        titleView.text = title.substringBeforeLast('.')
        pathView.text = (listOf(server.name.ifBlank { server.host }) + folder.split('/').filter { it.isNotEmpty() })
            .joinToString("  ›  ")
        btnPrev.alpha = if (i > 0) 1f else 0.4f
        btnNext.alpha = if (i + 1 < playlist.size) 1f else 0.4f
        btnChapters.visibility = View.GONE
        timeBar.setAdGroupTimesMs(null, null, 0)

        player.setMediaItem(buildMediaItem(ep), startMs)
        player.prepare()
        player.playWhenReady = true
        loadMkvInfo(ep, generation)
    }

    private fun loadMkvInfo(ep: Episode, gen: Int) {
        if (ep.entry.ext != "mkv" && ep.entry.ext != "webm") return
        io.execute {
            val info = runCatching {
                val f = SmbManager.openFile(server, ep.entry.path)
                try {
                    Mkv.read(RandomReader { pos, b, o, l -> f.read(b, pos, o, l) }, SmbManager.fileSize(f))
                } finally {
                    runCatching { f.close() }
                }
            }.getOrNull() ?: return@execute
            runOnUiThread {
                if (gen != generation || isDestroyed) return@runOnUiThread
                chapters = info.chapters
                mkvFps = info.frameRate
                val marks = chapters.filter { it.startMs > 0 }
                if (chapters.size > 1) {
                    btnChapters.visibility = View.VISIBLE
                    timeBar.setAdGroupTimesMs(marks.map { it.startMs }.toLongArray(), BooleanArray(marks.size), marks.size)
                }
                maybeMatchFrameRate()
            }
        }
    }

    // ---------------- controlli ----------------

    private fun setupControls() {
        timeBar.setKeyTimeIncrement(10_000)
        timeBar.setPlayedColor(0xFF0A84FF.toInt())
        timeBar.setScrubberColor(Color.WHITE)
        timeBar.setBufferedColor(0x66FFFFFF)
        timeBar.setUnplayedColor(0x29FFFFFF)
        timeBar.setAdMarkerColor(0xE6FFFFFF.toInt())
        timeBar.setPlayedAdMarkerColor(0xE6FFFFFF.toInt())
        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(tb: TimeBar, position: Long) {
                scrubbing = true
                showPreview(position)
            }

            override fun onScrubMove(tb: TimeBar, position: Long) = showPreview(position)

            override fun onScrubStop(tb: TimeBar, position: Long, canceled: Boolean) {
                scrubbing = false
                thumbBox.visibility = View.GONE
                if (!canceled) player.seekTo(position)
                scheduleHide()
            }
        })

        btnPlay.setOnClickListener { togglePlay() }
        btnRew.setOnClickListener { seekBy(-10_000) }
        btnFwd.setOnClickListener { seekBy(10_000) }
        btnPrev.setOnClickListener { previous() }
        btnNext.setOnClickListener { next() }
        btnAudio.setOnClickListener { openAudioPanel() }
        btnSubs.setOnClickListener { openSubsPanel() }
        btnChapters.setOnClickListener { openChaptersPanel() }
        btnAspect.setOnClickListener { cycleAspect() }
        btnSpeed.setOnClickListener { cycleSpeed() }
        btnAspect.text = RESIZE_LABELS[Store.resizeMode]
        nextPlay.setOnClickListener { playNext() }
        nextCancel.setOnClickListener { finish() }

        panelList.layoutManager = LinearLayoutManager(this)
        panelList.adapter = panelAdapter
        panelList.itemAnimator = null
        panelAdapter.onChoiceDone = { pos -> refreshPanel(pos) }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            if (controls.visibility != View.VISIBLE) return
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private fun updateProgress() {
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val pos = if (seekTarget >= 0) seekTarget else player.currentPosition
        timeBar.setDuration(dur)
        if (!scrubbing) timeBar.setPosition(pos)
        timeBar.setBufferedPosition(player.bufferedPosition)
        posView.text = formatTime(pos)
        remainingView.text = "-" + formatTime((dur - pos).coerceAtLeast(0))
        chapterAt(pos)?.let { pathView.text = it.title }
    }

    private val saveTick = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) saveProgress()
            handler.postDelayed(this, 15_000)
        }
    }

    private val hideRunnable = Runnable {
        if (player.isPlaying && panel.visibility != View.VISIBLE && !scrubbing) hideControls() else scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 5000)
    }

    private fun showControls(focus: View? = btnPlay) {
        val wasHidden = controls.visibility != View.VISIBLE
        controls.visibility = View.VISIBLE
        if (wasHidden) {
            controls.alpha = 0f
            controls.animate().alpha(1f).setDuration(150).start()
            updateBadges()
            focus?.requestFocus()
            handler.removeCallbacks(progressTick)
            handler.post(progressTick)
        }
        scheduleHide()
    }

    private fun hideControls() {
        controls.visibility = View.GONE
        thumbBox.visibility = View.GONE
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(progressTick)
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    private val doSeek = Runnable {
        if (seekTarget >= 0) {
            player.seekTo(seekTarget)
            seekTarget = -1L
        }
    }

    /** I salti ripetuti si sommano e partono con un solo seek (meno ricaricamenti). */
    private fun seekBy(delta: Long) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        val base = if (seekTarget >= 0) seekTarget else player.currentPosition
        seekTarget = (base + delta).coerceIn(0, dur)
        handler.removeCallbacks(doSeek)
        handler.postDelayed(doSeek, 500)
        val total = if (dur == Long.MAX_VALUE) "" else " / ${formatTime(dur)}"
        showOsd((if (delta < 0) "⏪  " else "⏩  ") + formatTime(seekTarget) + total, 1200)
        if (controls.visibility == View.VISIBLE) updateProgress()
    }

    private fun previous() {
        if (player.currentPosition > 5000 || index == 0) {
            player.seekTo(0)
        } else {
            playIndex(index - 1, Store.resume(playlist[index - 1].key))
        }
    }

    private fun next() {
        if (index + 1 < playlist.size) playIndex(index + 1, Store.resume(playlist[index + 1].key))
        else showOsd("È l'ultimo video della cartella", 1500)
    }

    private fun cycleAspect() {
        Store.resizeMode = (Store.resizeMode + 1) % RESIZE_LABELS.size
        playerView.resizeMode = RESIZE_MODES[Store.resizeMode]
        btnAspect.text = RESIZE_LABELS[Store.resizeMode]
        showOsd("Formato: ${RESIZE_LABELS[Store.resizeMode]}", 1200)
    }

    private fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.size
        player.setPlaybackSpeed(speeds[speedIdx])
        val s = speeds[speedIdx]
        btnSpeed.text = (if (s == s.toInt().toFloat()) "${s.toInt()}" else "$s".replace('.', ',')) + "×"
        showOsd("Velocità ${btnSpeed.text}", 1200)
    }

    private fun chapterAt(posMs: Long): Chapter? = chapters.lastOrNull { it.startMs <= posMs }

    private fun showPreview(posMs: Long) {
        val dur = player.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        thumbTime.text = formatTime(posMs) + (chapterAt(posMs)?.let { "   ·   ${it.title}" } ?: "")
        thumbBox.visibility = View.VISIBLE
        thumbBox.post {
            val parent = thumbBox.parent as View
            val barLoc = IntArray(2)
            val parentLoc = IntArray(2)
            timeBar.getLocationOnScreen(barLoc)
            parent.getLocationOnScreen(parentLoc)
            val frac = posMs.toFloat() / dur
            val x = barLoc[0] - parentLoc[0] + frac * timeBar.width - thumbBox.width / 2f
            thumbBox.translationX = x.coerceIn(0f, (parent.width - thumbBox.width).toFloat().coerceAtLeast(0f))
        }
        if (Store.thumbnails && playlist.isNotEmpty()) {
            thumbImage.visibility = View.VISIBLE
            val loader = thumbs ?: ThumbnailLoader(server, playlist[index].entry.path) { _, bmp ->
                if (thumbBox.visibility == View.VISIBLE) thumbImage.setImageBitmap(bmp)
            }.also { thumbs = it }
            loader.request(posMs)
        } else thumbImage.visibility = View.GONE
    }

    // ---------------- telecomando ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val code = event.keyCode

        if (down) when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-30_000); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { next(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { previous(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { toggleStats(); return true }
        }

        if (nextCard.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (down) finish()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (panel.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (down) hidePanel()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (controls.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (down) hideControls()
                return true
            }
            scheduleHide()
            return super.dispatchKeyEvent(event)
        }

        // controlli nascosti
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down) {
                    togglePlay(); showControls()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) {
                    val step = if (event.repeatCount > 8) 30_000L else 10_000L
                    seekBy(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) showControls()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showOsd(text: String, ms: Long) {
        osd.text = text
        osd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, ms)
    }

    private val hideOsd = Runnable { osd.visibility = View.GONE }

    // ---------------- pannelli (audio, sottotitoli, capitoli) ----------------

    private fun showPanel(title: String, builder: () -> List<PanelItem>) {
        panelReturnFocus = currentFocus
        panelTitle.text = title
        panelBuilder = builder
        panelAdapter.items = builder()
        hideControls()
        panel.visibility = View.VISIBLE
        val checked = panelAdapter.items.indexOfFirst { it is PanelItem.Choice && it.checked }
        val first = panelAdapter.items.indexOfFirst { it !is PanelItem.Header }.coerceAtLeast(0)
        focusPanel(if (checked >= 0) checked else first)
    }

    private fun focusPanel(pos: Int) {
        if (panelAdapter.itemCount == 0) return
        val p = pos.coerceIn(0, panelAdapter.itemCount - 1)
        panelList.scrollToPosition(p)
        panelList.post { panelList.findViewHolderForAdapterPosition(p)?.itemView?.requestFocus() }
    }

    private fun focusedPanelPos(): Int =
        panelList.focusedChild?.let { panelList.getChildAdapterPosition(it) }?.takeIf { it >= 0 } ?: 0

    private fun refreshPanel(pos: Int) {
        if (panel.visibility != View.VISIBLE) return
        val b = panelBuilder ?: return
        panelAdapter.items = b()
        focusPanel(pos)
    }

    private fun hidePanel() {
        panel.visibility = View.GONE
        panelBuilder = null
        showControls(panelReturnFocus ?: btnPlay)
        (panelReturnFocus ?: btnPlay).requestFocus()
    }

    private fun tracksOf(type: Int): List<Pair<Tracks.Group, Int>> {
        val out = ArrayList<Pair<Tracks.Group, Int>>()
        for (g in player.currentTracks.groups) {
            if (g.type != type) continue
            for (j in 0 until g.length) if (g.isTrackSupported(j)) out.add(g to j)
        }
        return out
    }

    private fun findTrack(type: Int, pred: (Format) -> Boolean): Pair<Tracks.Group, Int>? =
        tracksOf(type).firstOrNull { (g, j) -> pred(g.getTrackFormat(j)) }

    private fun prefs() = Store.seriesPrefs(folderKey)

    private fun updatePrefs(f: (SeriesPrefs) -> SeriesPrefs) = Store.saveSeriesPrefs(folderKey, f(prefs()))

    private fun openAudioPanel() = showPanel("Audio") {
        val items = ArrayList<PanelItem>()
        items.add(PanelItem.Header("Traccia audio"))
        tracksOf(C.TRACK_TYPE_AUDIO).forEach { (g, j) ->
            val f = g.getTrackFormat(j)
            items.add(PanelItem.Choice(trackTitle(f), trackDetails(f), g.isTrackSelected(j)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, j))
                    .build()
                updatePrefs { it.copy(audioLang = f.language, audioLabel = f.label) }
            })
        }
        items.add(PanelItem.Header("Sincronizzazione (ricordata per questa cartella)"))
        items.add(PanelItem.Value("Ritardo audio", { "%+d ms".format(prefs().audioDelayMs) }) { d ->
            val v = (prefs().audioDelayMs + d * 50).coerceIn(-3000, 3000)
            updatePrefs { it.copy(audioDelayMs = v) }
            renderers.audioDelayUs = v * 1000L
        })
        items
    }

    private fun openSubsPanel() = showPanel("Sottotitoli") {
        val items = ArrayList<PanelItem>()
        items.add(PanelItem.Header("Traccia"))
        val textTracks = tracksOf(C.TRACK_TYPE_TEXT)
        val anySelected = textTracks.any { (g, j) -> g.isTrackSelected(j) }
        items.add(PanelItem.Choice("Disattivati", "", !anySelected) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            updatePrefs { it.copy(subOff = true) }
        })
        textTracks.forEach { (g, j) ->
            val f = g.getTrackFormat(j)
            items.add(PanelItem.Choice(trackTitle(f), trackDetails(f), g.isTrackSelected(j)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, j))
                    .build()
                updatePrefs {
                    it.copy(subOff = false, subLang = f.language, subLabel = f.label, subForced = isForced(f))
                }
            })
        }
        items.add(PanelItem.Header("Sincronizzazione (ricordata per questa cartella)"))
        items.add(PanelItem.Value("Ritardo sottotitoli", { "%+d ms".format(prefs().subDelayMs) }) { d ->
            val v = (prefs().subDelayMs + d * 50).coerceIn(-10_000, 10_000)
            updatePrefs { it.copy(subDelayMs = v) }
            renderers.subDelayUs = v * 1000L
        })
        items.add(PanelItem.Header("Aspetto"))
        items.add(PanelItem.Value("Dimensione", { SUB_SIZES[Store.subSize].first }) { d ->
            Store.subSize = (Store.subSize + d + SUB_SIZES.size) % SUB_SIZES.size
            applySubtitleStyle()
        })
        items.add(PanelItem.Value("Colore", { SUB_COLORS[Store.subColor].first }) { d ->
            Store.subColor = (Store.subColor + d + SUB_COLORS.size) % SUB_COLORS.size
            applySubtitleStyle()
        })
        items.add(PanelItem.Value("Sfondo", { SUB_BGS[Store.subBg].first }) { d ->
            Store.subBg = (Store.subBg + d + SUB_BGS.size) % SUB_BGS.size
            applySubtitleStyle()
        })
        items
    }

    private fun openChaptersPanel() = showPanel("Capitoli") {
        val current = chapterAt(player.currentPosition)
        chapters.map { c ->
            PanelItem.Choice(c.title, formatTime(c.startMs), c == current) {
                player.seekTo(c.startMs)
                hidePanel()
            }
        }
    }

    private fun applySeriesPrefs() {
        if (prefsApplied || player.currentTracks.groups.isEmpty()) return
        prefsApplied = true
        val p = prefs()
        val b = player.trackSelectionParameters.buildUpon()
        var changed = false
        p.audioLang?.let { lang ->
            val t = findTrack(C.TRACK_TYPE_AUDIO) { it.language == lang && it.label == p.audioLabel }
                ?: findTrack(C.TRACK_TYPE_AUDIO) { it.language == lang }
            if (t != null) {
                b.setOverrideForType(TrackSelectionOverride(t.first.mediaTrackGroup, t.second))
                changed = true
            }
        }
        if (p.subOff) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            changed = true
        } else p.subLang?.let { lang ->
            val t = findTrack(C.TRACK_TYPE_TEXT) { it.language == lang && isForced(it) == p.subForced && it.label == p.subLabel }
                ?: findTrack(C.TRACK_TYPE_TEXT) { it.language == lang && isForced(it) == p.subForced }
                ?: findTrack(C.TRACK_TYPE_TEXT) { it.language == lang }
            if (t != null) {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                b.setOverrideForType(TrackSelectionOverride(t.first.mediaTrackGroup, t.second))
                changed = true
            }
        }
        if (changed) player.trackSelectionParameters = b.build()
    }

    private fun isForced(f: Format) = (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0

    private fun langName(code: String?): String =
        if (code.isNullOrBlank() || code == "und") "Lingua sconosciuta"
        else Locale(code).getDisplayLanguage(Locale.ITALIAN).replaceFirstChar { it.titlecase(Locale.ITALIAN) }

    private fun trackTitle(f: Format): String = buildString {
        val lang = langName(f.language)
        append(lang)
        val label = f.label
        if (!label.isNullOrBlank() && !label.equals(lang, ignoreCase = true)) append("  ·  ").append(label)
        if (isForced(f)) append("  ·  forzati")
    }

    private fun codecName(f: Format): String {
        val mime = if (f.sampleMimeType == CUES_MIME) f.codecs else f.sampleMimeType
        return when (mime) {
            MimeTypes.AUDIO_E_AC3 -> "EAC3"
            MimeTypes.AUDIO_E_AC3_JOC -> "EAC3 Atmos"
            MimeTypes.AUDIO_AC3 -> "AC3"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.TEXT_SSA -> "ASS"
            MimeTypes.APPLICATION_SUBRIP -> "SRT"
            MimeTypes.APPLICATION_PGS -> "PGS"
            MimeTypes.TEXT_VTT -> "WebVTT"
            MimeTypes.APPLICATION_VOBSUB -> "VobSub"
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_H265 -> "H.265"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            null -> ""
            else -> mime.substringAfter('/').uppercase()
        }
    }

    private fun channels(n: Int): String = when (n) {
        Format.NO_VALUE, 0 -> ""
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$n ch"
    }

    private fun trackDetails(f: Format): String =
        listOf(codecName(f), channels(f.channelCount)).filter { it.isNotEmpty() }.joinToString("  ·  ")

    private fun isHw(name: String) = name != "-" && !name.startsWith("OMX.google.") &&
        !name.startsWith("c2.android.") && !name.contains("ffmpeg", ignoreCase = true)

    private fun updateBadges() {
        if (!::player.isInitialized) return
        val v = player.videoFormat
        val a = player.audioFormat
        val parts = ArrayList<String>()
        if (videoDecoder != "-") parts.add(if (isHw(videoDecoder)) "HW" else "SW")
        if (v != null) parts.add("${codecName(v)} ${if (v.height > 0) "${v.height}p" else ""}".trim())
        if (a != null) parts.add(trackDetails(a))
        badges.text = parts.filter { it.isNotEmpty() }.joinToString("  ·  ")
        badges.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun applySubtitleStyle() {
        val sv = playerView.subtitleView ?: return
        sv.setStyle(
            CaptionStyleCompat(
                SUB_COLORS[Store.subColor].second,
                SUB_BGS[Store.subBg].second,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                Typeface.DEFAULT_BOLD
            )
        )
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * SUB_SIZES[Store.subSize].second)
        sv.setApplyEmbeddedFontSizes(false)
        sv.setBottomPaddingFraction(0.06f)
    }

    // ---------------- frequenza del TV ----------------

    private fun maybeMatchFrameRate() {
        if (fpsMatched || !Store.frameRateMatch || !::player.isInitialized) return
        val fps = player.videoFormat?.frameRate?.takeIf { it > 0 } ?: mkvFps.takeIf { it > 0 } ?: return
        fpsMatched = true
        @Suppress("DEPRECATION")
        val display: Display = windowManager.defaultDisplay
        val cur = display.mode
        var best: Display.Mode? = null
        var bestErr = Float.MAX_VALUE
        var bestK = Int.MAX_VALUE
        for (m in display.supportedModes) {
            if (m.physicalWidth != cur.physicalWidth || m.physicalHeight != cur.physicalHeight) continue
            val k = (m.refreshRate / fps).roundToInt()
            if (k < 1) continue
            val err = abs(m.refreshRate - k * fps)
            if (err > 0.05f * k) continue
            if (err < bestErr - 0.001f || (abs(err - bestErr) <= 0.001f && k < bestK)) {
                best = m; bestErr = err; bestK = k
            }
        }
        val target = best ?: return
        if (target.modeId == cur.modeId) return
        val wasPlaying = player.playWhenReady
        player.pause()
        val lp = window.attributes
        lp.preferredDisplayModeId = target.modeId
        window.attributes = lp
        showOsd("Frequenza TV: %.3f Hz".format(target.refreshRate), 2500)
        handler.postDelayed({ if (wasPlaying && !isFinishing) player.play() }, 2500)
    }

    // ---------------- fine episodio ----------------

    private fun onEnded() {
        Store.clearProgress(uriKey)
        Store.setWatched(uriKey, true)
        if (index + 1 < playlist.size) showNextCard() else finish()
    }

    private val nextTick = object : Runnable {
        override fun run() {
            if (nextCard.visibility != View.VISIBLE) return
            if (nextCountdown <= 0) {
                playNext(); return
            }
            nextLabel.text = "Prossimo episodio tra $nextCountdown s"
            nextCountdown--
            handler.postDelayed(this, 1000)
        }
    }

    private fun showNextCard() {
        val ep = playlist[index + 1]
        nextTitle.text = ep.entry.name.substringBeforeLast('.')
        nextCountdown = 10
        hideControls()
        panel.visibility = View.GONE
        nextCard.visibility = View.VISIBLE
        nextPlay.requestFocus()
        handler.removeCallbacks(nextTick)
        handler.post(nextTick)
    }

    private fun hideNextCard() {
        nextCard.visibility = View.GONE
        handler.removeCallbacks(nextTick)
    }

    private fun playNext() {
        hideNextCard()
        val i = index + 1
        if (i < playlist.size) playIndex(i, Store.resume(playlist[i].key)) else finish()
    }

    // ---------------- pannello info (tasto ≡) ----------------

    private val statsTick = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    private fun toggleStats() {
        if (stats.visibility == View.VISIBLE) {
            stats.visibility = View.GONE
            handler.removeCallbacks(statsTick)
        } else {
            stats.visibility = View.VISIBLE
            lastTime = 0
            handler.post(statsTick)
        }
    }

    private fun updateStats() {
        val now = SystemClock.elapsedRealtime()
        val bytes = SmbStats.bytesRead.get()
        val mbps = if (lastTime > 0) (bytes - lastBytes) * 8 / 1_000_000.0 / ((now - lastTime) / 1000.0) else 0.0
        lastBytes = bytes
        lastTime = now

        val v = player.videoFormat
        val a = player.audioFormat
        val buffered = ((player.bufferedPosition - player.currentPosition) / 1000).coerceAtLeast(0)
        val ahead = Prefetcher.totalAheadBytes() / (1024 * 1024)
        val preset = Store.bufferPreset()
        val audioDec = if (a != null && audioDecoder == "-") "passthrough" else audioDecoder
        val fps = v?.frameRate?.takeIf { it > 0 } ?: mkvFps

        stats.text = buildString {
            append("Video   ${v?.sampleMimeType ?: "-"}  ${v?.width ?: 0}x${v?.height ?: 0}")
            if (fps > 0) append("  %.3f fps".format(fps))
            append("\n        decoder: $videoDecoder\n")
            append("Audio   ${a?.sampleMimeType ?: "-"}  ${a?.channelCount ?: 0} ch  ${a?.language ?: ""}\n")
            append("        decoder: $audioDec\n")
            append("Buffer player:    $buffered s  (max ${preset.playerMb} MB)\n")
            append("Read-ahead SMB:   $ahead MB  (max ${preset.readAheadMb} MB)\n")
            append("Lettura SMB ora:  %.1f Mbps  (${Store.parallel} connessioni)\n".format(mbps))
            append("Capitoli:         ${chapters.size}\n")
            append("Rebuffering:      $rebuffers")
            if (lastRebufferAt.isNotEmpty()) append("  (ultimo a $lastRebufferAt)")
        }
    }

    private fun showError(e: PlaybackException) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Errore di riproduzione")
            .setMessage("${e.errorCodeName}\n\n${e.cause?.message ?: e.message ?: ""}")
            .setPositiveButton("Riprova") { _, _ -> player.prepare(); player.play() }
            .setNegativeButton("Esci") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    // ---------------- ciclo di vita ----------------

    private fun saveProgress() {
        if (!::player.isInitialized || uriKey.isEmpty()) return
        val pos = player.currentPosition
        val dur = player.duration
        if (dur != C.TIME_UNSET && dur > 0 && pos > dur * 0.95) {
            Store.clearProgress(uriKey)
            Store.setWatched(uriKey, true)
        } else if (pos > 30_000) {
            Store.saveProgress(uriKey, title, pos, if (dur == C.TIME_UNSET) 0L else dur)
        }
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        thumbs?.release()
        if (::player.isInitialized) player.release()
        io.shutdown()
    }
}

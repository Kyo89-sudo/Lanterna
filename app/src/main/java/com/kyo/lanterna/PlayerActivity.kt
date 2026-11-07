package com.kyo.lanterna

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
import androidx.media3.ui.PlayerView
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START = "start"
        const val EXTRA_SUB_URIS = "sub_uris"
        const val EXTRA_SUB_LABELS = "sub_labels"
    }

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var stats: TextView
    private lateinit var osd: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var uriKey = ""
    private var videoDecoder = "-"
    private var audioDecoder = "-"
    private var lastBytes = 0L
    private var lastTime = 0L
    private var rebuffers = 0
    private var lastRebufferAt = ""

    private val hideOsd = Runnable { osd.visibility = View.GONE }

    private val statsTick = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        stats = findViewById(R.id.stats)
        osd = findViewById(R.id.osd)

        uriKey = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val start = intent.getLongExtra(EXTRA_START, 0L)

        player = buildPlayer()
        playerView.player = player
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        playerView.setShowSubtitleButton(true)
        playerView.setShowNextButton(false)
        playerView.setShowPreviousButton(false)
        playerView.controllerShowTimeoutMs = 4000

        player.setMediaItem(buildMediaItem(uriKey), start)
        player.prepare()
        player.playWhenReady = true

        showOsd(title, 3000)
    }

    private fun buildPlayer(): ExoPlayer {
        val preset = Store.bufferPreset()

        // Buffer in RAM: fino a 10 minuti o al limite in MB, quello che arriva prima.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 600_000,
                /* bufferForPlaybackMs = */ 2_000,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000
            )
            .setTargetBufferBytes(preset.playerMb * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(20_000, false)
            .build()

        // Decoder hardware sempre per primi (AV1/HEVC sulla Fire TV Stick 4K Max).
        val codecSelector = MediaCodecSelector { mimeType, secure, tunneling ->
            MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
                .sortedByDescending { it.hardwareAccelerated }
        }
        val renderers = DefaultRenderersFactory(this)
            .setMediaCodecSelector(codecSelector)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

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
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .build()

        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long
            ) {
                videoDecoder = decoderName
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

            // I salti (±10 s, barra) mettono il player in caricamento: non sono rebuffering.
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
                    Player.STATE_ENDED -> {
                        Store.setResume(uriKey, 0)
                        finish()
                    }
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) = showError(error)
        })
        return p
    }

    private fun buildMediaItem(uri: String): MediaItem {
        val subUris = intent.getStringArrayListExtra(EXTRA_SUB_URIS) ?: arrayListOf()
        val subLabels = intent.getStringArrayListExtra(EXTRA_SUB_LABELS) ?: arrayListOf()
        val subs = subUris.mapIndexed { i, u ->
            val label = subLabels.getOrNull(i).orEmpty()
            val mime = when (u.substringAfterLast('.').lowercase()) {
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "vtt" -> MimeTypes.TEXT_VTT
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            val lang = label.split('.', ' ', '_', '-').firstOrNull { it.length in 2..3 }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(u))
                .setMimeType(mime)
                .setLanguage(lang)
                .setLabel(if (label.isBlank()) "Esterni" else "Esterni ($label)")
                .build()
        }
        return MediaItem.Builder().setUri(uri).setSubtitleConfigurations(subs).build()
    }

    // ---------- telecomando ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val controls = playerView.isControllerFullyVisible
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                    toggleStats(); return true
                }
                KeyEvent.KEYCODE_BACK -> if (controls) {
                    playerView.hideController(); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controls) {
                    val step = if (event.repeatCount > 8) 30_000L else 10_000L
                    val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                    seekBy(dir * step); return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!controls) {
                    if (player.isPlaying) {
                        player.pause(); playerView.showController()
                    } else player.play()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (!controls) {
                    playerView.showController(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun seekBy(delta: Long) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        val target = (player.currentPosition + delta).coerceIn(0, dur)
        player.seekTo(target)
        val total = if (dur == Long.MAX_VALUE) "" else " / ${formatTime(dur)}"
        showOsd((if (delta < 0) "⏪  " else "⏩  ") + formatTime(target) + total, 1200)
    }

    private fun showOsd(text: String, ms: Long) {
        osd.text = text
        osd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, ms)
    }

    // ---------- pannello info (tasto ≡) ----------

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

        stats.text = buildString {
            append("Video   ${v?.sampleMimeType ?: "-"}  ${v?.width ?: 0}x${v?.height ?: 0}")
            v?.frameRate?.takeIf { it > 0 }?.let { append("  %.3f fps".format(it)) }
            append("\n        decoder: $videoDecoder\n")
            append("Audio   ${a?.sampleMimeType ?: "-"}  ${a?.channelCount ?: 0} ch  ${a?.language ?: ""}\n")
            append("        decoder: $audioDec\n")
            append("Buffer player:    $buffered s  (max ${preset.playerMb} MB)\n")
            append("Read-ahead SMB:   $ahead MB  (max ${preset.readAheadMb} MB)\n")
            append("Lettura SMB ora:  %.1f Mbps\n".format(mbps))
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

    // ---------- ciclo di vita ----------

    private fun saveResume() {
        if (!::player.isInitialized) return
        val pos = player.currentPosition
        val dur = player.duration
        val nearEnd = dur != C.TIME_UNSET && dur > 0 && pos > dur * 0.95
        Store.setResume(uriKey, if (pos > 30_000 && !nearEnd) pos else 0)
    }

    override fun onStop() {
        super.onStop()
        saveResume()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
    }
}

package com.kyo.lanterna

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** Inoltra tutto al renderer originale, ma gli fa vedere il tempo spostato di qualche ms. */
class ShiftedRenderer(private val d: Renderer, private val shiftUs: () -> Long) : Renderer by d {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) =
        d.render(positionUs + shiftUs(), elapsedRealtimeUs)
}

/**
 * Ritardo sottotitoli: il renderer del testo vede il tempo spostato.
 * Ritardo audio: il video viene anticipato/posticipato rispetto all'audio.
 */
class DelayRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    /** > 0 = sottotitoli più tardi */
    @Volatile var subDelayUs = 0L
    /** > 0 = audio più tardi */
    @Volatile var audioDelayUs = 0L

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val tmp = ArrayList<Renderer>()
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, tmp)
        tmp.forEach { r -> out.add(ShiftedRenderer(r) { -subDelayUs }) }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        val tmp = ArrayList<Renderer>()
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, tmp
        )
        tmp.forEach { r -> out.add(ShiftedRenderer(r) { audioDelayUs }) }
    }
}

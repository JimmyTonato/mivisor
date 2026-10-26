package com.mivisor.app.engine

import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * LoadControl de Media3 con el buffer PEQUEÑO y ADAPTATIVO de [AdaptiveBuffer].
 *
 * Todo lo demás (asignador de memoria, selección de pistas, back-buffer…) lo hace un [DefaultLoadControl]
 * configurado con un tope de BYTES (low-RAM: 16 MB, resto: 32 MB) para que la RAM no dependa del bitrate.
 * Solo se sustituyen las dos decisiones de tiempo: cuándo empezar y hasta dónde seguir cargando.
 */
@OptIn(UnstableApi::class)
class AdaptiveLoadControl(val policy: AdaptiveBuffer, lowRam: Boolean) : LoadControl {

    private val delegate: DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ policy.unstableCapMs.toInt(),
            /* maxBufferMs = */ policy.unstableCapMs.toInt(),
            /* bufferForPlaybackMs = */ policy.startMs.toInt(),
            /* bufferForPlaybackAfterRebufferMs = */ policy.afterRebufferMs.toInt()
        )
        .setTargetBufferBytes(if (lowRam) 16 shl 20 else 32 shl 20)
        .setPrioritizeTimeOverSizeThresholds(false)
        .setBackBuffer(0, false)
        .build()

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        policy.shouldStartPlayback(parameters.bufferedDurationUs / 1000, parameters.rebuffering)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        policy.shouldContinueLoading(parameters.bufferedDurationUs / 1000) && delegate.shouldContinueLoading(parameters)

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<out Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>
    ) = delegate.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(): Allocator = delegate.getAllocator()

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)
}

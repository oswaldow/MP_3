package com.learnlayout.mp_3

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * RenderersFactory que arma el AudioSink de ExoPlayer con
 * SoftwareEqualizerProcessor metido en la cadena de AudioProcessor. Se
 * usa en MusicService.buildPlayer() para que TODOS los ExoPlayer del
 * servicio (cancion normal, restore, los dos players del crossfade)
 * pasen el audio por el ecualizador antes de llegar al hardware.
 */
@UnstableApi
class EqAudioSinkRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(SoftwareEqualizerProcessor())
            )
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}
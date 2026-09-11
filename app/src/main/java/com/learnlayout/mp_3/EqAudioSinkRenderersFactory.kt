package com.learnlayout.mp_3

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

@UnstableApi
class EqAudioSinkRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    PreampAudioProcessor(),
                    SpectrumAudioProcessor()
                )
            )
            // El ecualizador de bandas ya no vive aqui (ver
            // EqualizerRepository): ahora es android.media.audiofx.Equalizer,
            // atado directamente al audioSessionId por fuera de esta cadena
            // de AudioProcessor. Lo que SI sigue aqui es PreampAudioProcessor
            // (preamp manual del ecualizador, ver ese archivo; la
            // normalizacion de volumen por cancion que tenia antes se quito)
            // y SpectrumAudioProcessor (visualizador de espectro).
            //
            // SpectrumAudioProcessor solo sabe procesar PCM de 16 bits (ver su
            // configure(), que lanza UnhandledAudioFormatException para
            // cualquier otro encoding). Si se deja pasar enableFloatOutput=true
            // (lo que ExoPlayer pide solo con archivos de mayor calidad, ej.
            // FLAC/WAV de 24 bits), el procesador se sale solo de la cadena de
            // audio para ESA cancion y las barras del visualizador se quedan
            // congeladas. Forzamos siempre 16 bits para que el visualizador
            // funcione igual en cualquier cancion, sin importar su calidad.
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}
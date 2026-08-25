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
                    ReplayGainAudioProcessor(),
                    SoftwareEqualizerProcessor(),
                    SpectrumAudioProcessor()
                )
            )
            // SoftwareEqualizerProcessor y SpectrumAudioProcessor solo saben
            // procesar PCM de 16 bits (ver sus configure(), que lanzan
            // UnhandledAudioFormatException para cualquier otro encoding).
            // Si se deja pasar enableFloatOutput=true (lo que ExoPlayer
            // pide solo con archivos de mayor calidad, ej. FLAC/WAV de 24
            // bits), ambos procesadores se salen solos de la cadena de
            // audio para ESA cancion: el ecualizador deja de aplicarse y
            // las barras del visualizador de espectro se quedan
            // congeladas (no reciben datos nuevos).
            // Forzamos siempre 16 bits para que EQ y visualizador
            // funcionen igual en cualquier cancion, sin importar su
            // calidad. El costo es no aprovechar el mayor rango dinamico
            // de los archivos hi-res, pero esta app esta construida
            // alrededor de procesar el audio (EQ, ReplayGain, espectro),
            // asi que tiene mas sentido que esas funciones nunca se
            // desactiven solas.
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}
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
                    SoftwareEqualizerProcessor(),
                    SpectrumAudioProcessor()
                )
            )
            // El ecualizador de 10 bandas vive aqui como AudioProcessor propio
            // (SoftwareEqualizerProcessor), NO como android.media.audiofx.Equalizer:
            // asi el sonido es identico en cualquier fabricante, con headroom
            // calculado a partir de la respuesta real combinada de las bandas
            // en vez de una regla fija (ver EqualizerRepository para el porque
            // del cambio).
            //
            // El orden de la cadena importa: primero el preamp manual del
            // usuario (PreampAudioProcessor), despues el ecualizador de bandas
            // (que incluye su propio limitador de seguridad como ultimo recurso
            // contra clipping combinado de preamp + bandas), y al final el
            // visualizador de espectro, para que dibuje la señal ya final tal
            // como se escucha.
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
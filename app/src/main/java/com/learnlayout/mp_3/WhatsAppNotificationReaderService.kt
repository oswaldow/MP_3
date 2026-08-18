package com.learnlayout.mp_3

import android.app.Notification
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.UUID

// Lee en voz alta, por la bocina o audifonos Bluetooth que esten activos
// en ese momento, los mensajes que lleguen de WhatsApp. Solo actua si:
// 1) el usuario activo el interruptor en Ajustes (SettingsRepository)
// 2) el usuario le dio "Acceso a notificaciones" a la app en el sistema
// 3) hay un dispositivo Bluetooth de audio activo ahora mismo (si no,
//    no tiene caso leerlo por la bocina del telefono)
class WhatsAppNotificationReaderService : NotificationListenerService(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        tts = TextToSpeech(applicationContext, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) return

        tts?.language = Locale("es")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = releaseAudioFocus()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = releaseAudioFocus()
        })
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != PACKAGE_WHATSAPP && sbn.packageName != PACKAGE_WHATSAPP_BUSINESS) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extracted = extractMessage(sbn.notification) ?: return
        val (sender, message) = extracted
        if (message.isBlank()) return

        if (!SettingsRepository.isWhatsAppReadingEnabled(applicationContext)) return
        if (!ttsReady || !isBluetoothAudioActive()) return

        speak(if (sender.isNullOrBlank()) message else "Mensaje de $sender: $message")
    }

    // lastMessage.text (y el titulo/texto de las notificaciones normales)
    // llegan como CharSequence? desde el SDK de Android, por eso todo aqui
    // se maneja con ?. y toString() antes de comparar o concatenar nada.
    private fun extractMessage(notification: Notification): Pair<String?, String>? {
        val messagingStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification)
        val lastMessage = messagingStyle?.messages?.lastOrNull()
        val lastMessageText = lastMessage?.text?.toString()

        if (!lastMessageText.isNullOrBlank()) {
            val sender = lastMessage?.person?.name?.toString()
                ?: messagingStyle?.conversationTitle?.toString()
            return sender to lastMessageText
        }

        // Fallback para notificaciones que no vienen en MessagingStyle
        val extras = notification.extras
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        if (text.isNullOrBlank()) return null
        return title to text
    }

    private fun isBluetoothAudioActive(): Boolean {
        val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return false
        return outputs.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }

    private fun speak(text: String) {
        applySavedVoice()
        requestAudioFocus()
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    // Se aplica en cada lectura (no solo en onInit) porque el usuario puede
    // cambiar la voz en Ajustes mientras el servicio ya esta vivo.
    private fun applySavedVoice() {
        val savedName = SettingsRepository.getTtsVoiceName(applicationContext) ?: return
        val voice = tts?.voices?.firstOrNull { it.name == savedName } ?: return
        if (tts?.voice?.name != voice.name) {
            tts?.voice = voice
        }
    }

    @Suppress("DEPRECATION")
    private fun requestAudioFocus() {
        val manager = audioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()

            focusRequest = request
            manager.requestAudioFocus(request)
        } else {
            manager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            manager.abandonAudioFocus(focusChangeListener)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseAudioFocus()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val PACKAGE_WHATSAPP = "com.whatsapp"
        private const val PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    }
}
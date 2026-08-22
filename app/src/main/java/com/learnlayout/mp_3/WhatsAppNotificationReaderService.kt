package com.learnlayout.mp_3

import android.app.Notification
import android.content.ComponentName
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.UUID

// Lee en voz alta, por la bocina o audifonos Bluetooth que esten activos
// en ese momento, los mensajes que lleguen de WhatsApp. Solo actua si:
// 1) el usuario activo el interruptor en Ajustes (SettingsRepository)
// 2) el usuario le dio "Acceso a notificaciones" a la app en el sistema
// 3) hay un dispositivo Bluetooth de audio activo ahora mismo (si no,
//    no tiene caso leerlo por la bocina del telefono)
//
// ---------------------------------------------------------------------
// VERSION CON LOGGING PARA DIAGNOSTICO. Todas las lineas Log.d/Log.w/
// Log.e usan el tag TAG ("MP3_WhatsAppReader") para poder filtrarlas
// facil en Logcat con: tag:MP3_WhatsAppReader
//
// Deja el telefono conectado por USB, activa "Depuracion por USB", y
// mientras corre el logcat manda un WhatsApp de prueba. Copia todo lo
// que aparezca (o la falta total de lineas, que tambien es informacion)
// y mandamelo.
// ---------------------------------------------------------------------
class WhatsAppNotificationReaderService : NotificationListenerService(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val rebindHandler = Handler(Looper.getMainLooper())

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    // Deduplicacion PERMANENTE de mensajes ya leidos. WhatsApp reenvia/
    // actualiza la misma notificacion varias veces seguidas para la misma
    // conversacion (por ejemplo al reagrupar notificaciones o al recibir
    // "recibos" de entrega), y cada una de esas reposiciones dispara
    // onNotificationPosted() otra vez con el MISMO mensaje.
    //
    // Se guarda CADA contenido (remitente + texto) que ya se leyo en voz
    // alta, sin limite de tiempo: si ese mismo contenido vuelve a llegar
    // (sin importar cuanto tiempo haya pasado, ni si es una notificacion
    // distinta con otro sbn.key), ya no se vuelve a leer mientras el
    // servicio siga vivo. LinkedHashSet conserva el orden de insercion
    // para poder ir descartando lo mas viejo si la lista crece demasiado
    // (ver MAX_REMEMBERED_MESSAGES), y evitar que la memoria crezca sin
    // limite en un uso muy prolongado del telefono.
    //
    // Nota: esto significa que si de verdad mandan el MISMO texto otra vez
    // dias despues (ej. "Ok" enviado dos veces en ocasiones distintas), no
    // se leera la segunda vez. Es el comportamiento que se pidio: una vez
    // leido, no se repite.
    private val spokenMessages = LinkedHashSet<ExtractedContent>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate() - el servicio se esta creando")
        audioManager = getSystemService(AudioManager::class.java)
        tts = TextToSpeech(applicationContext, this)
    }

    // Se llama cuando el sistema termina de enlazar el servicio (al
    // arrancar, o despues de una reconexion pedida con requestRebind).
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "onListenerConnected() - el listener quedo enlazado y activo")
        rebindHandler.removeCallbacksAndMessages(null)
    }

    // Se llama cuando el sistema desvincula el servicio. En vez de
    // esperar a que HyperOS decida reconectarlo por su cuenta (a veces
    // tarda minutos u horas), se pide la reconexion de inmediato.
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "onListenerDisconnected() - el sistema desvinculo el listener, pidiendo rebind")
        requestRebindSafely()
    }

    private fun requestRebindSafely() {
        rebindHandler.removeCallbacksAndMessages(null)
        rebindHandler.postDelayed({
            try {
                Log.d(TAG, "Pidiendo requestRebind() ahora")
                requestRebind(ComponentName(applicationContext, WhatsAppNotificationReaderService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "requestRebind() fallo: ${e.message}", e)
            }
        }, REBIND_DELAY_MS)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        Log.d(TAG, "onInit() - TTS listo=$ttsReady (status=$status)")
        if (!ttsReady) return

        tts?.language = Locale("es")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS onStart id=$utteranceId")
                // Baja la musica (si esta sonando) mientras dura la
                // lectura, en vez de pausarla. MusicService.getRunningInstance()
                // devuelve null si el servicio de musica no esta vivo en
                // ese momento (nada que duckear).
                MusicService.getRunningInstance()?.duckForSpeech()
            }
            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS onDone id=$utteranceId")
                releaseAudioFocus()
                MusicService.getRunningInstance()?.unduckAfterSpeech()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS onError id=$utteranceId")
                releaseAudioFocus()
                MusicService.getRunningInstance()?.unduckAfterSpeech()
            }
        })
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Log.d(TAG, "onNotificationPosted() paquete=${sbn.packageName}")

        if (sbn.packageName != PACKAGE_WHATSAPP && sbn.packageName != PACKAGE_WHATSAPP_BUSINESS) {
            Log.d(TAG, "Ignorada: no es WhatsApp (paquete=${sbn.packageName})")
            return
        }

        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            Log.d(TAG, "Ignorada: es un group summary, no un mensaje individual")
            return
        }

        val extracted = extractMessage(sbn.notification)
        if (extracted == null) {
            Log.w(TAG, "extractMessage() devolvio null, no se pudo sacar texto de la notificacion")
            return
        }
        val (sender, message, isAttachment) = extracted
        Log.d(TAG, "Mensaje extraido - remitente=$sender texto='$message' adjunto=$isAttachment")

        if (alreadySpoken(extracted)) {
            Log.d(TAG, "Ignorada: este mensaje ya se leyo antes (mismo remitente+texto)")
            return
        }

        // Se marca como "ya leido" aqui, antes de los filtros de abajo
        // (interruptor apagado, TTS no listo, sin Bluetooth). Esto evita
        // leer un reenvio duplicado del mismo mensaje aunque esta vez SI
        // pasen todos los filtros (por ejemplo: llega el mensaje con el
        // Bluetooth desconectado -no se lee, pero se registra igual-, y
        // 1 segundo despues WhatsApp reenvia la misma notificacion ya con
        // el Bluetooth conectado). Registrar por contenido, no por si
        // efectivamente se hablo, es lo que corresponde a "ya se vio este
        // mensaje", que es el problema que se esta resolviendo.
        rememberSpoken(extracted)

        // sanitizeForSpeech() ya le quito links/emojis a los mensajes de
        // texto; si un mensaje era SOLO un link o SOLO emojis, message
        // puede quedar en blanco aqui aunque no lo estuviera originalmente.
        if (message.isBlank()) {
            Log.d(TAG, "Ignorada: el texto del mensaje esta en blanco (o solo tenia link/emojis)")
            return
        }

        val readingEnabled = SettingsRepository.isWhatsAppReadingEnabled(applicationContext)
        Log.d(TAG, "isWhatsAppReadingEnabled=$readingEnabled")
        if (!readingEnabled) {
            Log.d(TAG, "Ignorada: el interruptor de lectura esta apagado")
            return
        }

        val bluetoothActive = isBluetoothAudioActive()
        Log.d(TAG, "ttsReady=$ttsReady bluetoothActive=$bluetoothActive")
        if (!ttsReady) {
            Log.w(TAG, "Ignorada: el motor TTS no esta listo")
            return
        }
        if (!bluetoothActive) {
            Log.w(TAG, "Ignorada: no se detecto audio Bluetooth activo")
            return
        }

        val finalText = when {
            isAttachment && !sender.isNullOrBlank() -> "$sender te $message"
            isAttachment -> "Te $message"
            sender.isNullOrBlank() -> message
            else -> "Mensaje de $sender: $message"
        }
        Log.d(TAG, "Mandando a leer: '$finalText'")
        speak(finalText)
    }

    // true si este [content] (mismo remitente + mismo texto) ya se leyo
    // antes en algun momento, sin limite de tiempo.
    private fun alreadySpoken(content: ExtractedContent): Boolean =
        spokenMessages.contains(content)

    private fun rememberSpoken(content: ExtractedContent) {
        spokenMessages.add(content)
        // Si crece demasiado (uso muy prolongado sin reiniciar el
        // servicio), se descarta lo mas viejo primero para no acumular
        // memoria sin limite.
        if (spokenMessages.size > MAX_REMEMBERED_MESSAGES) {
            spokenMessages.remove(spokenMessages.first())
        }
    }

    /**
     * Resultado de leer una notificacion de WhatsApp.
     *
     * [spokenText] ya viene saneado para TTS (sin links crudos, sin
     * emojis) cuando viene de un mensaje de texto; cuando [isAttachment]
     * es true, en cambio, es una descripcion corta generada por
     * [describeAttachment] (por ejemplo "te mando un sticker"), no texto
     * literal del mensaje.
     */
    private data class ExtractedContent(
        val sender: String?,
        val spokenText: String,
        val isAttachment: Boolean = false
    )

    // lastMessage.text (y el titulo/texto de las notificaciones normales)
    // llegan como CharSequence? desde el SDK de Android, por eso todo aqui
    // se maneja con ?. y toString() antes de comparar o concatenar nada.
    private fun extractMessage(notification: Notification): ExtractedContent? {
        val messagingStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification)
        val lastMessage = messagingStyle?.messages?.lastOrNull()
        val lastMessageText = lastMessage?.text?.toString()
        val sender = lastMessage?.person?.name?.toString()
            ?: messagingStyle?.conversationTitle?.toString()

        if (!lastMessageText.isNullOrBlank()) {
            Log.d(TAG, "extractMessage(): via MessagingStyle (texto)")
            return ExtractedContent(sender, sanitizeForSpeech(lastMessageText))
        }

        // Sin texto: puede ser un sticker, foto, video o nota de voz.
        // WhatsApp manda estos adjuntos como MessagingStyle.Message CON
        // dataMimeType pero SIN texto legible, asi que antes caian en el
        // fallback de abajo y terminaban ignorados por texto en blanco.
        val mimeType = lastMessage?.dataMimeType
        if (mimeType != null) {
            Log.d(TAG, "extractMessage(): via MessagingStyle (adjunto sin texto, mime=$mimeType)")
            return ExtractedContent(sender, describeAttachment(mimeType), isAttachment = true)
        }

        // Fallback para notificaciones que no vienen en MessagingStyle
        val extras = notification.extras
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        if (text.isNullOrBlank()) {
            Log.w(TAG, "extractMessage(): ni MessagingStyle ni EXTRA_TEXT tenian texto")
            return null
        }
        Log.d(TAG, "extractMessage(): via fallback EXTRA_TEXT/EXTRA_TITLE")
        return ExtractedContent(title, sanitizeForSpeech(text))
    }

    /** Descripcion corta y hablable para un adjunto sin texto (sticker, foto, video, nota de voz). */
    private fun describeAttachment(mimeType: String): String = when {
        mimeType.startsWith("image/webp") -> "mando un sticker"
        mimeType.startsWith("image/") -> "mando una foto"
        mimeType.startsWith("video/") -> "mando un video"
        mimeType.startsWith("audio/") -> "mando una nota de voz"
        else -> "mando un archivo adjunto"
    }

    // Quita de un mensaje lo que NO tiene caso leer en voz alta: links
    // (se reemplazan por "un enlace", no se deletrea la URL) y emojis (se
    // quitan sin mas: la mayoria de los motores TTS en espanol los ignora
    // o los lee como "emoji" generico, ninguna de las dos cosas suma).
    // Si el mensaje era SOLO un link o SOLO emojis, esto puede devolver
    // una cadena en blanco; eso ya se maneja en onNotificationPosted().
    private fun sanitizeForSpeech(rawText: String): String {
        val withoutUrls = URL_REGEX.replace(rawText) { " un enlace " }
        val withoutEmoji = EMOJI_REGEX.replace(withoutUrls, " ")
        return WHITESPACE_REGEX.replace(withoutEmoji, " ").trim()
    }

    private fun isBluetoothAudioActive(): Boolean {
        val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (outputs == null) {
            Log.w(TAG, "isBluetoothAudioActive(): audioManager.getDevices() devolvio null")
            return false
        }
        val types = outputs.joinToString { it.type.toString() }
        Log.d(TAG, "isBluetoothAudioActive(): tipos de salida de audio detectados=[$types]")
        return outputs.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }

    private fun speak(text: String) {
        applySavedVoice()
        requestAudioFocus()
        val utteranceId = UUID.randomUUID().toString()
        val speakResult = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        Log.d(TAG, "tts.speak() resultado=$speakResult utteranceId=$utteranceId")
    }

    // Se aplica en cada lectura (no solo en onInit) porque el usuario puede
    // cambiar la voz en Ajustes mientras el servicio ya esta vivo.
    private fun applySavedVoice() {
        val savedName = SettingsRepository.getTtsVoiceName(applicationContext) ?: return
        val voice = tts?.voices?.firstOrNull { it.name == savedName }
        if (voice == null) {
            Log.w(TAG, "applySavedVoice(): la voz guardada '$savedName' ya no existe en tts.voices")
            return
        }
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
            val result = manager.requestAudioFocus(request)
            Log.d(TAG, "requestAudioFocus() resultado=$result")
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
        Log.w(TAG, "onDestroy() - el servicio se esta destruyendo")
        super.onDestroy()
        rebindHandler.removeCallbacksAndMessages(null)
        releaseAudioFocus()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "MP3_WhatsAppReader"
        private const val PACKAGE_WHATSAPP = "com.whatsapp"
        private const val PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"
        private const val REBIND_DELAY_MS = 3000L

        // Tope de mensajes distintos que se recuerdan como "ya leidos"
        // mientras el servicio sigue vivo. 200 es de sobra para un uso
        // normal del telefono entre reinicios del servicio.
        private const val MAX_REMEMBERED_MESSAGES = 200

        // Usados por sanitizeForSpeech() para limpiar el texto antes de
        // mandarlo al TTS. EMOJI_REGEX cubre los rangos Unicode donde
        // vive la gran mayoria de los emojis (incluye los planos
        // suplementarios via \x{...}, que si soporta java.util.regex).
        private val URL_REGEX = Regex("""(https?://\S+|www\.\S+)""", RegexOption.IGNORE_CASE)
        private val EMOJI_REGEX = Regex(
            "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2190}-\\x{21FF}" +
                    "\\x{2B00}-\\x{2BFF}\\x{1F1E6}-\\x{1F1FF}\\uFE0F\\u200D]+"
        )
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}



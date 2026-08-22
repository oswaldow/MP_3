package com.learnlayout.mp_3

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.learnlayout.mp_3.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var isDownloadingLyrics = false

    // TTS propio de esta pantalla, solo para listar/previsualizar voces.
    // No tiene relacion con el TTS que usa WhatsAppNotificationReaderService
    // para leer mensajes en tiempo real (ese vive en el servicio).
    private var tts: TextToSpeech? = null
    private var availableVoices: List<Voice> = emptyList()

    // ---------- Fondo dinamico (Material You + destellos, igual al Home) ----------
    // Ajustes no tiene una cancion propia en pantalla, asi que nos
    // conectamos al MusicService solo para saber que esta sonando ahora
    // mismo y pintar el fondo con ese color. Si no hay nada sonando, se
    // queda con el degradado neutro (sin destellos), igual que el Home.
    private val ambientBackground: AmbientBackgroundController by lazy {
        AmbientBackgroundController(this, binding.root)
    }

    private var musicService: MusicService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            musicService = (binder as MusicService.MusicBinder).getService()
            isBound = true
            ambientBackground.updateForSong(musicService?.getCurrentSong())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ambientBackground.updateForSong(null)
        bindService(Intent(this, MusicService::class.java), connection, Context.BIND_AUTO_CREATE)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                availableVoices = tts?.voices
                    ?.filter { it.locale?.language == "es" && !it.isNetworkConnectionRequired }
                    ?.sortedBy { it.locale?.toString() ?: it.name }
                    ?: emptyList()
                updateVoiceSummary()
            }
        }

        loadCurrentSettings()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        // El usuario puede volver de Ajustes del sistema tras dar (o quitar)
        // el acceso a notificaciones, asi que refrescamos el estado aqui.
        updateNotificationAccessStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        tts = null
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    private fun loadCurrentSettings() {
        val enabled = SettingsRepository.isCrossfadeEnabled(this)
        val seconds = SettingsRepository.getCrossfadeSeconds(this)

        binding.switchCrossfade.isChecked = enabled
        binding.seekCrossfadeSeconds.progress = seconds - SettingsRepository.MIN_CROSSFADE_SECONDS
        binding.tvCrossfadeSeconds.text = "$seconds s"
        updateDurationGroupEnabled(enabled)

        binding.switchWhatsappReading.isChecked = SettingsRepository.isWhatsAppReadingEnabled(this)
        binding.switchVolumeNormalization.isChecked = SettingsRepository.isVolumeNormalizationEnabled(this)
        updateNotificationAccessStatus()
        updateVoiceSummary()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.switchCrossfade.setOnCheckedChangeListener { _, isChecked ->
            SettingsRepository.setCrossfadeEnabled(this, isChecked)
            updateDurationGroupEnabled(isChecked)
        }

        binding.seekCrossfadeSeconds.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress + SettingsRepository.MIN_CROSSFADE_SECONDS
                binding.tvCrossfadeSeconds.text = "$seconds s"
                if (fromUser) {
                    SettingsRepository.setCrossfadeSeconds(this@SettingsActivity, seconds)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.rowBluetooth.setOnClickListener {
            startActivity(Intent(this, BluetoothAudioActivity::class.java))
        }

        binding.rowEqualizer.setOnClickListener {
            startActivity(Intent(this, EqualizerActivity::class.java))
        }

        binding.switchVolumeNormalization.setOnCheckedChangeListener { _, isChecked ->
            SettingsRepository.setVolumeNormalizationEnabled(this, isChecked)
            // Efecto inmediato: no hace falta esperar a la siguiente
            // cancion para que se note el cambio.
            ReplayGainAudioProcessor.setEnabled(isChecked)
        }

        binding.rowDownloadLyrics.setOnClickListener {
            downloadAllLyricsAndArt()
        }

        binding.switchWhatsappReading.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isNotificationAccessGranted()) {
                binding.switchWhatsappReading.isChecked = false
                Toast.makeText(
                    this,
                    "Primero activa el acceso a notificaciones para MP_3",
                    Toast.LENGTH_LONG
                ).show()
                openNotificationAccessSettings()
            } else {
                SettingsRepository.setWhatsAppReadingEnabled(this, isChecked)
            }
        }

        binding.rowNotificationAccess.setOnClickListener {
            openNotificationAccessSettings()
        }

        binding.rowVoicePicker.setOnClickListener {
            showVoicePickerDialog()
        }
    }

    private fun updateDurationGroupEnabled(enabled: Boolean) {
        binding.groupCrossfadeDuration.alpha = if (enabled) 1f else 0.4f
        setViewTreeEnabled(binding.groupCrossfadeDuration, enabled)
    }

    private fun setViewTreeEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is LinearLayout) {
            for (i in 0 until view.childCount) {
                setViewTreeEnabled(view.getChildAt(i), enabled)
            }
        }
    }

    // ==================== LECTURA DE MENSAJES DE WHATSAPP ====================
    //
    // El acceso a notificaciones no se puede pedir como un permiso runtime
    // normal: Android obliga a que el usuario lo active a mano desde una
    // pantalla especial del sistema (ACTION_NOTIFICATION_LISTENER_SETTINGS).
    // Aqui solo detectamos si ya esta activo y mandamos para alla si hace falta.

    private fun isNotificationAccessGranted(): Boolean {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledListeners.contains(packageName)
    }

    private fun openNotificationAccessSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }.onFailure {
            Toast.makeText(this, "No se pudo abrir el ajuste de notificaciones", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateNotificationAccessStatus() {
        binding.tvNotificationAccessStatus.text = if (isNotificationAccessGranted()) {
            "Activado"
        } else {
            "Toca para activarlo (necesario para leer WhatsApp)"
        }
    }

    // ==================== FIN LECTURA DE MENSAJES DE WHATSAPP ====================

    // ==================== VOZ DE LECTURA ====================
    //
    // Deja elegir con que voz del sistema se leen los mensajes (ver
    // WhatsAppNotificationReaderService.applySavedVoice).

    private fun updateVoiceSummary() {
        val savedName = SettingsRepository.getTtsVoiceName(this)
        val selectedIndex = availableVoices.indexOfFirst { it.name == savedName }

        binding.tvVoiceSummary.text = if (selectedIndex >= 0) {
            voiceDisplayName(availableVoices[selectedIndex], selectedIndex)
        } else {
            "Voz predeterminada del sistema"
        }
    }

    private fun voiceDisplayName(voice: Voice, index: Int): String {
        val localeLabel = voice.locale?.displayName ?: voice.name
        return "Voz ${index + 1} ($localeLabel)"
    }

    private fun showVoicePickerDialog() {
        if (availableVoices.isEmpty()) {
            Toast.makeText(this, "No se encontraron voces en espanol instaladas", Toast.LENGTH_SHORT).show()
            return
        }

        val savedName = SettingsRepository.getTtsVoiceName(this)
        val defaultLabel = "Predeterminada del sistema"
        val voiceLabels = availableVoices.mapIndexed { index, voice -> voiceDisplayName(voice, index) }
        val allLabels = (listOf(defaultLabel) + voiceLabels).toTypedArray()

        val savedIndex = availableVoices.indexOfFirst { it.name == savedName }
        val currentChecked = if (savedIndex >= 0) savedIndex + 1 else 0

        AlertDialog.Builder(this, R.style.RoundedAlertDialog)
            .setTitle("Voz de lectura")
            .setSingleChoiceItems(allLabels, currentChecked) { dialog, which ->
                if (which == 0) {
                    SettingsRepository.setTtsVoiceName(this, null)
                    previewVoice(null)
                } else {
                    val voice = availableVoices[which - 1]
                    SettingsRepository.setTtsVoiceName(this, voice.name)
                    previewVoice(voice)
                }
                updateVoiceSummary()
                dialog.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun previewVoice(voice: Voice?) {
        val engine = tts ?: return
        engine.voice = voice ?: engine.defaultVoice
        engine.speak("Asi sueno yo", TextToSpeech.QUEUE_FLUSH, null, "voice_preview")
    }

    // ==================== FIN VOZ DE LECTURA ====================

    // ==================== DESCARGA MASIVA DE LETRAS Y CARATULAS ====================
    //
    // Este es el unico lugar (junto con el selector de mantener presionada
    // la caratula) donde la app sale a buscar letras/caratulas en red de
    // forma masiva. Se guarda todo en disco (SavedLyricsRepository /
    // AlbumArtRepository) para que despues, mientras se esta escuchando
    // musica, no haga falta ninguna busqueda en red (ver
    // AlbumArtRepository.loadCoverCacheOnly y LyricsPanelController
    // .loadForSong).

    private fun downloadAllLyricsAndArt() {
        if (isDownloadingLyrics) return

        val songs = SongRepository.getAllSongs(this)
        if (songs.isEmpty()) {
            Toast.makeText(this, "No se encontraron canciones", Toast.LENGTH_SHORT).show()
            return
        }

        isDownloadingLyrics = true
        binding.rowDownloadLyrics.isEnabled = false
        binding.progressDownloadLyrics.visibility = View.VISIBLE

        var index = 0
        var lyricsFoundCount = 0
        var artFoundCount = 0

        fun finishDownload() {
            isDownloadingLyrics = false
            binding.rowDownloadLyrics.isEnabled = true
            binding.progressDownloadLyrics.visibility = View.GONE
            binding.tvDownloadLyricsSummary.text =
                "Letras: $lyricsFoundCount de ${songs.size} - Caratulas: $artFoundCount de ${songs.size}"
            Toast.makeText(
                this,
                "Listo: $lyricsFoundCount letras y $artFoundCount caratulas de ${songs.size} canciones",
                Toast.LENGTH_LONG
            ).show()
        }

        // processNext se referencia a si misma indirectamente a traves de
        // fetchLyricsForCurrentSong (y viceversa), asi que no puede ser un
        // simple "fun" local: se declara primero como variable para que
        // ambas closures puedan capturarla antes de que tenga cuerpo.
        lateinit var processNext: () -> Unit

        fun fetchLyricsForCurrentSong(song: Song) {
            if (SavedLyricsRepository.isSaved(this, song.id)) {
                lyricsFoundCount++
                index++
                processNext()
                return
            }

            val durationSeconds = song.duration / 1000
            LyricsRepository.fetch(song.title, song.artist, durationSeconds, object : LyricsRepository.LyricsCallback {
                override fun onSuccess(result: LyricsResult) {
                    val hasLyrics = !result.isInstrumental &&
                            (!result.syncedLines.isNullOrEmpty() || !result.plainLyrics.isNullOrBlank())
                    if (hasLyrics) {
                        SavedLyricsRepository.save(this@SettingsActivity, song.id, result)
                        lyricsFoundCount++
                    }
                    index++
                    processNext()
                }

                override fun onError(message: String) {
                    index++
                    processNext()
                }
            })
        }

        processNext = {
            if (index >= songs.size) {
                finishDownload()
            } else {
                val song = songs[index]
                binding.tvDownloadLyricsSummary.text = "Descargando ${index + 1}/${songs.size}: ${song.title}"

                AlbumArtRepository.prefetchCover(this, song) { found ->
                    if (found) artFoundCount++
                    fetchLyricsForCurrentSong(song)
                }
            }
        }

        processNext()
    }

    // ==================== FIN DESCARGA MASIVA DE LETRAS Y CARATULAS ====================
}
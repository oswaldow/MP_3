package com.learnlayout.mp_3

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.learnlayout.mp_3.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var isDownloadingLyrics = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadCurrentSettings()
        setupListeners()
    }

    private fun loadCurrentSettings() {
        val enabled = SettingsRepository.isCrossfadeEnabled(this)
        val seconds = SettingsRepository.getCrossfadeSeconds(this)

        binding.switchCrossfade.isChecked = enabled
        binding.seekCrossfadeSeconds.progress = seconds - SettingsRepository.MIN_CROSSFADE_SECONDS
        binding.tvCrossfadeSeconds.text = "$seconds s"
        updateDurationGroupEnabled(enabled)
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

        binding.rowDownloadLyrics.setOnClickListener {
            downloadAllLyricsAndArt()
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
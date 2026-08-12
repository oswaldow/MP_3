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
            downloadAllLyrics()
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

    // ==================== DESCARGA MASIVA DE LETRAS ====================

    private fun downloadAllLyrics() {
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
        var foundCount = 0

        fun finishDownload() {
            isDownloadingLyrics = false
            binding.rowDownloadLyrics.isEnabled = true
            binding.progressDownloadLyrics.visibility = View.GONE
            binding.tvDownloadLyricsSummary.text = "Se encontraron $foundCount de ${songs.size} letras"
            Toast.makeText(
                this,
                "Listo: $foundCount de ${songs.size} letras encontradas",
                Toast.LENGTH_LONG
            ).show()
        }

        fun processNext() {
            while (index < songs.size && SavedLyricsRepository.isSaved(this, songs[index].id)) {
                foundCount++
                index++
            }

            if (index >= songs.size) {
                finishDownload()
                return
            }

            val song = songs[index]
            binding.tvDownloadLyricsSummary.text = "Buscando ${index + 1}/${songs.size}: ${song.title}"

            val durationSeconds = song.duration / 1000
            LyricsRepository.fetch(song.title, song.artist, durationSeconds, object : LyricsRepository.LyricsCallback {
                override fun onSuccess(result: LyricsResult) {
                    val hasLyrics = !result.isInstrumental &&
                            (!result.syncedLines.isNullOrEmpty() || !result.plainLyrics.isNullOrBlank())
                    if (hasLyrics) {
                        SavedLyricsRepository.save(this@SettingsActivity, song.id, result)
                        foundCount++
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

        processNext()
    }

    // ==================== FIN DESCARGA MASIVA DE LETRAS ====================
}
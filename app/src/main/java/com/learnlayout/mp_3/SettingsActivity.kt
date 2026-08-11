package com.learnlayout.mp_3

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var switchCrossfade: SwitchMaterial
    private lateinit var groupCrossfadeDuration: LinearLayout
    private lateinit var seekCrossfadeSeconds: SeekBar
    private lateinit var tvCrossfadeSeconds: TextView
    private lateinit var rowBluetooth: LinearLayout
    private lateinit var rowEqualizer: LinearLayout

    private lateinit var rowDownloadLyrics: LinearLayout
    private lateinit var tvDownloadLyricsSummary: TextView
    private lateinit var progressDownloadLyrics: ProgressBar
    private var isDownloadingLyrics = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        bindViews()
        loadCurrentSettings()
        setupListeners()
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        switchCrossfade = findViewById(R.id.switchCrossfade)
        groupCrossfadeDuration = findViewById(R.id.groupCrossfadeDuration)
        seekCrossfadeSeconds = findViewById(R.id.seekCrossfadeSeconds)
        tvCrossfadeSeconds = findViewById(R.id.tvCrossfadeSeconds)
        rowBluetooth = findViewById(R.id.rowBluetooth)
        rowEqualizer = findViewById(R.id.rowEqualizer)

        rowDownloadLyrics = findViewById(R.id.rowDownloadLyrics)
        tvDownloadLyricsSummary = findViewById(R.id.tvDownloadLyricsSummary)
        progressDownloadLyrics = findViewById(R.id.progressDownloadLyrics)
    }

    private fun loadCurrentSettings() {
        val enabled = SettingsRepository.isCrossfadeEnabled(this)
        val seconds = SettingsRepository.getCrossfadeSeconds(this)

        switchCrossfade.isChecked = enabled
        seekCrossfadeSeconds.progress = seconds - SettingsRepository.MIN_CROSSFADE_SECONDS
        tvCrossfadeSeconds.text = "$seconds s"
        updateDurationGroupEnabled(enabled)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        switchCrossfade.setOnCheckedChangeListener { _, isChecked ->
            SettingsRepository.setCrossfadeEnabled(this, isChecked)
            updateDurationGroupEnabled(isChecked)
        }

        seekCrossfadeSeconds.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress + SettingsRepository.MIN_CROSSFADE_SECONDS
                tvCrossfadeSeconds.text = "$seconds s"
                if (fromUser) {
                    SettingsRepository.setCrossfadeSeconds(this@SettingsActivity, seconds)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        rowBluetooth.setOnClickListener {
            startActivity(Intent(this, BluetoothAudioActivity::class.java))
        }

        rowEqualizer.setOnClickListener {
            startActivity(Intent(this, EqualizerActivity::class.java))
        }

        rowDownloadLyrics.setOnClickListener {
            downloadAllLyrics()
        }
    }

    private fun updateDurationGroupEnabled(enabled: Boolean) {
        groupCrossfadeDuration.alpha = if (enabled) 1f else 0.4f
        setViewTreeEnabled(groupCrossfadeDuration, enabled)
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
        rowDownloadLyrics.isEnabled = false
        progressDownloadLyrics.visibility = View.VISIBLE

        var index = 0
        var foundCount = 0

        fun finishDownload() {
            isDownloadingLyrics = false
            rowDownloadLyrics.isEnabled = true
            progressDownloadLyrics.visibility = View.GONE
            tvDownloadLyricsSummary.text = "Se encontraron $foundCount de ${songs.size} letras"
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
            tvDownloadLyricsSummary.text = "Buscando ${index + 1}/${songs.size}: ${song.title}"

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
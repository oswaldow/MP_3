package com.learnlayout.mp_3

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var switchCrossfade: SwitchMaterial
    private lateinit var groupCrossfadeDuration: LinearLayout
    private lateinit var seekCrossfadeSeconds: SeekBar
    private lateinit var tvCrossfadeSeconds: TextView
    private lateinit var rowBluetooth: LinearLayout

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
}
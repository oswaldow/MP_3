package com.learnlayout.mp_3

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

class EqualizerActivity : AppCompatActivity() {

    // El switch en activity_equalizer.xml esta declarado como
    // com.google.android.material.switchmaterial.SwitchMaterial (no
    // android.widget.Switch): son clases distintas, SwitchMaterial NO
    // hereda de Switch. Si este campo se tipa como Switch, findViewById
    // revienta con ClassCastException apenas se abre la pantalla (por
    // eso se cerraba solo el Ecualizador). Debe coincidir con el tag
    // real del XML.
    private lateinit var switchEnabled: SwitchMaterial
    private lateinit var llBandsContainer: LinearLayout
    private lateinit var btnReset: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var tvUnavailable: TextView

    private val bandSeekBars = mutableListOf<SeekBar>()
    private val bandValueLabels = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_equalizer)

        bindViews()

        if (!EqualizerRepository.isAvailable) {
            showUnavailableState()
            return
        }

        setupEnabledSwitch()
        buildBandSliders()
        setupResetButton()
        applyEnabledStateToControls(EqualizerRepository.isEnabled())
    }

    private fun bindViews() {
        switchEnabled = findViewById(R.id.switchEqEnabled)
        llBandsContainer = findViewById(R.id.llEqBandsContainer)
        btnReset = findViewById(R.id.btnEqReset)
        btnBack = findViewById(R.id.btnEqBack)
        tvUnavailable = findViewById(R.id.tvEqUnavailable)

        btnBack.setOnClickListener { finish() }
    }

    private fun showUnavailableState() {
        tvUnavailable.visibility = View.VISIBLE
        switchEnabled.visibility = View.GONE
        llBandsContainer.visibility = View.GONE
        btnReset.visibility = View.GONE
    }

    private fun setupEnabledSwitch() {
        switchEnabled.isChecked = EqualizerRepository.isEnabled()
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            EqualizerRepository.setEnabled(isChecked)
            applyEnabledStateToControls(isChecked)
        }
    }

    private fun applyEnabledStateToControls(enabled: Boolean) {
        llBandsContainer.alpha = if (enabled) 1f else 0.4f
        bandSeekBars.forEach { it.isEnabled = enabled }
        btnReset.isEnabled = enabled
    }

    private fun buildBandSliders() {
        llBandsContainer.removeAllViews()
        bandSeekBars.clear()
        bandValueLabels.clear()

        val bandCount = EqualizerRepository.getNumberOfBands()
        val range = EqualizerRepository.getBandLevelRange()
        val minLevel = range[0]
        val maxLevel = range[1]

        for (band in 0 until bandCount) {
            llBandsContainer.addView(buildBandColumn(band, minLevel, maxLevel))
        }
    }

    private fun buildBandColumn(band: Int, minLevel: Short, maxLevel: Short): LinearLayout {
        val currentLevel = EqualizerRepository.getBandLevel(band)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueLabel = TextView(this).apply {
            text = formatDb(currentLevel)
            textSize = 11f
            setTextColor(
                ContextCompat.getColor(
                    this@EqualizerActivity,
                    R.color.text_secondary_light
                )
            )
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Slider vertical = un SeekBar horizontal normal rotado 270 grados.
        val sliderLengthPx = dpToPx(200)
        val sliderThicknessPx = dpToPx(32)

        val seekBar = SeekBar(this).apply {
            max = (maxLevel - minLevel).toInt()
            progress = (currentLevel - minLevel).toInt()
            rotation = 270f
            layoutParams = FrameLayout.LayoutParams(sliderLengthPx, sliderThicknessPx).apply {
                gravity = Gravity.CENTER
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val level = (progress + minLevel).toShort()
                    EqualizerRepository.setBandLevel(band, level)
                    valueLabel.text = formatDb(level)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }

        val sliderWrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(sliderThicknessPx, sliderLengthPx)
            addView(seekBar)
        }

        val freqLabel = TextView(this).apply {
            text = formatFreq(EqualizerRepository.getCenterFreqHz(band))
            textSize = 11f
            setTextColor(
                ContextCompat.getColor(
                    this@EqualizerActivity,
                    R.color.text_secondary_light
                )
            )
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dpToPx(8), 0, 0)
        }

        bandSeekBars.add(seekBar)
        bandValueLabels.add(valueLabel)

        column.addView(valueLabel)
        column.addView(sliderWrapper)
        column.addView(freqLabel)

        return column
    }

    private fun setupResetButton() {
        btnReset.setOnClickListener {
            EqualizerRepository.resetAllBands()
            val range = EqualizerRepository.getBandLevelRange()
            bandSeekBars.forEach { it.progress = (0 - range[0]).toInt() }
            bandValueLabels.forEach { it.text = formatDb(0) }
            Toast.makeText(this, "Ecualizador reiniciado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatDb(levelMillibels: Short): String {
        val db = Math.round(levelMillibels / 100.0)
        return if (db > 0) "+${db}dB" else "${db}dB"
    }

    private fun formatFreq(hz: Int): String {
        return if (hz >= 1000) {
            String.format(Locale.getDefault(), "%.1fkHz", hz / 1000.0)
        } else {
            "${hz}Hz"
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
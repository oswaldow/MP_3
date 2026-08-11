package com.learnlayout.mp_3

import android.animation.ValueAnimator
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
    private lateinit var llPresetsContainer: LinearLayout
    private lateinit var tvSelectedPreset: TextView
    private lateinit var btnReset: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var tvUnavailable: TextView

    private val bandSeekBars = mutableListOf<SeekBar>()
    private val bandValueLabels = mutableListOf<TextView>()
    private val presetChipViews = mutableListOf<TextView>()
    private val runningAnimators = mutableListOf<ValueAnimator>()

    // Descripcion en lenguaje sencillo de que hace cada banda, para gente
    // que no sabe de audio. Mismo orden que
    // SoftwareEqualizerProcessor.CENTER_FREQS_HZ (60, 230, 910, 3600, 14000 Hz).
    private val bandNames = listOf(
        "Graves\n(bajo, bombo)",
        "Cuerpo\n(calidez, voz grave)",
        "Medios\n(voces, guitarra)",
        "Claridad\n(nitidez, detalle)",
        "Brillo\n(aire, platillos)"
    )

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
        buildPresetChips()
        setupResetButton()
        applyEnabledStateToControls(EqualizerRepository.isEnabled())
        highlightChip(findMatchingPresetLabel())
    }

    private fun bindViews() {
        switchEnabled = findViewById(R.id.switchEqEnabled)
        llBandsContainer = findViewById(R.id.llEqBandsContainer)
        llPresetsContainer = findViewById(R.id.llEqPresetsContainer)
        tvSelectedPreset = findViewById(R.id.tvEqSelectedPreset)
        btnReset = findViewById(R.id.btnEqReset)
        btnBack = findViewById(R.id.btnEqBack)
        tvUnavailable = findViewById(R.id.tvEqUnavailable)

        btnBack.setOnClickListener { finish() }

        // El header (flecha + titulo + switch) queda pegado a la barra de
        // estado del sistema con edge-to-edge (Android dibuja debajo de
        // ella por defecto en targetSdk 35+), lo que hacia que el boton
        // "Regresar" y el switch quedaran muy cerca del reloj/notificaciones
        // y a veces el toque se lo llevaba el sistema en vez de la app.
        // Aqui le sumamos el alto real del status bar como padding extra.
        val headerContainer = findViewById<View>(R.id.eqHeaderContainer)
        val basePaddingTop = headerContainer.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(headerContainer) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = basePaddingTop + statusBarHeight)
            insets
        }
    }

    private fun showUnavailableState() {
        tvUnavailable.visibility = View.VISIBLE
        switchEnabled.visibility = View.GONE
        llBandsContainer.visibility = View.GONE
        llPresetsContainer.visibility = View.GONE
        tvSelectedPreset.visibility = View.GONE
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
        llPresetsContainer.alpha = if (enabled) 1f else 0.4f
        bandSeekBars.forEach { it.isEnabled = enabled }
        presetChipViews.forEach { it.isEnabled = enabled }
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

    // Infla item_equalizer_band.xml (tarjeta redondeada con dB, slider
    // vertical, frecuencia y nombre de banda) en vez de armar las vistas
    // por codigo. La logica de guardado (EqualizerRepository.setBandLevel)
    // es exactamente la misma que antes.
    private fun buildBandColumn(band: Int, minLevel: Short, maxLevel: Short): View {
        val currentLevel = EqualizerRepository.getBandLevel(band)

        val column = LayoutInflater.from(this)
            .inflate(R.layout.item_equalizer_band, llBandsContainer, false)
        column.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)

        val valueLabel = column.findViewById<TextView>(R.id.tvBandLevel)
        val freqLabel = column.findViewById<TextView>(R.id.tvBandFreq)
        val nameLabel = column.findViewById<TextView>(R.id.tvBandLabel)
        val seekBar = column.findViewById<SeekBar>(R.id.seekBand)

        valueLabel.text = formatDb(currentLevel)
        freqLabel.text = formatFreq(EqualizerRepository.getCenterFreqHz(band))
        nameLabel.text = bandNames.getOrNull(band) ?: ""

        seekBar.max = (maxLevel - minLevel).toInt()
        seekBar.progress = (currentLevel - minLevel).toInt()
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val level = (progress + minLevel).toShort()
                EqualizerRepository.setBandLevel(band, level)
                valueLabel.text = formatDb(level)
                // El usuario esta ajustando a mano: si el resultado ya no
                // coincide con ningun preset, se marca como personalizado;
                // si por coincidencia cae exacto en uno, se resalta ese chip.
                highlightChip(findMatchingPresetLabel())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        bandSeekBars.add(seekBar)
        bandValueLabels.add(valueLabel)

        return column
    }

    // Construye la fila de chips (Pop, Rock, Jazz, etc.) dentro del
    // HorizontalScrollView. Cada chip guarda su nombre en el tag para
    // poder resaltarlo despues sin volver a recorrer EqualizerPresets.ALL.
    private fun buildPresetChips() {
        llPresetsContainer.removeAllViews()
        presetChipViews.clear()

        for (preset in EqualizerPresets.ALL) {
            val chip = TextView(this).apply {
                text = preset.label
                tag = preset.label
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(18), dp(10), dp(18), dp(10))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    if (!EqualizerRepository.isEnabled()) return@setOnClickListener
                    applyPreset(preset)
                }
            }
            styleChip(chip, selected = false)
            llPresetsContainer.addView(chip)
            presetChipViews.add(chip)
        }
    }

    // Aplica un preset animando cada slider hasta su valor destino en vez
    // de saltar de golpe, y guarda cada banda igual que si el usuario la
    // hubiera movido a mano.
    private fun applyPreset(preset: EqPreset) {
        val range = EqualizerRepository.getBandLevelRange()
        val minLevel = range[0]
        val maxLevel = range[1]

        preset.gainsDb.forEachIndexed { band, gainDb ->
            if (band >= bandSeekBars.size) return@forEachIndexed

            val targetLevel = (gainDb * 100)
                .coerceIn(minLevel.toInt(), maxLevel.toInt())
                .toShort()
            EqualizerRepository.setBandLevel(band, targetLevel)

            val targetProgress = (targetLevel - minLevel).toInt()
            animateSeekBarTo(bandSeekBars[band], bandValueLabels[band], targetProgress, minLevel)
        }

        highlightChip(preset.label)
    }

    private fun animateSeekBarTo(seekBar: SeekBar, label: TextView, targetProgress: Int, minLevel: Short) {
        ValueAnimator.ofInt(seekBar.progress, targetProgress).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val progress = anim.animatedValue as Int
                seekBar.progress = progress
                label.text = formatDb((progress + minLevel).toShort())
            }
            runningAnimators.add(this)
            start()
        }
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.setBackgroundResource(
            if (selected) R.drawable.bg_chip_eq_preset_selected else R.drawable.bg_chip_eq_preset_unselected
        )
        chip.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.spotify_black else R.color.text_primary_light
            )
        )
    }

    // Resalta el chip cuyo label coincide con "label" y actualiza el
    // texto de estado. label == null significa que los valores actuales
    // no coinciden con ningun preset (ajuste manual).
    private fun highlightChip(label: String?) {
        presetChipViews.forEach { chip -> styleChip(chip, chip.tag == label) }
        tvSelectedPreset.text = if (label != null) "Preset: $label" else "Ajuste personalizado"
    }

    // Compara los niveles actuales de banda contra cada preset para saber
    // si alguno coincide exacto. Se usa al abrir la pantalla (para saber
    // que chip resaltar si ya habia un preset guardado) y cada vez que el
    // usuario mueve un slider a mano.
    private fun findMatchingPresetLabel(): String? {
        val range = EqualizerRepository.getBandLevelRange()
        val minLevel = range[0].toInt()
        val maxLevel = range[1].toInt()
        val bandCount = EqualizerRepository.getNumberOfBands()

        val currentLevels = (0 until bandCount).map {
            EqualizerRepository.getBandLevel(it).toInt()
        }

        for (preset in EqualizerPresets.ALL) {
            val presetLevels = preset.gainsDb.map { (it * 100).coerceIn(minLevel, maxLevel) }
            if (presetLevels == currentLevels) return preset.label
        }
        return null
    }

    private fun setupResetButton() {
        btnReset.setOnClickListener {
            EqualizerRepository.resetAllBands()
            val range = EqualizerRepository.getBandLevelRange()
            bandSeekBars.forEach { it.progress = (0 - range[0]).toInt() }
            bandValueLabels.forEach { it.text = formatDb(0) }
            highlightChip("Plano")
            Toast.makeText(this, "Ecualizador reiniciado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runningAnimators.forEach { it.cancel() }
        runningAnimators.clear()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
}
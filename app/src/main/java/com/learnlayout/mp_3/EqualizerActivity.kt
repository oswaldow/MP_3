package com.learnlayout.mp_3

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
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
    private lateinit var rootLayout: View
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

    // Label del preset actualmente resaltado (o null = ajuste personalizado).
    // Se guarda aparte para poder re-pintar los chips cuando cambia el
    // acento dinamico sin tener que volver a calcular cual esta activo.
    private var currentPresetLabel: String? = null

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

    // ---------- Tema dinamico (Material You / PlayerPaletteTheme) ----------
    // Mismo espiritu que el panel del reproductor: el fondo de toda la
    // pantalla se oscurece segun la caratula de la cancion que esta sonando
    // (PlayerPaletteTheme.applyFromBitmap ya limita la luminosidad para no
    // romper el contraste con el texto claro fijo), y el acento que antes
    // era spotify_green fijo (switch, chip de preset seleccionado, texto
    // de preset, boton Restablecer) pasa a seguir el color de la caratula.
    // Si no hay cancion sonando o no tiene caratula, se mantiene el look
    // original (fondo background_dark, acento spotify_green).
    private lateinit var musicService: MusicService
    private var isBound = false

    private val defaultBannerColor: Int by lazy { ContextCompat.getColor(this, R.color.background_dark) }
    private val defaultAccentColor: Int by lazy { ContextCompat.getColor(this, R.color.spotify_green) }
    private var currentAccentColor: Int = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            musicService = (binder as MusicService.MusicBinder).getService()
            isBound = true
            loadThemeFromCurrentSong()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_equalizer)

        currentAccentColor = defaultAccentColor

        bindViews()

        if (!EqualizerRepository.isAvailable) {
            showUnavailableState()
        } else {
            setupEnabledSwitch()
            buildBandSliders()
            buildPresetChips()
            setupResetButton()
            applyEnabledStateToControls(EqualizerRepository.isEnabled())
            highlightChip(findMatchingPresetLabel())
        }

        // Se enlaza al servicio siempre (haya o no ecualizador disponible)
        // para poder tematizar el fondo con la caratula de la cancion actual.
        bindService(Intent(this, MusicService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun bindViews() {
        rootLayout = findViewById(R.id.rootEqLayout)
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

    // El color base (seleccionado o no) sigue al acento dinamico en vez de
    // spotify_green fijo. Cuando el chip esta seleccionado, el color del
    // texto se decide con PlayerPaletteTheme.onColorFor() (igual que los
    // controles del panel) para que siga siendo legible sin importar que
    // tan claro u oscuro sea el acento extraido de la caratula.
    private fun styleChip(chip: TextView, selected: Boolean) {
        if (selected) {
            chip.setBackgroundResource(R.drawable.bg_chip_eq_preset_selected)
            chip.backgroundTintList = ColorStateList.valueOf(currentAccentColor)
            chip.setTextColor(PlayerPaletteTheme.onColorFor(currentAccentColor))
        } else {
            chip.setBackgroundResource(R.drawable.bg_chip_eq_preset_unselected)
            chip.backgroundTintList = null
            chip.setTextColor(ContextCompat.getColor(this, R.color.text_primary_light))
        }
    }

    // Resalta el chip cuyo label coincide con "label" y actualiza el
    // texto de estado. label == null significa que los valores actuales
    // no coinciden con ningun preset (ajuste manual).
    private fun highlightChip(label: String?) {
        currentPresetLabel = label
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

    // ---------- Tema dinamico: carga y aplicacion ----------

    private fun loadThemeFromCurrentSong() {
        val song = musicService.getCurrentSong()
        if (song == null) {
            applyThemeFallback()
            return
        }
        AlbumArtRepository.loadCover(this, song, object : AlbumArtRepository.Callback {
            override fun onCoverReady(bitmap: Bitmap) {
                applyThemeFromBitmap(bitmap)
            }
        })
        // Si no hay caratula en cache ni en red, loadCover simplemente no
        // llama al callback: la pantalla se queda con el fallback ya
        // aplicado arriba (fondo background_dark, acento spotify_green).
    }

    private fun applyThemeFromBitmap(bitmap: Bitmap) {
        PlayerPaletteTheme.applyFromBitmap(bitmap, rootLayout, defaultBannerColor)
        PlayerPaletteTheme.applyAccentFromBitmap(
            bitmap, defaultAccentColor, currentAccentColor
        ) { color ->
            currentAccentColor = color
            applyAccentToControls(color)
        }
    }

    private fun applyThemeFallback() {
        PlayerPaletteTheme.applyFallback(rootLayout, defaultBannerColor)
        PlayerPaletteTheme.applyAccentFallback(defaultAccentColor, currentAccentColor) { color ->
            currentAccentColor = color
            applyAccentToControls(color)
        }
    }

    private fun applyAccentToControls(color: Int) {
        val accentTint = ColorStateList.valueOf(color)
        switchEnabled.thumbTintList = accentTint
        btnReset.backgroundTintList = accentTint
        tvSelectedPreset.setTextColor(color)
        presetChipViews.forEach { chip -> styleChip(chip, chip.tag == currentPresetLabel) }
    }

    override fun onDestroy() {
        super.onDestroy()
        runningAnimators.forEach { it.cancel() }
        runningAnimators.clear()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
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
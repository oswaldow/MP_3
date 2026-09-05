package com.learnlayout.mp_3

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
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
    private lateinit var eqCurveView: EqCurveView
    private lateinit var seekPreamp: SeekBar
    private lateinit var tvPreampValue: TextView
    private lateinit var seekBassBoost: SeekBar
    private lateinit var tvBassBoostValue: TextView
    private lateinit var seekVirtualizer: SeekBar
    private lateinit var tvVirtualizerValue: TextView

    private val bandSeekBars = mutableListOf<SeekBar>()
    private val bandValueLabels = mutableListOf<TextView>()
    private val presetChipViews = mutableListOf<TextView>()

    // Tema dinamico: color de acento actual (de la caratula de la cancion
    // en reproduccion, o el fallback morado si no hay ninguna). Se
    // inicializa con el fallback y se actualiza via AppAccentColor.
    private var chipAccentColor: Int = 0
    private val accentColorListener: (Int?) -> Unit = { color ->
        chipAccentColor = color ?: ContextCompat.getColor(this, R.color.purple_primary)
        eqCurveView.setAccentColor(chipAccentColor)
        presetChipViews.forEach { chip -> styleChip(chip, chip.tag == currentPresetLabel) }
    }
    private val runningAnimators = mutableListOf<ValueAnimator>()

    // Label del preset actualmente resaltado (o null = ajuste personalizado).
    // Se guarda aparte para poder re-pintar los chips cuando cambia el
    // acento dinamico sin tener que volver a calcular cual esta activo.
    private var currentPresetLabel: String? = null

    // Descripcion en lenguaje sencillo de que hace cada banda, para gente
    // que no sabe de audio. Mismo orden que
    // SoftwareEqualizerProcessor.CENTER_FREQS_HZ (31 Hz a 16 kHz).
    private val bandNames = listOf(
        "Subgrave",
        "Grave",
        "Grave medio",
        "Cuerpo",
        "Medios bajos",
        "Medios",
        "Presencia",
        "Claridad",
        "Brillo",
        "Aire"
    )

    // ============================================================
    // FONDO DINAMICO (Material You con destellos), igual que el Home
    // ============================================================
    //
    // Esta pantalla no recibe la cancion por Intent (se abre desde
    // Ajustes), asi que nos conectamos al MusicService igual que hace
    // LyricsActivity solo para leer que se esta reproduciendo ahora y
    // pintar el fondo con ese color. Los chips de preset SI se quedan
    // fijos en verde Spotify (eso no cambia): lo unico que ahora sigue
    // a la caratula es el fondo de toda la pantalla.
    private val ambientBackground: AmbientBackgroundController by lazy {
        AmbientBackgroundController(this, rootLayout)
    }

    private var musicService: MusicService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            musicService = (binder as MusicService.MusicBinder).getService()
            isBound = true
            ambientBackground.updateForSong(musicService?.getCurrentSong())

            // Bass Boost y Virtualizador son efectos por SESION de audio
            // (android.media.audiofx), no por ExoPlayer individual: en
            // cuanto sabemos el audioSessionId real del servicio, los
            // enganchamos aqui. Si el servicio aun no ha reproducido
            // ninguna cancion en esta sesion de la app, getAudioSessionId()
            // devuelve AudioManager.ERROR y attachToSession() no hace nada
            // todavia (los sliders siguen guardando el valor con
            // normalidad; se aplicara solo con que se abra esta pantalla
            // de nuevo una vez haya sonado musica, sin que el usuario
            // tenga que volver a tocar nada).
            musicService?.getAudioSessionId()?.let { sessionId ->
                BassVirtualizerRepository.attachToSession(sessionId)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    private fun setupPreampControls() {
        val range = EqualizerRepository.getPreampRange()
        val minLevel = range[0].toInt()
        val maxLevel = range[1].toInt()

        seekPreamp.max = maxLevel - minLevel
        seekPreamp.progress = EqualizerRepository.getPreampProgress()
        tvPreampValue.text = formatDb(EqualizerRepository.getPreampLevel())

        seekPreamp.isEnabled = EqualizerRepository.isEnabled()

        seekPreamp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val level = (progress + minLevel).toShort()
                tvPreampValue.text = formatDb(level)
                if (fromUser) EqualizerRepository.setPreampLevel(level)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // Bass Boost y Virtualizador son efectos nativos del sistema
    // (android.media.audiofx), no software como el EQ de bandas. Se
    // enganchan a la sesion de audio compartida desde
    // PlaybackEngine.buildPlayer() (ver BassVirtualizerRepository). Aqui
    // solo leemos/escribimos el estado guardado y reflejamos el valor en
    // pantalla; si todavia no hay ninguna sesion de audio activa (por
    // ejemplo, se abrio el Ecualizador sin haber reproducido nada), el
    // valor se guarda igual y se aplicara en cuanto arranque el primer
    // player, sin que el usuario tenga que volver a tocar el slider.
    private fun setupBassAndVirtualizerControls() {
        BassVirtualizerRepository.init(applicationContext)

        val bassStrength = BassVirtualizerRepository.getBassBoostStrength()
        seekBassBoost.max = 1000
        seekBassBoost.progress = bassStrength
        tvBassBoostValue.text = formatPercent(bassStrength)
        seekBassBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBassBoostValue.text = formatPercent(progress)
                if (fromUser) BassVirtualizerRepository.setBassBoostStrength(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val virtualizerStrength = BassVirtualizerRepository.getVirtualizerStrength()
        seekVirtualizer.max = 1000
        seekVirtualizer.progress = virtualizerStrength
        tvVirtualizerValue.text = formatPercent(virtualizerStrength)
        seekVirtualizer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVirtualizerValue.text = formatPercent(progress)
                if (fromUser) BassVirtualizerRepository.setVirtualizerStrength(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun formatPercent(strength: Int): String = "${strength / 10}%"

    private fun currentBandGainsDb(): FloatArray =
        (0 until EqualizerRepository.getNumberOfBands())
            .map { EqualizerRepository.getBandLevel(it) / 100f }
            .toFloatArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_equalizer)

        // Red de seguridad: si por alguna razon esta pantalla se abre antes
        // de que MusicService haya arrancado (y por lo tanto antes de que
        // se haya llamado a EqualizerRepository.init desde ahi), esto evita
        // mostrar el estado por defecto en vez del guardado. init() ya se
        // protege a si mismo para no volver a ejecutar su logica si ya
        // corrio antes, asi que llamarlo de nuevo aqui no tiene costo.
        EqualizerRepository.init(applicationContext)

        bindViews()
        // Se registra despues de bindViews() porque el listener toca
        // eqCurveView y presetChipViews en cuanto se suscribe (con el
        // valor actual de AppAccentColor), y ambos deben existir ya.
        AppAccentColor.addListener(accentColorListener)

        // Fondo neutro (igual al del Home sin cancion) mientras se conecta
        // el servicio; en cuanto llegue onServiceConnected se actualiza con
        // la cancion real, si hay una sonando.
        ambientBackground.updateForSong(null)
        bindService(Intent(this, MusicService::class.java), connection, Context.BIND_AUTO_CREATE)

        if (!EqualizerRepository.isAvailable) {
            showUnavailableState()
        } else {
            setupEnabledSwitch()
            buildBandSliders()
            buildPresetChips()
            setupResetButton()
            setupPreampControls()
            setupBassAndVirtualizerControls()
            applyEnabledStateToControls(EqualizerRepository.isEnabled())
            highlightChip(findMatchingPresetLabel())
        }

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
        eqCurveView = findViewById(R.id.eqCurveView)
        seekPreamp = findViewById(R.id.seekPreamp)
        tvPreampValue = findViewById(R.id.tvPreampValue)
        seekBassBoost = findViewById(R.id.seekBassBoost)
        tvBassBoostValue = findViewById(R.id.tvBassBoostValue)
        seekVirtualizer = findViewById(R.id.seekVirtualizer)
        tvVirtualizerValue = findViewById(R.id.tvVirtualizerValue)

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
        eqCurveView.visibility = View.GONE
        seekPreamp.visibility = View.GONE
        tvPreampValue.visibility = View.GONE
        seekBassBoost.visibility = View.GONE
        tvBassBoostValue.visibility = View.GONE
        seekVirtualizer.visibility = View.GONE
        tvVirtualizerValue.visibility = View.GONE
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
        seekPreamp.isEnabled = enabled
        seekBassBoost.isEnabled = enabled
        seekVirtualizer.isEnabled = enabled
        BassVirtualizerRepository.setMasterEnabled(enabled)
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
        eqCurveView.setFrequencies(EqualizerRepository.getCenterFrequenciesHz())
        eqCurveView.setGainsDb(currentBandGainsDb())
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
                eqCurveView.setGainsDb(currentBandGainsDb())
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

        eqCurveView.setGainsDb(currentBandGainsDb())
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
                eqCurveView.setGainsDb(currentBandGainsDb())
            }
            runningAnimators.add(this)
            start()
        }
    }

    // Los chips de preset seleccionados siguen chipAccentColor (caratula
    // de la cancion actual, o el fallback morado si no hay ninguna). El
    // fondo (ambientBackground) ya seguia la cancion; ahora tambien lo
    // hacen estos chips, via accentColorListener.
    private fun styleChip(chip: TextView, selected: Boolean) {
        if (selected) {
            chip.setBackgroundResource(R.drawable.bg_chip_eq_preset_selected)
            chip.backgroundTintList = ColorStateList.valueOf(chipAccentColor)
            chip.setTextColor(ContextCompat.getColor(this, R.color.spotify_black))
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
            eqCurveView.setGainsDb(currentBandGainsDb())
            tvPreampValue.text = formatDb(EqualizerRepository.getPreampLevel())
            seekPreamp.progress = EqualizerRepository.getPreampProgress()
            seekPreamp.isEnabled = EqualizerRepository.isEnabled()
            BassVirtualizerRepository.reset()
            seekBassBoost.progress = 0
            tvBassBoostValue.text = formatPercent(0)
            seekVirtualizer.progress = 0
            tvVirtualizerValue.text = formatPercent(0)
            highlightChip("Plano")
            Toast.makeText(this, "Ecualizador reiniciado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppAccentColor.removeListener(accentColorListener)
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
        return when {
            hz >= 1000 -> {
                val k = hz / 1000
                "${k}k"
            }
            else -> "${hz}Hz"
        }
    }
}
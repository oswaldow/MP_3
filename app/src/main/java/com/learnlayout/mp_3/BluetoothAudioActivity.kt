package com.learnlayout.mp_3

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class BluetoothAudioActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var llConnectedDevices: LinearLayout
    private lateinit var tvEmptyDevices: TextView
    private lateinit var tvPermissionNeeded: TextView
    private lateinit var btnOpenBluetoothSettings: Button
    private lateinit var btnOpenDeveloperOptions: Button

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var a2dpProxy: BluetoothA2dp? = null
    private var headsetProxy: BluetoothHeadset? = null

    // Direcciones (mac) de los dispositivos que ya se mostraron, para no
    // duplicar tarjetas cuando A2DP y Headset reportan el mismo aparato.
    private val shownAddresses = mutableSetOf<String>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            tvPermissionNeeded.visibility = android.view.View.GONE
            loadConnectedDevices()
        } else {
            tvPermissionNeeded.visibility = android.view.View.VISIBLE
            Toast.makeText(this, "Sin permiso no se pueden ver los dispositivos conectados", Toast.LENGTH_SHORT).show()
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = proxy as? BluetoothA2dp
                BluetoothProfile.HEADSET -> headsetProxy = proxy as? BluetoothHeadset
            }
            refreshDeviceList()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = null
                BluetoothProfile.HEADSET -> headsetProxy = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bluetooth_audio)

        bindViews()
        setupListeners()

        val manager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = manager?.adapter

        checkPermissionAndLoad()
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        llConnectedDevices = findViewById(R.id.llConnectedDevices)
        tvEmptyDevices = findViewById(R.id.tvEmptyDevices)
        tvPermissionNeeded = findViewById(R.id.tvPermissionNeeded)
        btnOpenBluetoothSettings = findViewById(R.id.btnOpenBluetoothSettings)
        btnOpenDeveloperOptions = findViewById(R.id.btnOpenDeveloperOptions)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        btnOpenBluetoothSettings.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }.onFailure {
                Toast.makeText(this, "No se pudo abrir ajustes de Bluetooth", Toast.LENGTH_SHORT).show()
            }
        }

        btnOpenDeveloperOptions.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }.onFailure {
                Toast.makeText(
                    this,
                    "Activa primero las opciones de desarrollador (Ajustes > Acerca del telefono > toca 7 veces el numero de compilacion)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun checkPermissionAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                tvPermissionNeeded.visibility = android.view.View.GONE
                loadConnectedDevices()
            } else {
                tvPermissionNeeded.visibility = android.view.View.VISIBLE
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            loadConnectedDevices()
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadConnectedDevices() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            showEmptyState("El Bluetooth esta apagado")
            return
        }

        shownAddresses.clear()
        llConnectedDevices.removeAllViews()

        adapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
        adapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)

        // refreshDeviceList() tambien se llama desde onServiceConnected en
        // cuanto los proxies esten listos (es asincrono), pero si ya
        // estaban conectados de una llamada previa refrescamos de una vez.
        refreshDeviceList()
    }

    @SuppressLint("MissingPermission")
    private fun refreshDeviceList() {
        val connected = mutableListOf<Pair<BluetoothDevice, String>>()

        a2dpProxy?.connectedDevices?.forEach { device ->
            connected.add(device to "Conectado - Audio (A2DP)")
        }
        headsetProxy?.connectedDevices?.forEach { device ->
            if (connected.none { it.first.address == device.address }) {
                connected.add(device to "Conectado - Manos libres")
            }
        }

        llConnectedDevices.removeAllViews()
        shownAddresses.clear()

        if (connected.isEmpty()) {
            showEmptyState("No hay bocinas ni audifonos Bluetooth conectados en este momento")
            return
        }

        tvEmptyDevices.visibility = android.view.View.GONE

        connected.forEach { (device, statusLabel) ->
            if (shownAddresses.add(device.address)) {
                addDeviceCard(device, statusLabel)
            }
        }
    }

    private fun showEmptyState(message: String) {
        llConnectedDevices.removeAllViews()
        tvEmptyDevices.text = message
        tvEmptyDevices.visibility = android.view.View.VISIBLE
    }

    @SuppressLint("MissingPermission")
    private fun addDeviceCard(device: BluetoothDevice, statusLabel: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_bluetooth_device, llConnectedDevices, false)

        val tvName = view.findViewById<TextView>(R.id.tvDeviceName)
        val tvType = view.findViewById<TextView>(R.id.tvDeviceType)
        val tvSpecs = view.findViewById<TextView>(R.id.tvDeviceSpecs)

        tvName.text = device.name ?: "Dispositivo Bluetooth"
        tvType.text = statusLabel
        tvSpecs.text = findAudioSpecsFor(device)

        llConnectedDevices.addView(view)
    }

    // Android no expone a las apps el codec real (SBC/AAC/aptX/LDAC), pero
    // si expone, via AudioManager, las capacidades del "salida" de audio
    // que esta usando ese dispositivo Bluetooth (sample rate, canales).
    // Es informacion real y verificable, a diferencia de intentar forzar
    // un "modo de mejor calidad" que ninguna app de terceros puede activar.
    private fun findAudioSpecsFor(device: BluetoothDevice): String {
        val audioManager = getSystemService(AudioManager::class.java) ?: return "Sin datos de audio disponibles"
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val btOutput = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } ?: return "Conectado, pero no esta activo como salida de audio ahora mismo"

        val maxSampleRate = btOutput.sampleRates.maxOrNull()
        val channels = btOutput.channelCounts.maxOrNull()

        val parts = mutableListOf<String>()
        if (maxSampleRate != null && maxSampleRate > 0) parts.add("Hasta ${maxSampleRate} Hz")
        if (channels != null && channels > 0) {
            parts.add(if (channels >= 2) "Estereo" else "Mono")
        }
        return if (parts.isEmpty()) "Especificaciones no disponibles" else parts.joinToString(" - ")
    }

    override fun onDestroy() {
        super.onDestroy()
        val adapter = bluetoothAdapter
        a2dpProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        headsetProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
    }
}
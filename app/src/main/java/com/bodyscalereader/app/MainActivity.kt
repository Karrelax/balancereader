package com.bodyscalereader.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.bodyscalereader.app.databinding.ActivityMainBinding
import com.bodyscalereader.app.database.AppDatabase
import com.bodyscalereader.app.database.Measurement
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AppDatabase
    private lateinit var adapter: MeasurementAdapter
    private var bluetoothLeService: BluetoothLeService? = null
    private val handler = Handler(Looper.getMainLooper())

    // Datos temporales para procesar frames
    private var currentWeight: Float? = null
    private var currentImpedance: Float? = null
    private var currentHr: Int? = null

    private var currentDevice: BluetoothDevice? = null

    // --- Permisos y Bluetooth ---
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
    }

    // Lanzador para solicitar permisos
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startBluetoothService() // Inicia el servicio BLE si los permisos están concedidos
        } else {
            Toast.makeText(
                this,
                "Permisos necesarios para usar Bluetooth",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Lanzador para activar Bluetooth
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startBluetoothService()
        } else {
            Toast.makeText(this, "Bluetooth es necesario", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Receptor de Broadcasts (BLE) ---
    private val bleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothLeService.ACTION_DATA_AVAILABLE -> {
                    val frame = intent.getStringExtra(BluetoothLeService.EXTRA_FRAME) ?: return
                    processFrame(frame)
                }
                BluetoothLeService.ACTION_GATT_CONNECTED -> {
                    runOnUiThread {
                        binding.btnConnect.text = "Conectado"
                        binding.tvStatus.text = "Conectado - Esperando datos..."
                    }
                }
                BluetoothLeService.ACTION_GATT_DISCONNECTED -> {
                    runOnUiThread {
                        val autoConnect = SettingsActivity.isAutoConnectEnabled(this@MainActivity)
                        binding.btnConnect.text = if (autoConnect) "Reconectando..." else "Conectar"
                        binding.tvStatus.text = if (autoConnect) {
                            "Desconectado - Reconectando..."
                        } else {
                            "Desconectado"
                        }
                    }
                }
                BluetoothLeService.ACTION_ACTIVATION_SENT -> {
                    runOnUiThread {
                        binding.tvStatus.text = "Código de activación enviado - Esperando datos..."
                    }
                }
            }
        }
    }

    // --- Conexión al servicio BLE ---
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as BluetoothLeService.LocalBinder
            bluetoothLeService = binder.getService()

            // Si hay un dispositivo guardado, conéctate automáticamente
            val savedAddress = SettingsActivity.getSavedDeviceAddress(this@MainActivity)
            if (savedAddress != null) {
                val device = bluetoothAdapter?.getRemoteDevice(savedAddress)
                device?.let {
                    currentDevice = it
                    val autoConnect = SettingsActivity.isAutoConnectEnabled(this@MainActivity)
                    bluetoothLeService?.connect(it.address, autoConnect)
                }
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            bluetoothLeService = null
            runOnUiThread {
                binding.btnConnect.text = "Conectar"
                binding.tvStatus.text = "Servicio BLE desconectado"
            }
        }
    }

    // --- Ciclo de vida ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicializa la base de datos y el adaptador
        db = AppDatabase.getInstance(this)
        adapter = MeasurementAdapter { measurement -> showMeasurementDetail(measurement) }
        binding.rvMeasurements.layoutManager = LinearLayoutManager(this)
        binding.rvMeasurements.adapter = adapter

        // Configura los botones
        setupButtons()
        loadMeasurements()

        // Registra el receptor de broadcasts
        LocalBroadcastManager.getInstance(this).apply {
            registerReceiver(bleReceiver, IntentFilter(BluetoothLeService.ACTION_DATA_AVAILABLE))
            registerReceiver(bleReceiver, IntentFilter(BluetoothLeService.ACTION_GATT_CONNECTED))
            registerReceiver(bleReceiver, IntentFilter(BluetoothLeService.ACTION_GATT_DISCONNECTED))
            registerReceiver(bleReceiver, IntentFilter(BluetoothLeService.ACTION_ACTIVATION_SENT))
        }

        // Solicita permisos y activa Bluetooth al iniciar
        checkPermissionsAndStart()
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleReceiver)
        // NO desconectes el servicio si autoconnect está activado
        if (!SettingsActivity.isAutoConnectEnabled(this)) {
            bluetoothLeService?.disconnect()
        }
        unbindService(serviceConnection)
    }

    // --- Configuración de botones ---
    private fun setupButtons() {
        // Botón de conectar/desconectar (ahora solo desconecta manualmente)
        binding.btnConnect.setOnClickListener {
            if (bluetoothLeService != null) {
                bluetoothLeService?.disconnect()
                SettingsActivity.setAutoConnectEnabled(this, false)
                binding.btnConnect.text = "Conectar"
                binding.tvStatus.text = "Desconectado"
            } else {
                checkPermissionsAndStart()
            }
        }

        // Botón de exportar
        binding.btnExport.setOnClickListener { showExportDialog() }

        // Botón de borrar
        binding.btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Borrar todo")
                .setMessage("¿Borrar todas las mediciones?")
                .setPositiveButton("Borrar") { _, _ ->
                    lifecycleScope.launch {
                        db.measurementDao().deleteAll()
                        loadMeasurements()
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Borrado", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        // Botón de configuración
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // --- Permisos y Bluetooth ---
    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()

        // Permisos para Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        // Permiso para Android < 12 (ACCESS_FINE_LOCATION es necesario para BLE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            // Verifica si Bluetooth está activado
            if (bluetoothAdapter?.isEnabled == true) {
                startBluetoothService()
            } else {
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        }
    }

    private fun startBluetoothService() {
        val gattIntent = Intent(this, BluetoothLeService::class.java)
        bindService(gattIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // --- Procesamiento de datos ---
    private fun processFrame(frame: String) {
        runOnUiThread { binding.tvStatus.text = "Recibido: $frame" }

        val result = DataParser.parseFrame(frame)

        result.weight?.let { currentWeight = it }
        result.impedance?.let { currentImpedance = it }
        result.heartRate?.let { currentHr = it }

        // Si tenemos todos los datos, guarda la medición
        if (currentWeight != null && currentImpedance != null && currentHr != null) {
            saveMeasurement(currentWeight!!, currentImpedance!!, currentHr!!)
            currentWeight = null
            currentImpedance = null
            currentHr = null
        }
    }

    private fun saveMeasurement(weight: Float, impedance: Float, hr: Int) {
        val profile = SettingsActivity.getUserProfile(this)
        val stats = BodyCompositionCalculator.calculate(weight, impedance, hr, profile)

        val measurement = Measurement(
            timestamp = Date(),
            weight = weight,
            impedance = impedance,
            heartRate = hr,
            bmi = stats.bmi,
            bodyWater = stats.bodyWater,
            bodyFat = stats.bodyFat,
            muscleMass = stats.muscleMass,
            visceralFat = stats.visceralFat,
            boneMass = stats.boneMass,
            bmr = stats.bmr,
            protein = stats.protein,
            subcutaneousFat = stats.subcutaneousFat,
            physicalAge = stats.physicalAge,
            leanMass = stats.leanMass,
            standardWeight = stats.standardWeight,
            skeletalMuscle = stats.skeletalMuscle,
            muscleRatio = stats.muscleRatio,
            bodyType = stats.bodyType,
            rawFrames = ""
        )

        lifecycleScope.launch {
            db.measurementDao().insert(measurement)
            loadMeasurements()
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Medición guardada ✓", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadMeasurements() {
        lifecycleScope.launch {
            val measurements = db.measurementDao().getAll()
            withContext(Dispatchers.Main) {
                adapter.submitList(measurements)
            }
        }
    }

    // --- Exportación de datos ---
    private fun showExportDialog() {
        val options = arrayOf("Exportar CSV", "Exportar JSON")
        AlertDialog.Builder(this)
            .setTitle("Exportar datos")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportAndShare("csv")
                    1 -> exportAndShare("json")
                }
            }
            .show()
    }

    private fun exportAndShare(format: String) {
        lifecycleScope.launch {
            val measurements = db.measurementDao().getAll()
            val (content, mimeType) = when (format) {
                "json" -> Gson().toJson(measurements) to "application/json"
                else -> buildCSV(measurements) to "text/csv"
            }

            val filename = "balanza_${System.currentTimeMillis()}.$format"
            val file = saveToFile(filename, content)

            withContext(Dispatchers.Main) {
                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.provider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Compartir mediciones"))
            }
        }
    }

    private fun buildCSV(measurements: List<Measurement>): String {
        val sb = StringBuilder()
        sb.append("Fecha,Peso(kg),Impedancia(Ω),HR(bpm),BMI,Agua(%),Grasa(%),MasaMuscular(kg),GrasaVisceral(%),MasaOsea(kg),BMR(kcal),Proteína(%),GrasaSubcutánea(%),EdadFísica,PesoMagro(kg),PesoEstándar(kg),MúsculoEsquelético(%),RelaciónMuscular(%),TipoCuerpo\n")
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        for (m in measurements) {
            sb.append("${sdf.format(m.timestamp)},${m.weight},${m.impedance},${m.heartRate},")
            sb.append("${m.bmi},${m.bodyWater},${m.bodyFat},${m.muscleMass},")
            sb.append("${m.visceralFat},${m.boneMass},${m.bmr},${m.protein},")
            sb.append("${m.subcutaneousFat},${m.physicalAge},${m.leanMass},")
            sb.append("${m.standardWeight},${m.skeletalMuscle},${m.muscleRatio},${m.bodyType}\n")
        }
        return sb.toString()
    }

    private fun saveToFile(filename: String, content: String): File {
        val file = File(getExternalFilesDir(null), filename)
        FileOutputStream(file).use { it.write(content.toByteArray()) }
        return file
    }

    // --- Detalles de medición ---
    private fun showMeasurementDetail(measurement: Measurement) {
        val stats = BodyCompositionCalculator.Result(
            bmi = measurement.bmi,
            bodyWater = measurement.bodyWater,
            bodyFat = measurement.bodyFat,
            muscleMass = measurement.muscleMass,
            visceralFat = measurement.visceralFat,
            boneMass = measurement.boneMass,
            bmr = measurement.bmr,
            protein = measurement.protein,
            subcutaneousFat = measurement.subcutaneousFat,
            physicalAge = measurement.physicalAge,
            leanMass = measurement.leanMass,
            standardWeight = measurement.standardWeight,
            skeletalMuscle = measurement.skeletalMuscle,
            muscleRatio = measurement.muscleRatio,
            bodyType = measurement.bodyType
        )

        AlertDialog.Builder(this)
            .setTitle("Detalle - ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(measurement.timestamp)}")
            .setMessage(BodyCompositionCalculator.formatStats(stats))
            .setPositiveButton("OK", null)
            .show()
    }
}

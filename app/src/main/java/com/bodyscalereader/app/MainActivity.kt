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
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    private var currentWeight: Float? = null
    private var currentImpedance: Float? = null
    private var currentHr: Int? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
    }

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
                        binding.btnConnect.text = "Conectar"
                        binding.tvStatus.text = if (autoConnect) "Desconectado - Reconectando..." else "Desconectado"
                        isScanning = false
                    }
                }
            }
        }
    }

    private val scanResults = mutableListOf<BluetoothDevice>()

    private val scanCallback = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            val device = result.device
            if (device.name?.contains("INSMART") == true && !scanResults.contains(device)) {
                scanResults.add(device)
                runOnUiThread { updateDeviceList() }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Error de escaneo: $errorCode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val enableBtLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startBleScan()
        else Toast.makeText(this, "Bluetooth necesario", Toast.LENGTH_SHORT).show()
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) startBleScan()
        else Toast.makeText(this, "Permisos necesarios para escanear", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)
        adapter = MeasurementAdapter { measurement -> showMeasurementDetail(measurement) }

        binding.rvMeasurements.layoutManager = LinearLayoutManager(this)
        binding.rvMeasurements.adapter = adapter

        setupButtons()
        loadMeasurements()

        LocalBroadcastManager.getInstance(this).apply {
            registerReceiver(bleReceiver, IntentFilter(BluetoothLeService.ACTION_DATA_AVAILABLE))
            registerReceiver(bleReceiver, IntentFilter(BluetoothLeService.ACTION_GATT_CONNECTED))
            registerReceiver(bleReceiver, IntentFilter(BluetoothLeService.ACTION_GATT_DISCONNECTED))
        }

        // Autoconnect on launch if a saved device exists and user opted in
        tryAutoConnect()
    }

    private fun setupButtons() {
        binding.btnConnect.setOnClickListener {
            if (isScanning) stopBleScan() else checkPermissionsAndStart()
        }

        binding.btnExport.setOnClickListener { showExportDialog() }

        binding.btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Borrar todo")
                .setMessage("¿Borrar todas las mediciones?")
                .setPositiveButton("Borrar") { _, _ ->
                    lifecycleScope.launch {
                        db.measurementDao().deleteAll()
                        loadMeasurements()
                        runOnUiThread { Toast.makeText(this@MainActivity, "Borrado", Toast.LENGTH_SHORT).show() }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // FIX: permissions now correctly handle both Android 12+ and older versions
    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

            if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
            else startBleScan()
        } else {
            // FIX: pre-Android-12 requires ACCESS_FINE_LOCATION for BLE scan
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                return
            }
            if (bluetoothAdapter?.isEnabled != true) {
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                startBleScan()
            }
        }
    }

    // FIX: actually refreshes the UI — previously just called println()
    private fun updateDeviceList() {
        binding.tvStatus.text = "Encontrada(s) ${scanResults.size} báscula(s)..."
    }

    private fun startBleScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        }

        scanResults.clear()
        isScanning = true
        binding.btnConnect.text = "Buscando..."
        binding.tvStatus.text = "Buscando báscula..."

        bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)

        handler.postDelayed({
            if (isScanning) stopBleScan()
        }, 10_000)
    }

    private fun stopBleScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        }

        isScanning = false
        binding.btnConnect.text = "Conectar"
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)

        if (scanResults.isEmpty()) binding.tvStatus.text = "No se encontró báscula"
        else showDeviceSelectionDialog()
    }

    private fun showDeviceSelectionDialog() {
        val deviceNames = scanResults.map { "${it.name ?: "Desconocido"} (${it.address})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Selecciona tu báscula")
            .setItems(deviceNames) { _, which ->
                val device = scanResults[which]
                // Save for future autoconnect
                SettingsActivity.saveDeviceAddress(this, device.address)
                connectToDevice(device)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun connectToDevice(device: BluetoothDevice) {
        binding.tvStatus.text = "Conectando a ${device.name}..."
        currentDevice = device

        val gattIntent = Intent(this, BluetoothLeService::class.java)
        bindService(gattIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private var currentDevice: BluetoothDevice? = null

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as BluetoothLeService.LocalBinder
            bluetoothLeService = binder.getService()

            currentDevice?.let { device ->
                val autoConnect = SettingsActivity.isAutoConnectEnabled(this@MainActivity)
                if (bluetoothLeService?.connect(device.address, autoConnect) == true) {
                    currentWeight = null
                    currentImpedance = null
                    currentHr = null
                } else {
                    binding.tvStatus.text = "Error de conexión"
                }
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            bluetoothLeService = null
            binding.btnConnect.text = "Conectar"
            binding.tvStatus.text = "Desconectado"
        }
    }

    // Autoconnect: skip scan if we already know the device address
    private fun tryAutoConnect() {
        if (!SettingsActivity.isAutoConnectEnabled(this)) return
        val address = SettingsActivity.getSavedDeviceAddress(this) ?: return

        val device = runCatching {
            bluetoothAdapter?.getRemoteDevice(address)
        }.getOrNull() ?: return

        currentDevice = device
        binding.tvStatus.text = "Reconectando a ${device.name ?: address}..."

        val gattIntent = Intent(this, BluetoothLeService::class.java)
        bindService(gattIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun processFrame(frame: String) {
        runOnUiThread { binding.tvStatus.text = "Recibido: $frame" }

        val result = DataParser.parseFrame(frame)

        result.weight?.let { currentWeight = it }
        result.impedance?.let { currentImpedance = it }
        result.heartRate?.let { currentHr = it }

        if (currentWeight != null && currentImpedance != null && currentHr != null) {
            saveMeasurement(currentWeight!!, currentImpedance!!, currentHr!!)
            currentWeight = null
            currentImpedance = null
            currentHr = null
        }
    }

    private fun saveMeasurement(weight: Float, impedance: Float, hr: Int) {
        // FIX: user profile comes from Settings, not hardcoded constants
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

    private fun showExportDialog() {
        val options = arrayOf("Exportar CSV", "Exportar JSON")   // FIX: removed fake XLS option

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

    // FIX: exports now use a share intent so the user can open/send the file directly
    private fun exportAndShare(format: String) {
        lifecycleScope.launch {
            val measurements = db.measurementDao().getAll()
            val (content, mimeType) = when (format) {
                "json" -> Gson().toJson(measurements) to "application/json"
                else   -> buildCSV(measurements) to "text/csv"
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

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleReceiver)
        // Note: do NOT call disconnect() here if autoconnect is on — the service keeps running
        if (!SettingsActivity.isAutoConnectEnabled(this)) {
            bluetoothLeService?.disconnect()
        }
    }
}

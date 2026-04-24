package com.bodyscalereader.app

import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.*

class BluetoothLeService : Service() {

    private val binder = LocalBinder()
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val scanPeriod = 10_000L // Escanea cada 10 segundos (ajustable)
    private val reconnectDelayMs = 5_000L // Reintenta conexión cada 5 segundos

    // --- Autoconnect state ---
    private var autoConnectAddress: String? = null
    private var autoConnectEnabled: Boolean = false

    // MAC de tu balanza (REemplaza con la MAC real)
    private val SCALE_MAC_ADDRESS = "CF:E7:9A:09:04:00" // Ejemplo: "00:22:5E:11:22:33"

    companion object {
        private const val TAG = "BLEService"

        // UUIDs de la balanza (Insmart/Feelfit)
        val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Código de activación para la balanza
        val ACTIVATION_CODE: ByteArray = byteArrayOf(0xFD.toByte(), 0x00.toByte())

        // Acciones para Broadcast
        const val ACTION_GATT_CONNECTED = "ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED = "ACTION_GATT_DISCONNECTED"
        const val ACTION_DATA_AVAILABLE = "ACTION_DATA_AVAILABLE"
        const val ACTION_ACTIVATION_SENT = "ACTION_ACTIVATION_SENT"
        const val EXTRA_FRAME = "EXTRA_FRAME"
    }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothLeService = this@BluetoothLeService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        startScanning() // Empieza a escanear al crear el servicio
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScanning()
        disconnect()
    }

    // --- Escaneo BLE ---
    private fun startScanning() {
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth no está activado")
            return
        }

        if (scanning) return
        scanning = true

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Prioriza detección rápida
            .build()

        scanner?.startScan(null, settings, scanCallback)
        Log.i(TAG, "Escaneo BLE iniciado")

        // Detener el escaneo después de `scanPeriod` y reiniciar
        handler.postDelayed({
            scanner?.stopScan(scanCallback)
            scanning = false
            startScanning()
        }, scanPeriod)
    }

    private fun stopScanning() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        Log.i(TAG, "Escaneo BLE detenido")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.address == SCALE_MAC_ADDRESS) {
                Log.i(TAG, "Balanza detectada: ${device.address}")
                stopScanning()
                connect(device.address, autoConnect = true)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Error en el escaneo: $errorCode")
            scanning = false
            startScanning() // Reinicia el escaneo
        }
    }

    // --- Conexión BLE ---
    fun connect(address: String, autoConnect: Boolean = false): Boolean {
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return false

        // Cierra cualquier conexión existente
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null

        autoConnectAddress = address
        autoConnectEnabled = autoConnect

        // Usa autoConnect = true para reconexión automática
        bluetoothGatt = device.connectGatt(this, autoConnect, gattCallback)
        Log.i(TAG, "Intentando conectar a $address (autoConnect=$autoConnect)")
        return true
    }

    fun disconnect() {
        autoConnectEnabled = false
        autoConnectAddress = null
        handler.removeCallbacksAndMessages(null)

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        Log.i(TAG, "Desconectado manualmente")
    }

    private fun scheduleReconnect() {
        val address = autoConnectAddress ?: return
        if (!autoConnectEnabled) return
        Log.i(TAG, "Reintentando conexión a $address en ${reconnectDelayMs}ms")
        handler.postDelayed({
            connect(address, autoConnect = true)
        }, reconnectDelayMs)
    }

    // --- Envío del código de activación ---
    private fun sendActivationCode(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID)
        val writeChar = service?.getCharacteristic(WRITE_CHARACTERISTIC_UUID)

        if (writeChar == null) {
            Log.w(TAG, "Característica de escritura (fff1) no encontrada")
            return
        }

        val sent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                writeChar,
                ACTIVATION_CODE,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            writeChar.value = ACTIVATION_CODE
            writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(writeChar)
        }

        if (sent) {
            Log.i(TAG, "Código de activación enviado: ${ACTIVATION_CODE.joinToString("-") { "%02X".format(it) }}")
            broadcastUpdate(ACTION_ACTIVATION_SENT)
        } else {
            Log.w(TAG, "Fallo al enviar código de activación")
        }
    }

    // --- Callback de BLE ---
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Conectado a ${gatt.device.address}")
                    broadcastUpdate(ACTION_GATT_CONNECTED)
                    // Descubre servicios y envía el código de activación lo antes posible
                    if (gatt.discoverServices()) {
                        // Espera 500ms y envía el código de activación (sin esperar a onServicesDiscovered)
                        handler.postDelayed({
                            sendActivationCode(gatt)
                        }, 500)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Desconectado (status=$status)")
                    broadcastUpdate(ACTION_GATT_DISCONNECTED)
                    gatt.close()
                    if (bluetoothGatt == gatt) bluetoothGatt = null
                    if (autoConnectEnabled) {
                        scheduleReconnect()
                    } else {
                        startScanning() // Si no es autoConnect, vuelve a escanear
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val notifyChar = service?.getCharacteristic(CHARACTERISTIC_UUID)

                notifyChar?.let { char ->
                    gatt.setCharacteristicNotification(char, true)
                    val descriptor = char.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                    descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                    Log.i(TAG, "Notificaciones habilitadas para ${CHARACTERISTIC_UUID}")
                } ?: Log.w(TAG, "Característica de notificación (fff4) no encontrada")
            } else {
                Log.w(TAG, "Fallo al descubrir servicios: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val hexString = value.joinToString("-") { "%02X".format(it) }
            Log.d(TAG, "Datos recibidos: $hexString")
            broadcastUpdate(ACTION_DATA_AVAILABLE, hexString)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Fallo al escribir descriptor: $status")
            }
        }
    }

    // --- Broadcasts ---
    private fun broadcastUpdate(action: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(action))
    }

    private fun broadcastUpdate(action: String, frame: String) {
        val intent = Intent(action)
        intent.putExtra(EXTRA_FRAME, frame)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}

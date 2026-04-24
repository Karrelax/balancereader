package com.bodyscalereader.app

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.UUID

class BluetoothLeService : Service() {

    private val binder = LocalBinder()
    private var bluetoothGatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())

    // --- Autoconnect state ---
    private var autoConnectAddress: String? = null
    private var autoConnectEnabled: Boolean = false
    private val reconnectDelayMs = 5_000L

    companion object {
        private const val TAG = "BLEService"

        val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Activation code sent to fff1 to wake the scale up.
        // FD-00 is the standard INSMART/Feelfit activation handshake.
        // If the scale doesn't respond, try: FD-AA, or FE-<user bytes>
        val ACTIVATION_CODE: ByteArray = byteArrayOf(0xFD.toByte(), 0x00.toByte())

        const val ACTION_GATT_CONNECTED    = "ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED = "ACTION_GATT_DISCONNECTED"
        const val ACTION_DATA_AVAILABLE    = "ACTION_DATA_AVAILABLE"
        const val ACTION_ACTIVATION_SENT   = "ACTION_ACTIVATION_SENT"
        const val EXTRA_FRAME = "EXTRA_FRAME"
    }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothLeService = this@BluetoothLeService
    }

    override fun onBind(intent: Intent): IBinder = binder

    fun connect(address: String, autoConnect: Boolean = false): Boolean {
        val device = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) ?: return false

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null

        autoConnectAddress = address
        autoConnectEnabled = autoConnect

        bluetoothGatt = device.connectGatt(this, false, gattCallback)
        return true
    }

    fun disconnect() {
        autoConnectEnabled = false
        autoConnectAddress = null
        handler.removeCallbacksAndMessages(null)

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private fun scheduleReconnect() {
        val address = autoConnectAddress ?: return
        if (!autoConnectEnabled) return
        Log.i(TAG, "Scheduling reconnect in ${reconnectDelayMs}ms to $address")
        handler.postDelayed({
            connect(address, autoConnect = true)
        }, reconnectDelayMs)
    }

    /**
     * Sends the activation code to the scale's write characteristic (fff1).
     * Called automatically once notifications are enabled (onDescriptorWrite).
     * The scale responds with F1-00 / F2-00 / F2-01 and then starts streaming data.
     */
    private fun sendActivationCode(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID)
        val writeChar = service?.getCharacteristic(WRITE_CHARACTERISTIC_UUID)

        if (writeChar == null) {
            Log.w(TAG, "Write characteristic fff1 not found — cannot send activation code")
            return
        }

        val sent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: modern non-deprecated API
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
            Log.i(TAG, "Activation code sent: ${ACTIVATION_CODE.joinToString("-") { "%02X".format(it) }}")
            broadcastUpdate(ACTION_ACTIVATION_SENT)
        } else {
            Log.w(TAG, "Failed to send activation code")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected")
                    broadcastUpdate(ACTION_GATT_CONNECTED)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected (status=$status)")
                    broadcastUpdate(ACTION_GATT_DISCONNECTED)
                    gatt.close()
                    if (bluetoothGatt == gatt) bluetoothGatt = null
                    scheduleReconnect()
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
                    // Activation code is sent in onDescriptorWrite once notifications are confirmed ready
                } ?: Log.w(TAG, "Notify characteristic fff4 not found")
            } else {
                Log.w(TAG, "Service discovery failed: $status")
            }
        }

        /**
         * Called when the CCCD descriptor write completes (notifications enabled).
         * This is the right moment to send the activation code — the scale is
         * connected, services are discovered, and it is now listening for our write.
         */
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Notifications enabled — sending activation code")
                // 200ms delay lets the scale settle before the write
                handler.postDelayed({ sendActivationCode(gatt) }, 200)
            } else {
                Log.w(TAG, "Descriptor write failed: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val hexString = value.joinToString("-") { "%02X".format(it) }
            Log.d(TAG, "Received: $hexString")
            broadcastUpdate(ACTION_DATA_AVAILABLE, hexString)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }

    private fun broadcastUpdate(action: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(action))
    }

    private fun broadcastUpdate(action: String, frame: String) {
        val intent = Intent(action)
        intent.putExtra(EXTRA_FRAME, frame)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}

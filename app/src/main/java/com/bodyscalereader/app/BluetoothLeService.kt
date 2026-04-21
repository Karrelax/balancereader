package com.bodyscalereader.app

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.UUID

class BluetoothLeService : Service() {
    
    private val binder = LocalBinder()
    private var bluetoothGatt: BluetoothGatt? = null
    
    companion object {
        private const val TAG = "BLEService"
        
        val SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        
        const val ACTION_GATT_CONNECTED = "ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED = "ACTION_GATT_DISCONNECTED"
        const val ACTION_DATA_AVAILABLE = "ACTION_DATA_AVAILABLE"
        const val EXTRA_FRAME = "EXTRA_FRAME"
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): BluetoothLeService = this@BluetoothLeService
    }
    
    override fun onBind(intent: Intent): IBinder = binder
    
    fun connect(address: String): Boolean {
        val device = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
            ?: return false
        
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
        return true
    }
    
    fun disconnect() {
        bluetoothGatt?.disconnect()
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
                    Log.i(TAG, "Disconnected")
                    broadcastUpdate(ACTION_GATT_DISCONNECTED)
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                
                characteristic?.let { char ->
                    gatt.setCharacteristicNotification(char, true)
                    val descriptor = char.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                    descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
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
    
    private fun broadcastUpdate(action: String) {
        val intent = Intent(action)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
    
    private fun broadcastUpdate(action: String, frame: String) {
        val intent = Intent(action)
        intent.putExtra(EXTRA_FRAME, frame)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
package com.example.smarthelmet

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

@SuppressLint("MissingPermission")
object BluetoothManager {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null

    // This function can be called from ANYWHERE in your app safely
    suspend fun sendPayload(payload: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 1. If not connected, try to connect to the helmet
                if (socket == null || socket?.isConnected == false) {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    val device = adapter?.bondedDevices?.find { it.name == "SmartHelmet" }

                    if (device != null) {
                        socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                        socket?.connect()
                    }
                }

                // 2. If connection is active, send the payload
                if (socket?.isConnected == true) {
                    val message = "$payload\n"
                    socket?.outputStream?.write(message.toByteArray())
                    socket?.outputStream?.flush()
                    true // Success
                } else {
                    false // Failed to connect
                }
            } catch (e: Exception) {
                // If the helmet goes out of range, close the broken socket
                closeConnection()
                false
            }
        }
    }

    fun closeConnection() {
        try { socket?.close() } catch (e: Exception) {}
        socket = null
    }
}
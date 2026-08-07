package com.example.smarthelmet

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.smarthelmet.models.BottomNavItem
import com.example.smarthelmet.models.Contact
import com.example.smarthelmet.ui.theme.SmartHelmetTheme
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var latitude by mutableStateOf("--")
    private var longitude by mutableStateOf("--")
    private var speed by mutableStateOf("--")
    private var accuracy by mutableStateOf("--")

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            accuracy = String.format("%.1f", location.accuracy)
            latitude = String.format("%.6f", location.latitude)
            longitude = String.format("%.6f", location.longitude)
            val speedKmh = location.speed * 3.6
            speed = if (speedKmh < 1.0) "0.0" else String.format("%.1f", speedKmh)
        }
    }



    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun sendContactsToBluetooth(contacts: List<Contact>) {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            android.widget.Toast.makeText(this, "Bluetooth is disabled on your phone!", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101)
                android.widget.Toast.makeText(this, "Permission requested. Tap 'Save & Sync' again after allowing.", android.widget.Toast.LENGTH_LONG).show()
                return
            }
        }

        val device = bluetoothAdapter.bondedDevices.find { it.name == "SmartHelmet" }
        if (device == null) {
            android.widget.Toast.makeText(this, "SmartHelmet not found! Please pair it in Android settings first.", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        android.widget.Toast.makeText(this, "Connecting to Helmet...", android.widget.Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()

                val contactString = contacts.joinToString(",") { "${it.name}|${it.number}" }
                val message = "CONTACTS:$contactString\n"

                socket.outputStream.write(message.toByteArray())
                socket.outputStream.flush()
                delay(1000)

                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@MainActivity, "✅ Contacts Synced to Helmet!", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@MainActivity, "❌ Connection failed! Is the helmet turned on?", android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                socket?.close()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                        android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
        }

        startLocationUpdates()

        setContent {
            SmartHelmetTheme {
                val navController = rememberNavController()

                Box(modifier = Modifier.fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = BottomNavItem.Home.route,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable(BottomNavItem.Home.route) { HomeScreen(navController) }
                        composable(BottomNavItem.Contacts.route) { ManageContactsScreen(navController) }
                        composable(BottomNavItem.Telemetry.route) { TelemetryScreen(navController, latitude, longitude, speed, accuracy) }
                        composable(BottomNavItem.Helplines.route) { HelplinesScreen(navController) }
                    }

                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        BottomNavigationBar(navController = navController)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}
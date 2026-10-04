package com.example.smarthelmet

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val sppUuid: UUID =
        UUID.fromString(
            "00001101-0000-1000-8000-00805F9B34FB"
        )

    private var latitude by mutableStateOf("--")
    private var longitude by mutableStateOf("--")
    private var speed by mutableStateOf("--")
    private var accuracy by mutableStateOf("--")

    private val locationCallback =
        object : LocationCallback() {

            override fun onLocationResult(
                locationResult: LocationResult
            ) {
                val location =
                    locationResult.lastLocation
                        ?: return

                accuracy =
                    String.format(
                        "%.1f",
                        location.accuracy
                    )

                latitude =
                    String.format(
                        "%.6f",
                        location.latitude
                    )

                longitude =
                    String.format(
                        "%.6f",
                        location.longitude
                    )

                val speedKmh =
                    location.speed * 3.6

                speed =
                    if (speedKmh < 1.0) {
                        "0.0"
                    } else {
                        String.format(
                            "%.1f",
                            speedKmh
                        )
                    }
            }
        }

    private fun hasLocationPermission(): Boolean {
        val fineGranted =
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    private fun startLocationUpdates() {
        /*
         * Defensive permission check.
         *
         * Location updates can only start once the runtime permission
         * has actually been granted.
         */
        if (!hasLocationPermission()) {
            return
        }

        val locationRequest =
            LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                1000L
            )
                .setMinUpdateIntervalMillis(500L)
                .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            /*
             * Keep this visible in Logcat instead of allowing an unexpected
             * permission-state mismatch to crash the activity.
             */
            e.printStackTrace()
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun sendContactsToBluetooth(
        contacts: List<Contact>,
        riderName: String
    ) {
        val bluetoothAdapter =
            BluetoothAdapter.getDefaultAdapter()

        if (
            bluetoothAdapter == null ||
            !bluetoothAdapter.isEnabled
        ) {
            Toast.makeText(
                this,
                "Bluetooth is disabled on your phone!",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        /*
         * Android 12+ requires BLUETOOTH_CONNECT for accessing
         * bonded devices and connecting to them.
         */
        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.S
        ) {
            if (
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT
                    ),
                    BLUETOOTH_PERMISSION_REQUEST_CODE
                )

                Toast.makeText(
                    this,
                    "Permission requested. Tap 'Save & Sync' again after allowing.",
                    Toast.LENGTH_LONG
                ).show()

                return
            }
        }

        val device =
            bluetoothAdapter.bondedDevices
                .find {
                    it.name == "SmartHelmet"
                }

        if (device == null) {
            Toast.makeText(
                this,
                "SmartHelmet not found! Please pair it in Android settings first.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        Toast.makeText(
            this,
            "Connecting to Helmet...",
            Toast.LENGTH_SHORT
        ).show()

        lifecycleScope.launch(Dispatchers.IO) {

            var socket: BluetoothSocket? = null

            try {
                socket =
                    device.createRfcommSocketToServiceRecord(
                        sppUuid
                    )

                socket.connect()

                /*
                 * 1. Send contacts.
                 */
                val contactString =
                    contacts.joinToString(",") {
                        "${it.name}|${it.number}"
                    }

                val contactsMessage =
                    "CONTACTS:$contactString\n"

                socket.outputStream.write(
                    contactsMessage.toByteArray()
                )

                socket.outputStream.flush()

                /*
                 * Give the ESP32 a small amount of time to process
                 * the first payload.
                 */
                delay(500L)

                /*
                 * 2. Send rider name.
                 */
                val ownerMessage =
                    "OWNER:$riderName\n"

                socket.outputStream.write(
                    ownerMessage.toByteArray()
                )

                socket.outputStream.flush()

                /*
                 * Small delay before closing the socket.
                 */
                delay(500L)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Contacts & Profile Synced to Helmet!",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {

                e.printStackTrace()

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Connection failed! Is the helmet turned on?",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        /*
         * Kept unchanged for now.
         *
         * Edge-to-edge will be handled in the dedicated UI/device
         * compatibility phase.
         */
        enableEdgeToEdge()

        window.decorView.systemUiVisibility =
            (
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )

        fusedLocationClient =
            LocationServices
                .getFusedLocationProviderClient(this)

        /*
         * Correct permission flow:
         *
         * If permission already exists, start immediately.
         * Otherwise request it and wait for onRequestPermissionsResult().
         */
        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            requestLocationPermission()
        }

        setContent {

            SmartHelmetTheme {

                val navController =
                    rememberNavController()

                Box(
                    modifier =
                        Modifier.fillMaxSize()
                ) {

                    NavHost(
                        navController =
                            navController,
                        startDestination =
                            BottomNavItem.Home.route,
                        modifier =
                            Modifier.fillMaxSize(),
                        enterTransition = {
                            EnterTransition.None
                        },
                        exitTransition = {
                            ExitTransition.None
                        }
                    ) {

                        composable(
                            BottomNavItem.Home.route
                        ) {
                            HomeScreen(
                                navController
                            )
                        }

                        composable(
                            BottomNavItem.Contacts.route
                        ) {
                            ManageContactsScreen(
                                navController
                            )
                        }

                        composable(
                            BottomNavItem.Telemetry.route
                        ) {
                            TelemetryScreen(
                                navController,
                                latitude,
                                longitude,
                                speed,
                                accuracy
                            )
                        }

                        composable(
                            BottomNavItem.Helplines.route
                        ) {
                            HelplinesScreen(
                                navController
                            )
                        }
                    }

                    Box(
                        modifier =
                            Modifier.align(
                                Alignment.BottomCenter
                            )
                    ) {
                        BottomNavigationBar(
                            navController =
                                navController
                        )
                    }
                }
            }
        }
    }

    /*
     * Called after the runtime permission dialog is answered.
     *
     * This is the missing piece in the old implementation.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        when (requestCode) {

            LOCATION_PERMISSION_REQUEST_CODE -> {

                if (hasLocationPermission()) {
                    /*
                     * Permission has actually been granted,
                     * so now it is safe to start receiving fixes.
                     */
                    startLocationUpdates()
                } else {
                    Toast.makeText(
                        this,
                        "Location permission is required for live ride data.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            BLUETOOTH_PERMISSION_REQUEST_CODE -> {

                /*
                 * We intentionally do not automatically retry the
                 * Bluetooth sync here.
                 *
                 * Existing behavior is preserved:
                 * user can tap Save & Sync again after granting it.
                 */
                if (
                    android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.S
                ) {
                    val granted =
                        ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED

                    if (granted) {
                        Toast.makeText(
                            this,
                            "Bluetooth permission granted. Tap 'Save & Sync' again.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            fusedLocationClient
                .removeLocationUpdates(
                    locationCallback
                )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        super.onDestroy()
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 100
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 101
    }
}
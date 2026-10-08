package com.example.smarthelmet

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.smarthelmet.models.BottomNavItem
import com.example.smarthelmet.models.Contact
import com.example.smarthelmet.ui.theme.SmartHelmetTheme
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
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

    // ============================================================
    // USB SERIAL - YOLO LAPTOP CONNECTION
    // ============================================================

    private lateinit var usbManager: UsbManager

    private var usbSerialPort: UsbSerialPort? = null
    private var usbReadThread: Thread? = null

    private val usbPermissionAction =
        "com.example.smarthelmet.USB_PERMISSION"

    // ============================================================
    // LILYGO BLUETOOTH SPP CONNECTION
    // ============================================================

    /*
     * Unlike the old implementation, the SPP connection is kept open.
     *
     * This allows:
     *
     *     USB -> Android -> Bluetooth -> LilyGO
     *
     * to happen continuously during a ride.
     */
    private var helmetSocket: BluetoothSocket? = null

    private var helmetOutputStream:
            java.io.OutputStream? = null

    private val helmetLock = Any()

    // ============================================================
    // USB BROADCAST RECEIVER
    // ============================================================

    private val usbPermissionReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                when (intent?.action) {

                    usbPermissionAction -> {

                        val device =
                            intent.getParcelableExtra<UsbDevice>(
                                UsbManager.EXTRA_DEVICE
                            )

                        if (
                            device != null &&
                            usbManager.hasPermission(device)
                        ) {
                            connectToUsbSerial(device)
                        }
                    }

                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {

                        /*
                         * A USB serial device has just been connected.
                         */
                        initializeUsbSerial()
                    }
                }
            }
        }

    // ============================================================
    // LOCATION
    // ============================================================

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

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {

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

            e.printStackTrace()
        }
    }

    // ============================================================
    // FULLSCREEN
    // ============================================================

    private fun hideSystemBars() {

        val controller =
            WindowCompat.getInsetsController(
                window,
                window.decorView
            )

        controller.systemBarsBehavior =
            WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        controller.hide(
            WindowInsetsCompat.Type.systemBars()
        )
    }

    // ============================================================
    // CONTACT SYNC
    // ============================================================

    @SuppressLint("MissingPermission")
    fun sendContactsToBluetooth(
        contacts: List<Contact>,
        riderName: String
    ) {

        if (!hasBluetoothPermission()) {
            requestBluetoothPermission()
            return
        }

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            try {

                /*
                 * Reuse the same SPP connection used for
                 * YOLO speed-limit messages.
                 */
                if (
                    !ensureHelmetBluetoothConnection()
                ) {

                    withContext(
                        Dispatchers.Main
                    ) {

                        Toast.makeText(
                            this@MainActivity,
                            "SmartHelmet not found! Please pair it and make sure the helmet is on.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    return@launch
                }

                /*
                 * Send contacts.
                 */
                val contactString =
                    contacts.joinToString(",") {
                        "${it.name}|${it.number}"
                    }

                val contactsMessage =
                    "CONTACTS:$contactString\n"

                if (
                    !writeToHelmet(
                        contactsMessage
                    )
                ) {
                    throw Exception(
                        "Failed to send contacts"
                    )
                }

                /*
                 * Give LilyGO a small amount of time
                 * to process the contacts.
                 */
                delay(500L)

                /*
                 * Send rider name.
                 */
                val ownerMessage =
                    "OWNER:$riderName\n"

                if (
                    !writeToHelmet(
                        ownerMessage
                    )
                ) {
                    throw Exception(
                        "Failed to send rider name"
                    )
                }

                withContext(
                    Dispatchers.Main
                ) {

                    Toast.makeText(
                        this@MainActivity,
                        "Contacts & Profile Synced to Helmet!",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {

                e.printStackTrace()

                closeHelmetBluetooth()

                withContext(
                    Dispatchers.Main
                ) {

                    Toast.makeText(
                        this@MainActivity,
                        "Connection failed! Is the helmet turned on?",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ============================================================
    // BLUETOOTH PERMISSIONS
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun hasBluetoothPermission(): Boolean {

        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.S
        ) {

            return ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        }

        return true
    }

    private fun requestBluetoothPermission() {

        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.S
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
                "Allow Bluetooth permission, then tap 'Save & Sync' again.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ============================================================
    // LILYGO BLUETOOTH CONNECTION
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun ensureHelmetBluetoothConnection(): Boolean {

        synchronized(helmetLock) {

            try {

                /*
                 * Already connected.
                 */
                if (
                    helmetSocket?.isConnected == true &&
                    helmetOutputStream != null
                ) {

                    return true
                }

                /*
                 * Clean up any dead connection.
                 */
                closeHelmetBluetooth()

                val bluetoothAdapter =
                    BluetoothAdapter.getDefaultAdapter()

                if (
                    bluetoothAdapter == null ||
                    !bluetoothAdapter.isEnabled
                ) {

                    return false
                }

                if (!hasBluetoothPermission()) {
                    return false
                }

                /*
                 * Find the already-paired LilyGO.
                 */
                val device =
                    bluetoothAdapter.bondedDevices
                        .find {
                            it.name == "SmartHelmet"
                        }
                        ?: return false

                /*
                 * Create Bluetooth Classic SPP connection.
                 */
                val socket =
                    device.createRfcommSocketToServiceRecord(
                        sppUuid
                    )

                socket.connect()

                helmetSocket = socket

                helmetOutputStream =
                    socket.outputStream

                println(
                    "[BT] Connected to SmartHelmet"
                )

                return true

            } catch (e: Exception) {

                e.printStackTrace()

                closeHelmetBluetooth()

                return false
            }
        }
    }

    // ============================================================
    // SEND DATA TO LILYGO
    // ============================================================

    private fun writeToHelmet(
        message: String
    ): Boolean {

        synchronized(helmetLock) {

            return try {

                val output =
                    helmetOutputStream
                        ?: return false

                output.write(
                    message.toByteArray(
                        Charsets.UTF_8
                    )
                )

                output.flush()

                println(
                    "[BT] Sent: ${message.trim()}"
                )

                true

            } catch (e: Exception) {

                e.printStackTrace()

                closeHelmetBluetooth()

                false
            }
        }
    }

    private fun closeHelmetBluetooth() {

        synchronized(helmetLock) {

            try {
                helmetOutputStream?.close()
            } catch (_: Exception) {
            }

            try {
                helmetSocket?.close()
            } catch (_: Exception) {
            }

            helmetOutputStream = null
            helmetSocket = null
        }
    }

    // ============================================================
    // YOLO -> LILYGO
    // ============================================================

    private fun sendSpeedLimitToHelmet(
        message: String
    ) {

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            try {

                /*
                 * Make sure the LilyGO SPP connection exists.
                 */
                if (
                    !ensureHelmetBluetoothConnection()
                ) {

                    println(
                        "[BT] Could not connect to SmartHelmet"
                    )

                    return@launch
                }

                /*
                 * The USB reader gives us:
                 *
                 *     L:50
                 *
                 * LilyGO expects:
                 *
                 *     L:50\n
                 */
                val formattedMessage =
                    message.trim() + "\n"

                if (
                    writeToHelmet(
                        formattedMessage
                    )
                ) {

                    println(
                        "[YOLO] Forwarded to LilyGO: ${message.trim()}"
                    )
                }

            } catch (e: Exception) {

                e.printStackTrace()
            }
        }
    }

    // ============================================================
    // USB SERIAL INITIALIZATION
    // ============================================================

    private fun initializeUsbSerial() {

        try {

            val drivers =
                UsbSerialProber
                    .getDefaultProber()
                    .findAllDrivers(
                        usbManager
                    )

            if (drivers.isEmpty()) {

                println(
                    "[USB] No USB serial device found"
                )

                return
            }

            /*
             * For now we use the first USB serial device.
             */
            val driver =
                drivers[0]

            val device =
                driver.device

            println(
                "[USB] Found device: ${device.deviceName}"
            )

            if (
                !usbManager.hasPermission(
                    device
                )
            ) {

                println(
                    "[USB] Requesting USB permission"
                )

                val permissionIntent =
                    PendingIntent.getBroadcast(
                        this,
                        0,
                        Intent(
                            usbPermissionAction
                        ),
                        PendingIntent.FLAG_IMMUTABLE
                    )

                usbManager.requestPermission(
                    device,
                    permissionIntent
                )

            } else {

                connectToUsbSerial(
                    device
                )
            }

        } catch (e: Exception) {

            e.printStackTrace()

            println(
                "[USB] Initialization failed: ${e.message}"
            )
        }
    }

    // ============================================================
    // USB SERIAL CONNECTION
    // ============================================================

    private fun connectToUsbSerial(
        device: UsbDevice
    ) {

        try {

            val driver =
                UsbSerialProber
                    .getDefaultProber()
                    .probeDevice(
                        device
                    )

            if (driver == null) {

                println(
                    "[USB] No compatible serial driver found"
                )

                return
            }

            val connection =
                usbManager.openDevice(
                    device
                )

            if (connection == null) {

                println(
                    "[USB] Could not open USB device"
                )

                return
            }

            /*
             * Use the first serial port.
             */
            val port =
                driver.ports[0]

            /*
             * Open serial connection.
             */
            port.open(
                connection
            )

            /*
             * IMPORTANT:
             *
             * This must match the baud rate used by
             * the laptop YOLO Python program.
             */
            port.setParameters(
                115200,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            usbSerialPort =
                port

            println(
                "[USB] Serial connected at 115200 baud"
            )

            startUsbReadThread()

        } catch (e: Exception) {

            e.printStackTrace()

            println(
                "[USB] Connection failed: ${e.message}"
            )
        }
    }

    // ============================================================
    // USB SERIAL READER
    // ============================================================

    private fun startUsbReadThread() {

        /*
         * Stop an old reader if one exists.
         */
        usbReadThread?.interrupt()

        usbReadThread =
            Thread {

                val buffer =
                    ByteArray(
                        4096
                    )

                val messageBuffer =
                    StringBuilder()

                println(
                    "[USB] Reader started"
                )

                while (
                    !Thread.currentThread()
                        .isInterrupted
                ) {

                    try {

                        val port =
                            usbSerialPort
                                ?: break

                        /*
                         * Wait up to 1 second for serial data.
                         */
                        val length =
                            port.read(
                                buffer,
                                1000
                            )

                        if (length <= 0) {
                            continue
                        }

                        val incoming =
                            String(
                                buffer,
                                0,
                                length,
                                Charsets.UTF_8
                            )

                        /*
                         * Append the incoming bytes.
                         *
                         * Serial data is not guaranteed to arrive
                         * in exactly the same chunks that Python sent.
                         */
                        messageBuffer.append(
                            incoming
                        )

                        /*
                         * Process complete newline-terminated messages.
                         */
                        while (
                            messageBuffer.contains(
                                "\n"
                            )
                        ) {

                            val newlineIndex =
                                messageBuffer.indexOf(
                                    "\n"
                                )

                            val message =
                                messageBuffer
                                    .substring(
                                        0,
                                        newlineIndex
                                    )
                                    .trim()

                            /*
                             * Remove the processed message
                             * including the newline.
                             */
                            messageBuffer.delete(
                                0,
                                newlineIndex + 1
                            )

                            if (
                                message.isNotEmpty()
                            ) {

                                println(
                                    "[USB] Received: $message"
                                )

                                handleUsbMessage(
                                    message
                                )
                            }
                        }

                    } catch (e: Exception) {

                        if (
                            !Thread.currentThread()
                                .isInterrupted
                        ) {

                            e.printStackTrace()
                        }

                        break
                    }
                }

                println(
                    "[USB] Reader stopped"
                )
            }

        usbReadThread?.start()
    }

    // ============================================================
    // USB MESSAGE HANDLER
    // ============================================================

    private fun handleUsbMessage(
        message: String
    ) {

        /*
         * YOLO speed-limit messages:
         *
         * L:30
         * L:40
         * L:50
         * L:60
         * L:70
         * L:80
         *
         * L:0 means the speed limit was cleared.
         */
        if (
            message.startsWith("L:")
        ) {

            sendSpeedLimitToHelmet(
                message
            )
        }
    }

    // ============================================================
    // CLOSE USB
    // ============================================================

    private fun closeUsbSerial() {

        usbReadThread?.interrupt()
        usbReadThread = null

        try {
            usbSerialPort?.close()
        } catch (_: Exception) {
        }

        usbSerialPort = null
    }

    // ============================================================
    // ACTIVITY
    // ============================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        /*
         * Keep modern edge-to-edge support.
         */
        enableEdgeToEdge()

        /*
         * Enter immersive mode.
         */
        hideSystemBars()

        // ========================================================
        // LOCATION
        // ========================================================

        fusedLocationClient =
            LocationServices
                .getFusedLocationProviderClient(
                    this
                )

        if (
            hasLocationPermission()
        ) {

            startLocationUpdates()

        } else {

            requestLocationPermission()
        }

        // ========================================================
        // USB SERIAL
        // ========================================================

        usbManager =
            getSystemService(
                Context.USB_SERVICE
            ) as UsbManager

        val usbIntentFilter =
            IntentFilter().apply {

                addAction(
                    usbPermissionAction
                )

                addAction(
                    UsbManager.ACTION_USB_DEVICE_ATTACHED
                )
            }

        /*
         * Android 13+ requires a receiver export flag
         * when registering a dynamic receiver.
         */
        ContextCompat.registerReceiver(
            this,
            usbPermissionReceiver,
            usbIntentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        /*
         * Check whether the USB serial device is already
         * connected when the app starts.
         */
        initializeUsbSerial()

        // ========================================================
        // UI
        // ========================================================

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

    // ============================================================
    // RESUME
    // ============================================================

    override fun onResume() {

        super.onResume()

        /*
         * Re-hide system bars.
         */
        hideSystemBars()

        if (
            hasLocationPermission()
        ) {

            startLocationUpdates()
        }

        /*
         * Check for the USB device again when
         * returning to the app.
         */
        if (
            ::usbManager.isInitialized
        ) {

            initializeUsbSerial()
        }
    }

    // ============================================================
    // WINDOW FOCUS
    // ============================================================

    override fun onWindowFocusChanged(
        hasFocus: Boolean
    ) {

        super.onWindowFocusChanged(
            hasFocus
        )

        if (hasFocus) {

            hideSystemBars()
        }
    }

    // ============================================================
    // PERMISSIONS
    // ============================================================

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

                if (
                    hasLocationPermission()
                ) {

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

                if (
                    android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.S
                ) {

                    val granted =
                        ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) ==
                                PackageManager.PERMISSION_GRANTED

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

    // ============================================================
    // DESTROY
    // ============================================================

    override fun onDestroy() {

        try {

            fusedLocationClient
                .removeLocationUpdates(
                    locationCallback
                )

        } catch (e: Exception) {

            e.printStackTrace()
        }

        /*
         * Stop USB reader and close USB serial.
         */
        closeUsbSerial()

        /*
         * Close persistent LilyGO SPP connection.
         */
        closeHelmetBluetooth()

        /*
         * Remove USB broadcast receiver.
         */
        try {

            unregisterReceiver(
                usbPermissionReceiver
            )

        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    // ============================================================
    // CONSTANTS
    // ============================================================

    companion object {

        private const val LOCATION_PERMISSION_REQUEST_CODE =
            100

        private const val BLUETOOTH_PERMISSION_REQUEST_CODE =
            101
    }
}
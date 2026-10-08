package com.example.smarthelmet

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.smarthelmet.database.RideDatabase
import com.example.smarthelmet.database.RideEntity
import com.example.smarthelmet.database.TelemetryPoint
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.maplibre.geojson.Point
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.abs

class LocationService : Service() {

    private val binder = LocalBinder()

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private val serviceScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    /*
     * Prevent overlapping Bluetooth operations.
     *
     * Speed and location messages both use the same SPP socket,
     * so they must never try to connect/write simultaneously.
     */
    private val bluetoothMutex = Mutex()

    private var snapJob: Job? = null

    /*
     * Persistent Bluetooth SPP connection to LilyGO.
     */
    private var telemetrySocket: BluetoothSocket? = null

    private val sppUuid: UUID =
        UUID.fromString(
            "00001101-0000-1000-8000-00805F9B34FB"
        )

    /*
     * Route data.
     */
    private val _routePoints =
        MutableStateFlow<List<Point>>(emptyList())

    val routePoints: StateFlow<List<Point>> =
        _routePoints

    private val _matchedRoutePoints =
        MutableStateFlow<List<Point>>(emptyList())

    val matchedRoutePoints: StateFlow<List<Point>> =
        _matchedRoutePoints

    /*
     * Ride state.
     */
    private val _isTracking =
        MutableStateFlow(false)

    val isTracking: StateFlow<Boolean> =
        _isTracking

    private val _rideDistance =
        MutableStateFlow(0f)

    val rideDistance: StateFlow<Float> =
        _rideDistance

    private val _maxSpeed =
        MutableStateFlow(0f)

    val maxSpeed: StateFlow<Float> =
        _maxSpeed

    private val _rideStartTime =
        MutableStateFlow(0L)

    val rideStartTime: StateFlow<Long> =
        _rideStartTime

    private val _speedHistory =
        MutableStateFlow<List<TelemetryPoint>>(emptyList())

    val speedHistory: StateFlow<List<TelemetryPoint>> =
        _speedHistory

    /*
     * Bluetooth timing.
     */
    private var lastSpeedSampleTime = 0L

    private var lastSampledSpeedKmh = -1f

    private var lastBluetoothSendTime = 0L

    /*
     * Phone location is sent approximately once per second.
     */
    private var lastLocationSendTime = 0L

    inner class LocalBinder : Binder() {

        fun getService(): LocationService =
            this@LocationService
    }

    override fun onBind(intent: Intent?): IBinder =
        binder

    override fun onCreate() {

        super.onCreate()

        fusedLocationClient =
            LocationServices.getFusedLocationProviderClient(
                this
            )

        locationCallback =
            object : LocationCallback() {

                override fun onLocationResult(
                    result: LocationResult
                ) {

                    if (!_isTracking.value) {
                        return
                    }

                    val location =
                        result.lastLocation
                            ?: return

                    /*
                     * Ignore poor-quality GPS fixes.
                     */
                    if (
                        location.hasAccuracy() &&
                        location.accuracy > 15f
                    ) {
                        return
                    }

                    /*
                     * GPS speed is m/s.
                     * Convert to km/h.
                     */
                    var currentSpeedKmh =
                        location.speed * 3.6f

                    /*
                     * Ignore very small GPS drift.
                     */
                    if (currentSpeedKmh < 4.0f) {
                        currentSpeedKmh = 0f
                    }

                    /*
                     * Update maximum speed.
                     */
                    if (
                        currentSpeedKmh >
                        _maxSpeed.value
                    ) {

                        _maxSpeed.value =
                            currentSpeedKmh
                    }

                    val currentTime =
                        System.currentTimeMillis()

                    /*
                     * ------------------------------------------
                     * SPEED HISTORY
                     * ------------------------------------------
                     */

                    val timeSinceLastSample =
                        currentTime -
                                lastSpeedSampleTime

                    val speedDelta =
                        abs(
                            currentSpeedKmh -
                                    lastSampledSpeedKmh
                        )

                    if (
                        lastSampledSpeedKmh < 0f ||
                        timeSinceLastSample >= 5000L ||
                        speedDelta >= 5f
                    ) {

                        val elapsedSec =
                            (
                                    (
                                            currentTime -
                                                    _rideStartTime.value
                                            ) / 1000L
                                    ).toInt()

                        _speedHistory.value =
                            _speedHistory.value +
                                    TelemetryPoint(
                                        timeSec = elapsedSec,
                                        speedKmh = currentSpeedKmh,
                                        lat = location.latitude,
                                        lng = location.longitude
                                    )

                        lastSpeedSampleTime =
                            currentTime

                        lastSampledSpeedKmh =
                            currentSpeedKmh
                    }

                    /*
                     * ------------------------------------------
                     * BLUETOOTH SPEED
                     * ------------------------------------------
                     *
                     * Existing protocol:
                     *
                     * S:67
                     */

                    val timeSinceLastSpeedSend =
                        currentTime -
                                lastBluetoothSendTime

                    val requiredSpeedInterval =
                        if (currentSpeedKmh >= 10f) {
                            500L
                        } else {
                            2000L
                        }

                    if (
                        timeSinceLastSpeedSend >=
                        requiredSpeedInterval
                    ) {

                        sendTelemetryToHelmet(
                            "S:${currentSpeedKmh.toInt()}\n",
                            "speed ${currentSpeedKmh.toInt()} km/h"
                        )

                        lastBluetoothSendTime =
                            currentTime
                    }

                    /*
                     * ------------------------------------------
                     * BLUETOOTH PHONE LOCATION
                     * ------------------------------------------
                     *
                     * Send:
                     *
                     * LOC:12.971600,77.594600
                     *
                     * The LilyGO stores the latest value.
                     * This means the SOS button can immediately
                     * use the most recent phone location.
                     */

                    val timeSinceLastLocationSend =
                        currentTime -
                                lastLocationSendTime

                    if (
                        timeSinceLastLocationSend >=
                        1000L
                    ) {

                        val locationMessage =
                            "LOC:" +
                                    location.latitude +
                                    "," +
                                    location.longitude +
                                    "\n"

                        sendTelemetryToHelmet(
                            locationMessage,
                            "location ${location.latitude},${location.longitude}"
                        )

                        lastLocationSendTime =
                            currentTime
                    }

                    /*
                     * ------------------------------------------
                     * ROUTE RECORDING
                     * ------------------------------------------
                     */

                    /*
                     * Do not record route points while stopped.
                     */
                    if (currentSpeedKmh == 0f) {
                        return
                    }

                    val newPoint =
                        Point.fromLngLat(
                            location.longitude,
                            location.latitude
                        )

                    val currentList =
                        _routePoints.value

                    if (currentList.isNotEmpty()) {

                        val lastPoint =
                            currentList.last()

                        val results =
                            FloatArray(1)

                        android.location.Location.distanceBetween(
                            lastPoint.latitude(),
                            lastPoint.longitude(),
                            newPoint.latitude(),
                            newPoint.longitude(),
                            results
                        )

                        /*
                         * Ignore movement smaller than 5m.
                         */
                        if (results[0] < 5.0f) {
                            return
                        }

                        _rideDistance.value +=
                            results[0] / 1000f
                    }

                    _routePoints.value =
                        currentList + newPoint
                }
            }
    }

    /*
     * ------------------------------------------
     * LOCATION PERMISSION
     * ------------------------------------------
     */

    private fun hasLocationPermission(): Boolean {

        val fineGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    /*
     * ------------------------------------------
     * BLUETOOTH PERMISSION
     * ------------------------------------------
     */

    private fun hasBluetoothConnectPermission(): Boolean {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.S
        ) {
            return true
        }

        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    /*
     * ------------------------------------------
     * GENERIC BLUETOOTH TELEMETRY
     * ------------------------------------------
     *
     * Both:
     *
     * S:67
     *
     * and:
     *
     * LOC:12.9716,77.5946
     *
     * go through this function.
     *
     * This guarantees that only ONE SPP connection
     * is used.
     */

    @SuppressLint("MissingPermission")
    private fun sendTelemetryToHelmet(
        message: String,
        description: String
    ) {

        serviceScope.launch(Dispatchers.IO) {

            bluetoothMutex.withLock {

                if (!hasBluetoothConnectPermission()) {

                    Log.w(
                        TAG,
                        "Bluetooth telemetry skipped: BLUETOOTH_CONNECT not granted"
                    )

                    return@withLock
                }

                try {

                    val adapter =
                        BluetoothAdapter.getDefaultAdapter()

                    if (adapter == null) {

                        Log.w(
                            TAG,
                            "Bluetooth adapter is unavailable"
                        )

                        return@withLock
                    }

                    if (!adapter.isEnabled) {

                        Log.w(
                            TAG,
                            "Bluetooth is disabled"
                        )

                        return@withLock
                    }

                    /*
                     * Reuse existing connection.
                     */
                    if (
                        telemetrySocket == null ||
                        !telemetrySocket!!.isConnected
                    ) {

                        val device =
                            adapter.bondedDevices
                                .find {
                                    it.name ==
                                            "SmartHelmet"
                                }

                        if (device == null) {

                            Log.w(
                                TAG,
                                "Paired SmartHelmet device not found"
                            )

                            return@withLock
                        }

                        Log.d(
                            TAG,
                            "Connecting to SmartHelmet for telemetry"
                        )

                        telemetrySocket =
                            device.createRfcommSocketToServiceRecord(
                                sppUuid
                            )

                        telemetrySocket?.connect()
                    }

                    if (
                        telemetrySocket?.isConnected ==
                        true
                    ) {

                        telemetrySocket
                            ?.outputStream
                            ?.write(
                                message.toByteArray(
                                    Charsets.UTF_8
                                )
                            )

                        telemetrySocket
                            ?.outputStream
                            ?.flush()

                        Log.d(
                            TAG,
                            "Sent telemetry: $description"
                        )

                    } else {

                        Log.w(
                            TAG,
                            "Bluetooth socket is not connected"
                        )
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Failed to send telemetry: $description",
                        e
                    )

                    try {

                        telemetrySocket?.close()

                    } catch (closeException: Exception) {

                        Log.w(
                            TAG,
                            "Failed to close Bluetooth socket",
                            closeException
                        )
                    }

                    telemetrySocket = null
                }
            }
        }
    }

    /*
     * ------------------------------------------
     * START RIDE
     * ------------------------------------------
     */

    fun startTracking(): Boolean {

        if (_isTracking.value) {
            return true
        }

        if (!hasLocationPermission()) {

            Log.e(
                TAG,
                "Cannot start tracking: location permission is not granted"
            )

            return false
        }

        /*
         * Cancel old road-snapping loop.
         */
        snapJob?.cancel()
        snapJob = null

        /*
         * Reset ride state.
         */
        _routePoints.value =
            emptyList()

        _matchedRoutePoints.value =
            emptyList()

        _rideDistance.value =
            0f

        _maxSpeed.value =
            0f

        _rideStartTime.value =
            System.currentTimeMillis()

        _speedHistory.value =
            emptyList()

        lastSpeedSampleTime =
            0L

        lastSampledSpeedKmh =
            -1f

        lastBluetoothSendTime =
            0L

        lastLocationSendTime =
            0L

        try {

            /*
             * Enter foreground before requesting location updates.
             */
            startForeground(
                NOTIFICATION_ID,
                createNotification()
            )

            _isTracking.value =
                true

            /*
             * Publish the ride state only after foreground service
             * startup and location tracking have succeeded.
             */
            activeRideState = true

            val request =
                LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    2000L
                )
                    .setMinUpdateIntervalMillis(
                        500L
                    )
                    .build()

            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )

            snapJob =
                serviceScope.launch {

                    while (
                        isActive &&
                        _isTracking.value
                    ) {

                        delay(10_000L)

                        if (_isTracking.value) {
                            snapToRoadNetwork()
                        }
                    }
                }

            Log.d(
                TAG,
                "Ride tracking started"
            )

            return true

        } catch (e: SecurityException) {

            _isTracking.value =
                false

            activeRideState = false

            Log.e(
                TAG,
                "SecurityException while starting location tracking",
                e
            )

            try {

                fusedLocationClient
                    .removeLocationUpdates(
                        locationCallback
                    )

            } catch (cleanupException: Exception) {

                Log.w(
                    TAG,
                    "Failed to clean up location updates",
                    cleanupException
                )
            }

            snapJob?.cancel()
            snapJob = null

            try {

                stopForeground(
                    STOP_FOREGROUND_REMOVE
                )

            } catch (cleanupException: Exception) {

                Log.w(
                    TAG,
                    "Failed to remove foreground state",
                    cleanupException
                )
            }

            return false

        } catch (e: Exception) {

            _isTracking.value =
                false

            activeRideState = false

            Log.e(
                TAG,
                "Unexpected error while starting location tracking",
                e
            )

            try {

                fusedLocationClient
                    .removeLocationUpdates(
                        locationCallback
                    )

            } catch (cleanupException: Exception) {

                Log.w(
                    TAG,
                    "Failed to clean up location updates",
                    cleanupException
                )
            }

            snapJob?.cancel()
            snapJob = null

            try {

                stopForeground(
                    STOP_FOREGROUND_REMOVE
                )

            } catch (cleanupException: Exception) {

                Log.w(
                    TAG,
                    "Failed to remove foreground state",
                    cleanupException
                )
            }

            return false
        }
    }

    /*
     * ------------------------------------------
     * STOP RIDE
     * ------------------------------------------
     */

    fun stopTracking() {

        if (!_isTracking.value) {
            return
        }

        /*
         * Mark the ride inactive immediately so another part of the
         * app can safely begin contact synchronization while cleanup
         * is still finishing.
         */
        _isTracking.value = false
        activeRideState = false

        try {

            fusedLocationClient
                .removeLocationUpdates(
                    locationCallback
                )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Failed to remove location updates",
                e
            )
        }

        snapJob?.cancel()
        snapJob = null

        /*
         * Close Bluetooth connection when ride ends.
         */
        try {

            telemetrySocket?.close()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Failed to close Bluetooth socket",
                e
            )
        }

        telemetrySocket = null

        try {

            stopForeground(
                STOP_FOREGROUND_REMOVE
            )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Failed to stop foreground service",
                e
            )
        }

        Log.d(
            TAG,
            "Ride tracking stopped"
        )
    }

    /*
     * ------------------------------------------
     * ROAD SNAPPING
     * ------------------------------------------
     *
     * Keep your existing implementation here.
     * The code below is the same structure your
     * current LocationService uses.
     */

    private suspend fun snapToRoadNetwork() {

        val points =
            _routePoints.value

        if (points.size < 2) {
            return
        }

        try {

            val coordinates =
                points.joinToString(";") {
                    "${it.longitude()},${it.latitude()}"
                }

            val url =
                URL(
                    "https://router.project-osrm.org/match/v1/driving/$coordinates?overview=full&geometries=geojson"
                )

            val connection =
                withContext(Dispatchers.IO) {
                    url.openConnection()
                            as HttpURLConnection
                }

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                5000

            connection.readTimeout =
                5000

            if (
                connection.responseCode ==
                HttpURLConnection.HTTP_OK
            ) {

                val response =
                    connection.inputStream
                        .bufferedReader()
                        .use {
                            it.readText()
                        }

                val json =
                    JSONObject(response)

                val matchings =
                    json.optJSONArray(
                        "matchings"
                    )

                if (
                    matchings != null &&
                    matchings.length() > 0
                ) {

                    val geometry =
                        matchings
                            .getJSONObject(0)
                            .optJSONObject(
                                "geometry"
                            )

                    val coordinatesArray =
                        geometry
                            ?.optJSONArray(
                                "coordinates"
                            )

                    if (
                        coordinatesArray != null
                    ) {

                        val snappedPoints =
                            mutableListOf<Point>()

                        for (
                        i in 0 until
                                coordinatesArray.length()
                        ) {

                            val coordinate =
                                coordinatesArray
                                    .getJSONArray(i)

                            snappedPoints.add(
                                Point.fromLngLat(
                                    coordinate.getDouble(0),
                                    coordinate.getDouble(1)
                                )
                            )
                        }

                        _matchedRoutePoints.value =
                            snappedPoints
                    }
                }
            }

            connection.disconnect()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Road snapping failed",
                e
            )
        }
    }

    /*
     * ------------------------------------------
     * NOTIFICATION
     * ------------------------------------------
     */

    private fun createNotification(): Notification {

        val channelId =
            "shelmet_location"

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    channelId,
                    "Shelmet Ride Tracking",
                    NotificationManager.IMPORTANCE_LOW
                )

            val manager =
                getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

            manager.createNotificationChannel(
                channel
            )
        }

        return NotificationCompat
            .Builder(
                this,
                channelId
            )
            .setContentTitle(
                "Shelmet Ride Active"
            )
            .setContentText(
                "Tracking your ride location"
            )
            .setSmallIcon(
                android.R.drawable.ic_menu_mylocation
            )
            .setOngoing(true)
            .build()
    }

    /*
     * ------------------------------------------
     * SERVICE DESTROY
     * ------------------------------------------
     */

    override fun onDestroy() {

        try {

            fusedLocationClient
                .removeLocationUpdates(
                    locationCallback
                )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Failed to remove location updates",
                e
            )
        }

        try {

            telemetrySocket?.close()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Failed to close Bluetooth socket",
                e
            )
        }

        telemetrySocket = null

        _isTracking.value =
            false

        activeRideState = false

        serviceScope.cancel()

        super.onDestroy()
    }

    companion object {

        private const val TAG =
            "LocationService"

        private const val NOTIFICATION_ID =
            1001

        /*
         * Process-wide ride state.
         *
         * MainActivity uses this to prevent contact-sync attempts
         * while the Bluetooth SPP connection is being used for
         * live ride telemetry.
         */
        @Volatile
        private var activeRideState = false

        @JvmStatic
        fun isRideActive(): Boolean {
            return activeRideState
        }
    }
}

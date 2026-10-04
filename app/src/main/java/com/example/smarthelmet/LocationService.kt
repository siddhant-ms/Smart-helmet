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
     * Prevent overlapping Bluetooth connection/send operations.
     *
     * Location callbacks can arrive quickly enough that multiple coroutines
     * could otherwise try to create/connect the socket simultaneously.
     */
    private val bluetoothMutex = Mutex()

    private var snapJob: Job? = null

    private var telemetrySocket: BluetoothSocket? = null

    private val sppUuid: UUID =
        UUID.fromString(
            "00001101-0000-1000-8000-00805F9B34FB"
        )

    private val _routePoints =
        MutableStateFlow<List<Point>>(emptyList())

    val routePoints: StateFlow<List<Point>> =
        _routePoints

    private val _matchedRoutePoints =
        MutableStateFlow<List<Point>>(emptyList())

    val matchedRoutePoints: StateFlow<List<Point>> =
        _matchedRoutePoints

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

    private var lastSpeedSampleTime = 0L

    private var lastSampledSpeedKmh = -1f

    private var lastBluetoothSendTime = 0L

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
                     * Keep the existing 15m accuracy requirement.
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
                     * Ignore very low movement/drift.
                     */
                    if (currentSpeedKmh < 4.0f) {
                        currentSpeedKmh = 0f
                    }

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
                     * Speed-history sampling.
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
                     * Bluetooth speed transmission.
                     */
                    val timeSinceLastSend =
                        currentTime -
                                lastBluetoothSendTime

                    val requiredInterval =
                        if (currentSpeedKmh >= 10f) {
                            500L
                        } else {
                            2000L
                        }

                    if (
                        timeSinceLastSend >=
                        requiredInterval
                    ) {
                        sendSpeedToHelmet(
                            currentSpeedKmh.toInt()
                        )

                        lastBluetoothSendTime =
                            currentTime
                    }

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

    private fun hasBluetoothConnectPermission(): Boolean {

        /*
         * BLUETOOTH_CONNECT is only a runtime permission on
         * Android 12 / API 31 and newer.
         *
         * On older Android versions the legacy Bluetooth permission
         * model applies.
         */
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

    @SuppressLint("MissingPermission")
    private fun sendSpeedToHelmet(
        speed: Int
    ) {
        serviceScope.launch(Dispatchers.IO) {

            bluetoothMutex.withLock {

                /*
                 * Never touch paired-device information on Android 12+
                 * without BLUETOOTH_CONNECT.
                 *
                 * Importantly, a missing Bluetooth permission should
                 * NOT kill the ride itself.
                 */
                if (!hasBluetoothConnectPermission()) {
                    Log.w(
                        TAG,
                        "Bluetooth speed update skipped: BLUETOOTH_CONNECT not granted"
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
                     * Reuse an existing connection whenever possible.
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
                            "Connecting to SmartHelmet for speed telemetry"
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

                        val message =
                            "S:$speed\n"

                        telemetrySocket
                            ?.outputStream
                            ?.write(
                                message.toByteArray()
                            )

                        telemetrySocket
                            ?.outputStream
                            ?.flush()

                        Log.d(
                            TAG,
                            "Sent speed to helmet: $speed km/h"
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
                        "Failed to send speed to helmet",
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

    /**
     * Starts a new ride.
     *
     * This method intentionally does NOT run automatically from onCreate().
     * Binding to the service must not create/reset a ride.
     *
     * @return true if tracking started successfully.
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
         * Cancel an old road-snapping loop before starting
         * another ride.
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
                    "Failed to clean up location updates after startup failure",
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
                    "Failed to remove foreground state after startup failure",
                    cleanupException
                )
            }

            return false

        } catch (e: Exception) {

            _isTracking.value =
                false

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
                    "Failed to clean up location updates after startup failure",
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
                    "Failed to remove foreground state after startup failure",
                    cleanupException
                )
            }

            return false
        }
    }

    fun stopTracking(
        rideName: String
    ) {

        _isTracking.value =
            false

        try {
            fusedLocationClient
                .removeLocationUpdates(
                    locationCallback
                )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to remove location updates",
                e
            )
        }

        snapJob?.cancel()
        snapJob = null

        /*
         * Close the current telemetry connection.
         */
        try {
            telemetrySocket?.close()
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed to close telemetry Bluetooth socket",
                e
            )
        }

        telemetrySocket = null

        val finalDistance =
            _rideDistance.value

        val finalMaxSpeed =
            _maxSpeed.value

        val finalStartTime =
            _rideStartTime.value

        val finalDuration =
            if (finalStartTime > 0L) {
                System.currentTimeMillis() -
                        finalStartTime
            } else {
                0L
            }

        val rawPoints =
            _routePoints.value.toList()

        val matchedPoints =
            _matchedRoutePoints.value.toList()

        val finalSpeedHistory =
            _speedHistory.value.toList()

        serviceScope.launch(Dispatchers.IO) {

            if (
                rawPoints.isNotEmpty() &&
                finalDistance >= 0.1f
            ) {

                try {

                    val db =
                        RideDatabase.getDatabase(
                            applicationContext
                        )

                    val newRide =
                        RideEntity(
                            name = rideName,
                            startTime = finalStartTime,
                            durationMs = finalDuration,
                            distanceKm = finalDistance,
                            maxSpeedKmh = finalMaxSpeed,
                            rawRoutePoints = rawPoints,
                            matchedRoutePoints = matchedPoints,
                            speedHistory = finalSpeedHistory
                        )

                    db.rideDao().insertRide(
                        newRide
                    )

                    Log.d(
                        TAG,
                        "Ride saved successfully: $rideName"
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Failed to save ride: $rideName",
                        e
                    )
                }

            } else {

                Log.d(
                    TAG,
                    "Ride not saved: insufficient route data or distance"
                )
            }

            withContext(Dispatchers.Main) {

                try {
                    stopForeground(
                        STOP_FOREGROUND_REMOVE
                    )
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Failed to remove foreground notification",
                        e
                    )
                }

                stopSelf()
            }
        }
    }

    private fun snapToRoadNetwork() {

        val currentRaw =
            _routePoints.value

        if (currentRaw.size < 2) {
            return
        }

        val chunks =
            currentRaw.windowed(
                size = 90,
                step = 89,
                partialWindows = true
            )

        val allSnapped =
            mutableListOf<Point>()

        try {

            for (chunk in chunks) {

                if (chunk.size < 2) {
                    allSnapped.addAll(
                        chunk
                    )
                    continue
                }

                val coordsString =
                    chunk.joinToString(";") {
                        "${it.longitude()},${it.latitude()}"
                    }

                val urlString =
                    "https://router.project-osrm.org/match/v1/driving/" +
                            coordsString +
                            "?overview=full&geometries=geojson&tidy=true"

                val url =
                    URL(urlString)

                val connection =
                    url.openConnection()
                            as HttpURLConnection

                try {

                    connection.requestMethod =
                        "GET"

                    connection.connectTimeout =
                        5000

                    connection.readTimeout =
                        5000

                    if (
                        connection.responseCode ==
                        200
                    ) {

                        val response =
                            connection
                                .inputStream
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
                                    .getJSONObject(
                                        "geometry"
                                    )

                            val coords =
                                geometry
                                    .getJSONArray(
                                        "coordinates"
                                    )

                            for (
                            i in 0 until
                                    coords.length()
                            ) {

                                val pointArray =
                                    coords.getJSONArray(
                                        i
                                    )

                                allSnapped.add(
                                    Point.fromLngLat(
                                        pointArray
                                            .getDouble(0),
                                        pointArray
                                            .getDouble(1)
                                    )
                                )
                            }
                        }

                    } else {

                        Log.w(
                            TAG,
                            "OSRM returned HTTP ${connection.responseCode}"
                        )
                    }

                } finally {
                    connection.disconnect()
                }
            }

            if (allSnapped.isNotEmpty()) {

                _matchedRoutePoints.value =
                    allSnapped
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to snap route to road network",
                e
            )
        }
    }

    private fun createNotification(): Notification {

        val channelId =
            "ride_tracking_channel"

        val notificationManager =
            getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    channelId,
                    "Smart Helmet Ride Tracking",
                    NotificationManager.IMPORTANCE_LOW
                )

            notificationManager.createNotificationChannel(
                channel
            )
        }

        return NotificationCompat.Builder(
            this,
            channelId
        )
            .setContentTitle(
                "Smart Helmet Ride in Progress"
            )
            .setContentText(
                "Recording and mapping your route..."
            )
            .setSmallIcon(
                android.R.drawable.ic_menu_compass
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {

        Log.d(
            TAG,
            "LocationService destroyed"
        )

        try {
            fusedLocationClient
                .removeLocationUpdates(
                    locationCallback
                )
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed to remove location updates during destruction",
                e
            )
        }

        snapJob?.cancel()
        snapJob = null

        try {
            telemetrySocket?.close()
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed to close Bluetooth socket during destruction",
                e
            )
        }

        telemetrySocket = null

        _isTracking.value =
            false

        serviceScope.cancel()

        super.onDestroy()
    }

    companion object {
        private const val TAG =
            "LocationService"

        private const val NOTIFICATION_ID =
            1001
    }
}
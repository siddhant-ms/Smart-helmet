package com.example.smarthelmet

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.maplibre.geojson.Point
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class LocationService : Service() {

    private val binder = LocalBinder()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var telemetrySocket: BluetoothSocket? = null
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val _routePoints = MutableStateFlow<List<Point>>(emptyList())
    val routePoints: StateFlow<List<Point>> = _routePoints

    private val _matchedRoutePoints = MutableStateFlow<List<Point>>(emptyList())
    val matchedRoutePoints: StateFlow<List<Point>> = _matchedRoutePoints

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking

    private val _rideDistance = MutableStateFlow(0f)
    val rideDistance: StateFlow<Float> = _rideDistance

    private val _maxSpeed = MutableStateFlow(0f)
    val maxSpeed: StateFlow<Float> = _maxSpeed

    private val _rideStartTime = MutableStateFlow(0L)
    val rideStartTime: StateFlow<Long> = _rideStartTime

    private var lastBluetoothSendTime = 0L

    inner class LocalBinder : Binder() {
        fun getService(): LocationService = this@LocationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!_isTracking.value) return

                val location = result.lastLocation ?: return

                // 1. STRICT ACCURACY: Reject anything worse than 15 meters.
                // This will instantly kill 99% of indoor desk drift.
                if (location.hasAccuracy() && location.accuracy > 15f) return

                // 2. SPEED CALCULATION
                var currentSpeedKmh = location.speed * 3.6f

                // Clamped to 6 km/h. If it's slower than brisk walking, ignore it.
                if (currentSpeedKmh < 6.0f) {
                    currentSpeedKmh = 0f
                }

                if (currentSpeedKmh > _maxSpeed.value) {
                    _maxSpeed.value = currentSpeedKmh
                }

                // 3. BLUETOOTH & DASHBOARD
                val currentTime = System.currentTimeMillis()
                val timeSinceLastSend = currentTime - lastBluetoothSendTime
                val requiredInterval = if (currentSpeedKmh >= 10f) 500L else 2000L
                if (timeSinceLastSend >= requiredInterval) {
                    sendSpeedToHelmet(currentSpeedKmh.toInt())
                    lastBluetoothSendTime = currentTime
                }

                // 4. THE ANTI-DRIFT GATE
                // If clamped to 0, stop right here.
                if (currentSpeedKmh == 0f) return

                val newPoint = Point.fromLngLat(location.longitude, location.latitude)
                val currentList = _routePoints.value

                if (currentList.isNotEmpty()) {
                    val lastPoint = currentList.last()
                    val results = FloatArray(1)
                    android.location.Location.distanceBetween(
                        lastPoint.latitude(), lastPoint.longitude(),
                        newPoint.latitude(), newPoint.longitude(),
                        results
                    )

                    // 5. DISTANCE FILTER: Must move at least 5 meters
                    if (results[0] < 5.0f) return

                    _rideDistance.value += (results[0] / 1000f)
                }

                _routePoints.value = currentList + newPoint
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendSpeedToHelmet(speed: Int) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (telemetrySocket == null || !telemetrySocket!!.isConnected) {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    val device = adapter?.bondedDevices?.find { it.name == "SmartHelmet" }
                    if (device != null) {
                        telemetrySocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                        telemetrySocket?.connect()
                    }
                }
                if (telemetrySocket?.isConnected == true) {
                    val message = "S:$speed\n"
                    telemetrySocket?.outputStream?.write(message.toByteArray())
                    telemetrySocket?.outputStream?.flush()
                }
            } catch (e: Exception) {
                try { telemetrySocket?.close() } catch (ex: Exception) {}
                telemetrySocket = null
            }
        }
    }

    fun startTracking() {
        if (_isTracking.value) return
        _isTracking.value = true

        _routePoints.value = emptyList()
        _matchedRoutePoints.value = emptyList()
        _rideDistance.value = 0f
        _maxSpeed.value = 0f
        _rideStartTime.value = System.currentTimeMillis()

        startForeground(NOTIFICATION_ID, createNotification())

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(500)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            serviceScope.launch {
                while (_isTracking.value) {
                    delay(10000)
                    snapToRoadNetwork()
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun stopTracking(rideName: String) {
        _isTracking.value = false
        fusedLocationClient.removeLocationUpdates(locationCallback)

        try { telemetrySocket?.close() } catch (e: Exception) {}
        telemetrySocket = null

        val finalDistance = _rideDistance.value
        val finalMaxSpeed = _maxSpeed.value
        val finalStartTime = _rideStartTime.value
        val finalDuration = System.currentTimeMillis() - finalStartTime

        val rawPoints = _routePoints.value.toList()
        val matchedPoints = _matchedRoutePoints.value.toList()

        serviceScope.launch(Dispatchers.IO) {
            if (rawPoints.isNotEmpty() && finalDistance >= 0.1f) {
                try {
                    val db = com.example.smarthelmet.database.RideDatabase.getDatabase(applicationContext)
                    val newRide = com.example.smarthelmet.database.RideEntity(
                        name = rideName,
                        startTime = finalStartTime,
                        durationMs = finalDuration,
                        distanceKm = finalDistance,
                        maxSpeedKmh = finalMaxSpeed,
                        rawRoutePoints = rawPoints,
                        matchedRoutePoints = matchedPoints
                    )
                    db.rideDao().insertRide(newRide)
                } catch (e: Exception) {}
            }
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun snapToRoadNetwork() {
        val currentRaw = _routePoints.value
        if (currentRaw.size < 2) return

        val chunks = currentRaw.windowed(size = 90, step = 89, partialWindows = true)
        val allSnapped = mutableListOf<Point>()

        try {
            for (chunk in chunks) {
                if (chunk.size < 2) {
                    allSnapped.addAll(chunk)
                    continue
                }

                val coordsString = chunk.joinToString(";") { "${it.longitude()},${it.latitude()}" }
                val urlString = "https://router.project-osrm.org/match/v1/driving/$coordsString?overview=full&geometries=geojson&tidy=true"
                val url = URL(urlString)

                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)
                    val matchings = json.optJSONArray("matchings")

                    if (matchings != null && matchings.length() > 0) {
                        val geometry = matchings.getJSONObject(0).getJSONObject("geometry")
                        val coords = geometry.getJSONArray("coordinates")

                        for (i in 0 until coords.length()) {
                            val pointArray = coords.getJSONArray(i)
                            allSnapped.add(Point.fromLngLat(pointArray.getDouble(0), pointArray.getDouble(1)))
                        }
                    }
                }
                connection.disconnect()
            }

            if (allSnapped.isNotEmpty()) {
                _matchedRoutePoints.value = allSnapped
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotification(): Notification {
        val channelId = "ride_tracking_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Smart Helmet Ride Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Smart Helmet Ride in Progress")
            .setContentText("Recording and mapping your route...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { telemetrySocket?.close() } catch (e: Exception) {}
        telemetrySocket = null
        serviceScope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
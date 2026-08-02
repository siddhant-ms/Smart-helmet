package com.example.smarthelmet

import android.app.*
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

class LocationService : Service() {

    private val binder = LocalBinder()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Map Tracking States
    private val _routePoints = MutableStateFlow<List<Point>>(emptyList())
    private val _matchedRoutePoints = MutableStateFlow<List<Point>>(emptyList())
    val matchedRoutePoints: StateFlow<List<Point>> = _matchedRoutePoints

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking

    // --- DASHBOARD METRIC STATES ---
    private val _rideDistance = MutableStateFlow(0f) // Stored in kilometers
    val rideDistance: StateFlow<Float> = _rideDistance

    private val _maxSpeed = MutableStateFlow(0f) // Stored in km/h
    val maxSpeed: StateFlow<Float> = _maxSpeed

    private val _rideStartTime = MutableStateFlow(0L) // Stored in milliseconds
    val rideStartTime: StateFlow<Long> = _rideStartTime
    // -------------------------------

    inner class LocalBinder : Binder() {
        fun getService(): LocationService = this@LocationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return

                if (location.hasAccuracy() && location.accuracy > 15f) return

                // Handle Max Speed (location.speed is meters/sec, multiply by 3.6 for km/h)
                var currentSpeedKmh = location.speed * 3.6f

                // Ignore GPS drift while stationary
                if (currentSpeedKmh < 2f) {
                    currentSpeedKmh = 0f
                }

                if (currentSpeedKmh > _maxSpeed.value) {
                    _maxSpeed.value = currentSpeedKmh
                }

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

                    if (results[0] < 5.0f) {
                        Log.d("LocationService", "Point too close, skipping (${results[0]}m)")
                        return
                    }

                    // Add to total distance (converted from meters to kilometers)
                    _rideDistance.value += (results[0] / 1000f)
                }

                _routePoints.value = currentList + newPoint
                Log.d("LocationService", "New raw point added. Total: ${currentList.size + 1}")
            }
        }
    }

    fun startTracking() {
        if (_isTracking.value) return
        _isTracking.value = true

        // Reset all metrics for the new ride
        _routePoints.value = emptyList()
        _matchedRoutePoints.value = emptyList()
        _rideDistance.value = 0f
        _maxSpeed.value = 0f
        _rideStartTime.value = System.currentTimeMillis()

        startForeground(NOTIFICATION_ID, createNotification())

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .setMinUpdateDistanceMeters(3f)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())

            serviceScope.launch {
                while (_isTracking.value) {
                    delay(5000) // CHANGED: Snap every 5 seconds instead of 10
                    snapToRoadNetwork()
                }
            }
        } catch (e: SecurityException) {
            Log.e("LocationService", "Location permission denied", e)
            e.printStackTrace()
        }
    }

    fun stopTracking() {
        _isTracking.value = false
        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun snapToRoadNetwork() {
        val currentRaw = _routePoints.value
        Log.d("LocationService", "snapToRoadNetwork called: Raw=${currentRaw.size}, Matched=${_matchedRoutePoints.value.size}")

        if (currentRaw.size < 2) {
            Log.d("LocationService", "Not enough raw points yet (${currentRaw.size})")
            return
        }

        val chunks = currentRaw.windowed(size = 90, step = 89, partialWindows = true)
        val allSnapped = mutableListOf<Point>()

        try {
            for ((chunkIndex, chunk) in chunks.withIndex()) {
                if (chunk.size < 2) {
                    Log.d("LocationService", "Chunk $chunkIndex: skipping (only ${chunk.size} points)")
                    allSnapped.addAll(chunk)
                    continue
                }

                Log.d("LocationService", "Processing chunk $chunkIndex with ${chunk.size} points")
                val coordsString = chunk.joinToString(";") { "${it.longitude()},${it.latitude()}" }
                val urlString = "https://router.project-osrm.org/match/v1/driving/$coordsString?overview=full&geometries=geojson"

                Log.d("LocationService", "Calling OSRM: $urlString")
                val url = URL(urlString)

                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                try {
                    if (connection.responseCode == 200) {
                        val response = connection.inputStream.bufferedReader().readText()
                        Log.d("LocationService", "OSRM response received (${response.length} chars)")

                        val json = JSONObject(response)
                        val matchings = json.optJSONArray("matchings")

                        if (matchings != null && matchings.length() > 0) {
                            val geometry = matchings.getJSONObject(0).getJSONObject("geometry")
                            val coords = geometry.getJSONArray("coordinates")

                            Log.d("LocationService", "Got ${coords.length()} snapped coordinates from OSRM")

                            for (i in 0 until coords.length()) {
                                val pointArray = coords.getJSONArray(i)
                                allSnapped.add(Point.fromLngLat(pointArray.getDouble(0), pointArray.getDouble(1)))
                            }
                        } else {
                            Log.w("LocationService", "No matchings in OSRM response")
                        }
                    } else {
                        Log.e("LocationService", "OSRM returned status ${connection.responseCode}")
                    }
                } finally {
                    connection.disconnect()
                }
            }

            if (allSnapped.isNotEmpty()) {
                Log.d("LocationService", "Updating matchedRoutePoints with ${allSnapped.size} points")
                _matchedRoutePoints.value = allSnapped
            } else {
                Log.w("LocationService", "No snapped points to update")
            }

        } catch (e: Exception) {
            Log.e("LocationService", "Error in snapToRoadNetwork", e)
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
        serviceScope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
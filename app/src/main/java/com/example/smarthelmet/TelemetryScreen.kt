package com.example.smarthelmet

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

import com.example.smarthelmet.database.RideDatabase
import com.example.smarthelmet.database.RideEntity
import com.example.smarthelmet.database.TelemetryPoint

@Composable
fun TelemetryScreen(
    navController: NavController,
    latitude: String,
    longitude: String,
    speed: String,
    accuracy: String
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    MapLibre.getInstance(context)

    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var locationService by remember { mutableStateOf<LocationService?>(null) }

    /*
     * Important:
     *
     * If the user presses START RIDE before the service connection has
     * completed, we remember that request and execute it from
     * onServiceConnected().
     *
     * This removes the timing-dependent:
     *
     *     locationService?.startTracking()
     *
     * behavior that could silently do nothing on another device.
     */
    var pendingStart by remember { mutableStateOf(false) }

    var showRideHistory by remember { mutableStateOf(false) }
    var rideList by remember { mutableStateOf<List<RideEntity>>(emptyList()) }

    val coroutineScope = rememberCoroutineScope()

    val db = remember {
        RideDatabase.getDatabase(context)
    }

    var selectedRide by remember {
        mutableStateOf<RideEntity?>(null)
    }

    /*
     * Telemetry point currently selected by the speed graph scrubber.
     */
    var scrubbedPoint by remember {
        mutableStateOf<TelemetryPoint?>(null)
    }

    LaunchedEffect(selectedRide) {
        scrubbedPoint = null
    }

    var showNameDialog by remember {
        mutableStateOf(false)
    }

    var pendingRideName by remember {
        mutableStateOf("")
    }

    fun hasLocationPermission(): Boolean {
        val fineGranted =
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }
    fun requestStartRide() {
        if (pendingStart) {
            return
        }

        if (!hasLocationPermission()) {
            Toast.makeText(
                context,
                "Location permission is required to start a ride.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        selectedRide = null
        pendingStart = true

        val intent =
            Intent(context, LocationService::class.java)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            pendingStart = false

            Toast.makeText(
                context,
                "Unable to start ride tracking",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        /*
         * The service may already be connected.
         *
         * In that case, don't wait for a callback that has already happened.
         */
        locationService?.let { service ->
            pendingStart = false

            val started =
                service.startTracking()

            if (!started) {
                context.stopService(intent)

                Toast.makeText(
                    context,
                    "Ride could not start. Check location permission.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /*
     * Bind to LocationService.
     *
     * BIND_AUTO_CREATE lets the UI observe an existing/new service instance.
     *
     * IMPORTANT:
     * Binding itself does NOT start a ride.
     *
     * Therefore reopening the telemetry screen during an active ride does
     * not reset or restart tracking.
     */
    DisposableEffect(context) {
        val connection =
            object : ServiceConnection {

                override fun onServiceConnected(
                    name: ComponentName?,
                    service: IBinder?
                ) {
                    val binder =
                        service as? LocationService.LocalBinder

                    if (binder == null) {
                        locationService = null
                        pendingStart = false

                        Toast.makeText(
                            context,
                            "Unable to connect to ride service",
                            Toast.LENGTH_SHORT
                        ).show()

                        return
                    }

                    val connectedService =
                        binder.getService()

                    locationService = connectedService

                    /*
                     * This is the actual race-condition fix.
                     *
                     * START RIDE may have been pressed before the binder
                     * became available.
                     *
                     * In that case the request is now fulfilled.
                     */
                    if (pendingStart) {
                        pendingStart = false

                        val started =
                            connectedService.startTracking()

                        if (!started) {
                            context.stopService(
                                Intent(context, LocationService::class.java)
                            )

                            Toast.makeText(
                                context,
                                "Ride could not start. Check location permission.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                override fun onServiceDisconnected(
                    name: ComponentName?
                ) {
                    locationService = null
                }

                override fun onBindingDied(
                    name: ComponentName?
                ) {
                    locationService = null

                    if (pendingStart) {
                        pendingStart = false

                        Toast.makeText(
                            context,
                            "Ride service connection was lost",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onNullBinding(
                    name: ComponentName?
                ) {
                    locationService = null
                    pendingStart = false

                    Toast.makeText(
                        context,
                        "Ride service unavailable",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        val intent =
            Intent(context, LocationService::class.java)

        val successfullyBound =
            try {
                context.bindService(
                    intent,
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Unable to connect to ride service",
                    Toast.LENGTH_SHORT
                ).show()

                false
            }

        onDispose {
            if (successfullyBound) {
                try {
                    context.unbindService(connection)
                } catch (_: IllegalArgumentException) {
                    /*
                     * The connection was already removed by the system.
                     */
                }
            }
        }
    }

    val isTracking by
    locationService
        ?.isTracking
        ?.collectAsState()
        ?: remember {
            mutableStateOf(false)
        }

    val rawRoutePoints by
    locationService
        ?.routePoints
        ?.collectAsState()
        ?: remember {
            mutableStateOf(emptyList<Point>())
        }

    val matchedRoutePoints by
    locationService
        ?.matchedRoutePoints
        ?.collectAsState()
        ?: remember {
            mutableStateOf(emptyList<Point>())
        }

    val rideDistance by
    locationService
        ?.rideDistance
        ?.collectAsState()
        ?: remember {
            mutableStateOf(0f)
        }

    val maxSpeed by
    locationService
        ?.maxSpeed
        ?.collectAsState()
        ?: remember {
            mutableStateOf(0f)
        }

    val rideStartTime by
    locationService
        ?.rideStartTime
        ?.collectAsState()
        ?: remember {
            mutableStateOf(0L)
        }

    var durationText by remember {
        mutableStateOf("00:00")
    }

    /*
     * Ride duration timer.
     */
    LaunchedEffect(isTracking, rideStartTime) {
        if (isTracking && rideStartTime > 0) {
            while (true) {
                val elapsed =
                    System.currentTimeMillis() - rideStartTime

                val hours =
                    elapsed / (1000 * 60 * 60)

                val minutes =
                    (elapsed / (1000 * 60)) % 60

                durationText =
                    String.format(
                        "%02d:%02d",
                        hours,
                        minutes
                    )

                delay(1000)
            }
        } else {
            durationText = "00:00"
        }
    }

    /*
     * Update displayed route whenever live or historical route data changes.
     */
    LaunchedEffect(
        rawRoutePoints,
        matchedRoutePoints,
        mapInstance,
        selectedRide,
        isTracking
    ) {
        mapInstance?.getStyle { style ->

            val flatSource =
                style.getSourceAs<GeoJsonSource>(
                    "route-source"
                )

            val gradientSource =
                style.getSourceAs<GeoJsonSource>(
                    "speed-route-source"
                )

            val locatedTelemetry =
                selectedRide
                    ?.speedHistory
                    ?.filter { it.hasLocation }
                    ?: emptyList()

            if (
                selectedRide != null &&
                locatedTelemetry.size > 1
            ) {
                /*
                 * Historical ride has location-tagged speed telemetry.
                 */
                gradientSource?.setGeoJson(
                    speedGradientFeatureCollection(
                        locatedTelemetry
                    )
                )

                flatSource?.setGeoJson(
                    Feature.fromGeometry(
                        LineString.fromLngLats(
                            emptyList<Point>()
                        )
                    )
                )
            } else {
                gradientSource?.setGeoJson(
                    FeatureCollection.fromFeatures(
                        emptyList()
                    )
                )

                val pointsToDisplay =
                    when {
                        selectedRide != null -> {
                            if (
                                selectedRide!!
                                    .matchedRoutePoints
                                    .size > 1
                            ) {
                                selectedRide!!
                                    .matchedRoutePoints
                            } else {
                                selectedRide!!
                                    .rawRoutePoints
                            }
                        }

                        !isTracking -> {
                            emptyList()
                        }

                        matchedRoutePoints.size > 1 -> {
                            matchedRoutePoints
                        }

                        rawRoutePoints.size > 1 -> {
                            rawRoutePoints
                        }

                        else -> {
                            emptyList()
                        }
                    }

                if (pointsToDisplay.size > 1) {
                    flatSource?.setGeoJson(
                        Feature.fromGeometry(
                            LineString.fromLngLats(
                                pointsToDisplay
                            )
                        )
                    )
                } else {
                    flatSource?.setGeoJson(
                        Feature.fromGeometry(
                            LineString.fromLngLats(
                                emptyList<Point>()
                            )
                        )
                    )
                }
            }
        }
    }

    /*
     * Move the map marker when the graph scrubber changes.
     */
    LaunchedEffect(
        scrubbedPoint,
        mapInstance
    ) {
        mapInstance?.getStyle { style ->

            val markerSource =
                style.getSourceAs<GeoJsonSource>(
                    "scrub-marker-source"
                )

            val point = scrubbedPoint

            if (
                point != null &&
                point.hasLocation
            ) {
                markerSource?.setGeoJson(
                    Feature.fromGeometry(
                        Point.fromLngLat(
                            point.lng!!,
                            point.lat!!
                        )
                    )
                )
            } else {
                markerSource?.setGeoJson(
                    FeatureCollection.fromFeatures(
                        emptyList()
                    )
                )
            }
        }
    }

    /*
     * Fit the map to the selected historical ride.
     */
    LaunchedEffect(
        selectedRide,
        mapInstance
    ) {
        selectedRide?.let { ride ->

            mapInstance?.let { map ->

                val pointsToUse =
                    if (ride.matchedRoutePoints.isNotEmpty()) {
                        ride.matchedRoutePoints
                    } else {
                        ride.rawRoutePoints
                    }

                if (pointsToUse.size >= 2) {
                    val bounds =
                        computeBoundingBox(
                            pointsToUse
                        )

                    if (bounds != null) {
                        map.animateCamera(
                            CameraUpdateFactory
                                .newLatLngBounds(
                                    bounds,
                                    100
                                )
                        )
                    }
                }
            }
        }
    }

    val currentLat =
        latitude.toDoubleOrNull()
            ?: 12.9716

    val currentLon =
        longitude.toDoubleOrNull()
            ?: 77.5946

    val mapOptions =
        MapLibreMapOptions
            .createFromAttributes(context)
            .apply {
                camera(
                    CameraPosition.Builder()
                        .target(
                            LatLng(
                                currentLat,
                                currentLon
                            )
                        )
                        .zoom(16.5)
                        .tilt(0.0)
                        .build()
                )
            }

    val mapView =
        remember {
            MapView(
                context,
                mapOptions
            )
        }

    /*
     * Forward Compose lifecycle events to MapLibre MapView.
     */
    DisposableEffect(lifecycleOwner) {

        val observer =
            LifecycleEventObserver { _, event ->

                when (event) {
                    Lifecycle.Event.ON_CREATE ->
                        mapView.onCreate(null)

                    Lifecycle.Event.ON_START ->
                        mapView.onStart()

                    Lifecycle.Event.ON_RESUME ->
                        mapView.onResume()

                    Lifecycle.Event.ON_PAUSE ->
                        mapView.onPause()

                    Lifecycle.Event.ON_STOP ->
                        mapView.onStop()

                    Lifecycle.Event.ON_DESTROY ->
                        mapView.onDestroy()

                    else -> Unit
                }
            }

        lifecycleOwner.lifecycle.addObserver(
            observer
        )

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(
                observer
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Color(0xFF090909)
            )
    ) {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.TopCenter)
        ) {

            AndroidView(
                factory = {

                    mapView.apply {

                        getMapAsync { map ->

                            mapInstance = map

                            map.setStyle(
                                "https://tiles.openfreemap.org/styles/liberty"
                            ) { style ->

                                style.addSource(
                                    GeoJsonSource(
                                        "route-source"
                                    )
                                )

                                style.addLayer(
                                    LineLayer(
                                        "route-layer",
                                        "route-source"
                                    ).withProperties(
                                        PropertyFactory.lineColor(
                                            android.graphics.Color.parseColor(
                                                "#7ED4E0"
                                            )
                                        ),
                                        PropertyFactory.lineWidth(
                                            6f
                                        ),
                                        PropertyFactory.lineCap(
                                            Property.LINE_CAP_ROUND
                                        ),
                                        PropertyFactory.lineJoin(
                                            Property.LINE_JOIN_ROUND
                                        )
                                    )
                                )

                                /*
                                 * Speed-colored historical route.
                                 */
                                style.addSource(
                                    GeoJsonSource(
                                        "speed-route-source"
                                    )
                                )

                                style.addLayer(
                                    LineLayer(
                                        "speed-route-layer",
                                        "speed-route-source"
                                    ).withProperties(
                                        PropertyFactory.lineWidth(
                                            6f
                                        ),
                                        PropertyFactory.lineCap(
                                            Property.LINE_CAP_ROUND
                                        ),
                                        PropertyFactory.lineJoin(
                                            Property.LINE_JOIN_ROUND
                                        ),
                                        PropertyFactory.lineColor(
                                            Expression.interpolate(
                                                Expression.linear(),
                                                Expression.get("speed"),
                                                Expression.stop(
                                                    0f,
                                                    Expression.color(
                                                        android.graphics.Color.parseColor(
                                                            "#3AA0FF"
                                                        )
                                                    )
                                                ),
                                                Expression.stop(
                                                    30f,
                                                    Expression.color(
                                                        android.graphics.Color.parseColor(
                                                            "#7ED4E0"
                                                        )
                                                    )
                                                ),
                                                Expression.stop(
                                                    60f,
                                                    Expression.color(
                                                        android.graphics.Color.parseColor(
                                                            "#FFC53D"
                                                        )
                                                    )
                                                ),
                                                Expression.stop(
                                                    90f,
                                                    Expression.color(
                                                        android.graphics.Color.parseColor(
                                                            "#FF4D4D"
                                                        )
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )

                                /*
                                 * Graph scrub marker.
                                 */
                                style.addSource(
                                    GeoJsonSource(
                                        "scrub-marker-source"
                                    )
                                )

                                style.addLayer(
                                    CircleLayer(
                                        "scrub-marker-layer",
                                        "scrub-marker-source"
                                    ).withProperties(
                                        PropertyFactory.circleRadius(
                                            8f
                                        ),
                                        PropertyFactory.circleColor(
                                            android.graphics.Color.WHITE
                                        ),
                                        PropertyFactory.circleStrokeWidth(
                                            3f
                                        ),
                                        PropertyFactory.circleStrokeColor(
                                            android.graphics.Color.parseColor(
                                                "#7ED4E0"
                                            )
                                        )
                                    )
                                )

                                /*
                                 * MapLibre's location component.
                                 *
                                 * Permission handling itself will be
                                 * cleaned up in the dedicated permission phase.
                                 */
                                val locationComponent =
                                    map.locationComponent

                                if (
                                    ActivityCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    locationComponent
                                        .activateLocationComponent(
                                            LocationComponentActivationOptions
                                                .builder(
                                                    context,
                                                    style
                                                )
                                                .build()
                                        )

                                    locationComponent
                                        .isLocationComponentEnabled =
                                        true

                                    locationComponent.cameraMode =
                                        CameraMode.TRACKING_COMPASS

                                    locationComponent.renderMode =
                                        RenderMode.COMPASS

                                    locationComponent
                                        .zoomWhileTracking(
                                            16.5
                                        )
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            /*
             * Ride history / back button.
             */
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .size(48.dp)
                    .background(
                        Color(0xFF1E1E1E).copy(
                            alpha = 0.9f
                        ),
                        RoundedCornerShape(50)
                    )
                    .border(
                        1.dp,
                        Color.White.copy(
                            alpha = 0.15f
                        ),
                        RoundedCornerShape(50)
                    )
                    .clickable {

                        if (selectedRide != null) {
                            selectedRide = null
                        } else {
                            showRideHistory = true

                            coroutineScope.launch(
                                Dispatchers.IO
                            ) {
                                val rides =
                                    db.rideDao()
                                        .getAllRides()

                                withContext(
                                    Dispatchers.Main
                                ) {
                                    rideList = rides
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector =
                        if (selectedRide != null) {
                            Icons.Default.ArrowBack
                        } else {
                            Icons.Default.Menu
                        },
                    contentDescription =
                        if (selectedRide != null) {
                            "Back to Tracking"
                        } else {
                            "Ride History Menu"
                        },
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            /*
             * Re-center button.
             */
            if (selectedRide == null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(16.dp)
                        .size(48.dp)
                        .background(
                            Color(0xFF1E1E1E).copy(
                                alpha = 0.9f
                            ),
                            RoundedCornerShape(50)
                        )
                        .border(
                            1.dp,
                            Color.White.copy(
                                alpha = 0.15f
                            ),
                            RoundedCornerShape(50)
                        )
                        .clickable {

                            mapInstance
                                ?.locationComponent
                                ?.let { locationComponent ->

                                    if (
                                        locationComponent
                                            .isLocationComponentActivated
                                    ) {
                                        locationComponent.cameraMode =
                                            CameraMode.TRACKING_COMPASS

                                        locationComponent
                                            .zoomWhileTracking(
                                                16.5
                                            )
                                    }
                                }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector =
                            Icons.Default.LocationOn,
                        contentDescription =
                            "Re-center Map",
                        tint =
                            Color(0xFF7ED4E0),
                        modifier =
                            Modifier.size(24.dp)
                    )
                }
            }
        }

        /*
         * Historical ride panel.
         */
        if (selectedRide != null) {

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        color =
                            Color(0xFF090909).copy(
                                alpha = 0.80f
                            ),
                        shape =
                            RoundedCornerShape(
                                topStart = 24.dp,
                                topEnd = 24.dp
                            )
                    )
                    .padding(
                        top = 12.dp,
                        bottom = 90.dp,
                        start = 16.dp,
                        end = 16.dp
                    ),
                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {

                Text(
                    text = selectedRide!!.name,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                SpeedTimeGraph(
                    data =
                        selectedRide!!.speedHistory,
                    startTimeMs =
                        selectedRide!!.startTime,
                    modifier =
                        Modifier.fillMaxWidth(),
                    onScrub = {
                        scrubbedPoint = it
                    }
                )
            }

        } else {

            /*
             * Live ride controls.
             */
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        color =
                            Color(0xFF090909).copy(
                                alpha = 0.95f
                            ),
                        shape =
                            RoundedCornerShape(
                                topStart = 24.dp,
                                topEnd = 24.dp
                            )
                    )
                    .padding(
                        start = 24.dp,
                        end = 24.dp,
                        top = 24.dp,
                        bottom = 110.dp
                    ),
                horizontalAlignment =
                    Alignment.CenterHorizontally,
                verticalArrangement =
                    Arrangement.SpaceEvenly
            ) {

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.SpaceEvenly,
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    MetricItem(
                        title = "DISTANCE",
                        value =
                            String.format(
                                "%.2f",
                                rideDistance
                            ),
                        label = "km"
                    )

                    MetricItem(
                        title = "DURATION",
                        value = durationText,
                        label = "hr:min"
                    )

                    MetricItem(
                        title = "TOP SPEED",
                        value =
                            String.format(
                                "%.1f",
                                maxSpeed
                            ),
                        label = "km/h"
                    )
                }

                Spacer(
                    modifier = Modifier.height(24.dp)
                )

                if (isTracking) {

                    SlideToStopButton(
                        onStop = {

                            selectedRide = null

                            if (rideDistance >= 0.1f) {
                                showNameDialog = true
                            } else {

                                locationService
                                    ?.stopTracking(
                                        "Discarded"
                                    )

                                Toast.makeText(
                                    context,
                                    "Ride discarded: Less than 100m",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )

                } else {

                    Button(
                        onClick = {
                            requestStartRide()
                        },
                        enabled = !pendingStart,
                        shape =
                            RoundedCornerShape(50),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor =
                                    Color.White
                            ),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                    ) {

                        Text(
                            text =
                                if (pendingStart) {
                                    "STARTING..."
                                } else {
                                    "START RIDE"
                                },
                            color = Color.Black,
                            fontWeight =
                                FontWeight.Bold,
                            fontSize = 16.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }

        /*
         * Save ride dialog.
         */
        if (showNameDialog) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color.Black.copy(
                            alpha = 0.8f
                        )
                    )
                    .clickable(
                        enabled = false
                    ) {},
                contentAlignment =
                    Alignment.Center
            ) {

                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .background(
                            Color(0xFF1E1E1E),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(24.dp),
                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    Text(
                        text = "Name Your Ride",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        modifier = Modifier.height(16.dp)
                    )

                    OutlinedTextField(
                        value = pendingRideName,
                        onValueChange = {
                            pendingRideName = it
                        },
                        placeholder = {
                            Text(
                                text =
                                    "e.g. Morning Commute",
                                color = Color.Gray
                            )
                        },
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedTextColor =
                                    Color.White,
                                unfocusedTextColor =
                                    Color.White,
                                focusedBorderColor =
                                    Color(0xFF7ED4E0),
                                unfocusedBorderColor =
                                    Color.DarkGray
                            ),
                        singleLine = true,
                        modifier =
                            Modifier.fillMaxWidth()
                    )

                    Spacer(
                        modifier = Modifier.height(24.dp)
                    )

                    Button(
                        onClick = {

                            val finalName =
                                if (
                                    pendingRideName
                                        .isNotBlank()
                                ) {
                                    pendingRideName
                                } else {
                                    "Unnamed Ride"
                                }

                            locationService
                                ?.stopTracking(
                                    finalName
                                )

                            showNameDialog = false
                            pendingRideName = ""
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor =
                                    Color(0xFF7ED4E0)
                            ),
                        shape =
                            RoundedCornerShape(
                                12.dp
                            )
                    ) {
                        Text(
                            text = "Save Ride",
                            color = Color.Black,
                            fontWeight =
                                FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }

        /*
         * Ride history overlay.
         */
        if (showRideHistory) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color.Black.copy(
                            alpha = 0.85f
                        )
                    )
                    .clickable {
                        showRideHistory = false
                    }
            ) {

                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(
                            horizontal = 24.dp
                        )
                        .fillMaxWidth()
                        .clickable(
                            enabled = false
                        ) {}
                ) {

                    Text(
                        text = "Ride History",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight =
                            FontWeight.Bold,
                        modifier =
                            Modifier
                                .padding(
                                    bottom = 16.dp
                                )
                                .align(
                                    Alignment.CenterHorizontally
                                )
                    )

                    if (rideList.isEmpty()) {

                        Text(
                            text = "No rides saved.",
                            color = Color.Gray,
                            fontSize = 16.sp,
                            modifier =
                                Modifier.align(
                                    Alignment.CenterHorizontally
                                )
                        )

                    } else {

                        LazyColumn(
                            modifier =
                                Modifier.fillMaxHeight(
                                    0.6f
                                ),
                            verticalArrangement =
                                Arrangement.spacedBy(
                                    12.dp
                                )
                        ) {

                            items(
                                rideList
                            ) { ride ->

                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .background(
                                                Color(
                                                    0xFF1E1E1E
                                                ),
                                                RoundedCornerShape(
                                                    12.dp
                                                )
                                            )
                                            .border(
                                                1.dp,
                                                Color.White.copy(
                                                    alpha = 0.1f
                                                ),
                                                RoundedCornerShape(
                                                    12.dp
                                                )
                                            )
                                            .padding(
                                                16.dp
                                            )
                                            .clickable {

                                                selectedRide =
                                                    ride

                                                showRideHistory =
                                                    false
                                            },
                                    verticalAlignment =
                                        Alignment.CenterVertically
                                ) {

                                    Column(
                                        modifier =
                                            Modifier.weight(
                                                1f
                                            )
                                    ) {

                                        Text(
                                            text = ride.name,
                                            color =
                                                Color.White,
                                            fontSize =
                                                18.sp,
                                            fontWeight =
                                                FontWeight.SemiBold
                                        )

                                        Text(
                                            text =
                                                "${String.format("%.2f", ride.distanceKm)} km • " +
                                                        "${String.format("%.1f", ride.maxSpeedKmh)} km/h max",
                                            color =
                                                Color.Gray,
                                            fontSize =
                                                14.sp
                                        )
                                    }

                                    Icon(
                                        imageVector =
                                            Icons.Default.Delete,
                                        contentDescription =
                                            "Delete Ride",
                                        tint =
                                            Color(0xFFE53935),
                                        modifier =
                                            Modifier
                                                .size(28.dp)
                                                .clickable {

                                                    coroutineScope.launch(
                                                        Dispatchers.IO
                                                    ) {

                                                        db.rideDao()
                                                            .deleteRideById(
                                                                ride.id
                                                            )

                                                        val updatedList =
                                                            db.rideDao()
                                                                .getAllRides()

                                                        withContext(
                                                            Dispatchers.Main
                                                        ) {
                                                            rideList =
                                                                updatedList
                                                        }
                                                    }
                                                }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/*
 * Builds one LineString feature per consecutive pair of located telemetry
 * points and tags each segment with the ending point's speed.
 */
fun speedGradientFeatureCollection(
    points: List<TelemetryPoint>
): FeatureCollection {

    if (points.size < 2) {
        return FeatureCollection.fromFeatures(
            emptyList()
        )
    }

    val features =
        mutableListOf<Feature>()

    for (i in 0 until points.size - 1) {

        val a = points[i]
        val b = points[i + 1]

        val segment =
            LineString.fromLngLats(
                listOf(
                    Point.fromLngLat(
                        a.lng!!,
                        a.lat!!
                    ),
                    Point.fromLngLat(
                        b.lng!!,
                        b.lat!!
                    )
                )
            )

        val feature =
            Feature.fromGeometry(
                segment
            )

        feature.addNumberProperty(
            "speed",
            b.speedKmh
        )

        features.add(feature)
    }

    return FeatureCollection.fromFeatures(
        features
    )
}

fun computeBoundingBox(
    points: List<Point>
): LatLngBounds? {

    if (points.isEmpty()) {
        return null
    }

    var minLat =
        points[0].latitude()

    var maxLat =
        points[0].latitude()

    var minLng =
        points[0].longitude()

    var maxLng =
        points[0].longitude()

    for (point in points) {

        val lat =
            point.latitude()

        val lng =
            point.longitude()

        if (lat < minLat) {
            minLat = lat
        }

        if (lat > maxLat) {
            maxLat = lat
        }

        if (lng < minLng) {
            minLng = lng
        }

        if (lng > maxLng) {
            maxLng = lng
        }
    }

    return LatLngBounds.Builder()
        .include(
            LatLng(
                minLat,
                minLng
            )
        )
        .include(
            LatLng(
                maxLat,
                maxLng
            )
        )
        .build()
}

@Composable
fun MetricItem(
    title: String,
    value: String,
    label: String
) {
    Column(
        horizontalAlignment =
            Alignment.CenterHorizontally
    ) {

        Text(
            text = title,
            color =
                Color.Gray.copy(
                    alpha = 0.8f
                ),
            fontSize = 11.sp,
            fontWeight =
                FontWeight.SemiBold,
            letterSpacing = 1.sp
        )

        Spacer(
            modifier = Modifier.height(2.dp)
        )

        Text(
            text = value,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight =
                FontWeight.Bold
        )

        Text(
            text = label,
            color = Color(0xFF7ED4E0),
            fontSize = 13.sp
        )
    }
}

@Composable
fun SlideToStopButton(
    onStop: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(
                Color(0xFF0F1720),
                RoundedCornerShape(50)
            )
            .border(
                1.dp,
                Color.White.copy(
                    alpha = 0.1f
                ),
                RoundedCornerShape(50)
            )
    ) {

        val maxDrag =
            with(LocalDensity.current) {
                (maxWidth - 64.dp).toPx()
            }

        var offsetX by remember {
            mutableFloatStateOf(0f)
        }

        var isTriggered by remember {
            mutableStateOf(false)
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment =
                Alignment.Center
        ) {

            Text(
                text = "Slide to stop",
                color =
                    Color.White.copy(
                        alpha = 0.9f
                    ),
                fontSize = 16.sp,
                fontWeight =
                    FontWeight.Medium
            )
        }

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        offsetX.roundToInt(),
                        0
                    )
                }
                .padding(4.dp)
                .size(56.dp)
                .background(
                    Color.White,
                    RoundedCornerShape(50)
                )
                .pointerInput(Unit) {

                    detectHorizontalDragGestures(

                        onDragEnd = {

                            if (
                                offsetX <
                                maxDrag * 0.8f
                            ) {
                                offsetX = 0f

                            } else if (
                                !isTriggered
                            ) {
                                isTriggered = true
                                offsetX = maxDrag
                                onStop()
                            }
                        },

                        onHorizontalDrag = {
                                change,
                                dragAmount ->

                            change.consume()

                            if (!isTriggered) {
                                offsetX =
                                    (
                                            offsetX +
                                                    dragAmount
                                            )
                                        .coerceIn(
                                            0f,
                                            maxDrag
                                        )
                            }
                        }
                    )
                },
            contentAlignment =
                Alignment.Center
        ) {

            Icon(
                imageVector =
                    Icons.Default.ArrowForward,
                contentDescription =
                    "Slide Arrow",
                tint = Color.Black,
                modifier =
                    Modifier.size(24.dp)
            )
        }
    }
}
package com.example.smarthelmet

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.SolidColor

data class CustomMarker(val name: String, val lat: Double, val lng: Double)

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
    val prefs = context.getSharedPreferences("smart_helmet_prefs", Context.MODE_PRIVATE)

    MapLibre.getInstance(context)

    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var locationService by remember { mutableStateOf<LocationService?>(null) }

    // --- CUSTOM PIN STATES ---
    var isPlacingPin by remember { mutableStateOf(false) }
    var newPinName by remember { mutableStateOf("") }
    var selectedMarkerForDeletion by remember { mutableStateOf<CustomMarker?>(null) }
    val savedMarkers = remember { mutableStateListOf<CustomMarker>() }

    // Load markers from SharedPreferences on startup
    LaunchedEffect(Unit) {
        val savedJson = prefs.getString("custom_markers", "[]")
        val jsonArray = JSONArray(savedJson)
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            savedMarkers.add(CustomMarker(obj.getString("name"), obj.getDouble("lat"), obj.getDouble("lng")))
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val intent = Intent(context, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            locationService?.startTracking()
        }
    }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as LocationService.LocalBinder
                locationService = binder.getService()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                locationService = null
            }
        }
        val intent = Intent(context, LocationService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose {
            context.unbindService(connection)
        }
    }

    val isTracking by locationService?.isTracking?.collectAsState() ?: remember { mutableStateOf(false) }
    val matchedRoutePoints by locationService?.matchedRoutePoints?.collectAsState() ?: remember { mutableStateOf(emptyList<Point>()) }

    val rideDistance by locationService?.rideDistance?.collectAsState() ?: remember { mutableStateOf(0f) }
    val maxSpeed by locationService?.maxSpeed?.collectAsState() ?: remember { mutableStateOf(0f) }
    val rideStartTime by locationService?.rideStartTime?.collectAsState() ?: remember { mutableStateOf(0L) }

    var durationText by remember { mutableStateOf("00:00") }

    LaunchedEffect(isTracking, rideStartTime) {
        if (isTracking && rideStartTime > 0) {
            while (true) {
                val elapsed = System.currentTimeMillis() - rideStartTime
                val hours = (elapsed / (1000 * 60 * 60))
                val minutes = (elapsed / (1000 * 60)) % 60
                durationText = String.format("%02d:%02d", hours, minutes)
                delay(1000)
            }
        } else {
            durationText = "00:00"
        }
    }

    // Reactively draws the Trail Line
    LaunchedEffect(matchedRoutePoints, mapInstance) {
        mapInstance?.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>("route-source")
            if (source != null && matchedRoutePoints.size > 1) {
                source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(matchedRoutePoints)))
            } else if (source != null && matchedRoutePoints.isEmpty()) {
                source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(emptyList<Point>())))
            }
        }
    }

    // Reactively draws the Custom Pins whenever savedMarkers.size changes
    LaunchedEffect(savedMarkers.size, mapInstance) {
        mapInstance?.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>("saved-pins-source")
            if (source != null) {
                val featureList = savedMarkers.map { marker ->
                    val point = Point.fromLngLat(marker.lng, marker.lat)
                    val feature = Feature.fromGeometry(point)
                    feature.addStringProperty("name", marker.name)
                    feature
                }
                source.setGeoJson(FeatureCollection.fromFeatures(featureList))
            }
        }
    }

    val currentLat = latitude.toDoubleOrNull() ?: 12.9716
    val currentLon = longitude.toDoubleOrNull() ?: 77.5946

    val mapOptions = MapLibreMapOptions.createFromAttributes(context).apply {
        camera(
            CameraPosition.Builder()
                .target(LatLng(currentLat, currentLon))
                .zoom(16.5)
                .tilt(0.0)
                .build()
        )
    }

    val mapView = remember { MapView(context, mapOptions) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090909))
    ) {
        // --- TOP 50%: MAP AREA ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.50f)
                .align(Alignment.TopCenter)
        ) {
            AndroidView(
                factory = {
                    mapView.apply {
                        getMapAsync { map ->
                            mapInstance = map
                            map.setStyle("https://tiles.openfreemap.org/styles/liberty") { style ->

                                style.addSource(GeoJsonSource("route-source"))
                                style.addLayer(
                                    LineLayer("route-layer", "route-source").withProperties(
                                        PropertyFactory.lineColor(android.graphics.Color.parseColor("#7ED4E0")),
                                        PropertyFactory.lineWidth(6f),
                                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                                    )
                                )

                                // Initial pin setup for when map first boots
                                val initialFeatures = savedMarkers.map { marker ->
                                    val feature = Feature.fromGeometry(Point.fromLngLat(marker.lng, marker.lat))
                                    feature.addStringProperty("name", marker.name)
                                    feature
                                }

                                style.addSource(GeoJsonSource("saved-pins-source", FeatureCollection.fromFeatures(initialFeatures)))

                                style.addLayer(
                                    CircleLayer("saved-pins-circle-layer", "saved-pins-source").withProperties(
                                        PropertyFactory.circleColor(android.graphics.Color.parseColor("#FF8800")),
                                        PropertyFactory.circleRadius(10f),
                                        PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                                        PropertyFactory.circleStrokeWidth(2f)
                                    )
                                )
                                // Safely format the text layer so it doesn't break the source
                                style.addLayer(
                                    SymbolLayer("saved-pins-text-layer", "saved-pins-source").withProperties(
                                        PropertyFactory.textField("{name}"),
                                        PropertyFactory.textColor(android.graphics.Color.BLACK),
                                        PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
                                        PropertyFactory.textHaloWidth(2f),
                                        PropertyFactory.textOffset(arrayOf(0f, -1.5f)),
                                        PropertyFactory.textSize(12f)
                                    )
                                )

                                val locationComponent = map.locationComponent
                                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                    locationComponent.activateLocationComponent(
                                        LocationComponentActivationOptions.builder(context, style).build()
                                    )
                                    locationComponent.isLocationComponentEnabled = true
                                    locationComponent.cameraMode = CameraMode.TRACKING_COMPASS
                                    locationComponent.renderMode = RenderMode.COMPASS
                                    locationComponent.zoomWhileTracking(16.5)
                                }
                            }

                            // --- CLICK LISTENER FOR DELETING PINS ---
                            map.addOnMapClickListener { point ->
                                val pixel = map.projection.toScreenLocation(point)
                                val rectF = RectF(pixel.x - 40f, pixel.y - 40f, pixel.x + 40f, pixel.y + 40f)
                                val features = map.queryRenderedFeatures(rectF, "saved-pins-circle-layer", "saved-pins-text-layer")

                                if (features.isNotEmpty()) {
                                    val clickedName = features[0].getStringProperty("name")
                                    val foundMarker = savedMarkers.find { it.name == clickedName }
                                    if (foundMarker != null) {
                                        selectedMarkerForDeletion = foundMarker
                                        isPlacingPin = false
                                    }
                                    true
                                } else {
                                    selectedMarkerForDeletion = null
                                    false
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // --- CUSTOM PIN UI (TOP LEFT) ---
            if (isPlacingPin) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Target",
                    tint = Color(0xFFFF8800),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                        .offset(y = (-18).dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.TopCenter)
                        .background(Color(0xFF1E1E1E).copy(alpha = 0.95f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = newPinName,
                        onValueChange = { newPinName = it },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            if (newPinName.isEmpty()) {
                                Text("Name this location...", color = Color.Gray, fontSize = 16.sp)
                            }
                            innerTextField()
                        }
                    )

                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = Color.Gray,
                        modifier = Modifier
                            .size(28.dp)
                            .clickable {
                                isPlacingPin = false
                                newPinName = ""
                            }
                            .padding(4.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFF25D366), RoundedCornerShape(50))
                            .clickable {
                                if (newPinName.isNotBlank()) {
                                    val target = mapInstance?.cameraPosition?.target
                                    if (target != null) {
                                        // Update state (this triggers the LaunchedEffect to draw the pin)
                                        savedMarkers.add(CustomMarker(newPinName, target.latitude, target.longitude))

                                        // Save to memory
                                        val jsonArray = JSONArray()
                                        savedMarkers.forEach {
                                            val obj = JSONObject()
                                            obj.put("name", it.name)
                                            obj.put("lat", it.lat)
                                            obj.put("lng", it.lng)
                                            jsonArray.put(obj)
                                        }
                                        prefs.edit().putString("custom_markers", jsonArray.toString()).apply()

                                        isPlacingPin = false
                                        newPinName = ""
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Save Pin",
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else if (selectedMarkerForDeletion != null) {
                // DELETE PIN OVERLAY
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.TopCenter)
                        .background(Color(0xFF1E1E1E).copy(alpha = 0.95f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedMarkerForDeletion!!.name,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = Color.Gray,
                        modifier = Modifier
                            .size(28.dp)
                            .clickable { selectedMarkerForDeletion = null }
                            .padding(4.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFFE53935), RoundedCornerShape(50))
                            .clickable {
                                // Update state (triggers LaunchedEffect to erase the pin)
                                savedMarkers.remove(selectedMarkerForDeletion)

                                // Permanently delete from phone memory
                                val jsonArray = JSONArray()
                                savedMarkers.forEach {
                                    val obj = JSONObject()
                                    obj.put("name", it.name)
                                    obj.put("lat", it.lat)
                                    obj.put("lng", it.lng)
                                    jsonArray.put(obj)
                                }
                                prefs.edit().putString("custom_markers", jsonArray.toString()).apply()

                                selectedMarkerForDeletion = null
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Pin",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } else {
                // Default Top-Left "Add Pin" Button
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .size(48.dp)
                        .background(Color(0xFF1E1E1E).copy(alpha = 0.9f), RoundedCornerShape(50))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(50))
                        .clickable {
                            isPlacingPin = true
                            mapInstance?.locationComponent?.cameraMode = CameraMode.NONE
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AddLocation,
                        contentDescription = "Add Pin",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Re-center button (Bottom Right)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(48.dp)
                    .background(Color(0xFF1E1E1E).copy(alpha = 0.9f), RoundedCornerShape(50))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(50))
                    .clickable {
                        mapInstance?.locationComponent?.let { locationComponent ->
                            if (locationComponent.isLocationComponentActivated) {
                                locationComponent.cameraMode = CameraMode.TRACKING_COMPASS
                                locationComponent.zoomWhileTracking(16.5)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Re-center Map",
                    tint = Color(0xFF7ED4E0),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // --- BOTTOM 50%: DASHBOARD & BUTTON ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.50f)
                .align(Alignment.BottomCenter)
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricItem(title = "DISTANCE", value = String.format("%.2f", rideDistance), label = "km")
                MetricItem(title = "DURATION", value = durationText, label = "hr:min")
                MetricItem(title = "TOP SPEED", value = String.format("%.1f", maxSpeed), label = "km/h")
            }

            if (isTracking) {
                SlideToStopButton(
                    onStop = { locationService?.stopTracking() }
                )
            } else {
                Button(
                    onClick = {
                        val intent = Intent(context, LocationService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                            locationService?.startTracking()
                        }
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                ) {
                    Text(
                        text = "START RIDE",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

// --- SUB-COMPONENTS ---

@Composable
fun MetricItem(title: String, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            color = Color.Gray.copy(alpha = 0.8f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = value,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color(0xFF7ED4E0),
            fontSize = 13.sp
        )
    }
}

@Composable
fun SlideToStopButton(onStop: () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Color(0xFF0F1720), RoundedCornerShape(50))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(50))
    ) {
        val maxDrag = with(LocalDensity.current) { (maxWidth - 64.dp).toPx() }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var isTriggered by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Slide to stop",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .padding(4.dp)
                .size(56.dp)
                .background(Color.White, RoundedCornerShape(50))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < maxDrag * 0.8f) {
                                offsetX = 0f
                            } else if (!isTriggered) {
                                isTriggered = true
                                offsetX = maxDrag
                                onStop()
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            if (!isTriggered) {
                                offsetX = (offsetX + dragAmount).coerceIn(0f, maxDrag)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "Slide Arrow",
                tint = Color.Black,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
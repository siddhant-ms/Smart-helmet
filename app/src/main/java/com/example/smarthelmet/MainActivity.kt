package com.example.smarthelmet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.smarthelmet.ui.theme.SmartHelmetTheme
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import com.google.android.gms.location.*
import org.json.JSONArray
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.compose.material3.FloatingActionButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import android.provider.ContactsContract
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import androidx.lifecycle.lifecycleScope
import java.util.UUID
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.text.style.TextAlign



data class Contact(
    val name: String,
    val number: String
)
sealed class BottomNavItem(val route: String, val title: String, val icon: ImageVector) {
    object Home : BottomNavItem("home", "Home", Icons.Default.Home)
    object Contacts : BottomNavItem("managecontacts", "Contacts", Icons.Default.Call)
    object Telemetry : BottomNavItem("telemetry", "Live Data", Icons.Default.LocationOn)
    object Helplines : BottomNavItem("helplines", "Helplines", Icons.Default.Warning)
}



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

            speed = if (speedKmh < 1.0) {
                "0.0"
            } else {
                String.format("%.1f", speedKmh)
            }
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000
        )
            .setMinUpdateIntervalMillis(500)
            .build()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun sendContactsToBluetooth(contacts: List<Contact>) {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            android.widget.Toast.makeText(
                this,
                "Bluetooth is disabled on your phone!",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        // 1. Check Permissions
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    101
                )
                android.widget.Toast.makeText(
                    this,
                    "Permission requested. Tap 'Save & Sync' again after allowing.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        // 2. Find the Device
        val device = bluetoothAdapter.bondedDevices.find { it.name == "SmartHelmet" }
        if (device == null) {
            android.widget.Toast.makeText(
                this,
                "SmartHelmet not found! Please pair it in Android settings first.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        android.widget.Toast.makeText(
            this,
            "Connecting to Helmet...",
            android.widget.Toast.LENGTH_SHORT
        ).show()

        // 3. Attempt Connection in Background
        // 3. Attempt Connection in Background
        lifecycleScope.launch(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()

                val contactString = contacts.joinToString(",") { "${it.name}|${it.number}" }
                val message = "CONTACTS:$contactString\n"

                socket.outputStream.write(message.toByteArray())
                socket.outputStream.flush()

                // THE FIX: Wait 1 second before hanging up so the ESP32 can process the text!
                kotlinx.coroutines.delay(1000)

                // Success Message
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "✅ Contacts Synced to Helmet!",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Failure Message
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "❌ Connection failed! Is the helmet turned on?",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                socket?.close()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide system bars
        enableEdgeToEdge()
        window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                        android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                100
            )
        }

        startLocationUpdates()

        setContent {
            SmartHelmetTheme {
                val navController = rememberNavController()

                // Box stacks elements on top of each other
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 1. Screen Content (Fills 100% of the full screen height)
                    NavHost(
                        navController = navController,
                        startDestination = BottomNavItem.Home.route,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable(BottomNavItem.Home.route) {
                            HomeScreen(navController)
                        }
                        composable(BottomNavItem.Contacts.route) {
                            ManageContactsScreen(navController)
                        }
                        composable(BottomNavItem.Telemetry.route) {
                            TelemetryScreen(navController, latitude, longitude, speed, accuracy)
                        }
                        composable(BottomNavItem.Helplines.route) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Helplines Screen Coming Soon", color = Color.White)
                            }
                        }
                    }

                    // 2. Navigation Bar (Overlaid at the bottom)
                    Box(
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
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

@Composable
fun HomeScreen(
    navController: NavController
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {

        Image(
            painter = painterResource(id = R.drawable.background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
    }
}


//hehehehhehe
@Composable
fun BottomNavigationBar(navController: NavController) {
    val items = listOf(
        BottomNavItem.Home,
        BottomNavItem.Contacts,
        BottomNavItem.Telemetry,
        BottomNavItem.Helplines
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Calculates which tab is active for the sliding outer glow
    val selectedIndex = items.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    val glowX by animateFloatAsState(targetValue = selectedIndex * 250f, label = "glowAnimation")

    // The Main Outer Bar
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
            .height(56.dp)
            .background(Color.Transparent)
            // OUTER BORDER: Uses the sliding glowX animation (No 'isSelected' check here)
            .border(
                width = 1.dp,
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0.0f to Color.White.copy(alpha = 0.35f), // Dimmer top-left glow
                        0.08f to Color.Transparent,              // Cuts the glow off quickly
                        0.92f to Color.Transparent,              // Keeps the entire middle totally clean
                        1.0f to Color.White.copy(alpha = 0.05f)  // Very faint bottom-right reflection
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                ),
                shape = RoundedCornerShape(percent = 50)
            )
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            // This is where 'isSelected' is actually defined!
            val isSelected = currentRoute == item.route

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null // Removes the default rectangular ripple
                    ) {
                        if (currentRoute != item.route) {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // INNER BOX: The 3D Glassy Oval Highlight (Wraps the Icon only)
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(28.dp)
                        .background(Color.Transparent) // Fully transparent center
                        // INNER BORDER: Uses 'isSelected' for the dual-edge reflection
                        .border(
                            width = if (isSelected) 1.dp else 0.dp,
                            brush = if (isSelected) {
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.7f), // Bright top-left reflection
                                        Color.Transparent,              // Clear middle
                                        Color.Transparent,              // Clear middle
                                        Color.White.copy(alpha = 0.4f)  // Secondary bottom-right reflection
                                    ),
                                    start = Offset(0f, 0f),
                                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                                )
                            } else {
                                Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                            },
                            shape = RoundedCornerShape(percent = 50)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.title,
                        tint = if (isSelected) Color.White else Color.Gray.copy(alpha = 0.8f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Text(
                    text = item.title,
                    fontSize = 11.sp,
                    color = if (isSelected) Color.White else Color.Gray.copy(alpha = 0.8f),
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}



@Composable
fun ManageContactsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("shelmet_contacts", android.content.Context.MODE_PRIVATE)
    val contacts = remember { mutableStateListOf<Contact>() }

    LaunchedEffect(Unit) {
        val saved = prefs.getString("contacts", null)
        if (saved != null) {
            val array = JSONArray(saved)
            contacts.clear()
            for (i in 0 until array.length()) {
                val parts = array.getString(i).split("|")
                if (parts.size == 2) {
                    contacts.add(Contact(parts[0], parts[1]))
                }
            }
        }
    }

    val contactPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult

        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
                val contactId = it.getString(idIndex)
                val name = it.getString(nameIndex)

                val phoneCursor = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    null,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?",
                    arrayOf(contactId),
                    null
                )

                phoneCursor?.use { pc ->
                    if (pc.moveToFirst()) {
                        val numberIndex = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val number = pc.getString(numberIndex)

                        if (contacts.size < 5 && contacts.none { c -> c.number == number }) {
                            contacts.add(Contact(name, number))
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090909))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                // INCREASED BOTTOM PADDING TO 80.dp SO FLOATING BAR DOESN'T COVER THE SAVE BUTTON
                .padding(top = 80.dp, bottom = 120.dp)
        ) {
            // The Top Header Texts
            Text(
                text = "Contact Manager",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )



            // The little "EMERGENCY CONTACTS" label with the dot
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color.Gray.copy(alpha = 0.8f), RoundedCornerShape(50))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "EMERGENCY CONTACTS",
                    color = Color.Gray.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp, // Spreads the letters out slightly
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Contact Cards Scrollable Area
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                contacts.forEach { contact ->
                    // The Glassy Contact Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xFF1E1E1E), // Slightly lighter top
                                        Color(0xFF0A0A0A)  // Deep shadow bottom
                                    )
                                ),
                                shape = RoundedCornerShape(20.dp) // Softer, rounder corners
                            )
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.08f), // Faint outer glare
                                shape = RoundedCornerShape(20.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left Avatar Icon
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(
                                        color = Color.White.copy(alpha = 0.05f),
                                        shape = RoundedCornerShape(percent = 50)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = Color.White.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(percent = 50)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "User Icon",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Center Text (Name & Number)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 16.dp)
                            ) {
                                Text(
                                    text = contact.name,
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = contact.number,
                                    color = Color.Gray,
                                    fontSize = 13.sp
                                )
                            }

                            // Right Delete Icon (Trash Can)
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(
                                        color = Color.White.copy(alpha = 0.05f),
                                        shape = RoundedCornerShape(percent = 50)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = Color.White.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(percent = 50)
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        contacts.remove(contact)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete Contact",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            // Unified Glassy Action Pill (Save & Add)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                // Cranked up to 35% opacity for a much brighter internal glow
                                Color(0xFF7ED4E0).copy(alpha = 0.35f),
                                // Fades to 15% at the bottom so it still feels deep, but stays bright
                                Color(0xFF7ED4E0).copy(alpha = 0.15f)
                            )
                        ),
                        shape = RoundedCornerShape(percent = 50)
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.5f),  // Bright top glare
                                Color.White.copy(alpha = 0.25f), // Keeps the glow alive along the sides
                                Color.White.copy(alpha = 0.4f)   // Bright bottom glare
                            )
                        ),
                        shape = RoundedCornerShape(percent = 50)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // Trigger the Save & Sync logic when clicking the main pill
                        val jsonArray = JSONArray()
                        contacts.forEach { jsonArray.put("${it.name}|${it.number}") }

                        prefs.edit()
                            .putString("contacts", jsonArray.toString())
                            .apply()

                        (context as? MainActivity)?.sendContactsToBluetooth(contacts)
                    }
            ) {
                // Perfectly centered Save text
                Text(
                    text = "Save & Sync to Helmet",
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.Center)
                )

                // Circular "+" Add Button nested on the right side
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp)
                        .size(48.dp)
                        .background(
                            color = Color(0xFF7ED4E0).copy(alpha = 0.15f), // Soft cyan inner glow
                            shape = RoundedCornerShape(percent = 50)
                        )
                        .border(
                            width = 1.dp,
                            color = Color(0xFF7ED4E0).copy(alpha = 0.4f), // Sharp cyan rim light
                            shape = RoundedCornerShape(percent = 50)
                        )
                        .clickable {
                            contactPicker.launch(null)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        color = Color(0xFF7ED4E0), // Vivid cyan text
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            // Main Save & Sync Button at bottom

        }



    }
}
            // heheheheheheh
@Composable
fun TelemetryScreen(
    navController: NavController,
    latitude: String,
    longitude: String,
    speed: String,
    accuracy: String
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090909))
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        Text(
            text = "",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        CardPanel {

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {

                Text(
                    speed,
                    color = Color.White,
                    fontSize = 80.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Text(
                    "KM/H",
                    color = Color(0xFFFF8800),
                    fontSize = 20.sp
                )
            }
        }

        DataCard(
            title = "LATITUDE",
            value = latitude
        )

        DataCard(
            title = "LONGITUDE",
            value = longitude
        )

        DataCard(
            title = "GPS ACCURACY",
            value = "±$accuracy m"
        )

        DataCard(
            title = "SYSTEM STATUS",
            value = "Active"
        )
    }
}





@Composable
fun CardPanel(
    content: @Composable () -> Unit
) {

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                Color(0xFFFF8800),
                RoundedCornerShape(12.dp)
            )
            .padding(20.dp)
    ) {
        content()
    }
}


@Composable
fun DataCard(
    title: String,
    value: String
) {

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                Color.DarkGray,
                RoundedCornerShape(12.dp)
            )
            .padding(20.dp)
    ) {

        Column {

            Text(
                text = title,
                color = Color(0xFFFF8800),
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = value,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}


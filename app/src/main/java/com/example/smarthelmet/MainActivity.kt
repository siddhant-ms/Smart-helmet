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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.currentBackStackEntryAsState

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

        fusedLocationClient =
            LocationServices.getFusedLocationProviderClient(this)
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

                // Scaffold provides the structural layout for the bottom bar
                Scaffold(
                    bottomBar = {
                        // We will build this function in Step 2!
                        // BottomNavigationBar(navController = navController)
                    },
                    containerColor = Color(0xFF090909) // Keeps the dark theme background
                ) { innerPadding ->

                    // The NavHost handles screen swapping while leaving the bottom bar intact
                    NavHost(
                        navController = navController,
                        startDestination = BottomNavItem.Home.route,
                        modifier = Modifier.padding(innerPadding) // Prevents content from hiding behind the bar
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
                            // We will build this screen in Step 3!
                            // HelplinesScreen()
                        }
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

        Button(
            onClick = {
                navController.navigate("intermediate")
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .fillMaxWidth(0.5f)
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.2f),
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("CONTINUE")
        }
    }
}


//hehehehhehe



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
                .padding(top = 80.dp, bottom = 16.dp)
        ) {
            Text(
                text = "CONTACT MANAGER",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Contact Cards Scrollable Area
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                contacts.forEach { contact ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .border(1.dp, Color.DarkGray, RoundedCornerShape(12.dp))
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Text Column gets weight(1f) so it expands to fit names/numbers horizontally
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = contact.name,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = contact.number,
                                    color = Color.Gray,
                                    fontSize = 14.sp
                                )
                            }

                            // Clean Delete Button per item
                            Button(
                                onClick = { contacts.remove(contact) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Red.copy(alpha = 0.2f),
                                    contentColor = Color.Red
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Delete", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Save & Sync Button at bottom
            Button(
                onClick = {
                    val jsonArray = JSONArray()
                    contacts.forEach { jsonArray.put("${it.name}|${it.number}") }

                    prefs.edit()
                        .putString("contacts", jsonArray.toString())
                        .apply()

                    (context as? MainActivity)?.sendContactsToBluetooth(contacts)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8800)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Save & Sync to Helmet", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        // Floating Action Button (+)
        FloatingActionButton(
            onClick = { contactPicker.launch(null) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 80.dp),
            containerColor = Color(0xFFFF8800)
        ) {
            Text("+", fontSize = 28.sp, color = Color.White)
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
            .padding(20.dp),
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


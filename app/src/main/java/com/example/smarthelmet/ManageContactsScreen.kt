package com.example.smarthelmet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.smarthelmet.models.Contact
import org.json.JSONArray

@Composable
fun ManageContactsScreen(
    navController: NavController
) {
    val context = LocalContext.current

    val prefs = remember {
        context.getSharedPreferences(
            "shelmet_contacts",
            Context.MODE_PRIVATE
        )
    }

    val contacts = remember {
        mutableStateListOf<Contact>()
    }

    var riderName by rememberSaveable {
        mutableStateOf("")
    }

    // true = show the red "enter a name" error text
    var nameError by remember {
        mutableStateOf(false)
    }

    val scrollState = rememberScrollState()

    // --------------------------------------------------------
    // Load saved rider name + contacts once on first compose.
    // --------------------------------------------------------

    LaunchedEffect(Unit) {
        riderName = prefs.getString("rider_name", "") ?: ""

        val saved = prefs.getString("contacts", null)

        if (!saved.isNullOrBlank()) {
            try {
                val array = JSONArray(saved)

                contacts.clear()

                for (i in 0 until array.length()) {
                    val value = array.optString(i)

                    if (value.isBlank()) continue

                    val separatorIndex = value.indexOf('|')

                    if (
                        separatorIndex <= 0 ||
                        separatorIndex >= value.length - 1
                    ) continue

                    val name =
                        value.substring(0, separatorIndex).trim()

                    val number =
                        value.substring(separatorIndex + 1).trim()

                    if (name.isNotBlank() && number.isNotBlank()) {
                        contacts.add(Contact(name, number))
                    }
                }
            } catch (_: Exception) {
                contacts.clear()
            }
        }
    }

    // --------------------------------------------------------
    // Helpers
    // --------------------------------------------------------

    fun saveContactsLocally() {
        val jsonArray = JSONArray()

        contacts.forEach { contact ->
            jsonArray.put("${contact.name}|${contact.number}")
        }

        prefs.edit()
            .putString("contacts", jsonArray.toString())
            .putString("rider_name", riderName.trim())
            .apply()
    }

    val contactPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickContact()
        ) { uri: Uri? ->

            uri ?: return@rememberLauncherForActivityResult

            try {
                val cursor = context.contentResolver.query(
                    uri, null, null, null, null
                )

                cursor?.use { contactCursor ->

                    if (!contactCursor.moveToFirst()) return@use

                    val nameIndex = contactCursor.getColumnIndex(
                        ContactsContract.Contacts.DISPLAY_NAME
                    )

                    val idIndex = contactCursor.getColumnIndex(
                        ContactsContract.Contacts._ID
                    )

                    if (nameIndex == -1 || idIndex == -1) return@use

                    val contactId = contactCursor.getString(idIndex)
                    val name = contactCursor.getString(nameIndex) ?: return@use

                    val phoneCursor = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?",
                        arrayOf(contactId),
                        null
                    )

                    phoneCursor?.use { phone ->

                        if (!phone.moveToFirst()) return@use

                        val numberIndex = phone.getColumnIndex(
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                        )

                        if (numberIndex == -1) return@use

                        val number = phone.getString(numberIndex) ?: return@use

                        val normalizedNumber = number.trim()

                        if (
                            contacts.size < 5 &&
                            contacts.none { it.number == normalizedNumber }
                        ) {
                            contacts.add(Contact(name.trim(), normalizedNumber))
                        }
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }

    val contactsPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) contactPicker.launch(null)
        }

    fun addContact() {
        if (contacts.size >= 5) return

        val permissionGranted =
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED

        if (permissionGranted) {
            contactPicker.launch(null)
        } else {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    // --------------------------------------------------------
    // UI
    // --------------------------------------------------------

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090909))
    ) {
        val screenHeight = maxHeight

        val topSpacing = (screenHeight * 0.075f).coerceIn(48.dp, 80.dp)
        val bottomSpacing = (screenHeight * 0.105f).coerceIn(88.dp, 116.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = topSpacing, bottom = bottomSpacing)
        ) {

            // Title
            Text(
                text = "Contact Manager",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ------------------------------------------------
            // Rider name section
            // ------------------------------------------------

            Text(
                text = "RIDER NAME",
                color = Color.Gray.copy(alpha = 0.8f),
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = riderName,
                onValueChange = {
                    riderName = it
                    // Clear error as soon as the user starts typing
                    if (it.isNotBlank()) nameError = false
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = nameError,
                placeholder = {
                    Text(
                        text = "Enter rider name",
                        color = Color.Gray.copy(alpha = 0.55f),
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Rider Name",
                        tint = if (nameError)
                            Color(0xFFFF6B6B).copy(alpha = 0.9f)
                        else
                            Color.White.copy(alpha = 0.75f)
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    errorTextColor = Color.White,
                    focusedContainerColor = Color.White.copy(alpha = 0.025f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.025f),
                    errorContainerColor = Color(0xFFFF6B6B).copy(alpha = 0.05f),
                    focusedIndicatorColor = Color(0xFF7ED4E0).copy(alpha = 0.7f),
                    unfocusedIndicatorColor = Color.White.copy(alpha = 0.08f),
                    errorIndicatorColor = Color(0xFFFF6B6B).copy(alpha = 0.7f),
                    cursorColor = Color(0xFF7ED4E0),
                    errorCursorColor = Color(0xFFFF6B6B),
                    focusedLeadingIconColor = Color.White.copy(alpha = 0.75f),
                    unfocusedLeadingIconColor = Color.White.copy(alpha = 0.75f),
                    errorLeadingIconColor = Color(0xFFFF6B6B).copy(alpha = 0.9f)
                ),
                shape = RoundedCornerShape(20.dp)
            )

            // Error message — only shown when nameError is true
            if (nameError) {
                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Enter a rider name before syncing.",
                    color = Color(0xFFFF6B6B),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ------------------------------------------------
            // Emergency contacts section label
            // ------------------------------------------------

            Row(verticalAlignment = Alignment.CenterVertically) {

                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            Color.Gray.copy(alpha = 0.8f),
                            RoundedCornerShape(50)
                        )
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "EMERGENCY CONTACTS",
                    color = Color.Gray.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ------------------------------------------------
            // Scrollable contact list
            // ------------------------------------------------

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {

                if (contacts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(
                                Color.White.copy(alpha = 0.025f),
                                RoundedCornerShape(20.dp)
                            )
                            .border(
                                1.dp,
                                Color.White.copy(alpha = 0.06f),
                                RoundedCornerShape(20.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No emergency contacts added",
                            color = Color.Gray.copy(alpha = 0.8f),
                            fontSize = 14.sp
                        )
                    }
                }

                contacts.forEach { contact ->

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xFF1E1E1E),
                                        Color(0xFF0A0A0A)
                                    )
                                ),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .border(
                                1.dp,
                                Color.White.copy(alpha = 0.08f),
                                RoundedCornerShape(20.dp)
                            )
                            .padding(16.dp)
                    ) {

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {

                            // Contact avatar
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(
                                        Color.White.copy(alpha = 0.05f),
                                        RoundedCornerShape(50)
                                    )
                                    .border(
                                        1.dp,
                                        Color.White.copy(alpha = 0.1f),
                                        RoundedCornerShape(50)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "Contact",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Name + number
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

                            // Delete button
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(
                                        Color.White.copy(alpha = 0.05f),
                                        RoundedCornerShape(50)
                                    )
                                    .border(
                                        1.dp,
                                        Color.White.copy(alpha = 0.1f),
                                        RoundedCornerShape(50)
                                    )
                                    .clickable(
                                        interactionSource = remember {
                                            MutableInteractionSource()
                                        },
                                        indication = null
                                    ) {
                                        contacts.remove(contact)
                                        saveContactsLocally()
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

            // ------------------------------------------------
            // Save & Sync + Add Contact button
            // ------------------------------------------------

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF7ED4E0).copy(alpha = 0.35f),
                                Color(0xFF7ED4E0).copy(alpha = 0.15f)
                            )
                        ),
                        shape = RoundedCornerShape(50)
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.5f),
                                Color.White.copy(alpha = 0.25f),
                                Color.White.copy(alpha = 0.4f)
                            )
                        ),
                        shape = RoundedCornerShape(50)
                    )
                    .clickable(
                        interactionSource = remember {
                            MutableInteractionSource()
                        },
                        indication = null
                    ) {
                        val trimmedName = riderName.trim()

                        // Guard: name must not be blank
                        if (trimmedName.isBlank()) {
                            nameError = true
                            return@clickable
                        }

                        // All good — persist then sync
                        nameError = false
                        saveContactsLocally()

                        (context as? MainActivity)?.sendContactsToBluetooth(
                            contacts,
                            trimmedName
                        )
                    }
            ) {

                Text(
                    text = "Save & Sync to Helmet",
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.Center)
                )

                // Add contact (+) button nested inside the action bar
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp)
                        .size(48.dp)
                        .background(
                            Color(0xFF7ED4E0).copy(alpha = 0.15f),
                            RoundedCornerShape(50)
                        )
                        .border(
                            1.dp,
                            Color(0xFF7ED4E0).copy(alpha = 0.4f),
                            RoundedCornerShape(50)
                        )
                        .clickable(
                            interactionSource = remember {
                                MutableInteractionSource()
                            },
                            indication = null
                        ) {
                            addContact()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        color = Color(0xFF7ED4E0),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
package com.example.smarthelmet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.example.smarthelmet.models.Contact
import org.json.JSONArray

@Composable
fun ManageContactsScreen(
    navController: NavController
) {
    val context = LocalContext.current

    val prefs =
        remember {
            context.getSharedPreferences(
                "shelmet_contacts",
                Context.MODE_PRIVATE
            )
        }

    val contacts =
        remember {
            mutableStateListOf<Contact>()
        }

    /*
     * Load previously saved contacts once when the screen enters.
     *
     * The old implementation assumed the stored JSON was always valid.
     * A malformed/older entry could therefore crash the screen.
     */
    LaunchedEffect(Unit) {
        try {
            val saved =
                prefs.getString(
                    "contacts",
                    null
                )

            if (saved != null) {
                val array =
                    JSONArray(saved)

                contacts.clear()

                for (i in 0 until array.length()) {
                    val value =
                        array.optString(i)

                    if (value.isBlank()) {
                        continue
                    }

                    /*
                     * Preserve compatibility with the current storage format:
                     *
                     * "name|number"
                     */
                    val parts =
                        value.split(
                            "|",
                            limit = 2
                        )

                    if (
                        parts.size == 2 &&
                        parts[0].isNotBlank() &&
                        parts[1].isNotBlank()
                    ) {
                        contacts.add(
                            Contact(
                                parts[0],
                                parts[1]
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            contacts.clear()

            Toast.makeText(
                context,
                "Saved contacts could not be loaded.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /*
     * Contact picker.
     */
    val contactPicker =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.PickContact()
        ) { uri: Uri? ->

            uri ?: return@rememberLauncherForActivityResult

            try {
                val cursor =
                    context.contentResolver.query(
                        uri,
                        null,
                        null,
                        null,
                        null
                    )

                cursor?.use { contactCursor ->

                    if (!contactCursor.moveToFirst()) {
                        return@use
                    }

                    val nameIndex =
                        contactCursor.getColumnIndex(
                            ContactsContract.Contacts.DISPLAY_NAME
                        )

                    val idIndex =
                        contactCursor.getColumnIndex(
                            ContactsContract.Contacts._ID
                        )

                    if (
                        nameIndex == -1 ||
                        idIndex == -1
                    ) {
                        return@use
                    }

                    val contactId =
                        contactCursor.getString(
                            idIndex
                        )

                    val name =
                        contactCursor.getString(
                            nameIndex
                        )

                    /*
                     * Query the selected contact's phone numbers.
                     */
                    val phoneCursor =
                        context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?",
                            arrayOf(contactId),
                            null
                        )

                    phoneCursor?.use { pc ->

                        if (!pc.moveToFirst()) {
                            Toast.makeText(
                                context,
                                "No phone number found for this contact.",
                                Toast.LENGTH_SHORT
                            ).show()

                            return@use
                        }

                        val numberIndex =
                            pc.getColumnIndex(
                                ContactsContract.CommonDataKinds.Phone.NUMBER
                            )

                        if (numberIndex == -1) {
                            return@use
                        }

                        val number =
                            pc.getString(
                                numberIndex
                            )

                        when {
                            contacts.size >= 5 -> {
                                Toast.makeText(
                                    context,
                                    "You can add up to 5 emergency contacts.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            contacts.any {
                                it.number == number
                            } -> {
                                Toast.makeText(
                                    context,
                                    "This contact is already added.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            else -> {
                                contacts.add(
                                    Contact(
                                        name,
                                        number
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                Toast.makeText(
                    context,
                    "Contact permission is required to read this contact.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Unable to read the selected contact.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    /*
     * Runtime READ_CONTACTS permission.
     *
     * We request it only when the user actually tries to add a contact.
     */
    val readContactsPermissionLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                contactPicker.launch(null)
            } else {
                Toast.makeText(
                    context,
                    "Contacts permission is required to add emergency contacts.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    fun launchContactPicker() {
        val granted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            contactPicker.launch(null)
        } else {
            readContactsPermissionLauncher.launch(
                Manifest.permission.READ_CONTACTS
            )
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Color(0xFF090909)
                )
    ) {

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .padding(
                        top = 80.dp,
                        bottom = 120.dp
                    )
        ) {

            Text(
                text = "Contact Manager",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight =
                    FontWeight.Bold
            )

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Box(
                    modifier =
                        Modifier
                            .size(6.dp)
                            .background(
                                Color.Gray.copy(
                                    alpha = 0.8f
                                ),
                                RoundedCornerShape(50)
                            )
                )

                Spacer(
                    modifier =
                        Modifier.width(8.dp)
                )

                Text(
                    text = "EMERGENCY CONTACTS",
                    color =
                        Color.Gray.copy(
                            alpha = 0.8f
                        ),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    fontWeight =
                        FontWeight.SemiBold
                )
            }

            Spacer(
                modifier =
                    Modifier.height(16.dp)
            )

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(
                            rememberScrollState()
                        )
            ) {

                contacts.forEach { contact ->

                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    vertical = 6.dp
                                )
                                .background(
                                    brush =
                                        Brush.verticalGradient(
                                            colors =
                                                listOf(
                                                    Color(0xFF1E1E1E),
                                                    Color(0xFF0A0A0A)
                                                )
                                        ),
                                    shape =
                                        RoundedCornerShape(
                                            20.dp
                                        )
                                )
                                .border(
                                    width = 1.dp,
                                    color =
                                        Color.White.copy(
                                            alpha = 0.08f
                                        ),
                                    shape =
                                        RoundedCornerShape(
                                            20.dp
                                        )
                                )
                                .padding(16.dp)
                    ) {

                        Row(
                            modifier =
                                Modifier.fillMaxWidth(),
                            verticalAlignment =
                                Alignment.CenterVertically,
                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {

                            Box(
                                modifier =
                                    Modifier
                                        .size(42.dp)
                                        .background(
                                            color =
                                                Color.White.copy(
                                                    alpha = 0.05f
                                                ),
                                            shape =
                                                RoundedCornerShape(
                                                    percent = 50
                                                )
                                        )
                                        .border(
                                            width = 1.dp,
                                            color =
                                                Color.White.copy(
                                                    alpha = 0.1f
                                                ),
                                            shape =
                                                RoundedCornerShape(
                                                    percent = 50
                                                )
                                        ),
                                contentAlignment =
                                    Alignment.Center
                            ) {

                                Icon(
                                    imageVector =
                                        Icons.Default.Person,
                                    contentDescription =
                                        "User Icon",
                                    tint =
                                        Color.White,
                                    modifier =
                                        Modifier.size(
                                            20.dp
                                        )
                                )
                            }

                            Column(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .padding(
                                            horizontal = 16.dp
                                        )
                            ) {

                                Text(
                                    text =
                                        contact.name,
                                    color =
                                        Color.White,
                                    fontSize =
                                        17.sp,
                                    fontWeight =
                                        FontWeight.Medium
                                )

                                Spacer(
                                    modifier =
                                        Modifier.height(2.dp)
                                )

                                Text(
                                    text =
                                        contact.number,
                                    color =
                                        Color.Gray,
                                    fontSize =
                                        13.sp
                                )
                            }

                            Box(
                                modifier =
                                    Modifier
                                        .size(42.dp)
                                        .background(
                                            color =
                                                Color.White.copy(
                                                    alpha = 0.05f
                                                ),
                                            shape =
                                                RoundedCornerShape(
                                                    percent = 50
                                                )
                                        )
                                        .border(
                                            width = 1.dp,
                                            color =
                                                Color.White.copy(
                                                    alpha = 0.1f
                                                ),
                                            shape =
                                                RoundedCornerShape(
                                                    percent = 50
                                                )
                                        )
                                        .clickable(
                                            interactionSource =
                                                remember {
                                                    MutableInteractionSource()
                                                },
                                            indication = null
                                        ) {
                                            contacts.remove(
                                                contact
                                            )
                                        },
                                contentAlignment =
                                    Alignment.Center
                            ) {

                                Icon(
                                    imageVector =
                                        Icons.Default.Delete,
                                    contentDescription =
                                        "Delete Contact",
                                    tint =
                                        Color.White,
                                    modifier =
                                        Modifier.size(
                                            18.dp
                                        )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(
                modifier =
                    Modifier.height(16.dp)
            )

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(
                            brush =
                                Brush.verticalGradient(
                                    colors =
                                        listOf(
                                            Color(0xFF7ED4E0)
                                                .copy(alpha = 0.35f),
                                            Color(0xFF7ED4E0)
                                                .copy(alpha = 0.15f)
                                        )
                                ),
                            shape =
                                RoundedCornerShape(
                                    percent = 50
                                )
                        )
                        .border(
                            width = 1.dp,
                            brush =
                                Brush.verticalGradient(
                                    colors =
                                        listOf(
                                            Color.White.copy(
                                                alpha = 0.5f
                                            ),
                                            Color.White.copy(
                                                alpha = 0.25f
                                            ),
                                            Color.White.copy(
                                                alpha = 0.4f
                                            )
                                        )
                                ),
                            shape =
                                RoundedCornerShape(
                                    percent = 50
                                )
                        )
                        .clickable(
                            interactionSource =
                                remember {
                                    MutableInteractionSource()
                                },
                            indication = null
                        ) {

                            val jsonArray =
                                JSONArray()

                            contacts.forEach { contact ->
                                jsonArray.put(
                                    "${contact.name}|${contact.number}"
                                )
                            }

                            /*
                             * Save locally first.
                             */
                            prefs.edit()
                                .putString(
                                    "contacts",
                                    jsonArray.toString()
                                )
                                .apply()

                            /*
                             * Existing rider-name behavior is preserved.
                             * getDeviceOwnerName() already has a fallback.
                             */
                            val autoRiderName =
                                getDeviceOwnerName(
                                    context
                                )

                            /*
                             * Existing Bluetooth path is preserved.
                             */
                            (
                                    context as? MainActivity
                                    )?.sendContactsToBluetooth(
                                    contacts,
                                    autoRiderName
                                )
                        }
            ) {

                Text(
                    text =
                        "Save & Sync to Helmet",
                    color =
                        Color.White,
                    fontWeight =
                        FontWeight.Medium,
                    fontSize =
                        15.sp,
                    modifier =
                        Modifier.align(
                            Alignment.Center
                        )
                )

                Box(
                    modifier =
                        Modifier
                            .align(
                                Alignment.CenterEnd
                            )
                            .padding(
                                end = 8.dp
                            )
                            .size(48.dp)
                            .background(
                                color =
                                    Color(0xFF7ED4E0).copy(
                                        alpha = 0.15f
                                    ),
                                shape =
                                    RoundedCornerShape(
                                        percent = 50
                                    )
                            )
                            .border(
                                width = 1.dp,
                                color =
                                    Color(0xFF7ED4E0).copy(
                                        alpha = 0.4f
                                    ),
                                shape =
                                    RoundedCornerShape(
                                        percent = 50
                                    )
                            )
                            .clickable {
                                launchContactPicker()
                            },
                    contentAlignment =
                        Alignment.Center
                ) {

                    Text(
                        text = "+",
                        color =
                            Color(0xFF7ED4E0),
                        fontSize = 26.sp,
                        fontWeight =
                            FontWeight.Medium
                    )
                }
            }
        }
    }
}

fun getDeviceOwnerName(
    context: Context
): String {

    /*
     * Keep the existing fallback behavior.
     *
     * Profile information is more restricted than ordinary contact
     * data, so failure to read it must never break contact syncing.
     */
    var ownerName =
        "the rider"

    val uri =
        ContactsContract.Profile.CONTENT_URI

    val projection =
        arrayOf(
            ContactsContract.Profile.DISPLAY_NAME_PRIMARY
        )

    try {
        context.contentResolver
            .query(
                uri,
                projection,
                null,
                null,
                null
            )
            ?.use { cursor ->

                if (cursor.moveToFirst()) {

                    val nameIndex =
                        cursor.getColumnIndex(
                            ContactsContract.Profile.DISPLAY_NAME_PRIMARY
                        )

                    if (nameIndex != -1) {

                        val retrievedName =
                            cursor.getString(
                                nameIndex
                            )

                        if (
                            !retrievedName.isNullOrBlank()
                        ) {
                            ownerName =
                                retrievedName
                        }
                    }
                }
            }

    } catch (_: SecurityException) {
        /*
         * Profile access is optional.
         * Fall back to "the rider".
         */
    } catch (_: Exception) {
        /*
         * A profile lookup must never break the sync flow.
         */
    }

    return ownerName
}
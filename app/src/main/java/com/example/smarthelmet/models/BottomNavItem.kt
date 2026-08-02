package com.example.smarthelmet.models

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector

sealed class BottomNavItem(val route: String, val title: String, val icon: ImageVector) {
    object Home : BottomNavItem("home", "Home", Icons.Default.Home)
    object Contacts : BottomNavItem("managecontacts", "Contacts", Icons.Default.Call)
    object Telemetry : BottomNavItem("telemetry", "Live Data", Icons.Default.LocationOn)
    object Helplines : BottomNavItem("helplines", "Helplines", Icons.Default.Warning)
}
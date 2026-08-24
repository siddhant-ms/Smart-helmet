package com.example.smarthelmet.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.maplibre.geojson.Point

@Entity(tableName = "ride_history")
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startTime: Long,
    val durationMs: Long,
    val distanceKm: Float,
    val maxSpeedKmh: Float,
    val rawRoutePoints: List<Point>,
    val matchedRoutePoints: List<Point>,
    val speedHistory: List<TelemetryPoint> = emptyList() // empty for rides saved before v3, no lat/lng for rides saved before this change
)
package com.example.smarthelmet.database

import androidx.room.TypeConverter
import org.maplibre.geojson.Point

class PointTypeConverter {
    @TypeConverter
    fun fromPointList(points: List<Point>?): String {
        if (points.isNullOrEmpty()) return ""
        return points.joinToString(";") { "${it.longitude()},${it.latitude()}" }
    }

    @TypeConverter
    fun toPointList(data: String?): List<Point>  {
        if (data.isNullOrEmpty()) return emptyList()
        return data.split(";").mapNotNull {
            val coords = it.split(",")
            if (coords.size == 2) {
                Point.fromLngLat(coords[0].toDouble(), coords[1].toDouble())
            } else null
        }
    }
}

// Converter for the speedHistory column added in Migration(2, 3).
// Format is now "timeSec,speedKmh,lat,lng;timeSec,speedKmh,lat,lng;..." when location
// is known, same convention as PointTypeConverter. Rows written before this change
// only have "timeSec,speedKmh" (2 fields) — those still parse fine, just with
// lat/lng left null. "" (the migration's original DEFAULT) still round-trips to
// emptyList(). No Room schema change needed: the column stays TEXT either way.
class TelemetryHistoryConverter {
    @TypeConverter
    fun fromTelemetryHistory(points: List<TelemetryPoint>?): String {
        if (points.isNullOrEmpty()) return ""
        return points.joinToString(";") { p ->
            if (p.hasLocation) "${p.timeSec},${p.speedKmh},${p.lat},${p.lng}"
            else "${p.timeSec},${p.speedKmh}"
        }
    }

    @TypeConverter
    fun toTelemetryHistory(data: String?): List<TelemetryPoint> {
        if (data.isNullOrEmpty()) return emptyList()
        return data.split(";").mapNotNull { entry ->
            val parts = entry.split(",")
            when (parts.size) {
                2 -> {
                    val t = parts[0].toIntOrNull()
                    val s = parts[1].toFloatOrNull()
                    if (t != null && s != null) TelemetryPoint(t, s) else null
                }
                4 -> {
                    val t = parts[0].toIntOrNull()
                    val s = parts[1].toFloatOrNull()
                    val lat = parts[2].toDoubleOrNull()
                    val lng = parts[3].toDoubleOrNull()
                    if (t != null && s != null && lat != null && lng != null)
                        TelemetryPoint(t, s, lat, lng)
                    else null
                }
                else -> null
            }
        }
    }
}
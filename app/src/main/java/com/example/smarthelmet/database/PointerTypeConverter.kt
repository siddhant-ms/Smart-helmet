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
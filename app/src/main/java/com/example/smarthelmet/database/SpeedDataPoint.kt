package com.example.smarthelmet.database

/**
 * A single speed sample for the Speed-vs-Time graph.
 *
 * @param timeSec  Seconds elapsed since ride start (relative, not epoch millis —
 *                 keeps the stored string short and makes the graph's x-axis trivial).
 * @param speedKmh Speed at that moment, already in km/h.
 */
data class SpeedDataPoint(
    val timeSec: Int,
    val speedKmh: Float
)
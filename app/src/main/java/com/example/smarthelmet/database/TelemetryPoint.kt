package com.example.smarthelmet.database

/**
 * A single telemetry sample: time + speed + (optionally) location, all at once.
 *
 * Replaces SpeedDataPoint. Adding lat/lng here — instead of trying to correlate
 * the speed graph against rawRoutePoints/matchedRoutePoints after the fact — is
 * what makes the graph-to-map scrubber and the speed-colored trail possible:
 * every sample already knows exactly where it happened, so there's no timestamp
 * matching or OSRM index correlation to worry about.
 *
 * @param timeSec  Seconds elapsed since ride start (relative, not epoch millis).
 * @param speedKmh Speed at that moment, already in km/h.
 * @param lat/lng  GPS location at that moment. Null for samples captured before
 *                 location-tagging existed (old v3 rides, or dummy backfilled
 *                 data) — those points just won't take part in the color-coded
 *                 trail or the scrub marker, same way old rides fall back
 *                 gracefully today when speedHistory is empty.
 */
data class TelemetryPoint(
    val timeSec: Int,
    val speedKmh: Float,
    val lat: Double? = null,
    val lng: Double? = null
) {
    val hasLocation: Boolean get() = lat != null && lng != null
}
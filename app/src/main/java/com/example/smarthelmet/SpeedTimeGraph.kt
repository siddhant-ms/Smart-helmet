package com.example.smarthelmet

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarthelmet.database.TelemetryPoint
import kotlin.math.abs
import kotlin.math.roundToInt

private val GraphCyan = Color(0xFF7ED4E0)

@Composable
fun SpeedTimeGraph(
    data: List<TelemetryPoint>,
    startTimeMs: Long,
    modifier: Modifier = Modifier,
    onScrub: (TelemetryPoint?) -> Unit = {}
) {
    if (data.size < 2) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(200.dp), // Height reduced to 200.dp
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Speed graph unavailable for older rides.",
                color = Color.Gray,
                fontSize = 13.sp
            )
        }
        return
    }

    val maxSpeed = data.maxOf { it.speedKmh }.coerceAtLeast(1f)
    val minTime = data.first().timeSec
    val maxTime = data.last().timeSec.coerceAtLeast(minTime + 1)
    val timeRange = (maxTime - minTime).coerceAtLeast(1)

    // Shared with the draw block below — kept as the same literal values so the
    // scrub-to-point math here matches the pixel math used for drawing.
    val leftMargin = 100f
    val rightMargin = 40f

    var scrubX by remember { mutableStateOf<Float?>(null) }
    var canvasWidthPx by remember { mutableStateOf(0f) }

    // Resolves the dragged pixel position to the nearest TelemetryPoint and reports
    // it upward, so TelemetryScreen can move a marker on the map in sync. Runs
    // outside the Canvas draw phase on purpose — draw runs every frame, this only
    // needs to run when the scrub position actually changes.
    LaunchedEffect(scrubX, canvasWidthPx, data) {
        val width = canvasWidthPx
        val x = scrubX
        if (x == null || width <= 0f) {
            onScrub(null)
        } else {
            val graphWidth = width - leftMargin - rightMargin
            val boundedX = x.coerceIn(leftMargin, width - rightMargin)
            val fraction = ((boundedX - leftMargin) / graphWidth).coerceIn(0f, 1f)
            val targetTime = minTime + (fraction * timeRange).toInt()
            onScrub(data.minByOrNull { abs(it.timeSec - targetTime) })
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp) // Height reduced to 200.dp
            .onSizeChanged { canvasWidthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> scrubX = offset.x },
                    onDragEnd = { scrubX = null },
                    onDragCancel = { scrubX = null },
                    onHorizontalDrag = { change, _ ->
                        scrubX = change.position.x
                    }
                )
            }
    ) {
        val widthPx = size.width
        val heightPx = size.height

        val leftMargin = 100f
        val bottomMargin = 80f
        val topMargin = 60f
        val rightMargin = 40f

        val graphWidth = widthPx - leftMargin - rightMargin
        val graphHeight = heightPx - bottomMargin - topMargin

        fun xFor(t: Int): Float =
            leftMargin + ((t - minTime).toFloat() / timeRange.toFloat() * graphWidth)

        fun yFor(speedKmh: Float): Float =
            topMargin + graphHeight - ((speedKmh / maxSpeed) * graphHeight)

        val textPaint = Paint().apply {
            color = android.graphics.Color.LTGRAY
            textSize = 32f
            isAntiAlias = true
        }

        // 1. Draw Y-Axis (Speed)
        val intervals = 4
        for (i in 0..intervals) {
            val speedVal = maxSpeed * (i.toFloat() / intervals)
            val y = yFor(speedVal)

            drawLine(
                color = Color.White.copy(alpha = 0.1f),
                start = Offset(leftMargin, y),
                end = Offset(widthPx - rightMargin, y),
                strokeWidth = 2f
            )

            drawContext.canvas.nativeCanvas.drawText(
                speedVal.roundToInt().toString(),
                10f,
                y + 10f,
                textPaint
            )
        }
        drawContext.canvas.nativeCanvas.drawText("km/h", 10f, topMargin - 20f, textPaint)

        // 2. Draw X-Axis (Real IST Time)
        val formatTime = { seconds: Int ->
            val epochMs = startTimeMs + (seconds * 1000L)
            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
            }
            sdf.format(java.util.Date(epochMs))
        }

        val timeLabels = listOf(minTime, minTime + (timeRange / 2), maxTime)
        timeLabels.forEach { t ->
            val x = xFor(t)
            drawContext.canvas.nativeCanvas.drawText(
                formatTime(t),
                x - 40f,
                heightPx - 20f,
                textPaint
            )
        }

        // 3. Draw The Line Graph
        val linePath = Path()
        val fillPath = Path()

        data.forEachIndexed { index, point ->
            val x = xFor(point.timeSec)
            val y = yFor(point.speedKmh)
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, topMargin + graphHeight)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        val lastX = xFor(data.last().timeSec)
        fillPath.lineTo(lastX, topMargin + graphHeight)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(GraphCyan.copy(alpha = 0.4f), GraphCyan.copy(alpha = 0f)),
                startY = topMargin,
                endY = topMargin + graphHeight
            )
        )

        drawPath(
            path = linePath,
            color = GraphCyan,
            style = Stroke(width = 4f)
        )

        // 4. Draw Floating Tooltip Scrubber
        scrubX?.let { currentX ->
            val boundedX = currentX.coerceIn(leftMargin, widthPx - rightMargin)

            val fraction = (boundedX - leftMargin) / graphWidth
            val targetTime = minTime + (fraction * timeRange).toInt()
            val closestPoint = data.minByOrNull { abs(it.timeSec - targetTime) }

            if (closestPoint != null) {
                val pointX = xFor(closestPoint.timeSec)
                val pointY = yFor(closestPoint.speedKmh)

                drawLine(
                    color = Color.White.copy(alpha = 0.6f),
                    start = Offset(pointX, topMargin),
                    end = Offset(pointX, topMargin + graphHeight),
                    strokeWidth = 3f
                )

                drawCircle(color = Color.White, radius = 10f, center = Offset(pointX, pointY))
                drawCircle(color = GraphCyan, radius = 7f, center = Offset(pointX, pointY))

                val tooltipText = "${closestPoint.speedKmh.roundToInt()} km/h | ${formatTime(closestPoint.timeSec)}"
                val tooltipPaint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 36f
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                }

                drawRoundRect(
                    color = Color(0xFF1E1E1E),
                    topLeft = Offset(pointX - 160f, 0f),
                    size = Size(320f, 60f),
                    cornerRadius = CornerRadius(16f, 16f)
                )

                drawContext.canvas.nativeCanvas.drawText(
                    tooltipText,
                    pointX,
                    42f,
                    tooltipPaint
                )
            }
        }
    }
}
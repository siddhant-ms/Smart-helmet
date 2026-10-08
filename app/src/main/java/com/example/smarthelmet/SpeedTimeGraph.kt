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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
                .height(200.dp),
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

    val maxSpeed =
        data.maxOf { it.speedKmh }
            .coerceAtLeast(1f)

    val minTime =
        data.first().timeSec

    val maxTime =
        data.last().timeSec
            .coerceAtLeast(minTime + 1)

    val timeRange =
        (maxTime - minTime)
            .coerceAtLeast(1)

    /*
     * Use dp/sp for dimensions that are drawn onto the Canvas.
     *
     * The old implementation used raw pixel values such as 100f,
     * 40f, 32f and 320f. Those values are physical pixels, so the
     * graph changes size and typography depending on the device density.
     *
     * These values are converted to pixels through LocalDensity while
     * drawing, giving the graph a consistent physical layout.
     */
    val density = androidx.compose.ui.platform.LocalDensity.current

    val leftMarginPx =
        with(density) { 72.dp.toPx() }

    val rightMarginPx =
        with(density) { 32.dp.toPx() }

    val topMarginPx =
        with(density) { 42.dp.toPx() }

    val bottomMarginPx =
        with(density) { 48.dp.toPx() }

    val axisTextSizePx =
        with(density) { 13.sp.toPx() }

    val tooltipTextSizePx =
        with(density) { 16.sp.toPx() }

    val tooltipHeightPx =
        with(density) { 48.dp.toPx() }

    val tooltipPaddingPx =
        with(density) { 14.dp.toPx() }

    val tooltipCornerPx =
        with(density) { 12.dp.toPx() }

    val lineWidthPx =
        with(density) { 3.dp.toPx() }

    val guideLineWidthPx =
        with(density) { 1.dp.toPx() }

    val scrubLineWidthPx =
        with(density) { 2.dp.toPx() }

    val outerPointRadiusPx =
        with(density) { 7.dp.toPx() }

    val innerPointRadiusPx =
        with(density) { 5.dp.toPx() }

    var scrubX by remember {
        mutableStateOf<Float?>(null)
    }

    var canvasWidthPx by remember {
        mutableStateOf(0f)
    }

    /*
     * Resolve the drag location to the nearest telemetry point.
     */
    LaunchedEffect(
        scrubX,
        canvasWidthPx,
        data
    ) {
        val width =
            canvasWidthPx

        val x =
            scrubX

        if (
            x == null ||
            width <= 0f
        ) {
            onScrub(null)
            return@LaunchedEffect
        }

        val graphWidth =
            (width -
                    leftMarginPx -
                    rightMarginPx)
                .coerceAtLeast(1f)

        val boundedX =
            x.coerceIn(
                leftMarginPx,
                width - rightMarginPx
            )

        val fraction =
            (
                    (boundedX - leftMarginPx) /
                            graphWidth
                    ).coerceIn(
                    0f,
                    1f
                )

        val targetTime =
            minTime +
                    (
                            fraction *
                                    timeRange
                            ).toInt()

        onScrub(
            data.minByOrNull {
                abs(
                    it.timeSec -
                            targetTime
                )
            }
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .onSizeChanged {
                canvasWidthPx =
                    it.width.toFloat()
            }
            .pointerInput(Unit) {

                detectHorizontalDragGestures(

                    onDragStart = { offset ->
                        scrubX =
                            offset.x
                    },

                    onDragEnd = {
                        scrubX =
                            null
                    },

                    onDragCancel = {
                        scrubX =
                            null
                    },

                    onHorizontalDrag = {
                            change,
                            _ ->
                        scrubX =
                            change.position.x
                    }
                )
            }
    ) {
        val widthPx =
            size.width

        val heightPx =
            size.height

        /*
         * Make sure the graph still has usable dimensions even on
         * unusually narrow screens.
         */
        val safeLeftMargin =
            leftMarginPx.coerceAtMost(
                widthPx * 0.22f
            )

        val safeRightMargin =
            rightMarginPx.coerceAtMost(
                widthPx * 0.10f
            )

        val graphWidth =
            (
                    widthPx -
                            safeLeftMargin -
                            safeRightMargin
                    ).coerceAtLeast(1f)

        val safeTopMargin =
            topMarginPx.coerceAtMost(
                heightPx * 0.30f
            )

        val safeBottomMargin =
            bottomMarginPx.coerceAtMost(
                heightPx * 0.30f
            )

        val graphHeight =
            (
                    heightPx -
                            safeTopMargin -
                            safeBottomMargin
                    ).coerceAtLeast(1f)

        fun xFor(
            timeSec: Int
        ): Float {
            return safeLeftMargin +
                    (
                            (timeSec - minTime)
                                .toFloat() /
                                    timeRange
                                        .toFloat()
                            ) *
                    graphWidth
        }

        fun yFor(
            speedKmh: Float
        ): Float {
            return safeTopMargin +
                    graphHeight -
                    (
                            speedKmh /
                                    maxSpeed
                            ) *
                    graphHeight
        }

        val textPaint =
            Paint().apply {
                color =
                    android.graphics.Color.LTGRAY
                textSize =
                    axisTextSizePx
                isAntiAlias =
                    true
            }

        /*
         * -------------------------------------------------------------
         * Y AXIS
         * -------------------------------------------------------------
         */
        val intervals = 4

        for (i in 0..intervals) {
            val speedVal =
                maxSpeed *
                        (
                                i.toFloat() /
                                        intervals
                                )

            val y =
                yFor(speedVal)

            drawLine(
                color =
                    Color.White.copy(
                        alpha = 0.1f
                    ),

                start =
                    Offset(
                        safeLeftMargin,
                        y
                    ),

                end =
                    Offset(
                        widthPx -
                                safeRightMargin,
                        y
                    ),

                strokeWidth =
                    guideLineWidthPx
            )

            val label =
                speedVal
                    .roundToInt()
                    .toString()

            drawContext
                .canvas
                .nativeCanvas
                .drawText(
                    label,
                    8.dp.toPx(),
                    y + axisTextSizePx * 0.35f,
                    textPaint
                )
        }

        /*
         * Axis unit label.
         */
        drawContext
            .canvas
            .nativeCanvas
            .drawText(
                "km/h",
                8.dp.toPx(),
                safeTopMargin -
                        10.dp.toPx(),
                textPaint
            )

        /*
         * -------------------------------------------------------------
         * X AXIS
         * -------------------------------------------------------------
         */
        val formatTime = { seconds: Int ->
            val epochMs =
                startTimeMs +
                        (
                                seconds *
                                        1000L
                                )

            val sdf =
                java.text.SimpleDateFormat(
                    "HH:mm",
                    java.util.Locale.getDefault()
                ).apply {
                    timeZone =
                        java.util.TimeZone
                            .getTimeZone(
                                "Asia/Kolkata"
                            )
                }

            sdf.format(
                java.util.Date(epochMs)
            )
        }

        val timeLabels =
            listOf(
                minTime,
                minTime +
                        (timeRange / 2),
                maxTime
            )

        timeLabels.forEach { time ->
            val x =
                xFor(time)

            val label =
                formatTime(time)

            val textWidth =
                textPaint.measureText(label)

            val centeredX =
                (
                        x -
                                (textWidth / 2f)
                        ).coerceIn(
                        0f,
                        widthPx -
                                textWidth
                    )

            drawContext
                .canvas
                .nativeCanvas
                .drawText(
                    label,
                    centeredX,
                    heightPx -
                            12.dp.toPx(),
                    textPaint
                )
        }

        /*
         * -------------------------------------------------------------
         * LINE GRAPH
         * -------------------------------------------------------------
         */
        val linePath =
            Path()

        val fillPath =
            Path()

        data.forEachIndexed { index, point ->

            val x =
                xFor(point.timeSec)

            val y =
                yFor(point.speedKmh)

            if (index == 0) {

                linePath.moveTo(
                    x,
                    y
                )

                fillPath.moveTo(
                    x,
                    safeTopMargin +
                            graphHeight
                )

                fillPath.lineTo(
                    x,
                    y
                )

            } else {

                linePath.lineTo(
                    x,
                    y
                )

                fillPath.lineTo(
                    x,
                    y
                )
            }
        }

        val lastX =
            xFor(
                data.last().timeSec
            )

        fillPath.lineTo(
            lastX,
            safeTopMargin +
                    graphHeight
        )

        fillPath.close()

        drawPath(
            path = fillPath,

            brush =
                Brush.verticalGradient(
                    colors =
                        listOf(
                            GraphCyan.copy(
                                alpha = 0.4f
                            ),
                            GraphCyan.copy(
                                alpha = 0f
                            )
                        ),

                    startY =
                        safeTopMargin,

                    endY =
                        safeTopMargin +
                                graphHeight
                )
        )

        drawPath(
            path = linePath,
            color = GraphCyan,
            style =
                Stroke(
                    width =
                        lineWidthPx
                )
        )

        /*
         * -------------------------------------------------------------
         * SCRUBBER + TOOLTIP
         * -------------------------------------------------------------
         */
        scrubX?.let { currentX ->

            val boundedX =
                currentX.coerceIn(
                    safeLeftMargin,
                    widthPx -
                            safeRightMargin
                )

            val fraction =
                (
                        boundedX -
                                safeLeftMargin
                        ) /
                        graphWidth

            val targetTime =
                minTime +
                        (
                                fraction *
                                        timeRange
                                ).toInt()

            val closestPoint =
                data.minByOrNull {
                    abs(
                        it.timeSec -
                                targetTime
                    )
                }

            if (closestPoint != null) {

                val pointX =
                    xFor(
                        closestPoint.timeSec
                    )

                val pointY =
                    yFor(
                        closestPoint.speedKmh
                    )

                /*
                 * Vertical scrub line.
                 */
                drawLine(
                    color =
                        Color.White.copy(
                            alpha = 0.6f
                        ),

                    start =
                        Offset(
                            pointX,
                            safeTopMargin
                        ),

                    end =
                        Offset(
                            pointX,
                            safeTopMargin +
                                    graphHeight
                        ),

                    strokeWidth =
                        scrubLineWidthPx
                )

                /*
                 * Selected data point.
                 */
                drawCircle(
                    color = Color.White,
                    radius =
                        outerPointRadiusPx,
                    center =
                        Offset(
                            pointX,
                            pointY
                        )
                )

                drawCircle(
                    color = GraphCyan,
                    radius =
                        innerPointRadiusPx,
                    center =
                        Offset(
                            pointX,
                            pointY
                        )
                )

                /*
                 * Tooltip.
                 *
                 * Width is calculated from the actual text instead of
                 * using a hard-coded 320px rectangle. It is also clamped
                 * to the available canvas width so it cannot run off the
                 * edge on narrow phones.
                 */
                val tooltipText =
                    "${closestPoint.speedKmh.roundToInt()} km/h | " +
                            formatTime(
                                closestPoint.timeSec
                            )

                val tooltipPaint =
                    Paint().apply {
                        color =
                            android.graphics.Color.WHITE
                        textSize =
                            tooltipTextSizePx
                        isAntiAlias =
                            true
                        textAlign =
                            Paint.Align.CENTER
                    }

                val measuredTextWidth =
                    tooltipPaint.measureText(
                        tooltipText
                    )

                val minimumTooltipWidth =
                    with(density) {
                        180.dp.toPx()
                    }

                val maximumTooltipWidth =
                    (
                            widthPx -
                                    16.dp.toPx()
                            )
                        .coerceAtLeast(
                            minimumTooltipWidth
                        )

                val tooltipWidth =
                    (
                            measuredTextWidth +
                                    tooltipPaddingPx * 2f
                            )
                        .coerceIn(
                            minimumTooltipWidth,
                            maximumTooltipWidth
                        )

                val tooltipLeft =
                    (
                            pointX -
                                    tooltipWidth / 2f
                            )
                        .coerceIn(
                            8.dp.toPx(),
                            widthPx -
                                    tooltipWidth -
                                    8.dp.toPx()
                        )

                val tooltipTop =
                    4.dp.toPx()

                drawRoundRect(
                    color =
                        Color(0xFF1E1E1E),

                    topLeft =
                        Offset(
                            tooltipLeft,
                            tooltipTop
                        ),

                    size =
                        Size(
                            tooltipWidth,
                            tooltipHeightPx
                        ),

                    cornerRadius =
                        CornerRadius(
                            tooltipCornerPx,
                            tooltipCornerPx
                        )
                )

                drawContext
                    .canvas
                    .nativeCanvas
                    .drawText(
                        tooltipText,

                        tooltipLeft +
                                tooltipWidth / 2f,

                        tooltipTop +
                                tooltipHeightPx / 2f -
                                (
                                        tooltipPaint
                                            .ascent() +
                                                tooltipPaint
                                                    .descent()
                                        ) / 2f,

                        tooltipPaint
                    )
            }
        }
    }
}

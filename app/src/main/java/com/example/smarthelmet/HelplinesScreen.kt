package com.example.smarthelmet

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlin.math.abs

data class RSABrand(
    val name: String,
    val number: String
)

@Composable
fun HelplinesScreen(
    navController: NavController
) {
    val context = LocalContext.current

    val rsaBrands = remember {
        listOf(
            RSABrand("Hero MotoCorp", "1800-309-9793"),
            RSABrand("Honda", "1800-103-3434"),
            RSABrand("TVS Motor", "1800-258-7111"),
            RSABrand("Bajaj Auto", "7219821111"),
            RSABrand("Suzuki", "1800-121-7996"),
            RSABrand("Royal Enfield", "1800-210-0007"),
            RSABrand("Yamaha", "1800-420-1600"),
            RSABrand("Ather Energy", "7676811777"),
            RSABrand("Ola Electric", "080-33113311"),
            RSABrand("KTM", "1800-2050-300")
        )
    }

    val listState = rememberLazyListState()

    val centerIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter =
                (layoutInfo.viewportStartOffset +
                        layoutInfo.viewportEndOffset) / 2

            layoutInfo.visibleItemsInfo
                .minByOrNull { itemInfo ->
                    abs(
                        itemInfo.offset +
                                (itemInfo.size / 2) -
                                viewportCenter
                    )
                }
                ?.index ?: 0
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090909))
    ) {
        val screenHeight = maxHeight
        val screenWidth = maxWidth

        /*
         * Responsive vertical spacing.
         *
         * The old screen always reserved exactly 80dp at the top and
         * 120dp at the bottom. Those values make the page disproportionately
         * compressed or spacious on different display sizes.
         */
        val topSpacing =
            (screenHeight * 0.075f).coerceIn(
                48.dp,
                80.dp
            )

        val bottomSpacing =
            (screenHeight * 0.105f).coerceIn(
                88.dp,
                116.dp
            )

        val horizontalPadding =
            if (screenWidth < 360.dp) {
                16.dp
            } else {
                20.dp
            }

        val compact =
            screenWidth < 360.dp

        val supportCardHeight =
            if (compact) 118.dp else 130.dp

        val titleSize =
            if (compact) 25.sp else 28.sp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = topSpacing,
                    bottom = bottomSpacing
                )
                .verticalScroll(
                    rememberScrollState()
                )
        ) {

            /*
             * ---------------------------------------------------------
             * HEADER
             * ---------------------------------------------------------
             */
            Text(
                text = "Helplines & Assistance",
                color = Color.White,
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(
                    horizontal = horizontalPadding
                )
            )

            Spacer(
                modifier = Modifier.height(4.dp)
            )

            Row(
                verticalAlignment =
                    Alignment.CenterVertically,

                modifier = Modifier.padding(
                    horizontal = horizontalPadding
                )
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            Color(0xFFFF8800),
                            RoundedCornerShape(50)
                        )
                )

                Spacer(
                    modifier = Modifier.width(8.dp)
                )

                Text(
                    text = "ROAD & HIGHWAY SUPPORT",
                    color =
                        Color.Gray.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    fontWeight =
                        FontWeight.SemiBold
                )
            }

            Spacer(
                modifier = Modifier.height(20.dp)
            )

            /*
             * ---------------------------------------------------------
             * EMERGENCY SUPPORT CARDS
             * ---------------------------------------------------------
             */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = horizontalPadding
                    ),

                horizontalArrangement =
                    Arrangement.spacedBy(
                        if (compact) 8.dp else 12.dp
                    )
            ) {

                SupportCallCard(
                    title = "HIGHWAY",
                    number = "1033",
                    subtitle = "NHAI Emergency",
                    accentColor = Color(0xFFFFB300),
                    weight = 1.2f,
                    height = supportCardHeight,
                    context = context
                )

                SupportCallCard(
                    title = "TRAFFIC",
                    number = "103",
                    subtitle = "Control Room",
                    accentColor = Color(0xFFC24934),
                    weight = 1f,
                    height = supportCardHeight,
                    context = context
                )
            }

            Spacer(
                modifier = Modifier.height(24.dp)
            )

            DividerLine(
                horizontalPadding = horizontalPadding
            )

            Spacer(
                modifier = Modifier.height(20.dp)
            )

            /*
             * ---------------------------------------------------------
             * BRAND RSA
             * ---------------------------------------------------------
             */
            SectionLabel(
                text = "BRAND ROADSIDE ASSISTANCE",
                dotColor = Color(0xFFFF8800),
                horizontalPadding = horizontalPadding
            )

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            /*
             * The carousel's side padding adapts to screen width,
             * keeping the selected brand centered without relying on
             * one hard-coded value.
             */
            val carouselPadding =
                ((screenWidth / 2) - 70.dp)
                    .coerceAtLeast(72.dp)

            LazyRow(
                state = listState,

                flingBehavior =
                    rememberSnapFlingBehavior(
                        lazyListState = listState
                    ),

                contentPadding =
                    PaddingValues(
                        horizontal = carouselPadding
                    ),

                horizontalArrangement =
                    Arrangement.spacedBy(
                        if (compact) 18.dp else 24.dp
                    ),

                modifier =
                    Modifier.fillMaxWidth()
            ) {
                itemsIndexed(rsaBrands) { index, brand ->

                    val isCenter =
                        index == centerIndex

                    val scale by
                    animateFloatAsState(
                        targetValue =
                            if (isCenter) {
                                1.3f
                            } else {
                                0.85f
                            },
                        animationSpec =
                            tween(300),
                        label = "brandScale"
                    )

                    val alpha by
                    animateFloatAsState(
                        targetValue =
                            if (isCenter) {
                                1f
                            } else {
                                0.4f
                            },
                        animationSpec =
                            tween(300),
                        label = "brandAlpha"
                    )

                    val brandColor =
                        if (isCenter) {
                            Color(0xFF7ED4E0)
                        } else {
                            Color.White
                        }

                    Text(
                        text = brand.name,
                        color = brandColor,
                        fontSize =
                            if (compact) {
                                15.sp
                            } else {
                                16.sp
                            },
                        fontWeight =
                            if (isCenter) {
                                FontWeight.ExtraBold
                            } else {
                                FontWeight.Medium
                            },
                        textAlign =
                            TextAlign.Center,

                        style =
                            TextStyle(
                                shadow =
                                    Shadow(
                                        color =
                                            if (isCenter) {
                                                brandColor.copy(
                                                    alpha = 0.5f
                                                )
                                            } else {
                                                Color.Transparent
                                            },
                                        blurRadius = 25f
                                    )
                            ),

                        modifier = Modifier
                            .scale(scale)
                            .alpha(alpha)
                            .padding(
                                vertical = 16.dp,
                                horizontal = 8.dp
                            )
                    )
                }
            }

            Spacer(
                modifier = Modifier.height(4.dp)
            )

            /*
             * Selected RSA number.
             *
             * The number changes with the centered brand.
             */
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = horizontalPadding
                    ),
                contentAlignment =
                    Alignment.Center
            ) {

                AnimatedContent(
                    targetState = centerIndex,

                    transitionSpec = {

                        if (targetState > initialState) {

                            (
                                    slideInVertically { height ->
                                        height
                                    } +
                                            fadeIn(
                                                tween(300)
                                            )
                                    ).togetherWith(
                                    slideOutVertically { height ->
                                        -height
                                    } +
                                            fadeOut(
                                                tween(300)
                                            )
                                )

                        } else {

                            (
                                    slideInVertically { height ->
                                        -height
                                    } +
                                            fadeIn(
                                                tween(300)
                                            )
                                    ).togetherWith(
                                    slideOutVertically { height ->
                                        height
                                    } +
                                            fadeOut(
                                                tween(300)
                                            )
                                )
                        }
                    },

                    label = "numberAnimation"
                ) { targetIndex ->

                    val number =
                        rsaBrands
                            .getOrNull(targetIndex)
                            ?.number
                            ?: ""

                    Row(
                        modifier = Modifier
                            .background(
                                brush =
                                    Brush.verticalGradient(
                                        colors =
                                            listOf(
                                                Color(0xFF252525),
                                                Color(0xFF0A0A0A)
                                            )
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
                                        alpha = 0.15f
                                    ),
                                shape =
                                    RoundedCornerShape(
                                        percent = 50
                                    )
                            )
                            .clickable {

                                val intent =
                                    Intent(
                                        Intent.ACTION_DIAL,
                                        Uri.parse(
                                            "tel:$number"
                                        )
                                    )

                                context.startActivity(
                                    intent
                                )
                            }
                            .padding(
                                horizontal =
                                    if (compact) {
                                        20.dp
                                    } else {
                                        24.dp
                                    },
                                vertical = 12.dp
                            ),

                        verticalAlignment =
                            Alignment.CenterVertically,

                        horizontalArrangement =
                            Arrangement.spacedBy(12.dp)
                    ) {

                        Icon(
                            imageVector =
                                Icons.Default.Call,
                            contentDescription =
                                "Call RSA",
                            tint = Color.White,
                            modifier =
                                Modifier.size(20.dp)
                        )

                        Text(
                            text = number,
                            color = Color.White,
                            fontSize =
                                if (compact) {
                                    18.sp
                                } else {
                                    20.sp
                                },
                            fontWeight =
                                FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            Spacer(
                modifier = Modifier.height(32.dp)
            )

            /*
             * ---------------------------------------------------------
             * CITIZEN REPORTING DIVIDER
             * ---------------------------------------------------------
             */
            DividerLine(
                horizontalPadding = horizontalPadding
            )

            Spacer(
                modifier = Modifier.height(20.dp)
            )

            SectionLabel(
                text = "CITIZEN REPORTING",
                dotColor = Color(0xFF25D366),
                horizontalPadding = horizontalPadding
            )

            Spacer(
                modifier = Modifier.height(16.dp)
            )

            /*
             * ---------------------------------------------------------
             * WHATSAPP REPORTING
             * ---------------------------------------------------------
             */
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = horizontalPadding
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
                                24.dp
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
                                24.dp
                            )
                    )
                    .clickable {

                        val intent =
                            Intent(
                                Intent.ACTION_VIEW
                            )

                        /*
                         * Keep the existing public reporting
                         * destination used by the app.
                         */
                        intent.data =
                            Uri.parse(
                                "https://wa.me/9480801800"
                            )

                        try {
                            context.startActivity(
                                intent
                            )
                        } catch (_: Exception) {
                            Toast.makeText(
                                context,
                                "WhatsApp is not installed on this device.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .padding(
                        if (compact) {
                            16.dp
                        } else {
                            20.dp
                        }
                    )
            ) {

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.SpaceBetween,

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Column(
                        modifier =
                            Modifier.weight(1f)
                    ) {

                        Text(
                            text =
                                "Bengaluru Traffic Police",
                            color =
                                Color.White,
                            fontSize =
                                if (compact) {
                                    17.sp
                                } else {
                                    18.sp
                                },
                            fontWeight =
                                FontWeight.Bold
                        )

                        Spacer(
                            modifier =
                                Modifier.height(4.dp)
                        )

                        Text(
                            text =
                                "Report potholes, broken signals, or traffic violations instantly.",
                            color =
                                Color.Gray,
                            fontSize =
                                if (compact) {
                                    12.sp
                                } else {
                                    13.sp
                                },
                            lineHeight = 18.sp
                        )
                    }

                    Spacer(
                        modifier =
                            Modifier.width(16.dp)
                    )

                    Box(
                        modifier = Modifier
                            .size(
                                if (compact) {
                                    48.dp
                                } else {
                                    52.dp
                                }
                            )
                            .background(
                                color =
                                    Color(0xFF25D366)
                                        .copy(
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
                                    Color(0xFF25D366)
                                        .copy(
                                            alpha = 0.4f
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
                                Icons.Default.Send,
                            contentDescription =
                                "WhatsApp",
                            tint =
                                Color(0xFF25D366),
                            modifier =
                                Modifier.size(
                                    if (compact) {
                                        22.dp
                                    } else {
                                        24.dp
                                    }
                                )
                        )
                    }
                }
            }
        }
    }
}


/*
 * Reusable emergency phone card.
 */
@Composable
private fun RowScope.SupportCallCard(
    title: String,
    number: String,
    subtitle: String,
    accentColor: Color,
    weight: Float,
    height: androidx.compose.ui.unit.Dp,
    context: android.content.Context
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .height(height)
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
                    RoundedCornerShape(24.dp)
            )
            .border(
                width = 1.dp,
                color =
                    Color.White.copy(
                        alpha = 0.08f
                    ),
                shape =
                    RoundedCornerShape(24.dp)
            )
            .clickable {

                val intent =
                    Intent(
                        Intent.ACTION_DIAL,
                        Uri.parse(
                            "tel:$number"
                        )
                    )

                context.startActivity(
                    intent
                )
            }
            .padding(16.dp)
    ) {

        Column(
            modifier =
                Modifier.fillMaxSize(),

            verticalArrangement =
                Arrangement.SpaceBetween
        ) {

            Row(
                modifier =
                    Modifier.fillMaxWidth(),

                horizontalArrangement =
                    Arrangement.SpaceBetween,

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Text(
                    text = title,
                    color = accentColor,
                    fontSize = 11.sp,
                    fontWeight =
                        FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Icon(
                    imageVector =
                        Icons.Default.Call,

                    contentDescription =
                        "Call",

                    tint =
                        Color.White.copy(
                            alpha = 0.6f
                        ),

                    modifier =
                        Modifier.size(16.dp)
                )
            }

            Column {

                Text(
                    text = number,
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    text = subtitle,
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }
    }
}


/*
 * Section label used throughout the screen.
 */
@Composable
private fun SectionLabel(
    text: String,
    dotColor: Color,
    horizontalPadding: androidx.compose.ui.unit.Dp
) {
    Row(
        verticalAlignment =
            Alignment.CenterVertically,

        modifier =
            Modifier.padding(
                horizontal = horizontalPadding
            )
    ) {

        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    dotColor,
                    RoundedCornerShape(50)
                )
        )

        Spacer(
            modifier =
                Modifier.width(8.dp)
        )

        Text(
            text = text,
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
}


/*
 * Thin horizontal divider.
 */
@Composable
private fun DividerLine(
    horizontalPadding: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = horizontalPadding
            )
            .height(1.dp)
            .background(
                brush =
                    Brush.horizontalGradient(
                        colors =
                            listOf(
                                Color.Transparent,
                                Color.White.copy(
                                    alpha = 0.15f
                                ),
                                Color.Transparent
                            )
                    )
            )
    )
}

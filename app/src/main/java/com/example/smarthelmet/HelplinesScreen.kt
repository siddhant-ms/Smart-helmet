package com.example.smarthelmet

import android.net.Uri
import androidx.compose.animation.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

data class RSABrand(val name: String, val number: String)

@Composable
fun HelplinesScreen(navController: NavController) {
    val context = LocalContext.current

    val rsaBrands = listOf(
        RSABrand("Hero MotoCorp", "1800-266-0018"),
        RSABrand("Honda", "1800-103-3121"),
        RSABrand("TVS Motor", "1800-258-7111"),
        RSABrand("Bajaj Auto", "7219821111"),
        RSABrand("Suzuki", "1800-121-7996"),
        RSABrand("Royal Enfield", "1800-210-0007"),
        RSABrand("Yamaha", "1800-420-1600"),
        RSABrand("Ather Energy", "1800-123-0033"),
        RSABrand("Ola Electric", "080-33113311"),
        RSABrand("KTM", "1800-2050-300")
    )

    val listState = rememberLazyListState()

    val centerIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            layoutInfo.visibleItemsInfo.minByOrNull { itemInfo ->
                kotlin.math.abs(itemInfo.offset + (itemInfo.size / 2) - viewportCenter)
            }?.index ?: 0
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090909))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 80.dp, bottom = 120.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Helplines & Assistance",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color(0xFFFF8800), RoundedCornerShape(50))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "ROAD & HIGHWAY SUPPORT",
                    color = Color.Gray.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .height(130.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFF1E1E1E), Color(0xFF0A0A0A))
                            ),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .clickable {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_DIAL,
                                Uri.parse("tel:1033")
                            )
                            context.startActivity(intent)
                        }
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "HIGHWAY",
                                color = Color(0xFFFFB300),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Call",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "1033",
                                color = Color.White,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "NHAI Emergency",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(130.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFF1E1E1E), Color(0xFF0A0A0A))
                            ),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .clickable {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_DIAL,
                                Uri.parse("tel:103")
                            )
                            context.startActivity(intent)
                        }
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TRAFFIC",
                                color = Color(0xffc24934),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Call",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "103",
                                color = Color.White,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Control Room",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(1.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color(0xFFFF8800), RoundedCornerShape(50))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "BRAND ROADSIDE ASSISTANCE",
                    color = Color.Gray.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                state = listState,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
                contentPadding = PaddingValues(horizontal = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(rsaBrands) { index, brand ->
                    val isCenter = index == centerIndex
                    val scale by animateFloatAsState(targetValue = if (isCenter) 1.3f else 0.85f, animationSpec = tween(300), label = "scale")
                    val alpha by animateFloatAsState(targetValue = if (isCenter) 1f else 0.4f, animationSpec = tween(300), label = "alpha")
                    val brandColor = if (isCenter) Color(0xFF7ED4E0) else Color.White

                    Text(
                        text = brand.name,
                        color = brandColor,
                        fontSize = 16.sp,
                        fontWeight = if (isCenter) FontWeight.ExtraBold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = if (isCenter) brandColor.copy(alpha = 0.5f) else Color.Transparent,
                                blurRadius = 25f
                            )
                        ),
                        modifier = Modifier
                            .scale(scale)
                            .alpha(alpha)
                            .padding(vertical = 16.dp, horizontal = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val selectedNumber = rsaBrands.getOrNull(centerIndex)?.number ?: ""

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = selectedNumber,
                    transitionSpec = {
                        (slideInVertically { height -> height } + fadeIn(tween(300))).togetherWith(
                            slideOutVertically { height -> -height } + fadeOut(tween(300)))
                    },
                    label = "numberAnimation"
                ) { number ->
                    Row(
                        modifier = Modifier
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color(0xFF252525), Color(0xFF0A0A0A))
                                ),
                                shape = RoundedCornerShape(percent = 50)
                            )
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(percent = 50)
                            )
                            .clickable {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_DIAL,
                                    Uri.parse("tel:$number")
                                )
                                context.startActivity(intent)
                            }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Call RSA",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )

                        Text(
                            text = number,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            // ==========================================
            // NEW SECTION: WHATSAPP CITIZEN REPORTING
            // ==========================================
            Spacer(modifier = Modifier.height(32.dp))

            // Thin Transparent Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(1.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Subtitle with WhatsApp Green Dot
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color(0xFF25D366), RoundedCornerShape(50)) // WhatsApp Green
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "CITIZEN REPORTING",
                    color = Color.Gray.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Wide Bento Box for WhatsApp Launch
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFF1E1E1E), Color(0xFF0A0A0A))
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .clickable {
                        // Triggers a deep link directly into WhatsApp
                        val url = "https://wa.me/9480801800 " // Official Public Eye/BTP Bot Number
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                        intent.data = Uri.parse(url)
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(
                                context,
                                "WhatsApp is not installed on this device.",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Text Column
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Bengaluru Traffic Police",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Report potholes, broken signals, or traffic violations instantly.",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Right WhatsApp Icon Pill
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(
                                color = Color(0xFF25D366).copy(alpha = 0.15f), // Faint Green Glow
                                shape = RoundedCornerShape(percent = 50)
                            )
                            .border(
                                width = 1.dp,
                                color = Color(0xFF25D366).copy(alpha = 0.4f), // Sharp Green Edge
                                shape = RoundedCornerShape(percent = 50)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "WhatsApp",
                            tint = Color(0xFF25D366),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
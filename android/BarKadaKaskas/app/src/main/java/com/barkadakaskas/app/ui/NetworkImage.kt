package com.barkadakaskas.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.SubcomposeAsyncImage

/**
 * A remote image that shows a shimmer while it downloads and a quiet fallback if it fails, so cards never sit blank
 * on slow connections. [dark] suits black backgrounds such as the full-screen viewer.
 */
@Composable fun NetworkImage(model: Any?, contentDescription: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, dark: Boolean = false) {
    SubcomposeAsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        loading = { Shimmer(Modifier.fillMaxSize(), dark) },
        error = {
            Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF1C2420) else Color(0xFFEEF2E9)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.BrokenImage, contentDescription = null, tint = Color(0xFF9AA89A))
            }
        },
    )
}

/** Animated loading placeholder. */
@Composable fun Shimmer(modifier: Modifier = Modifier, dark: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(initialValue = -1f, targetValue = 2f, animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "shift")
    val base = if (dark) Color(0xFF1C2420) else Color(0xFFEDF1E8)
    val glow = if (dark) Color(0xFF2A352F) else Color(0xFFF6F8F2)
    Box(modifier.background(Brush.linearGradient(listOf(base, glow, base), start = Offset(shift * 600f, 0f), end = Offset(shift * 600f + 600f, 0f))))
}

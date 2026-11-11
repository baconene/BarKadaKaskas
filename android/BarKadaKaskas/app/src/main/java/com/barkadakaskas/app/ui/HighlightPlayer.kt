package com.barkadakaskas.app.ui

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.barkadakaskas.app.domain.Highlight
import com.barkadakaskas.app.domain.HighlightScene
import com.barkadakaskas.app.domain.HighlightTravel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private val ReelGreen = Color(0xFF214E3E)
private val ReelMint = Color(0xFFE8F2D8)
private val ReelDark = Color(0xFF0D1512)

/** One piece of the reel's timeline. */
private sealed interface ReelPart { val seconds: Double; val section: Int }
private data class CoverPart(override val seconds: Double) : ReelPart { override val section = -1 }
private data class TravelPart(val scene: HighlightScene, val travel: HighlightTravel, override val section: Int) : ReelPart { override val seconds = travel.duration }
private data class PhotoPart(val scene: HighlightScene, val photo: Int, override val section: Int) : ReelPart { override val seconds = scene.photos[photo].duration }
private data class OutroPart(override val seconds: Double, override val section: Int) : ReelPart

private fun timeline(h: Highlight): List<ReelPart> = buildList {
    add(CoverPart(h.coverDuration))
    h.scenes.forEachIndexed { i, scene ->
        scene.travel?.let { add(TravelPart(scene, it, i)) }
        scene.photos.indices.forEach { add(PhotoPart(scene, it, i)) }
    }
    add(OutroPart(h.outroDuration, h.scenes.size))
}

private fun reelIcon(mode: String): ImageVector = when (mode) {
    "walking" -> Icons.AutoMirrored.Filled.DirectionsWalk; "bicycle" -> Icons.AutoMirrored.Filled.DirectionsBike; "motorcycle" -> Icons.Default.TwoWheeler
    "van" -> Icons.Default.AirportShuttle; "taxi" -> Icons.Default.LocalTaxi; "transit" -> Icons.Default.DirectionsBus; "ferry" -> Icons.Default.DirectionsBoat
    "flight" -> Icons.Default.Flight; else -> Icons.Default.DirectionsCar
}

private fun ease(t: Float) = if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2

/** A point on the route's gentle arc (higher for flights). */
private fun arcPoint(a: Offset, b: Offset, mode: String, t: Float): Offset {
    val bend = if (mode == "flight") 0.35f else 0.12f
    val c = Offset((a.x + b.x) / 2 - (b.y - a.y) * bend, (a.y + b.y) / 2 + (b.x - a.x) * bend)
    val u = 1 - t
    return Offset(u * u * a.x + 2 * u * t * c.x + t * t * b.x, u * u * a.y + 2 * u * t * c.y + t * t * b.y)
}

/**
 * The day highlight as a travel reel, matching the web: cover; for each stop, its transport icon travels the route
 * on a map to the place, then the place's photos play in quick reel cuts (place sticker, who posted, caption); outro;
 * with music. Tap to pause; day chips switch days; "AI cover" remakes the cover with the AI image model.
 */
@Composable internal fun HighlightPlayer(vm: TravelViewModel, dismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadHighlight() }
    val h = vm.highlight
    val parts = remember(h) { h?.let(::timeline) ?: emptyList() }
    var index by remember(h?.date) { mutableIntStateOf(0) }
    var playing by remember(h?.date) { mutableStateOf(false) }
    val progress = remember(h?.date, index) { Animatable(0f) }
    val player = remember { android.media.MediaPlayer() }
    var musicReady by remember { mutableStateOf(false) }
    DisposableEffect(h?.musicUrl) {
        musicReady = false
        h?.musicUrl?.takeIf { it.isNotBlank() }?.let { url -> runCatching { player.reset(); player.setDataSource(url); player.setOnPreparedListener { musicReady = true }; player.prepareAsync() } }
        onDispose { }
    }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }
    LaunchedEffect(playing, musicReady) { runCatching { if (playing && musicReady) player.start() else if (player.isPlaying) player.pause() } }
    LaunchedEffect(playing, index, h?.date) {
        if (!playing || parts.isEmpty()) return@LaunchedEffect
        val remaining = ((1f - progress.value) * parts[index].seconds * 1000).toInt()
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        if (index < parts.lastIndex) index++ else { playing = false; index = 0; runCatching { player.pause(); player.seekTo(0) } }
    }

    // Full screen behind the status and navigation bars, but told their sizes (decorFitsSystemWindows = false), so the
    // bars, buttons and captions below keep clear of the clock, camera cut-out and the phone's back/home buttons.
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = androidx.compose.ui.platform.LocalView.current
        SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
            }
        }
        Box(Modifier.fillMaxSize().background(ReelDark)) {
            if (h == null || vm.highlightLoading) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White); Spacer(Modifier.height(10.dp)); Text("Putting the day together…", color = Color.White)
            } else if (h.scenes.isEmpty()) Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No photos for this day yet", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Post photos during the day and they'll appear here with the itinerary.", color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            } else {
                // Photos at the same place cut straight to the next one, like a reel; everything else crossfades.
                val cut = index in 1..parts.lastIndex && parts[index] is PhotoPart && parts[index - 1] is PhotoPart && parts[index].section == parts[index - 1].section
                Crossfade(index, animationSpec = tween(if (cut) 0 else 600), label = "reel", modifier = Modifier.fillMaxSize().clickable { playing = !playing }) { i ->
                    val t = if (i == index) progress.value else 1f
                    when (val part = parts.getOrNull(i)) {
                        null -> Unit
                        is CoverPart -> CoverFrame(h, t, playing)
                        is TravelPart -> TravelFrame(part, t)
                        is PhotoPart -> ReelFrame(part, t)
                        is OutroPart -> OutroFrame(h)
                    }
                }
                SectionBars(parts, index, progress.value)
                if (!playing) Box(Modifier.align(Alignment.Center).size(84.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)).clickable { playing = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, "Play highlight", tint = ReelGreen, modifier = Modifier.size(48.dp))
                }
            }
            IconButton(onClick = dismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 18.dp, end = 6.dp)) { Icon(Icons.Default.Close, "Close highlight", tint = Color.White) }
            if (h != null && !playing) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, ReelDark.copy(alpha = 0.9f)))).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (h.days.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(h.days) { day -> FilterChip(day == h.date, { vm.loadHighlight(day) }, label = { Text(runCatching { LocalDate.parse(day).format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)) }.getOrDefault(day)) },
                        colors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedContainerColor = Color.White, selectedLabelColor = ReelGreen)) }
                }
                if (h.scenes.isNotEmpty()) OutlinedButton(onClick = vm::makeHighlightCover, enabled = !vm.highlightCovering, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                    if (vm.highlightCovering) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp) else Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (h.coverUrl != null) "Remake AI cover" else "AI cover")
                }
            }
        }
    }
}

/** One bar per section: cover, each place (the trip there and its photos), outro. */
@Composable private fun SectionBars(parts: List<ReelPart>, index: Int, progress: Float) {
    val sections = parts.indices.groupBy { parts[it].section }.values.toList()
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        sections.forEach { members ->
            val total = members.sumOf { parts[it].seconds }
            val done = members.sumOf { i -> if (i < index) parts[i].seconds else if (i == index) parts[i].seconds * progress else 0.0 }
            LinearProgressIndicator(progress = { (done / total).toFloat() }, modifier = Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)), color = Color.White, trackColor = Color.White.copy(alpha = 0.35f))
        }
    }
}

@Composable private fun CoverFrame(h: Highlight, t: Float, playing: Boolean) {
    Box(Modifier.fillMaxSize()) {
        val zoom = 1.05f + 0.12f * (if (playing) t else 0f)
        NetworkImage(model = h.coverUrl ?: h.basePhotoUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)), startY = 600f)))
        val c = h.cover
        Column(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 28.dp, end = 28.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("HIGHLIGHTS · " + (c?.eyebrow ?: "Day ${h.dayNumber}").uppercase(), color = Color(0xFFCFE0A5), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
            Text(c?.title ?: h.title, color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineLarge)
            Text(c?.date ?: h.subtitle, color = Color.White.copy(alpha = 0.95f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Box(Modifier.padding(vertical = 4.dp).width(40.dp).height(2.dp).background(Color.White.copy(alpha = 0.45f)))
            c?.destination?.let { Text(it, color = Color.White, fontWeight = FontWeight.SemiBold) }
            c?.route?.let { Text(it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium) }
            c?.stats?.takeIf { it.isNotBlank() }?.let { Text(it, color = ReelMint, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable private fun OutroFrame(h: Highlight) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(ReelGreen, ReelDark))), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("That was Day ${h.dayNumber}", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineMedium)
        Text(h.cover?.title ?: h.title, color = Color.White.copy(alpha = 0.85f))
        Text("BarkadaKasKas", color = Color(0xFFCFE0A5), fontWeight = FontWeight.SemiBold)
    }
}

/** The trip to the next stop: the transport icon moves along the route on the map; a pin pops up on arrival. */
@Composable private fun TravelFrame(part: TravelPart, t: Float) {
    val travel = part.travel
    val move = ease(((t - 0.08f) / 0.58f).coerceIn(0f, 1f))
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val hgt = constraints.maxHeight.toFloat()
        val hasMap = travel.mapUrl != null
        // The map image is 720 × 1280 and fills the screen (cropped); place the stops on it the same way.
        val s = max(w / 720f, hgt / 1280f); val ox = (w - 720f * s) / 2; val oy = (hgt - 1280f * s) / 2
        val a = if (hasMap) Offset(travel.fromX * 720f * s + ox, travel.fromY * 1280f * s + oy) else Offset(w * 0.18f, hgt * 0.54f)
        val b = if (hasMap) Offset(travel.toX * 720f * s + ox, travel.toY * 1280f * s + oy) else Offset(w * 0.82f, hgt * 0.46f)
        val line = if (hasMap) ReelGreen else ReelMint
        val focus = Offset(a.x + (b.x - a.x) * move, a.y + (b.y - a.y) * move)
        val zoom = 1f + 0.12f * t
        Box(Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom; scaleY = zoom
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(focus.x / w, focus.y / hgt)
        }) {
            if (hasMap) {
                NetworkImage(model = travel.mapUrl, contentDescription = "Map from ${travel.from} to ${part.scene.title}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(ReelDark.copy(alpha = 0.12f)))
            } else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2D6A55), ReelDark))))
            Canvas(Modifier.fillMaxSize()) {
                val full = Path().apply { (0..60).forEach { i -> val p = arcPoint(a, b, travel.mode, i / 60f); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                drawPath(full, line.copy(alpha = 0.45f), style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 9.dp.toPx()))))
                val done = Path().apply { (0..60).forEach { i -> val p = arcPoint(a, b, travel.mode, i / 60f * move); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                drawPath(done, line, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(Color.White, 8.dp.toPx(), a); drawCircle(line, 4.5.dp.toPx(), a)
            }
            // The transport icon in a white disc, bobbing while it moves.
            val here = arcPoint(a, b, travel.mode, move)
            val bob = if (move < 1f) sin(t * Math.PI.toFloat() * 6) * with(density) { 2.dp.toPx() } else 0f
            val disc = with(density) { 46.dp.toPx() }
            Surface(shape = CircleShape, color = Color.White, shadowElevation = 6.dp, border = androidx.compose.foundation.BorderStroke(2.5.dp, ReelGreen),
                modifier = Modifier.size(46.dp).offset { IntOffset((here.x - disc / 2).roundToInt(), (here.y + bob - disc / 2).roundToInt()) }) {
                Box(contentAlignment = Alignment.Center) { Icon(reelIcon(travel.mode), travel.label, tint = ReelGreen, modifier = Modifier.size(26.dp)) }
            }
            val pop = ((t - 0.6f) / 0.1f).coerceIn(0f, 1f)
            if (pop > 0f) {
                val pin = with(density) { 38.dp.toPx() }
                Surface(shape = CircleShape, color = Color.White, shadowElevation = 6.dp, modifier = Modifier.size(38.dp)
                    .offset { IntOffset((b.x - pin / 2).roundToInt(), (b.y - disc / 2 - pin * 1.1f).roundToInt()) }
                    .graphicsLayer { val k = if (pop < 1f) 0.5f + 0.7f * pop else 1f; scaleX = k; scaleY = k }) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.LocationOn, null, tint = ReelGreen, modifier = Modifier.size(24.dp)) }
                }
            }
            if (!hasMap) {
                Text(travel.from, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, textAlign = TextAlign.Center,
                    modifier = Modifier.width(140.dp).offset { IntOffset((a.x - with(density) { 50.dp.toPx() }).roundToInt(), (a.y + with(density) { 34.dp.toPx() }).roundToInt()) })
            }
        }
        // Header card: transport, from → to, arrival time.
        Surface(color = Color.White.copy(alpha = 0.95f), shape = RoundedCornerShape(22.dp), shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 30.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = ReelMint, shape = RoundedCornerShape(50)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(reelIcon(travel.mode), null, tint = ReelGreen, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                            Text(travel.label, color = ReelGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Spacer(Modifier.weight(1f)); Text(part.scene.time, color = Color(0xFF5B6B64), fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(travel.from, color = Color(0xFF5B6B64), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color(0xFF5B6B64), modifier = Modifier.padding(start = 4.dp).size(16.dp))
                }
                Text(part.scene.title, color = Color(0xFF17302A), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A reel frame: blurred fill behind the photo, a quick punch-in and flash on each cut, place sticker, poster and caption. */
@Composable private fun ReelFrame(part: PhotoPart, t: Float) {
    val scene = part.scene; val photo = scene.photos[part.photo]
    val local = t * photo.duration.toFloat()
    val punch = if (local < 0.22f) 1.1f - 0.1f * ease(local / 0.22f) else 1f + 0.05f * (t - 0.1f)
    Box(Modifier.fillMaxSize().background(ReelDark)) {
        if (Build.VERSION.SDK_INT >= 31) NetworkImage(model = photo.url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.1f; scaleY = 1.1f }.blur(28.dp))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        NetworkImage(model = photo.url, contentDescription = photo.caption ?: scene.title, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = punch; scaleY = punch; translationX = (if (part.photo % 2 == 0) -1 else 1) * 18f * t })
        if (local < 0.12f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.35f * (1 - local / 0.12f))))
        Box(Modifier.fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)), startY = 1200f)))
        // Place sticker, time and photo counter.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 52.dp, top = 34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(color = Color.White.copy(alpha = 0.95f), shape = RoundedCornerShape(50), modifier = Modifier.weight(1f, fill = false).shadow(4.dp, RoundedCornerShape(50))) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, null, tint = ReelGreen, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(scene.title, color = Color(0xFF17302A), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Surface(color = ReelGreen.copy(alpha = 0.92f), shape = RoundedCornerShape(50)) { Text(scene.time, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = ReelMint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
            if (scene.photos.size > 1) Text("${part.photo + 1}/${scene.photos.size}", color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        }
        // Who posted it and the caption, bottom left like a reel.
        Column(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            photo.by?.let { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(ReelMint), contentAlignment = Alignment.Center) { Text(name.take(1).uppercase(), color = ReelGreen, fontWeight = FontWeight.ExtraBold) }
                    Spacer(Modifier.width(8.dp)); Text(name, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            (photo.caption ?: scene.location?.split(",")?.take(2)?.joinToString(","))?.let {
                Text(it, color = Color.White.copy(alpha = if (photo.caption != null) 1f else 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

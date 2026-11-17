package com.barkadakaskas.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.barkadakaskas.app.domain.DeckDay
import com.barkadakaskas.app.domain.Presentation
import com.barkadakaskas.app.domain.TripBudget
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val DeckDark = Color(0xFF10231B)
private val DeckPaper = Color(0xFFF8F6EF)
private val DeckGreen = Color(0xFF214E3E)
private val DeckOrange = Color(0xFFD9643A)
private val DeckMuted = Color(0xFF6C7C72)
private val DeckInk = Color(0xFF1D2D24)
private val DeckPlanned = Color(0xFFCFDCC4)
private val DeckLine = Color(0xFFE3E9E1)

private sealed interface DeckSlide {
    data object Cover : DeckSlide
    data object Overview : DeckSlide
    data class Day(val day: DeckDay) : DeckSlide
    data object Budget : DeckSlide
    data object End : DeckSlide
}

private val categoryLabels = mapOf("food" to "Food", "transport" to "Transport", "activities" to "Activities", "lodging" to "Lodging", "other" to "Other")

/** A photo in the viewer (from a day's photos or a map pin). */
private data class ViewerPhoto(val url: String, val caption: String?, val by: String?, val time: String)

/**
 * The trip presentation, full screen, every slide fitting the screen (nothing scrolls): cover, the days at a glance,
 * one slide per day (its map with the route, numbered stops and photos pinned; tap a stop to zoom to it; tap a photo
 * to view and swipe through), the AI's planned budget next to what was spent (tap a day for its breakdown), and a
 * closing slide. Swipe or tap the arrows; Play moves on by itself.
 */
@Composable internal fun TripPresentation(vm: TravelViewModel, dismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadPresentation() }
    val deck = vm.presentation
    val slides = remember(deck) {
        deck?.let { d -> buildList { add(DeckSlide.Cover); add(DeckSlide.Overview); d.days.forEach { add(DeckSlide.Day(it)) }; add(DeckSlide.Budget); add(DeckSlide.End) } } ?: emptyList()
    }
    var index by remember(deck) { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Pair<List<ViewerPhoto>, Int>?>(null) }
    val go = { to: Int, manual: Boolean -> if (manual) playing = false; index = to.coerceIn(0, (slides.size - 1).coerceAtLeast(0)) }
    LaunchedEffect(playing, index) {
        if (!playing) return@LaunchedEffect
        delay(7000)
        if (index >= slides.size - 1) playing = false else index++
    }
    // The phone's status and navigation bar sizes, read from the app's window: some phones report none inside a
    // full-screen dialog, which put the slide controls under the back/home buttons.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val outerTop = WindowInsets.statusBars.getTop(density); val outerBottom = WindowInsets.navigationBars.getBottom(density)
    // Full screen, told the bar sizes so the controls keep clear of the clock and the phone's buttons.
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = androidx.compose.ui.platform.LocalView.current
        SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
            }
        }
        val top = with(density) { maxOf(outerTop, WindowInsets.statusBars.getTop(density)).toDp() }
        val bottom = with(density) { maxOf(outerBottom, WindowInsets.navigationBars.getBottom(density)).toDp() }
        Box(Modifier.fillMaxSize().background(DeckDark).padding(top = top, bottom = bottom)) {
            Column(Modifier.fillMaxSize()) {
                val progress by animateFloatAsState(if (slides.isEmpty()) 0f else (index + 1f) / slides.size, label = "progress")
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Color(0xFFF0A47E), trackColor = Color.White.copy(alpha = 0.12f))
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(deck?.title ?: "Trip presentation", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (deck != null) IconButton(onClick = { if (playing) playing = false else { if (index >= slides.size - 1) index = 0; playing = true } }) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White)
                    }
                    IconButton(onClick = dismiss) { Icon(Icons.Default.Close, "Close presentation", tint = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
                    when {
                        vm.presentationError != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(vm.presentationError.orEmpty(), color = Color.White, textAlign = TextAlign.Center); Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = vm::loadPresentation) { Text("Try again", color = Color.White) }
                        }
                        deck == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White); Spacer(Modifier.height(12.dp))
                            Text("Putting your trip together…", color = Color.White)
                        }
                        else -> {
                            var drag by remember { mutableFloatStateOf(0f) }
                            AnimatedContent(index, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "slide",
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).pointerInput(slides.size) {
                                    detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { if (abs(drag) > 120f) go(index + if (drag < 0) 1 else -1, true) }) { _, amount -> drag += amount }
                                }) { current ->
                                when (val slide = slides.getOrNull(current)) {
                                    DeckSlide.Cover -> CoverSlide(deck, end = false) { go(1, true) }
                                    DeckSlide.End -> CoverSlide(deck, end = true) { go(0, true) }
                                    DeckSlide.Overview -> OverviewSlide(deck) { day -> go(slides.indexOfFirst { it is DeckSlide.Day && it.day == day }, true) }
                                    is DeckSlide.Day -> DaySlide(slide.day, deck.currency) { photos, at -> playing = false; viewer = photos to at }
                                    DeckSlide.Budget -> BudgetSlide(vm.budget, vm.budgetError, deck.currency, vm::loadBudget)
                                    null -> Box(Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
                if (slides.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { go(index - 1, true) }, enabled = index > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous slide") }
                    val label = when (val s = slides.getOrNull(index)) { is DeckSlide.Day -> "Day ${s.day.dayNumber}"; DeckSlide.Budget -> "Budget"; DeckSlide.Overview -> "Overview"; else -> "" }
                    Text("$label  ${index + 1}/${slides.size}".trim(), color = Color(0xFFE2EADF), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    FilledTonalIconButton(onClick = { go(index + 1, true) }, enabled = index < slides.size - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next slide") }
                }
            }
            viewer?.let { (photos, at) -> PhotoViewer(photos, at, onStep = { viewer = photos to it }) { viewer = null } }
        }
    }
}

@Composable private fun PhotoViewer(photos: List<ViewerPhoto>, at: Int, onStep: (Int) -> Unit, close: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = close)
    val photo = photos.getOrNull(at) ?: return
    val step = { by: Int -> onStep((at + by + photos.size) % photos.size) }
    var drag by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().background(Color(0xF0060F0C)).pointerInput(at) {
        detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { if (abs(drag) > 100f && photos.size > 1) step(if (drag < 0) 1 else -1) }) { _, amount -> drag += amount }
    }) {
        Column(Modifier.align(Alignment.Center).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AsyncImage(photo.url, photo.caption ?: "Photo", Modifier.fillMaxWidth().weight(1f, fill = false).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
            photo.caption?.let { Text(it, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(listOfNotNull(photo.by, photo.time, if (photos.size > 1) "${at + 1}/${photos.size}" else null).joinToString(" · "), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
        }
        IconButton(onClick = close, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Default.Close, "Close photo", tint = Color.White) }
        if (photos.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilledTonalIconButton(onClick = { step(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous photo") }
            FilledTonalIconButton(onClick = { step(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next photo") }
        }
    }
}

/** A slide's page: fixed to the screen, children share the height (no scrolling). */
@Composable private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(DeckPaper).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable private fun SlideHead(title: String, chip: String? = null, sub: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            chip?.let { Surface(shape = CircleShape, color = DeckGreen) { Text(it, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) }; Spacer(Modifier.width(8.dp)) }
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = DeckInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        sub?.let { Text(it, color = DeckMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable private fun CoverSlide(deck: Presentation, end: Boolean, action: () -> Unit) {
    Box(Modifier.fillMaxSize().background(DeckGreen)) {
        deck.coverUrl?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = if (end) 0.45f else 1f) }
        Column(Modifier.align(if (end) Alignment.Center else Alignment.BottomStart).fillMaxWidth()
            .then(if (end) Modifier else Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6091A13)))))
            .padding(20.dp), horizontalAlignment = if (end) Alignment.CenterHorizontally else Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!end) Text((if (deck.ended) "Trip recap" else deck.destination).uppercase(), color = Color(0xFFFFC9A8), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (end) "Salamat, barkada!" else deck.title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 32.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = if (end) TextAlign.Center else TextAlign.Start)
            Text(if (end) deck.title else deck.dates, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!end) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                deck.stats.forEach { (value, label) -> Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.16f)) { Text("$value $label", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) } }
            }
            Button(onClick = action, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = DeckGreen)) {
                if (end) { Icon(Icons.Default.Replay, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Watch again", fontWeight = FontWeight.Bold) }
                else { Text("Start", fontWeight = FontWeight.Bold); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
            }
        }
    }
}

@Composable private fun OverviewSlide(deck: Presentation, open: (DeckDay) -> Unit) = Page {
    SlideHead("Day by day", sub = if (deck.spentMinor > 0) "${money(deck.spentMinor, deck.currency)} spent" else null)
    if (deck.days.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Add itinerary stops or post photos and they’ll show up here.", color = DeckMuted, textAlign = TextAlign.Center) }
    else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        deck.days.chunked(2).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { day ->
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(DeckGreen).clickable { open(day) }) {
                        day.photos.firstOrNull()?.let { AsyncImage(it.url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.75f) }
                        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))).padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text("Day ${day.dayNumber}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("${day.label.substringAfter(" · ")} · ${day.stops.size} ${if (day.stops.size == 1) "stop" else "stops"}", color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun DaySlide(day: DeckDay, currency: String, openPhoto: (List<ViewerPhoto>, Int) -> Unit) = Page {
    var focus by remember(day.date) { mutableStateOf<Int?>(null) }
    val photos = remember(day) { day.photos.map { ViewerPhoto(it.url, it.caption, it.by, it.time) } }
    SlideHead(day.label.substringAfter(" · "), chip = "Day ${day.dayNumber}",
        sub = listOfNotNull("${day.stops.size} ${if (day.stops.size == 1) "stop" else "stops"}", "${day.photoCount} ${if (day.photoCount == 1) "photo" else "photos"}", if (day.spentMinor > 0) money(day.spentMinor, currency) else null).joinToString(" · "))
    // The map takes the room that's left.
    Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFE7ECE2))) {
        if (hasGoogleMaps && (day.stops.any { it.onMap } || day.pins.isNotEmpty())) PresentationDayMap(day.stops, day.pins, Modifier.fillMaxSize(), focus) { pin ->
            val at = photos.indexOfFirst { it.url == pin.url }
            if (at >= 0) openPhoto(photos, at) else openPhoto(listOf(ViewerPhoto(pin.url, pin.caption, pin.by, pin.time)), 0)
        } else Text("No places on the map for this day.", color = DeckMuted, modifier = Modifier.align(Alignment.Center))
        if (focus != null) Surface(onClick = { focus = null }, shape = CircleShape, color = Color.White, shadowElevation = 3.dp, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Text("Whole day", color = DeckGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
    // Stops: tap to zoom the map to one.
    if (day.stops.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        day.stops.forEach { stop ->
            val on = focus == stop.n
            Surface(onClick = { focus = if (on) null else stop.n }, enabled = stop.onMap, shape = CircleShape, color = if (on) DeckGreen else Color.White, border = BorderStroke(1.dp, DeckLine)) {
                Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(DeckOrange), contentAlignment = Alignment.Center) { Text("${stop.n}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                    Spacer(Modifier.width(6.dp))
                    Text(stop.title, color = if (on) Color.White else DeckInk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                }
            }
        }
    }
    // Photos: tap to view, then swipe through.
    if (photos.isNotEmpty()) Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        photos.take(5).forEachIndexed { i, photo ->
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(Color(0xFFE7ECE2)).clickable { openPhoto(photos, i) }) {
                AsyncImage(photo.url, photo.caption ?: "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (i == 4 && photos.size > 5) Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) { Text("+${photos.size - 4}", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
        repeat((5 - photos.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable private fun BudgetSlide(budget: TripBudget?, error: String?, currency: String, retry: () -> Unit) = Page {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Budget", fontWeight = FontWeight.Bold, fontSize = 19.sp, color = DeckInk); Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.AutoAwesome, null, tint = DeckOrange, modifier = Modifier.size(13.dp)); Text(" AI plan vs actual", color = DeckMuted, fontSize = 12.sp)
    }
    when {
        error != null -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Text(error, color = DeckMuted, textAlign = TextAlign.Center); OutlinedButton(onClick = retry) { Text("Try again") } }
        budget == null -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = DeckGreen); Spacer(Modifier.height(8.dp)); Text("Estimating your budget…", color = DeckMuted) }
        else -> {
            val planned = budget.available && !budget.failed
            var picked by remember { mutableStateOf<String?>(null) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("Planned", if (planned) money(budget.totalEstimateMinor, currency) else "—", if (planned) "${money(budget.perPersonEstimateMinor, currency)} each" else if (budget.available) "AI unavailable" else "AI not set up", Modifier.weight(1f), onClick = if (budget.failed) retry else null)
                Tile("Spent", money(budget.totalActualMinor, currency), "${money(budget.perPersonActualMinor, currency)} each", Modifier.weight(1f))
                if (planned && budget.totalEstimateMinor > 0) {
                    val over = budget.totalActualMinor > budget.totalEstimateMinor
                    Tile(if (over) "Over" else "Under", money(abs(budget.totalActualMinor - budget.totalEstimateMinor), currency), "${(budget.totalActualMinor * 100.0 / budget.totalEstimateMinor).roundToInt()}% of plan", Modifier.weight(1f), if (over) Color(0xFFA6573E) else Color(0xFF3D7650))
                }
            }
            // Bars per day (planned and spent); tap a day for its breakdown.
            val max = (budget.days.flatMap { listOf(it.estimateMinor, it.actualMinor) }.maxOrNull() ?: 0L).coerceAtLeast(1L)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                budget.days.forEach { d ->
                    val on = picked == d.date
                    Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(if (on) Color(0xFFEEF2EA) else Color.Transparent).clickable { picked = if (on) null else d.date }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
                            Box(Modifier.weight(1f, fill = false).widthIn(max = 14.dp).fillMaxWidth().fillMaxHeight((d.estimateMinor.toFloat() / max).coerceIn(0.01f, 1f)).background(DeckPlanned, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                            Box(Modifier.weight(1f, fill = false).widthIn(max = 14.dp).fillMaxWidth().fillMaxHeight((d.actualMinor.toFloat() / max).coerceIn(0.01f, 1f)).background(DeckGreen, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                        }
                        Text(d.label.substringBefore(" · "), color = DeckMuted, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
            val day = budget.days.find { it.date == picked }
            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (day != null) {
                    Text("${day.label} · planned ${money(day.estimateMinor, currency)} · spent ${money(day.actualMinor, currency)}", color = DeckInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Chips(day.items.take(4).map { (label, amount) -> "$label ${money(amount, currency)}" })
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(DeckPlanned, RoundedCornerShape(2.dp))); Text(" Planned  ", color = DeckMuted, fontSize = 11.sp)
                        Box(Modifier.size(10.dp).background(DeckGreen, RoundedCornerShape(2.dp))); Text(" Spent · tap a day", color = DeckMuted, fontSize = 11.sp)
                    }
                    if (budget.categories.isNotEmpty()) Chips(budget.categories.take(4).map { (c, amount) -> "${categoryLabels[c] ?: c} ${money(amount, currency)}" })
                    budget.notes.firstOrNull()?.let { Text(it, color = Color(0xFF3F5A4B), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable private fun Chips(labels: List<String>) = Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
    labels.forEach { Surface(shape = CircleShape, color = Color.White, border = BorderStroke(1.dp, DeckLine)) { Text(it, color = DeckInk, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)) } }
}

@Composable private fun Tile(label: String, value: String, detail: String, modifier: Modifier, valueColor: Color = DeckInk, onClick: (() -> Unit)? = null) {
    Surface(modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), shape = RoundedCornerShape(12.dp), color = Color.White, border = BorderStroke(1.dp, DeckLine)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, color = DeckMuted, fontSize = 11.sp)
            Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = DeckMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

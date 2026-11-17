package com.barkadakaskas.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
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
import com.barkadakaskas.app.domain.DeckMapPhoto
import com.barkadakaskas.app.domain.Presentation
import com.barkadakaskas.app.domain.TripBudget
import kotlin.math.abs

private val DeckDark = Color(0xFF10231B)
private val DeckPaper = Color(0xFFF8F6EF)
private val DeckGreen = Color(0xFF214E3E)
private val DeckOrange = Color(0xFFD9643A)
private val DeckMuted = Color(0xFF6C7C72)
private val DeckPlanned = Color(0xFFCFDCC4)

private sealed interface DeckSlide {
    data object Cover : DeckSlide
    data object Overview : DeckSlide
    data class DayMap(val day: DeckDay) : DeckSlide
    data class DayPhotos(val day: DeckDay) : DeckSlide
    data object Budget : DeckSlide
    data object End : DeckSlide
}

private val categoryLabels = mapOf("food" to "Food & drinks", "transport" to "Getting around", "activities" to "Activities & fees", "lodging" to "Lodging", "other" to "Other")

/**
 * The trip presentation, full screen: cover, overview, each day's route on a map (numbered stops, some of the day's
 * photos pinned where they were taken) and its best photos, the AI's planned budget next to what was spent, and a
 * closing slide. Swipe or tap the arrows to move.
 */
@Composable internal fun TripPresentation(vm: TravelViewModel, dismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadPresentation() }
    val deck = vm.presentation
    val slides = remember(deck) {
        deck?.let { d -> buildList { add(DeckSlide.Cover); add(DeckSlide.Overview); d.days.forEach { add(DeckSlide.DayMap(it)); if (it.photos.isNotEmpty()) add(DeckSlide.DayPhotos(it)) }; add(DeckSlide.Budget); add(DeckSlide.End) } } ?: emptyList()
    }
    var index by remember(deck) { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<DeckMapPhoto?>(null) }
    val go = { to: Int -> index = to.coerceIn(0, (slides.size - 1).coerceAtLeast(0)) }
    // Full screen, told the bar sizes so the controls keep clear of the clock and the phone's buttons.
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = androidx.compose.ui.platform.LocalView.current
        SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
            }
        }
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(DeckDark).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(deck?.title ?: "Trip presentation", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (slides.isNotEmpty()) Text("${index + 1} / ${slides.size}", color = Color(0xFFB7C8B9), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = dismiss) { Icon(Icons.Default.Close, "Close presentation", tint = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                when {
                    vm.presentationError != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(vm.presentationError.orEmpty(), color = Color.White, textAlign = TextAlign.Center); Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = vm::loadPresentation) { Text("Try again", color = Color.White) }
                    }
                    deck == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White); Spacer(Modifier.height(12.dp))
                        Text("Putting your trip together: maps, routes and photos…", color = Color.White, textAlign = TextAlign.Center)
                    }
                    else -> {
                        var drag by remember { mutableFloatStateOf(0f) }
                        AnimatedContent(index, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "slide",
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).pointerInput(slides.size) {
                                detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { if (abs(drag) > 120f) go(index + if (drag < 0) 1 else -1) }) { _, amount -> drag += amount }
                            }) { current ->
                            when (val slide = slides.getOrNull(current)) {
                                DeckSlide.Cover -> CoverSlide(deck, end = false)
                                DeckSlide.End -> CoverSlide(deck, end = true)
                                DeckSlide.Overview -> OverviewSlide(deck) { day -> go(slides.indexOfFirst { it is DeckSlide.DayMap && it.day == day }) }
                                is DeckSlide.DayMap -> DayMapSlide(slide.day) { viewing = it }
                                is DeckSlide.DayPhotos -> DayPhotosSlide(slide.day)
                                DeckSlide.Budget -> BudgetSlide(vm.budget, vm.budgetError, deck.currency, vm::loadBudget)
                                null -> Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            if (slides.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { go(index - 1) }, enabled = index > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous slide") }
                LinearProgressIndicator(progress = { (index + 1f) / slides.size }, modifier = Modifier.weight(1f).padding(horizontal = 12.dp).height(4.dp).clip(CircleShape), color = Color.White, trackColor = Color.White.copy(alpha = 0.2f))
                FilledTonalIconButton(onClick = { go(index + 1) }, enabled = index < slides.size - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next slide") }
            }
        }
        viewing?.let { photo ->
            androidx.activity.compose.BackHandler { viewing = null }
            Box(Modifier.fillMaxSize().background(Color(0xE6060F0C)).systemBarsPadding().pointerInput(photo) { detectTapGestures { viewing = null } }, contentAlignment = Alignment.Center) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(photo.url, photo.caption ?: "Photo", Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.FillWidth)
                    photo.caption?.let { Text(it, color = Color.White) }
                    Text(listOfNotNull(photo.by, photo.time).joinToString(" · "), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = { viewing = null }, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Default.Close, "Close photo", tint = Color.White) }
            }
        }
        }
    }
}

@Composable private fun SlidePage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(DeckPaper).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable private fun Eyebrow(text: String, color: Color = DeckOrange) = Text(text.uppercase(), color = color, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, style = MaterialTheme.typography.labelMedium)

@Composable private fun CoverSlide(deck: Presentation, end: Boolean) {
    Box(Modifier.fillMaxSize().background(DeckGreen)) {
        deck.coverUrl?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = if (end) 0.5f else 1f) }
        Column(Modifier.align(if (end) Alignment.Center else Alignment.BottomStart).fillMaxWidth()
            .background(if (end) Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)) else Brush.verticalGradient(listOf(Color.Transparent, Color(0xE0091A13))))
            .padding(24.dp), horizontalAlignment = if (end) Alignment.CenterHorizontally else Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!end) Eyebrow(if (deck.ended) "Trip recap" else "Trip presentation", Color(0xFFFFC9A8))
            Text(if (end) "Salamat, barkada!" else deck.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp, textAlign = if (end) TextAlign.Center else TextAlign.Start)
            Text(if (end) "${deck.title} · ${deck.dates}" else "${deck.destination} · ${deck.dates}", color = Color.White.copy(alpha = 0.92f), textAlign = if (end) TextAlign.Center else TextAlign.Start)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                deck.stats.forEach { (value, label) -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp); Text(label, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall) } }
            }
        }
    }
}

@Composable private fun OverviewSlide(deck: Presentation, open: (DeckDay) -> Unit) = SlidePage {
    Text("The trip at a glance", fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Color(0xFF1D2D24))
    if (deck.days.isEmpty()) Text("Add itinerary stops or post photos and they will show up here.", color = DeckMuted)
    deck.days.forEach { day ->
        Surface(onClick = { open(day) }, shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFE3E9E1))) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = DeckGreen) { Text("Day ${day.dayNumber}", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(day.route, fontWeight = FontWeight.SemiBold, color = Color(0xFF1D2D24), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${day.label.substringAfter(" · ")} · ${day.stops.size} ${if (day.stops.size == 1) "stop" else "stops"} · ${day.photoCount} ${if (day.photoCount == 1) "photo" else "photos"}", color = DeckMuted, style = MaterialTheme.typography.bodySmall)
                }
                if (day.spentMinor > 0) Text(money(day.spentMinor, deck.currency), color = Color(0xFF3D7650), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (deck.spentMinor > 0) Text("Group spending so far: ${money(deck.spentMinor, deck.currency)}", color = Color(0xFF1D2D24))
}

@Composable private fun DayMapSlide(day: DeckDay, onPhoto: (DeckMapPhoto) -> Unit) = SlidePage {
    Eyebrow(day.label)
    Text(day.route, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 27.sp, color = Color(0xFF1D2D24))
    val map = day.map
    // Google Maps, like the trip's Map tab: the route between the numbered stops and the day's photos pinned on it.
    if (hasGoogleMaps && (day.stops.any { it.onMap } || day.pins.isNotEmpty())) Box(Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFE7ECE2))) {
        PresentationDayMap(day.stops, day.pins, Modifier.fillMaxSize(), onPhoto)
    }
    else if (map != null) BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(640f / 400f).clip(RoundedCornerShape(16.dp)).background(Color(0xFFE7ECE2))) {
        AsyncImage(map.url, "Map of the route on ${day.label}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        val pin = 42.dp
        map.pins.filter { it.x in 0.03f..0.97f && it.y in 0.08f..0.99f }.forEach { p ->
            Box(Modifier.offset(x = maxWidth * p.x - pin / 2, y = maxHeight * p.y - pin - 6.dp).size(pin).clip(CircleShape).background(Color.White).border(3.dp, Color.White, CircleShape)) {
                AsyncImage(p.url, p.caption ?: "Photo", Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
            }
        }
    } else Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFE7ECE2)) { Text("No map for this day: the stops have no places yet.", color = DeckMuted, modifier = Modifier.fillMaxWidth().padding(20.dp), textAlign = TextAlign.Center) }
    day.stops.forEach { stop ->
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(if (stop.onMap) DeckOrange else Color(0xFFB8C2B9)), contentAlignment = Alignment.Center) { Text("${stop.n}", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stop.title + if (stop.done) " ✓" else "", fontWeight = FontWeight.SemiBold, color = Color(0xFF1D2D24))
                Text(listOfNotNull(stop.time, stop.location).joinToString(" · "), color = DeckMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (day.stops.isEmpty()) Text("No itinerary stops; photos from the day are pinned on the map.", color = DeckMuted)
}

@Composable private fun DayPhotosSlide(day: DeckDay) = SlidePage {
    Eyebrow(day.label)
    Text("Moments from the day", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color(0xFF1D2D24))
    day.photos.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { photo ->
                Box(Modifier.weight(1f).aspectRatio(if (day.photos.size == 1) 4f / 3f else 3f / 4f).clip(RoundedCornerShape(14.dp)).background(Color(0xFFE7ECE2))) {
                    AsyncImage(photo.url, photo.caption ?: "Trip photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xA6000000)))).padding(8.dp)) {
                        photo.caption?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Text(listOfNotNull(photo.by, photo.time).joinToString(" · "), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (row.size == 1 && day.photos.size > 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable private fun BudgetSlide(budget: TripBudget?, error: String?, currency: String, retry: () -> Unit) = SlidePage {
    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AutoAwesome, null, tint = DeckOrange, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Eyebrow("Planned by AI · actual from expenses") }
    Text("Budget: planned vs spent", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color(0xFF1D2D24))
    when {
        error != null -> { Text(error, color = DeckMuted); OutlinedButton(onClick = retry) { Text("Try again") } }
        budget == null -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), color = DeckGreen, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("The AI is estimating your budget from the itinerary…", color = DeckMuted) }
        else -> {
            val planned = budget.available && !budget.failed
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BudgetTotal("Planned (AI)", if (planned) money(budget.totalEstimateMinor, currency) else "—", if (planned) "${money(budget.perPersonEstimateMinor, currency)} each" else null, Modifier.weight(1f))
                BudgetTotal("Spent", money(budget.totalActualMinor, currency), "${money(budget.perPersonActualMinor, currency)} each", Modifier.weight(1f))
            }
            if (planned && budget.totalEstimateMinor > 0) {
                val over = budget.totalActualMinor > budget.totalEstimateMinor
                Text("${if (over) "Over" else "Under"} plan by ${money(abs(budget.totalActualMinor - budget.totalEstimateMinor), currency)}", color = if (over) Color(0xFFA6573E) else Color(0xFF3D7650), fontWeight = FontWeight.Bold)
            }
            if (!budget.available) Text("The AI isn't set up on this server, so only actual spending is shown.", color = DeckMuted, style = MaterialTheme.typography.bodySmall)
            else if (budget.failed) Row(verticalAlignment = Alignment.CenterVertically) { Text("The AI couldn't estimate the budget right now.", color = DeckMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)); TextButton(onClick = retry) { Text("Try again") } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(DeckPlanned, RoundedCornerShape(2.dp))); Text(" Planned   ", color = DeckMuted, style = MaterialTheme.typography.labelSmall)
                Box(Modifier.size(10.dp).background(DeckGreen, RoundedCornerShape(2.dp))); Text(" Spent", color = DeckMuted, style = MaterialTheme.typography.labelSmall)
            }
            val max = (budget.days.flatMap { listOf(it.estimateMinor, it.actualMinor) }.maxOrNull() ?: 0L).coerceAtLeast(1L)
            budget.days.forEach { d ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row { Text(d.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Color(0xFF1D2D24)); Text("${money(d.estimateMinor, currency)} / ${money(d.actualMinor, currency)}", style = MaterialTheme.typography.labelSmall, color = DeckMuted) }
                    Box(Modifier.fillMaxWidth((d.estimateMinor.toFloat() / max).coerceIn(0.01f, 1f)).height(8.dp).background(DeckPlanned, RoundedCornerShape(4.dp)))
                    Box(Modifier.fillMaxWidth((d.actualMinor.toFloat() / max).coerceIn(0.01f, 1f)).height(8.dp).background(DeckGreen, RoundedCornerShape(4.dp)))
                }
            }
            if (budget.categories.isNotEmpty()) {
                Text("Planned by category", fontWeight = FontWeight.Bold, color = Color(0xFF3F5A4B))
                budget.categories.forEach { (c, amount) -> Row { Text(categoryLabels[c] ?: c, Modifier.weight(1f), color = Color(0xFF1D2D24)); Text(money(amount, currency), fontWeight = FontWeight.SemiBold) } }
            }
            if (budget.actualCategories.isNotEmpty()) {
                Text("Spent by category", fontWeight = FontWeight.Bold, color = Color(0xFF3F5A4B))
                budget.actualCategories.take(5).forEach { (c, amount) -> Row { Text(c, Modifier.weight(1f), color = Color(0xFF1D2D24)); Text(money(amount, currency), fontWeight = FontWeight.SemiBold) } }
            }
            if (budget.notes.isNotEmpty()) {
                Text("AI notes", fontWeight = FontWeight.Bold, color = Color(0xFF3F5A4B))
                budget.notes.forEach { Text("• $it", color = Color(0xFF1D2D24), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable private fun BudgetTotal(label: String, value: String, detail: String?, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFE3E9E1))) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = DeckMuted, style = MaterialTheme.typography.labelMedium)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFF1D2D24))
            detail?.let { Text(it, color = DeckMuted, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

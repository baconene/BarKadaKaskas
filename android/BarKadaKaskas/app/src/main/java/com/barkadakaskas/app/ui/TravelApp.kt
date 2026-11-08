@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.barkadakaskas.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.barkadakaskas.app.data.Photos
import com.barkadakaskas.app.data.Places
import com.barkadakaskas.app.domain.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch

private val Forest = Color(0xFF214E3E)
private val ForestDark = Color(0xFF173A2E)
private val Moss = Color(0xFF72883A)
private val Sage = Color(0xFFE3EDD8)
private val Cream = Color(0xFFF7F8F4)
private val Ink = Color(0xFF172F2A)
private val Muted = Color(0xFF738178)
private val Line = Color(0xFFDFE6D9)
private val Danger = Color(0xFF9C3E2D)
private val colors = lightColorScheme(
    primary = Forest, onPrimary = Color.White, primaryContainer = Sage, onPrimaryContainer = ForestDark,
    secondary = Moss, onSecondary = Color.White, secondaryContainer = Color(0xFFEDF2E6), onSecondaryContainer = ForestDark,
    tertiary = Color(0xFF947344), background = Cream, onBackground = Ink, surface = Color.White, onSurface = Ink,
    surfaceVariant = Color(0xFFEFF3EA), onSurfaceVariant = Muted, surfaceContainer = Color.White, surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color.White, outline = Color(0xFFC9D4C6), outlineVariant = Line, error = Danger,
)
/** Card banners cycle through these, matching the web trip cards. */
private val banners = listOf(Color(0xFFDCE7CB) to Color(0xFF507044), Color(0xFFEEE3D1) to Color(0xFF947344), Color(0xFFDCE9EA) to Color(0xFF47787C))
private val transportOptions = listOf("" to "Starting point / decide later", "car" to "Car", "van" to "Van", "taxi" to "Taxi / ride-hailing", "motorcycle" to "Motorcycle", "tricycle" to "Tricycle", "walking" to "Walking", "bicycle" to "Bicycle", "transit" to "Public transit", "ferry" to "Ferry / boat", "flight" to "Flight", "other" to "Other")
private val timezones = listOf("Asia/Manila", "Asia/Singapore", "Asia/Tokyo", "Asia/Seoul", "Asia/Hong_Kong", "Asia/Bangkok", "Asia/Dubai", "Australia/Sydney", "Europe/London", "Europe/Paris", "America/New_York", "America/Los_Angeles", "UTC")

fun amount(value: String): Long? = try { BigDecimal(value.replace(",", "").trim()).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact().takeIf { it in 1..100000000 } } catch (_: Exception) { null }
fun money(value: Long, currency: String): String = NumberFormat.getCurrencyInstance(Locale("en", "PH")).apply { this.currency = Currency.getInstance(currency) }.format(BigDecimal.valueOf(value, 2))
private fun zoneOf(timezone: String): ZoneId = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("Asia/Manila"))
private fun editDate(value: String, timezone: String): String = runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zoneOf(timezone)).toLocalDate().toString() }.getOrElse { value.take(10) }
private fun editClock(value: String, timezone: String): String = runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zoneOf(timezone)).toLocalTime().withSecond(0).withNano(0).toString() }.getOrElse { "09:00" }
private fun initials(name: String) = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "?" }

/** Hands a Google Maps navigation link to the Google Maps app (turn-by-turn from the current location), else the browser. */
private fun startNavigation(context: Context, url: String) {
    val maps = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { context.startActivity(maps) } catch (_: ActivityNotFoundException) { openUrl(context, url) }
}

private fun openUrl(context: Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: ActivityNotFoundException) { Toast.makeText(context, "Install Google Maps or a web browser to open directions.", Toast.LENGTH_LONG).show() }
}

private fun kindIcon(kind: Itinerary.Kind): ImageVector = when (kind) {
    Itinerary.Kind.Food -> Icons.Default.Restaurant; Itinerary.Kind.Flight -> Icons.Default.Flight; Itinerary.Kind.Ferry -> Icons.Default.DirectionsBoat
    Itinerary.Kind.Stay -> Icons.Default.Hotel; Itinerary.Kind.Water -> Icons.Default.BeachAccess; Itinerary.Kind.Sight -> Icons.Default.PhotoCamera
    Itinerary.Kind.Shop -> Icons.Default.ShoppingBag; Itinerary.Kind.Place -> Icons.Default.Place
}
private fun transportIcon(mode: String?): ImageVector = when (mode) {
    "walking" -> Icons.AutoMirrored.Filled.DirectionsWalk; "bicycle" -> Icons.AutoMirrored.Filled.DirectionsBike; "motorcycle" -> Icons.Default.TwoWheeler
    "transit" -> Icons.Default.DirectionsBus; "ferry" -> Icons.Default.DirectionsBoat; "flight" -> Icons.Default.Flight; else -> Icons.Default.DirectionsCar
}

@Composable fun TravelApp(vm: TravelViewModel) {
    MaterialTheme(colorScheme = colors, shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp))) {
        val context = LocalContext.current
        LaunchedEffect(vm.invitationUrl) { vm.invitationUrl?.let { url ->
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Join our trip on BarkadaKasKas: $url") }, "Invite your barkada")); vm.consumeInvitation()
        } }
        Surface(Modifier.fillMaxSize(), color = Cream) {
            val screen = when { vm.user == null -> 0; vm.detail == null -> 1; else -> 2 }
            AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { target ->
                when (target) { 0 -> AuthScreen(vm); 1 -> TripsScreen(vm); else -> vm.detail?.let { TripScreen(vm, it) } }
            }
        }
    }
}

/** Shows API errors as a snackbar on full screens; open forms show them inline instead. */
@Composable private fun ErrorSnackbar(vm: TravelViewModel, host: SnackbarHostState) {
    LaunchedEffect(vm.error, vm.formOpen) {
        val message = vm.error ?: return@LaunchedEffect
        if (vm.formOpen) return@LaunchedEffect
        host.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
        vm.clearError()
    }
}

@Composable private fun ErrorNotice(vm: TravelViewModel) {
    AnimatedVisibility(vm.error != null) {
        Surface(color = Color(0xFFFFF0EC), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0xFFEDC1B5))) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, null, tint = Danger); Spacer(Modifier.width(10.dp))
                Text(vm.error.orEmpty(), Modifier.weight(1f), color = Danger, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = vm::clearError) { Icon(Icons.Default.Close, "Dismiss", tint = Danger) }
            }
        }
    }
}
@Composable private fun Eyebrow(text: String, color: Color = Moss) { Text(text.uppercase(), color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp) }
@Composable private fun InfoCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { title?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }; content() }
    }
}
@Composable private fun EmptyState(icon: ImageVector, title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Line)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(Sage), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Forest, modifier = Modifier.size(32.dp)) }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(description, color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            action?.let { Spacer(Modifier.height(4.dp)); Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(it) } }
        }
    }
}
@Composable private fun Pill(text: String, icon: ImageVector? = null, container: Color = Color(0xFFEDF2E6), content: Color = Color(0xFF526936)) {
    Surface(color = container, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, null, tint = content, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(5.dp)) }
            Text(text, color = content, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
@Composable private fun Avatar(name: String, highlight: Boolean = false, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(if (highlight) Forest else Sage), contentAlignment = Alignment.Center) {
        Text(initials(name), color = if (highlight) Color.White else Forest, fontWeight = FontWeight.Bold, fontSize = (size / 2.8).sp)
    }
}
@Composable private fun Field(label: String, value: String, change: (String) -> Unit, password: Boolean = false, number: Boolean = false, email: Boolean = false, prefix: String? = null, leading: ImageVector? = null, singleLine: Boolean = true, supporting: String? = null, capitalize: Boolean = !password && !email) {
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = singleLine, minLines = if (singleLine) 1 else 2,
        shape = RoundedCornerShape(12.dp), prefix = prefix?.let { { Text(it) } }, leadingIcon = leading?.let { { Icon(it, null) } }, supportingText = supporting?.let { { Text(it) } },
        trailingIcon = if (password) { { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (reveal) "Hide password" else "Show password") } } } else null,
        visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None, keyboardType = when { password -> KeyboardType.Password; number -> KeyboardType.Decimal; email -> KeyboardType.Email; else -> KeyboardType.Text }))
}
@Composable private fun Choice(label: String, value: String, options: List<Pair<String, String>>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(options.find { it.first == value }?.second ?: "Choose", {}, readOnly = true, label = { Text(label) }, shape = RoundedCornerShape(12.dp),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(expanded, { expanded = false }) { options.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { change(key); expanded = false }, trailingIcon = if (key == value) { { Icon(Icons.Default.Check, null, tint = Forest) } } else null) } }
    }
}
@Composable private fun Submit(label: String, busy: Boolean, enabled: Boolean = true, icon: ImageVector? = null, click: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Button({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); click() }, Modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = enabled && !busy, shape = RoundedCornerShape(14.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else { icon?.let { Icon(it, null); Spacer(Modifier.width(8.dp)) }; Text(label, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable private fun AuthScreen(vm: TravelViewModel) {
    var register by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Scaffold(containerColor = Cream) { padding -> Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Forest), contentAlignment = Alignment.Center) { Icon(Icons.Default.Explore, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
        Text("BarkadaKasKas", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold)
        Text("Good trips. Great company. Fair shares.", color = Moss, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(!register, { register = false; vm.clearError() }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Log in") }
            SegmentedButton(register, { register = true; vm.clearError() }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Sign up") }
        }
        ErrorNotice(vm)
        AnimatedVisibility(register) { Field("Your name", name, { name = it }, leading = Icons.Default.Person) }
        Field("Email", email, { email = it }, email = true, leading = Icons.Default.Email)
        Field("Password", password, { password = it }, password = true, leading = Icons.Default.Lock)
        Submit(if (register) "Create account" else "Log in", vm.busy, email.isNotBlank() && password.isNotBlank() && (!register || name.isNotBlank())) { vm.authenticate(vm.serverUrl, name, email, password, register) }
    } }
}

@Composable private fun TripsScreen(vm: TravelViewModel) {
    var create by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val list = rememberLazyListState()
    val today = LocalDate.now()
    val sorted = remember(vm.trips) { vm.trips.sortedBy { it.startsOn } }
    val active = sorted.filter { !it.cancelled && Itinerary.phase(it, today) != Itinerary.Phase.Past }
    val past = sorted.filter { it.cancelled || Itinerary.phase(it, today) == Itinerary.Phase.Past }.reversed()
    ErrorSnackbar(vm, snackbar)
    Scaffold(containerColor = Cream, snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("My trips", fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream),
            actions = { IconButton(vm::signOut, enabled = !vm.busy) { Icon(Icons.AutoMirrored.Filled.Logout, "Log out") } }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { create = true }, expanded = list.firstVisibleItemIndex == 0, containerColor = Forest, contentColor = Color.White,
            icon = { Icon(Icons.Default.Add, null) }, text = { Text("Plan a trip", fontWeight = FontWeight.SemiBold) }) }) { padding ->
        PullToRefreshBox(isRefreshing = vm.busy, onRefresh = vm::refresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Eyebrow("Your next chapter")
                        Text("Where to, ${vm.user?.name?.substringBefore(' ').orEmpty()}?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Plans, people and every shared peso, all in one place.", color = Muted)
                    }
                }
                if (vm.trips.isEmpty() && !vm.busy) item { EmptyState(Icons.Default.Luggage, "Your adventures start here", "Create a trip, invite your barkada, and leave the expense math to us.", "Plan your first trip") { create = true } }
                active.firstOrNull()?.let { next -> item(key = "hero-${next.id}") { HeroTrip(next, today, Modifier.animateItem()) { vm.openTrip(next) } } }
                if (active.size > 1) {
                    item { SectionTitle("Coming up", active.size - 1) }
                    itemsIndexed(active.drop(1), key = { _, trip -> trip.id }) { index, trip -> TripCard(trip, index + 1, today, Modifier.animateItem()) { vm.openTrip(trip) } }
                }
                if (past.isNotEmpty()) {
                    item { SectionTitle(if (past.any { it.cancelled }) "Past & cancelled" else "Past trips", past.size) }
                    itemsIndexed(past, key = { _, trip -> trip.id }) { index, trip -> TripCard(trip, index, today, Modifier.animateItem()) { vm.openTrip(trip) } }
                }
            }
        }
    }
    if (create) TripForm(vm) { create = false }
}

@Composable private fun SectionTitle(title: String, count: Int) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp))
        Text("$count", color = Muted, style = MaterialTheme.typography.titleMedium)
    }
}

/** The next (or ongoing) trip, shown big with a countdown. */
@Composable private fun HeroTrip(trip: Trip, today: LocalDate, modifier: Modifier, open: () -> Unit) {
    val phase = Itinerary.phase(trip, today)
    Card(onClick = open, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Forest)) {
        Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Forest, Color(0xFF2F6B52))))) {
            if (trip.coverUrl != null) {
                AsyncImage(model = trip.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                // Darkens the photo so the white text stays readable.
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.7f)))))
            } else Icon(Icons.Default.Landscape, null, tint = Color.White.copy(alpha = 0.08f), modifier = Modifier.size(170.dp).align(Alignment.BottomEnd).offset(x = 30.dp, y = 40.dp))
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(Itinerary.phaseLabel(phase), Icons.Default.Schedule, Color.White.copy(alpha = 0.16f), Color.White)
                    Spacer(Modifier.weight(1f)); Pill(if (phase is Itinerary.Phase.Ongoing) "Happening now" else "Next trip", null, Color(0xFFCDDEBC), ForestDark)
                }
                Spacer(Modifier.height(4.dp))
                Eyebrow(trip.destination, Color(0xFFCDDEBC))
                Text(trip.name, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    IconText(Icons.Default.CalendarMonth, Itinerary.dateRange(trip.startsOn, trip.endsOn), Color(0xFFE3EDD8))
                    IconText(Icons.Default.Groups, "${trip.memberCount}", Color(0xFFE3EDD8))
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Open trip", color = Color.White, fontWeight = FontWeight.SemiBold); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White) }
            }
        }
    }
}

@Composable private fun IconText(icon: ImageVector, text: String, color: Color = Muted) {
    Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = color, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(text, color = color, style = MaterialTheme.typography.bodyMedium) }
}

@Composable private fun TripCard(trip: Trip, index: Int, today: LocalDate, modifier: Modifier, open: () -> Unit) {
    val (banner, accent) = banners[index % banners.size]
    val phase = Itinerary.phase(trip, today)
    Card(onClick = open, modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Line)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(banner), contentAlignment = Alignment.Center) {
                if (trip.coverUrl != null) AsyncImage(model = trip.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Default.Place, null, tint = accent, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Eyebrow(trip.destination)
                Text(trip.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${Itinerary.dateRange(trip.startsOn, trip.endsOn)} · ${trip.memberCount} traveler${if (trip.memberCount == 1) "" else "s"}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(2.dp))
                if (trip.cancelled) Pill("Cancelled", Icons.Default.Block, Color(0xFFFCE8E3), Color(0xFFA04735))
                else Pill(Itinerary.phaseLabel(phase), container = if (phase == Itinerary.Phase.Past) Color(0xFFE9EDEF) else Color(0xFFEDF2E6), content = if (phase == Itinerary.Phase.Past) Color(0xFF4D6168) else Color(0xFF526936))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
        }
    }
}

@Composable private fun TripScreen(vm: TravelViewModel, detail: TripDetail) {
    var tab by rememberSaveable(detail.trip.id) { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val labels = listOf("Itinerary", "Expenses", "Balances", "Group")
    val icons = listOf(Icons.Default.Map, Icons.AutoMirrored.Filled.ReceiptLong, Icons.Default.AccountBalanceWallet, Icons.Default.Groups)
    var menu by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmHide by remember { mutableStateOf(false) }
    // A cancelled trip locks the plan for everyone, organizer included; settling up stays available.
    val planning = if (detail.trip.cancelled) detail.copy(isOrganizer = false) else detail
    ErrorSnackbar(vm, snackbar)
    androidx.activity.compose.BackHandler(enabled = !vm.busy) { vm.backToTrips() }
    Scaffold(containerColor = Cream, snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream),
            title = { Column { Text(detail.trip.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold); Text("${detail.trip.destination} · ${Itinerary.dateRange(detail.trip.startsOn, detail.trip.endsOn)}", style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            navigationIcon = { IconButton(vm::backToTrips, enabled = !vm.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to trips") } },
            actions = { if (detail.isOrganizer) Box {
                IconButton(onClick = { menu = true }, enabled = !vm.busy) { Icon(Icons.Default.MoreVert, "Trip options") }
                DropdownMenu(menu, { menu = false }) {
                    if (!detail.trip.cancelled) DropdownMenuItem(text = { Text("Edit trip & cover photo") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; editing = true })
                    if (!detail.trip.cancelled) DropdownMenuItem(text = { Text("Cancel trip…", color = Danger) }, leadingIcon = { Icon(Icons.Default.Block, null, tint = Danger) }, onClick = { menu = false; cancelling = true })
                    else {
                        DropdownMenuItem(text = { Text("Reopen trip") }, leadingIcon = { Icon(Icons.Default.Restore, null) }, onClick = { menu = false; vm.reopenTrip() })
                        DropdownMenuItem(text = { Text(if (detail.trip.hidden) "Show in trip lists" else "Hide from trip lists") }, leadingIcon = { Icon(if (detail.trip.hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) },
                            onClick = { menu = false; if (detail.trip.hidden) vm.setTripHidden(false) else confirmHide = true })
                    }
                }
            } }) },
        bottomBar = { NavigationBar(containerColor = Color.White) { labels.forEachIndexed { index, label ->
            NavigationBarItem(tab == index, { if (tab != index) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); tab = index }, icon = { Icon(icons[index], null) }, label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = Forest, selectedTextColor = Forest, indicatorColor = Sage))
        } } }) { padding ->
        PullToRefreshBox(isRefreshing = vm.busy, onRefresh = vm::refresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { current ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (detail.trip.cancelled) CancelledBanner(detail, vm)
                    when (current) { 0 -> ItineraryTab(vm, planning); 1 -> ExpensesTab(vm, detail); 2 -> BalancesTab(vm, detail); else -> GroupTab(vm, planning) }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    if (cancelling) CancelTripSheet(vm, detail) { cancelling = false }
    if (editing) EditTripSheet(vm, detail) { editing = false }
    if (confirmHide) AlertDialog(onDismissRequest = { confirmHide = false }, icon = { Icon(Icons.Default.VisibilityOff, null) },
        title = { Text("Hide this trip?") }, text = { Text("It won't show in anyone's trip list. You can show it again from this menu.") },
        confirmButton = { TextButton(onClick = { confirmHide = false; vm.setTripHidden(true) }) { Text("Hide trip") } },
        dismissButton = { TextButton(onClick = { confirmHide = false }) { Text("Keep visible") } })
}

@Composable private fun ItineraryTab(vm: TravelViewModel, detail: TripDetail) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val trip = detail.trip
    val days = remember(detail.stops, trip.timezone) { Itinerary.days(detail.stops, trip.timezone) }
    val today = LocalDate.now(zoneOf(trip.timezone))
    var selected by rememberSaveable(trip.id) { mutableIntStateOf(days.indexOfFirst { it.key == today }.coerceAtLeast(0)) }
    val day = days.getOrNull(selected.coerceIn(0, (days.size - 1).coerceAtLeast(0)))
    var editing by remember { mutableStateOf<Stop?>(null) }; var form by remember { mutableStateOf(false) }
    // Typed addresses have no pin; look them up on the device so they still show on the map.
    val geocoded = remember { mutableStateMapOf<Long, Pair<Double, Double>>() }
    LaunchedEffect(day) { day?.stops?.filter { !Itinerary.hasPin(it) && it.location.isNotBlank() && it.id !in geocoded }?.forEach { stop ->
        Places.lookup(context, Places.query(stop.location, trip.destination))?.let { geocoded[stop.id] = it }
    } }

    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text("Itinerary", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("${days.size} day${if (days.size == 1) "" else "s"} · ${detail.stops.size} activit${if (detail.stops.size == 1) "y" else "ies"}", color = Muted)
        }
        if (detail.isOrganizer) FilledTonalButton(onClick = { editing = null; form = true }, enabled = !vm.busy) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add") }
    }
    Itinerary.totalCost(detail.stops)?.let { (min, max) -> Pill("${Itinerary.costRange(min, max, trip.currency)} per person (estimate)", Icons.Default.Payments) }
    if (days.isEmpty()) { EmptyState(Icons.Default.Map, "A blank itinerary", "Add your arrival point, then the places you want to visit.", if (detail.isOrganizer) "Add the first stop" else null) { editing = null; form = true }; if (form) StopForm(vm, detail, editing) { form = false }; return }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 8.dp)) {
        itemsIndexed(days, key = { _, it -> it.key.toString() }) { index, item ->
            val active = index == selected
            val background by animateColorAsState(if (active) Forest else Color.White, label = "day")
            Surface(onClick = { if (!active) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); selected = index }, shape = RoundedCornerShape(16.dp), color = background, border = if (active) null else BorderStroke(1.dp, Line), modifier = Modifier.width(76.dp)) {
                Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.key.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)).uppercase(), color = if (active) Color(0xFFCDDEBC) else Muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(item.key.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)), color = if (active) Color.White else Ink, fontWeight = FontWeight.Bold)
                    Text("${item.stops.size}", color = if (active) Color.White else Moss, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    val current = day ?: return
    val pins = current.stops.mapIndexedNotNull { index, stop ->
        val point = if (Itinerary.hasPin(stop)) stop.latitude!! to stop.longitude!! else geocoded[stop.id]
        point?.let { MapPin(stop.id, index + 1, stop.title, stop.location, it.first, it.second, approximate = !Itinerary.hasPin(stop)) }
    }
    val navigate = Itinerary.dayDirectionsUrl(current.stops.map { stop -> geocoded[stop.id]?.takeIf { !Itinerary.hasPin(stop) }?.let { stop.copy(latitude = it.first, longitude = it.second) } ?: stop })
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Line)) {
        Box(Modifier.fillMaxWidth().height(250.dp).background(Color(0xFFEFF3EA))) {
            if (pins.isEmpty()) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.LocationOff, null, tint = Muted, modifier = Modifier.size(32.dp)); Spacer(Modifier.height(8.dp))
                Text(if (current.stops.any { it.location.isNotBlank() }) "Finding places on the map…" else "Add locations to see this day on the map", color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } else TripMap(pins, Modifier.fillMaxSize(), Forest) { pin ->
                current.stops.find { it.id == pin.id }?.let { stop -> Itinerary.navigateUrl(stop.copy(latitude = pin.latitude, longitude = pin.longitude))?.let { startNavigation(context, it) } }
            }
        }
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Route for ${Itinerary.dayLabel(current.key)}", fontWeight = FontWeight.Bold)
                val missing = current.stops.count { it.location.isNotBlank() } - pins.size
                Text(if (missing > 0) "${pins.size} on the map · $missing still locating" else if (hasGoogleMaps) "Tap a pin, then its name, to navigate there" else "Tap a place below to navigate there", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (navigate != null) OutlinedButton(onClick = { openUrl(context, navigate) }, shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Directions, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Day route") }
        }
    }

    Column {
        current.stops.forEachIndexed { index, stop ->
            TimelineStop(stop, current.stops.getOrNull(index - 1), index == current.stops.lastIndex, trip, detail.isOrganizer && !vm.busy, geocoded[stop.id], { url -> openUrl(context, url) }, { url -> startNavigation(context, url) }) { editing = stop; form = true }
        }
    }
    if (form) StopForm(vm, detail, editing) { form = false }
}

@Composable private fun TimelineStop(stop: Stop, previous: Stop?, last: Boolean, trip: Trip, canEdit: Boolean, guessed: Pair<Double, Double>?, open: (String) -> Unit, navigate: (String) -> Unit, edit: () -> Unit) {
    var expanded by rememberSaveable(stop.id) { mutableStateOf(false) }
    val located = guessed?.takeIf { !Itinerary.hasPin(stop) }?.let { stop.copy(latitude = it.first, longitude = it.second) } ?: stop
    val previousLocated = previous
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(58.dp).padding(top = 14.dp), horizontalAlignment = Alignment.End) {
            Text(Itinerary.clock(stop.startsAt, trip.timezone), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(Itinerary.clock(stop.endsAt, trip.timezone), color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.width(44.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.size(30.dp).clip(CircleShape).background(Sage), contentAlignment = Alignment.Center) { Icon(kindIcon(Itinerary.kind(stop.title)), null, tint = Forest, modifier = Modifier.size(16.dp)) }
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(Line))
        }
        Card(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f).padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(stop.title, Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    if (canEdit) Icon(Icons.Default.Edit, "Edit stop", tint = Muted, modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = edit).padding(7.dp))
                }
                // Tapping the place starts navigation from where you are now.
                val go = Itinerary.navigateUrl(located)
                if (go != null) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF1F5EC)).clickable(onClickLabel = "Navigate to ${stop.location}") { navigate(go) }.padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, null, tint = Moss, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(stop.location, Modifier.weight(1f), color = Forest, style = MaterialTheme.typography.bodySmall, maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis)
                    Surface(color = Forest, shape = RoundedCornerShape(50)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Navigation, null, tint = Color.White, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                            Text("Go", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Itinerary.costRange(stop.costMinMinor, stop.costMaxMinor, trip.currency)?.let { Pill(it, Icons.Default.Payments) }
                    when {
                        previous == null -> Pill("Start", Icons.Default.Flag)
                        Itinerary.samePlace(previous, stop) -> Pill("Same spot", Icons.Default.MyLocation, Color(0xFFF1F3EF), Muted)
                        else -> Pill(transportOptions.find { it.first == stop.transport }?.second?.substringBefore(" /") ?: "Transport TBD", transportIcon(stop.transport), Color(0xFFF1F3EF), Muted)
                    }
                }
                AnimatedVisibility(expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (stop.description.isNotBlank()) Text(stop.description, color = Muted, style = MaterialTheme.typography.bodySmall)
                        stop.costNote?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (stop.location.isNotBlank()) OutlinedButton(onClick = { open(Itinerary.searchUrl(located)) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Default.Map, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("View on map") }
                            val directions = previousLocated?.takeIf { !Itinerary.samePlace(it, stop) }?.let { Itinerary.directionsUrl(it, located) }
                            if (directions != null) OutlinedButton(onClick = { open(directions) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Default.Directions, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("From previous stop") }
                        }
                    }
                }
                if (!expanded && (stop.description.isNotBlank() || stop.location.isNotBlank())) Text("Tap for details", color = Moss, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable private fun ExpensesTab(vm: TravelViewModel, detail: TripDetail) {
    var create by remember { mutableStateOf(false) }; var selectedId by remember { mutableStateOf<Long?>(null) }
    val total = detail.expenses.filter { it.status in listOf("completed", "closed") }.sumOf { it.totalMinor }
    val pending = detail.expenses.filter { it.status == "open" }.sumOf { it.totalMinor }
    Row(verticalAlignment = Alignment.Bottom) {
        Text("Shared expenses", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (!detail.trip.cancelled) FilledTonalButton(onClick = { create = true }, enabled = !vm.busy) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add") }
    }
    Card(colors = CardDefaults.cardColors(containerColor = Forest), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Trip spending", color = Color(0xFFCDDEBC)); Text(money(total, detail.trip.currency), color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(if (pending > 0) "+ ${money(pending, detail.trip.currency)} still open" else "Completed and closed expenses", color = Color(0xFFCDDEBC), style = MaterialTheme.typography.bodySmall)
    } }
    if (detail.expenses.isEmpty()) EmptyState(Icons.AutoMirrored.Filled.ReceiptLong, "No expenses yet", "Record your first shared bill to keep everyone on the same page.", if (detail.trip.cancelled) null else "Add an expense") { create = true }
    detail.expenses.forEach { expense -> Card(onClick = { selectedId = expense.id }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Line)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Sage), contentAlignment = Alignment.Center) { Icon(kindIcon(Itinerary.kind("${expense.category} ${expense.title}")), null, tint = Forest) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(expense.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(expense.category, expense.merchant).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(money(expense.totalMinor, detail.trip.currency), fontWeight = FontWeight.Bold); Status(expense.status) }
        }
    } }
    if (create) ExpenseForm(vm, detail) { create = false }
    detail.expenses.find { it.id == selectedId }?.let { ExpenseSheet(vm, detail, it) { selectedId = null } }
}

@Composable private fun Status(status: String) {
    val (container, content) = when (status) { "open" -> Color(0xFFFFF3D6) to Color(0xFF926815); "completed", "confirmed" -> Color(0xFFE0F3E8) to Color(0xFF276C47); "closed" -> Color(0xFFE9EDEF) to Color(0xFF4D6168); "cancelled" -> Color(0xFFFCE8E3) to Color(0xFFA04735); else -> Color(0xFFEDF2E6) to Color(0xFF526936) }
    Pill(status.replaceFirstChar { it.uppercase() }, null, container, content)
}

@Composable private fun BalancesTab(vm: TravelViewModel, detail: TripDetail) {
    var form by remember { mutableStateOf(false) }
    val mine = detail.balances.find { it.memberId == detail.currentMemberId }?.netMinor ?: 0
    Text("Keep it fair", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = if (mine < 0) Color(0xFFFFF0EC) else Sage)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when { mine < 0 -> "You owe"; mine > 0 -> "You're owed"; else -> "You're all settled" }, color = if (mine < 0) Danger else ForestDark, fontWeight = FontWeight.SemiBold)
            Text(if (mine == 0L) "🎉" else money(abs(mine), detail.trip.currency), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = if (mine < 0) Danger else ForestDark)
            Text("Reimbursements count once the recipient confirms them.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (detail.members.size > 1) Submit("Record money you sent", vm.busy, icon = Icons.AutoMirrored.Filled.Send) { form = true }
    InfoCard("Everyone") {
        detail.balances.forEach { balance -> Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(balance.name, balance.memberId == detail.currentMemberId); Spacer(Modifier.width(12.dp))
            Text(if (balance.memberId == detail.currentMemberId) "${balance.name} (you)" else balance.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Column(horizontalAlignment = Alignment.End) {
                Text(money(abs(balance.netMinor), detail.trip.currency), fontWeight = FontWeight.Bold, color = when { balance.netMinor < 0 -> Danger; balance.netMinor > 0 -> Forest; else -> Muted })
                Text(when { balance.netMinor > 0 -> "receives"; balance.netMinor < 0 -> "owes"; else -> "settled" }, color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        } }
    }
    Text("Payment history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    if (detail.settlements.isEmpty()) Text("No reimbursements recorded yet.", color = Muted)
    detail.settlements.forEach { payment -> val sender = detail.members.find { it.id == payment.fromMemberId }?.name ?: "Traveler"; val recipient = detail.members.find { it.id == payment.toMemberId }?.name ?: "Traveler"
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(sender, size = 34); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted); Avatar(recipient, size = 34); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text("$sender → $recipient", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${money(payment.amountMinor, detail.trip.currency)} · ${payment.method.replace('_', ' ')}", color = Muted, style = MaterialTheme.typography.bodySmall) }
                Status(payment.status)
            }
            val expense = detail.expenses.find { it.id == payment.expenseId }; Text(expense?.let { "For ${it.title} (${it.category})" } ?: "General trip reimbursement", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (payment.status == "pending" && payment.toMemberId == detail.currentMemberId) Submit("Confirm I received ${money(payment.amountMinor, detail.trip.currency)}", vm.busy, icon = Icons.Default.Check) { vm.mutate { repo, current -> repo.confirmSettlement(current.trip.id, payment.id) } }
        }
    }
    if (form) SettlementForm(vm, detail) { form = false }
}

@Composable private fun GroupTab(vm: TravelViewModel, detail: TripDetail) {
    Text("Your barkada", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("${detail.members.size} traveler${if (detail.members.size == 1) "" else "s"} · ${if (detail.isOrganizer) "You organize this trip" else "Trip member"}", color = Muted)
    if (detail.isOrganizer) Submit("Invite friends", vm.busy, icon = Icons.Default.PersonAdd, click = vm::invite)
    InfoCard {
        detail.members.forEachIndexed { index, member ->
            if (index > 0) HorizontalDivider(color = Line)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(member.name, member.id == detail.currentMemberId); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(member.name, fontWeight = FontWeight.SemiBold); Text(if (member.id == detail.currentMemberId) "You" else "Traveler", color = Moss, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable private fun FormSheet(title: String, vm: TravelViewModel, dismiss: () -> Unit, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    DisposableEffect(Unit) { vm.formOpen = true; onDispose { vm.formOpen = false } }
    ModalBottomSheet(onDismissRequest = { if (!vm.busy) dismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); subtitle?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) } }
                IconButton(onClick = dismiss, enabled = !vm.busy) { Icon(Icons.Default.Close, "Close") }
            }
            ErrorNotice(vm); content()
        }
    }
}

@Composable private fun TripForm(vm: TravelViewModel, dismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }; var destination by rememberSaveable { mutableStateOf("") }; var start by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(7).toString()) }; var end by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(9).toString()) }; var currency by rememberSaveable { mutableStateOf("PHP") }; var zone by rememberSaveable { mutableStateOf("Asia/Manila") }
    val nights = runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)) }.getOrDefault(0)
    FormSheet("Plan a trip", vm, dismiss, "Where is the barkada going?") {
        Field("Trip name", name, { name = it }, leading = Icons.Default.Luggage, supporting = "e.g. Siquijor Barkada Trip")
        Field("Destination", destination, { destination = it }, leading = Icons.Default.Place, supporting = "City or island, country")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DateField("Starts", start, { start = it; if (end < it) end = it }, Modifier.weight(1f))
            DateField("Ends", end, { end = it }, Modifier.weight(1f), min = start)
        }
        Text("${nights + 1} day${if (nights == 0L) "" else "s"}, $nights night${if (nights == 1L) "" else "s"}", color = Moss, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { Choice("Currency", currency, listOf("PHP", "USD", "EUR", "GBP", "SGD", "AUD", "CAD").map { it to it }, { currency = it }) }
            Box(Modifier.weight(1.4f)) { Choice("Time zone", zone, (timezones + listOfNotNull(ZoneId.systemDefault().id.takeIf { it !in timezones })).map { it to it.replace('_', ' ') }, { zone = it }) }
        }
        Submit("Create trip", vm.busy, name.isNotBlank() && destination.isNotBlank() && end >= start, Icons.Default.Check) { vm.createTrip(TripDraft(name, destination, start, end, currency, zone), dismiss) }
    }
}

@Composable private fun StopForm(vm: TravelViewModel, detail: TripDetail, stop: Stop?, dismiss: () -> Unit) {
    val context = LocalContext.current
    val tz = detail.trip.timezone
    var title by rememberSaveable(stop?.id) { mutableStateOf(stop?.title ?: "") }; var location by rememberSaveable(stop?.id) { mutableStateOf(stop?.location ?: "") }; var notes by rememberSaveable(stop?.id) { mutableStateOf(stop?.description ?: "") }
    var date by rememberSaveable(stop?.id) { mutableStateOf(stop?.let { editDate(it.startsAt, tz) } ?: detail.trip.startsOn) }
    var from by rememberSaveable(stop?.id) { mutableStateOf(stop?.let { editClock(it.startsAt, tz) } ?: "09:00") }
    var until by rememberSaveable(stop?.id) { mutableStateOf(stop?.let { editClock(it.endsAt, tz) } ?: "10:00") }
    var transport by rememberSaveable(stop?.id) { mutableStateOf(stop?.transport ?: "") }
    val overnight = runCatching { LocalTime.parse(until) < LocalTime.parse(from) }.getOrDefault(false)
    val endDate = if (overnight) runCatching { LocalDate.parse(date).plusDays(1).toString() }.getOrDefault(date) else date
    FormSheet(if (stop == null) "Add a stop" else "Edit stop", vm, dismiss, "Times in ${tz.replace('_', ' ')}") {
        Field("What's happening?", title, { title = it }, leading = kindIcon(Itinerary.kind(title)), supporting = "e.g. Lunch at Baha Ba'r, Cambugahay Falls")
        Field("Location / full address", location, { location = it }, leading = Icons.Default.Place, supporting = if (stop != null && Itinerary.hasPin(stop) && location.trim() == stop.location.trim()) "Pinned on the map" else "We'll find it on the map for you")
        DateField("Date", date, { date = it }, min = detail.trip.startsOn)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { TimeField("Starts", from, { from = it }, Modifier.weight(1f)); TimeField("Ends", until, { until = it }, Modifier.weight(1f)) }
        if (overnight) Text("Ends the next day", color = Moss, style = MaterialTheme.typography.bodySmall)
        Choice("Getting here from the previous stop", transport, transportOptions, { transport = it })
        Field("Notes", notes, { notes = it }, singleLine = false)
        Submit("Save stop", vm.busy, title.isNotBlank(), Icons.Default.Check) {
            vm.mutate(dismiss) { repo, current ->
                // Keep an existing pin when the address is unchanged; otherwise look up the new address.
                val pin = when {
                    location.isBlank() -> null
                    stop != null && Itinerary.hasPin(stop) && location.trim() == stop.location.trim() -> stop.latitude!! to stop.longitude!!
                    else -> Places.lookup(context, Places.query(location, current.trip.destination))
                }
                repo.saveStop(current.trip.id, stop?.id, StopDraft(title, location, notes, "${date}T$from", "${endDate}T$until", transport.ifBlank { null }, pin?.first, pin?.second))
            }
        }
    }
}

/** A receipt line in the form; [members] share it equally. */
private data class LineState(val description: String, val amountMinor: Long, val members: Set<Long>)

@Composable private fun ExpenseForm(vm: TravelViewModel, detail: TripDetail, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }; var merchant by rememberSaveable { mutableStateOf("") }; var total by rememberSaveable { mutableStateOf("") }; var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }; var category by rememberSaveable { mutableStateOf(detail.categories.firstOrNull()?.id?.toString() ?: "") }; var payer by rememberSaveable { mutableStateOf("") }; var selected by remember { mutableStateOf(detail.members.map { it.id }.toSet()) }
    // Filled by "Scan receipt": per-item lines, the scan to attach, and anything the reader wants checked.
    var lines by remember { mutableStateOf<List<LineState>>(emptyList()) }
    var scanId by remember { mutableStateOf<Long?>(null) }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var preparing by remember { mutableStateOf(false) }
    val everyone = detail.members.map { it.id }.toSet()
    val symbol = runCatching { Currency.getInstance(detail.trip.currency).getSymbol(Locale("en", "PH")) }.getOrDefault(detail.trip.currency)

    fun applyDraft(draft: ReceiptDraft) {
        scanId = draft.scanId; title = draft.title; draft.merchant?.let { merchant = it }; draft.incurredAt?.let { date = it }
        draft.categoryId?.takeIf { id -> detail.categories.any { it.id == id } }?.let { category = it.toString() }
        draft.totalMinor?.let { total = BigDecimal.valueOf(it, 2).toPlainString() }
        lines = draft.lines.map { LineState(it.description, it.amountMinor, everyone) }
        warnings = draft.warnings
    }
    fun upload(uri: Uri) = scope.launch {
        preparing = true
        val jpeg = Photos.jpeg(context, uri, maxSide = 2000)
        preparing = false
        if (jpeg == null) Toast.makeText(context, "That photo couldn't be opened. Try another one.", Toast.LENGTH_LONG).show() else vm.scanReceipt(jpeg, ::applyDraft)
    }
    // Full-resolution camera photo via a cache file, so small receipt text stays readable.
    val cameraFile = remember { java.io.File(context.cacheDir, "camera/receipt.jpg").apply { parentFile?.mkdirs() } }
    val cameraUri = remember { androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", cameraFile) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved -> if (saved) upload(cameraUri) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) upload(uri) }
    val scanning = preparing || (vm.busy && scanId == null && lines.isEmpty())
    val itemized = lines.isNotEmpty()
    val lineSum = lines.sumOf { it.amountMinor }

    FormSheet("Add shared expense", vm, dismiss, if (itemized) "Split by item from your receipt" else "Split equally with the barkada") {
        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFF5F9EF), border = BorderStroke(1.dp, Color(0xFF9CB865))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (scanning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(Icons.Default.DocumentScanner, null, tint = Forest)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (scanning) "Reading your receipt…" else if (scanId != null) "Filled in from your receipt" else "Scan a receipt", fontWeight = FontWeight.SemiBold, color = Forest)
                        Text(if (scanning) "This takes a few seconds." else if (scanId != null) "Check the amounts and who shares each item." else "We'll fill in the items for you.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { takePhoto.launch(cameraUri) }, enabled = !scanning && !vm.busy, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Take photo") }
                    OutlinedButton(onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !scanning && !vm.busy, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Choose") }
                }
                warnings.forEach { Row { Icon(Icons.Default.WarningAmber, null, tint = Color(0xFF926815), modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(it, color = Color(0xFF7A5512), style = MaterialTheme.typography.bodySmall) } }
            }
        }
        Field("Total", total, { total = it }, number = true, prefix = "$symbol ", supporting = when {
            itemized && amount(total) != lineSum -> "Items add up to ${money(lineSum, detail.trip.currency)}. Make the total match, or split equally instead."
            itemized -> "Matches the ${lines.size} receipt items"
            else -> amount(total)?.let { cents -> if (selected.isNotEmpty()) "About ${money(cents / selected.size, detail.trip.currency)} each" else null }
        })
        Field("Description", title, { title = it }, leading = Icons.Default.Edit, supporting = "e.g. Dinner, Van rental")
        Field("Merchant", merchant, { merchant = it }, leading = Icons.Default.Storefront)
        Choice("Expense type", category, detail.categories.map { it.id.toString() to it.name }, { category = it })
        DateField("Date", date, { date = it })
        Choice("Who paid the full bill?", payer, listOf("" to "Not paid yet") + detail.members.map { it.id.toString() to if (it.id == detail.currentMemberId) "${it.name} (you)" else it.name }, { payer = it })
        if (itemized) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Who had what?", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(onClick = { lines = emptyList() }) { Text("Split equally instead") }
            }
            lines.forEachIndexed { index, line ->
                Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Line), color = Color.White) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row { Text(line.description, Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text(money(line.amountMinor, detail.trip.currency), fontWeight = FontWeight.SemiBold) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            detail.members.forEach { member ->
                                val on = member.id in line.members
                                FilterChip(on, { lines = lines.toMutableList().also { it[index] = line.copy(members = if (on) line.members - member.id else line.members + member.id) } }, label = { Text(member.name) },
                                    leadingIcon = if (on) { { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) } } else null)
                            }
                        }
                        if (line.members.isEmpty()) Text("Pick at least one person", color = Danger, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Split between", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(onClick = { selected = if (selected.size == detail.members.size) emptySet() else everyone }) { Text(if (selected.size == detail.members.size) "Clear" else "Everyone") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.members.forEach { member -> FilterChip(member.id in selected, { selected = if (member.id in selected) selected - member.id else selected + member.id }, label = { Text(member.name) },
                    leadingIcon = if (member.id in selected) { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } } else null) }
            }
        }
        val valid = title.isNotBlank() && category.isNotBlank() && amount(total) != null &&
            (if (itemized) amount(total) == lineSum && lines.all { it.members.isNotEmpty() } else selected.isNotEmpty())
        Submit("Save expense", vm.busy, valid, Icons.Default.Check) {
            val items = lines.map { ItemDraft(it.description, it.amountMinor, it.members.toList()) }
            vm.mutate(dismiss) { repo, current -> repo.createExpense(current.trip.id, ExpenseDraft(title, merchant, category.toLong(), amount(total)!!, date, selected.toList(), payer.toLongOrNull(), items, scanId)) }
        }
    }
}

@Composable private fun ExpenseSheet(vm: TravelViewModel, detail: TripDetail, expense: Expense, dismiss: () -> Unit) {
    var action by remember { mutableStateOf<String?>(null) }; var reason by remember { mutableStateOf("") }; var voidPayments by remember { mutableStateOf(false) }; var selectedShare by remember { mutableStateOf<Share?>(null) }; var source by remember { mutableStateOf("") }; var payer by remember { mutableStateOf(detail.currentMemberId.toString()) }; var paymentAmount by remember { mutableStateOf("") }
    FormSheet(expense.title, vm, dismiss, listOf(expense.category, expense.merchant).filter { it.isNotBlank() }.joinToString(" · ")) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(money(expense.totalMinor, detail.trip.currency), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Forest); Status(expense.status) }
        Text("Assigned shares", fontWeight = FontWeight.Bold)
        expense.shares.forEach { share -> val member = detail.members.find { it.id == share.memberId }; Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Avatar(member?.name ?: "Traveler", share.memberId == detail.currentMemberId, 36); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text(member?.name ?: "Traveler", fontWeight = FontWeight.Medium); Text(if (share.remainingMinor <= 0) "Paid / covered" else "${money(share.remainingMinor, detail.trip.currency)} remaining", style = MaterialTheme.typography.bodySmall, color = if (share.remainingMinor <= 0) Forest else Muted) }
            Text(money(share.amountMinor, detail.trip.currency), fontWeight = FontWeight.SemiBold)
            val mayConfirm = detail.isOrganizer || expense.payments.any { it.memberId == detail.currentMemberId }
            if (mayConfirm && share.remainingMinor > 0 && expense.status in listOf("open", "completed", "closed")) TextButton(onClick = { selectedShare = share; source = "" }, enabled = !vm.busy) { Text("Mark paid") }
            else if (share.remainingMinor <= 0) Icon(Icons.Default.CheckCircle, "Paid", tint = Forest, modifier = Modifier.padding(start = 8.dp).size(20.dp))
        } }
        HorizontalDivider(color = Line)
        Text("Paid to merchant", fontWeight = FontWeight.Bold)
        expense.payments.forEach { Text("${detail.members.find { m -> m.id == it.memberId }?.name ?: "Traveler"} · ${money(it.amountMinor, detail.trip.currency)}") }
        if (expense.payments.isEmpty()) Text("No merchant payment recorded.", color = Muted)
        // The organizer manages every expense; whoever recorded one may cancel or delete it.
        val canManage = detail.isOrganizer || (expense.createdByMemberId != null && expense.createdByMemberId == detail.currentMemberId)
        val canDelete = canManage && expense.payments.isEmpty() && detail.settlements.none { it.expenseId == expense.id }
        if (detail.isOrganizer) {
            if (expense.status == "open") { OutlinedButton(onClick = { action = "payment" }, Modifier.fillMaxWidth()) { Text("Record merchant payment") }; Button(onClick = { action = "completed" }, Modifier.fillMaxWidth()) { Text("Complete merchant bill") } }
            if (expense.status == "completed") Button(onClick = { action = "closed" }, Modifier.fillMaxWidth()) { Text("Close expense") }
        }
        if (canManage || canDelete) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canManage && expense.status in listOf("open", "completed")) OutlinedButton(onClick = { action = "cancelled" }, enabled = !vm.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Icon(Icons.Default.Block, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Cancel") }
            if (canDelete) OutlinedButton(onClick = { action = "delete" }, enabled = !vm.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Icon(Icons.Default.Delete, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Delete") }
        }
        selectedShare?.let { share ->
            HorizontalDivider(); Text("Confirm payment for ${detail.members.find { it.id == share.memberId }?.name}", fontWeight = FontWeight.Bold)
            val existing = detail.settlements.filter { it.expenseId == null && it.fromMemberId == share.memberId && it.status in listOf("pending", "confirmed") }
            Choice("Payment to apply", source, listOf("" to "Choose payment", "new" to "A separate new payment") + existing.map { it.id.toString() to "Existing ${money(it.amountMinor, detail.trip.currency)} to ${detail.members.find { m -> m.id == it.toMemberId }?.name}" }, { source = it })
            Text("Only confirm money actually received. This records a reimbursement; it does not transfer funds.", style = MaterialTheme.typography.bodySmall, color = Muted)
            Submit("Confirm received", vm.busy, source.isNotBlank()) { vm.mutate({ selectedShare = null }) { repo, current -> repo.markSharePaid(current.trip.id, expense, share.memberId, source.toLongOrNull(), source == "new") } }
        }
        action?.let { target ->
            HorizontalDivider()
            when (target) {
                "delete" -> {
                    Text("Delete this expense? It is removed for everyone, including its shares and receipt photos. Use this for duplicates or mistakes.")
                    Button(onClick = { vm.mutate(dismiss) { repo, current -> repo.deleteExpense(current.trip.id, expense.id) } }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("Delete expense") }
                    TextButton(onClick = { action = null }, modifier = Modifier.fillMaxWidth()) { Text("Keep expense") }
                }
                "payment" -> { Choice("Paid by", payer, detail.members.map { it.id.toString() to it.name }, { payer = it }); Field("Amount (${detail.trip.currency})", paymentAmount, { paymentAmount = it }, number = true); Submit("Record payment", vm.busy, amount(paymentAmount) != null) { vm.mutate({ action = null }) { repo, current -> repo.recordMerchantPayment(current.trip.id, expense, payer.toLong(), amount(paymentAmount)!!) } } }
                else -> {
                    Text(if (target == "closed") "Close this expense? Further expense edits will be locked." else if (target == "completed") "Confirm the merchant has been paid in full. Friends may still owe reimbursements." else "Cancel this expense? Its spending will be excluded from balances.")
                    if (target == "cancelled") { Field("Cancellation reason", reason, { reason = it }); if (expense.payments.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(voidPayments, { voidPayments = it }); Text("Void recorded merchant payments from balances") } }
                    Submit("Confirm ${target.replaceFirstChar { it.uppercase() }}", vm.busy, target != "cancelled" || reason.isNotBlank()) { vm.mutate({ action = null }) { repo, current -> repo.changeExpenseStatus(current.trip.id, expense, target, reason.ifBlank { null }, voidPayments) } }
                }
            }
        }
    }
}

@Composable private fun SettlementForm(vm: TravelViewModel, detail: TripDetail, dismiss: () -> Unit) {
    val recipients = detail.members.filter { it.id != detail.currentMemberId }; var recipient by rememberSaveable { mutableStateOf(recipients.firstOrNull()?.id?.toString() ?: "") }; var total by rememberSaveable { mutableStateOf("") }; var method by rememberSaveable { mutableStateOf("gcash") }
    val symbol = runCatching { Currency.getInstance(detail.trip.currency).getSymbol(Locale("en", "PH")) }.getOrDefault(detail.trip.currency)
    FormSheet("Record money you sent", vm, dismiss, "This records a repayment; it does not send money. The recipient confirms receipt.") {
        Choice("Recipient", recipient, recipients.map { it.id.toString() to it.name }, { recipient = it }); Field("Amount", total, { total = it }, number = true, prefix = "$symbol ")
        Text("Payment method", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("gcash" to "GCash", "cash" to "Cash", "bank_transfer" to "Bank transfer", "other" to "Other").forEach { (key, label) -> FilterChip(method == key, { method = key }, label = { Text(label) }) } }
        Submit("Record reimbursement", vm.busy, recipient.isNotBlank() && amount(total) != null, Icons.AutoMirrored.Filled.Send) { vm.mutate(dismiss) { repo, current -> repo.recordSettlement(current.trip.id, recipient.toLong(), amount(total)!!, method) } }
    }
}

@Composable private fun CancelledBanner(detail: TripDetail, vm: TravelViewModel) {
    Surface(color = Color(0xFFFFF4EF), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0xFFEDC9BD))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Block, null, tint = Danger, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if (detail.trip.hidden) "Cancelled · hidden from trip lists" else "This trip was cancelled", color = Color(0xFF7D3424), fontWeight = FontWeight.Bold)
            }
            detail.trip.cancellationReason?.let { Text("“$it”", color = Color(0xFF7D3424), style = MaterialTheme.typography.bodyMedium) }
            Text("The plan and new expenses are locked. You can still record repayments and settle up.", color = Color(0xFF7D3424), style = MaterialTheme.typography.bodySmall)
            if (detail.isOrganizer) OutlinedButton(onClick = vm::reopenTrip, enabled = !vm.busy) { Icon(Icons.Default.Restore, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Reopen trip") }
        }
    }
}

@Composable private fun CancelTripSheet(vm: TravelViewModel, detail: TripDetail, dismiss: () -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    var hide by rememberSaveable { mutableStateOf(false) }
    FormSheet("Cancel “${detail.trip.name}”?", vm, dismiss, "The plan and new expenses are locked. Everyone can still settle up, and you can reopen the trip later.") {
        Field("Reason (optional)", reason, { reason = it }, singleLine = false, supporting = "e.g. Typhoon warning, moving to next month")
        Surface(onClick = { hide = !hide }, shape = RoundedCornerShape(14.dp), color = if (hide) Color(0xFFF5F9EF) else Color.White, border = BorderStroke(1.dp, if (hide) Color(0xFF9CB865) else Line)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VisibilityOff, null, tint = Forest); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Hide this trip", fontWeight = FontWeight.SemiBold)
                    Text("It won't show in anyone's trip list.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(hide, { hide = it })
            }
        }
        Button(onClick = { vm.cancelTrip(reason, hide, dismiss) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Danger)) {
            if (vm.busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text(if (hide) "Cancel & hide trip" else "Cancel trip", fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = dismiss, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Keep trip") }
    }
}

/** Organizer editor for the trip details and cover photo. The photo uploads as soon as it is picked. */
@Composable private fun EditTripSheet(vm: TravelViewModel, detail: TripDetail, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trip = detail.trip
    var name by rememberSaveable { mutableStateOf(trip.name) }; var destination by rememberSaveable { mutableStateOf(trip.destination) }; var about by rememberSaveable { mutableStateOf(trip.description) }
    var start by rememberSaveable { mutableStateOf(trip.startsOn) }; var end by rememberSaveable { mutableStateOf(trip.endsOn) }
    var currency by rememberSaveable { mutableStateOf(trip.currency) }; var zone by rememberSaveable { mutableStateOf(trip.timezone) }
    var preparing by remember { mutableStateOf(false) }
    val currencyLocked = detail.expenses.isNotEmpty()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            preparing = true
            val jpeg = Photos.jpeg(context, uri)
            preparing = false
            if (jpeg == null) Toast.makeText(context, "That photo couldn't be opened. Try another one.", Toast.LENGTH_LONG).show() else vm.uploadCover(jpeg)
        }
    }
    val changed = name != trip.name || destination != trip.destination || about != trip.description || start != trip.startsOn || end != trip.endsOn || currency != trip.currency || zone != trip.timezone
    FormSheet("Edit trip", vm, dismiss) {
        Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(18.dp)).background(Sage).clickable(enabled = !vm.busy && !preparing) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            if (trip.coverUrl != null) AsyncImage(model = trip.coverUrl, contentDescription = "Trip cover photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.AddPhotoAlternate, null, tint = Forest, modifier = Modifier.size(32.dp))
                Text("Add a cover photo", color = Forest, fontWeight = FontWeight.SemiBold)
                Text("Shown on everyone's trip card", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (preparing || vm.busy) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            Row(Modifier.align(Alignment.BottomEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (trip.coverUrl != null) SmallFloatingActionButton(onClick = { if (!vm.busy) vm.removeCover() }, containerColor = Color.White, contentColor = Danger) { Icon(Icons.Default.Delete, "Remove cover photo") }
                ExtendedFloatingActionButton(onClick = { if (!vm.busy && !preparing) pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    containerColor = Color.White, contentColor = Forest, modifier = Modifier.height(40.dp),
                    icon = { Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp)) }, text = { Text(if (trip.coverUrl != null) "Change" else "Upload") })
            }
        }
        Field("Trip name", name, { name = it }, leading = Icons.Default.Luggage)
        Field("Destination", destination, { destination = it }, leading = Icons.Default.Place)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DateField("Starts", start, { start = it; if (end < it) end = it }, Modifier.weight(1f))
            DateField("Ends", end, { end = it }, Modifier.weight(1f), min = start)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                if (currencyLocked) OutlinedTextField(currency, {}, label = { Text("Currency") }, readOnly = true, enabled = false, shape = RoundedCornerShape(12.dp), supportingText = { Text("Locked: has expenses") }, modifier = Modifier.fillMaxWidth())
                else Choice("Currency", currency, listOf("PHP", "USD", "EUR", "GBP", "SGD", "AUD", "CAD").map { it to it }, { currency = it })
            }
            Box(Modifier.weight(1.4f)) { Choice("Time zone", zone, (timezones + listOf(zone)).distinct().map { it to it.replace('_', ' ') }, { zone = it }) }
        }
        Field("About this trip", about, { about = it }, singleLine = false)
        Submit("Save changes", vm.busy, changed && name.isNotBlank() && destination.isNotBlank() && end >= start, Icons.Default.Check) {
            vm.updateTrip(TripDraft(name, destination, start, end, currency, zone, about), dismiss)
        }
    }
}

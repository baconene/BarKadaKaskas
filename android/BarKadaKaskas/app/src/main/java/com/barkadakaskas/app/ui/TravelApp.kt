@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.barkadakaskas.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.withContext

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
private fun startNavigation(context: Context, url: String) = com.barkadakaskas.app.nav.InAppNavigation.openGoogleMaps(context, url)

/**
 * Turn-by-turn to [stop] from where you are: inside the app when the Navigation SDK can route it, else Google Maps.
 * [day] (that day's stops, with any looked-up coordinates) is listed under the navigation map.
 */
private fun navigateTo(context: Context, stop: Stop, day: List<Stop> = listOf(stop), timezone: String = "UTC", tripId: Long? = null) {
    val url = Itinerary.navigateUrl(stop) ?: return
    val stops = day.map { s -> com.barkadakaskas.app.nav.InAppNavigation.NavStop(s.title, s.location.ifBlank { null }, s.latitude, s.longitude, s.transport, Itinerary.clock(s.startsAt, timezone), Itinerary.navigateUrl(s) ?: Itinerary.searchUrl(s), s.done, s.id) }
    com.barkadakaskas.app.nav.InAppNavigation.navigate(context, stop.latitude, stop.longitude, stop.title, stop.transport, url, stop.location.ifBlank { null }, stops, day.indexOfFirst { it.id == stop.id }, tripId)
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
        // Back from in-app navigation (where stops can be marked done): reload the open trip quietly.
        androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.refreshQuietly(); vm.loadAlerts(quiet = true) }
        // While signed in: check alerts every minute, and ask once (Android 13+) to allow phone notifications for them.
        val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(vm.user) {
            if (vm.user == null) return@LaunchedEffect
            if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            while (true) { vm.loadAlerts(quiet = true); kotlinx.coroutines.delay(60_000) }
        }
        LaunchedEffect(vm.invitationUrl) { vm.invitationUrl?.let { url ->
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Join our trip on BarkadaKasKas: $url") }, "Invite your barkada")); vm.consumeInvitation()
        } }
        Surface(Modifier.fillMaxSize(), color = Cream) {
            val screen = when { vm.restoring -> -1; vm.user == null -> 0; vm.detail == null -> 1; else -> 2 }
            AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { target ->
                when (target) { -1 -> LoadingScreen(vm); 0 -> AuthScreen(vm); 1 -> TripsScreen(vm); else -> vm.detail?.let { TripScreen(vm, it) } }
            }
        }
    }
}

/** Bell with the unread count; opens the alerts list. */
@Composable private fun AlertsBell(vm: TravelViewModel) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true; vm.loadAlerts() }) {
        BadgedBox(badge = { if (vm.unreadAlerts > 0) Badge(containerColor = Color(0xFFC2410C)) { Text(if (vm.unreadAlerts > 99) "99+" else "${vm.unreadAlerts}") } }) {
            Icon(Icons.Default.Notifications, if (vm.unreadAlerts > 0) "Alerts, ${vm.unreadAlerts} unread" else "Alerts")
        }
    }
    if (open) AlertsSheet(vm) { open = false }
}

/** Your alerts across trips; tap one to open its trip on the right tab. */
@Composable private fun AlertsSheet(vm: TravelViewModel, dismiss: () -> Unit) {
    FormSheet("Alerts", vm, dismiss, "Comments, photos, expenses and plan changes on your trips") {
        if (vm.unreadAlerts > 0) TextButton(onClick = { vm.markAlertsRead() }) { Icon(Icons.Default.DoneAll, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Mark all read") }
        if (vm.alertsLoading && vm.alerts.isEmpty()) repeat(3) { Shimmer(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp))) }
        else if (vm.alerts.isEmpty()) Text("No alerts yet. You'll hear here when your barkada comments, posts photos, adds expenses or changes the plan.", color = Muted)
        vm.alerts.forEach { alert ->
            val (icon, tint) = when (alert.type) { "expense" -> Icons.AutoMirrored.Filled.ReceiptLong to Color(0xFF926815); "itinerary" -> Icons.Default.Event to Color(0xFF2F5A8A); "post" -> Icons.Default.PhotoCamera to Forest; else -> Icons.Default.ChatBubbleOutline to Forest }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (alert.read) Color.Transparent else Color(0xFFF3F8EC)).clickable {
                if (!alert.read) vm.markAlertsRead(listOf(alert.id)); dismiss(); vm.openAlertTrip(alert.tripId, alert.tab)
            }.padding(10.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Sage), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(alert.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    alert.body?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    Text(listOfNotNull(alert.tripName, postTime(alert.createdAt)).joinToString(" · "), color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                if (!alert.read) Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(Color(0xFFC2410C)))
            }
        }
    }
}

/** Start-up screen while a saved session is checked, so signed-in travellers never see the login page flash by. */
@Composable private fun LoadingScreen(vm: TravelViewModel) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Cream, Color(0xFFEFF4E8)))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(32.dp)) {
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(Forest, Color(0xFF2F6B52)))), contentAlignment = Alignment.Center) {
                Text("BK", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineMedium)
            }
            Row { Text("Barkada", color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge); Text("KasKas", color = Moss, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) }
            val failed = vm.restoreFailed
            if (failed == null) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Forest, strokeWidth = 3.dp)
                Text("Getting your trips ready…", color = Muted)
            } else {
                Text(failed, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Button(onClick = vm::restoreSession, colors = ButtonDefaults.buttonColors(containerColor = Forest), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Try again") }
            }
        }
    }
}

/** Placeholder for a photo post while the feed loads. */
@Composable private fun PostSkeleton() {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Shimmer(Modifier.size(38.dp).clip(CircleShape)); Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Shimmer(Modifier.width(120.dp).height(12.dp).clip(RoundedCornerShape(6.dp))); Shimmer(Modifier.width(70.dp).height(10.dp).clip(RoundedCornerShape(5.dp))) }
            }
            Shimmer(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp)))
            Shimmer(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(6.dp)))
        }
    }
}

/** Placeholder rows while a traveller's documents load. */
@Composable private fun DocumentSkeleton() {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Shimmer(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))); Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Shimmer(Modifier.width(140.dp).height(12.dp).clip(RoundedCornerShape(6.dp))); Shimmer(Modifier.width(100.dp).height(10.dp).clip(RoundedCornerShape(5.dp))) }
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
@Composable private fun Avatar(name: String, highlight: Boolean = false, size: Int = 40, url: String? = null) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(if (highlight) Forest else Sage), contentAlignment = Alignment.Center) {
        if (url != null) NetworkImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(initials(name), color = if (highlight) Color.White else Forest, fontWeight = FontWeight.Bold, fontSize = (size / 2.8).sp)
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
    // The trip card being opened: shows "Opening…" until the trip loads, so slow networks still give feedback.
    var opening by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(vm.busy) { if (!vm.busy) opening = null }
    val snackbar = remember { SnackbarHostState() }
    val list = rememberLazyListState()
    val today = LocalDate.now()
    val sorted = remember(vm.trips) { vm.trips.sortedBy { it.startsOn } }
    val active = sorted.filter { !it.cancelled && Itinerary.phase(it, today) != Itinerary.Phase.Past }
    val past = sorted.filter { it.cancelled || Itinerary.phase(it, today) == Itinerary.Phase.Past }.reversed()
    ErrorSnackbar(vm, snackbar)
    Scaffold(containerColor = Cream, snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("My trips", fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream),
            actions = { AlertsBell(vm); IconButton(vm::signOut, enabled = !vm.busy) { Icon(Icons.AutoMirrored.Filled.Logout, "Log out") } }) },
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
                if (vm.trips.isEmpty() && vm.busy) items(3) { TripCardSkeleton() }
                active.firstOrNull()?.let { next -> item(key = "hero-${next.id}") { OpeningOverlay(opening == next.id && vm.busy, Modifier.animateItem()) { HeroTrip(next, today, Modifier) { opening = next.id; vm.openTrip(next) } } } }
                if (active.size > 1) {
                    item { SectionTitle("Coming up", active.size - 1) }
                    itemsIndexed(active.drop(1), key = { _, trip -> trip.id }) { index, trip -> OpeningOverlay(opening == trip.id && vm.busy, Modifier.animateItem()) { TripCard(trip, index + 1, today, Modifier) { opening = trip.id; vm.openTrip(trip) } } }
                }
                if (past.isNotEmpty()) {
                    item { SectionTitle(if (past.any { it.cancelled }) "Past & cancelled" else "Past trips", past.size) }
                    itemsIndexed(past, key = { _, trip -> trip.id }) { index, trip -> OpeningOverlay(opening == trip.id && vm.busy, Modifier.animateItem()) { TripCard(trip, index, today, Modifier) { opening = trip.id; vm.openTrip(trip) } } }
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
@Composable private fun OpeningOverlay(active: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        content()
        if (active) Box(Modifier.matchParentSize().clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Surface(color = Forest, shape = RoundedCornerShape(50), shadowElevation = 4.dp) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                    Text("Opening…", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Placeholder card while the trip list loads for the first time. */
@Composable private fun TripCardSkeleton() {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Line)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Shimmer(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp))); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Shimmer(Modifier.fillMaxWidth(0.4f).height(10.dp).clip(RoundedCornerShape(5.dp)))
                Shimmer(Modifier.fillMaxWidth(0.75f).height(14.dp).clip(RoundedCornerShape(6.dp)))
                Shimmer(Modifier.fillMaxWidth(0.55f).height(10.dp).clip(RoundedCornerShape(5.dp)))
            }
        }
    }
}

@Composable private fun HeroTrip(trip: Trip, today: LocalDate, modifier: Modifier, open: () -> Unit) {
    val phase = Itinerary.phase(trip, today)
    Card(onClick = open, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Forest)) {
        Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Forest, Color(0xFF2F6B52))))) {
            if (trip.coverUrl != null) {
                NetworkImage(model = trip.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
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
                if (trip.coverUrl != null) NetworkImage(model = trip.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
    // An alert or notification asked for a specific tab.
    LaunchedEffect(vm.requestedTab, detail.trip.id) { vm.requestedTab?.let { tab = it; vm.requestedTab = null } }
    val snackbar = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val labels = listOf("Itinerary", "Expenses", "Photos", "Balances", "Group")
    val icons = listOf(Icons.Default.Map, Icons.AutoMirrored.Filled.ReceiptLong, Icons.Default.PhotoLibrary, Icons.Default.AccountBalanceWallet, Icons.Default.Groups)
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
            actions = { AlertsBell(vm); if (detail.isOrganizer) Box {
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
                    when (current) { 0 -> ItineraryTab(vm, planning); 1 -> ExpensesTab(vm, detail); 2 -> PhotosTab(vm, detail); 3 -> BalancesTab(vm, detail); else -> GroupTab(vm, planning) }
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
    val canPlanWithAi = detail.isOrganizer && detail.aiPlanner && !trip.cancelled
    var planner by remember { mutableStateOf(false) }
    LaunchedEffect(vm.pendingAiPrompt) { if (vm.pendingAiPrompt != null && canPlanWithAi) planner = true }
    if (planner) AiPlannerSheet(vm, detail) { planner = false; vm.pendingAiPrompt = null }
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
        if (canPlanWithAi) OutlinedButton(onClick = { planner = true }, enabled = !vm.busy, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("AI plan") }
        if (canPlanWithAi && detail.isOrganizer) Spacer(Modifier.width(8.dp))
        if (detail.isOrganizer) FilledTonalButton(onClick = { editing = null; form = true }, enabled = !vm.busy) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add") }
    }
    Itinerary.totalCost(detail.stops)?.let { (min, max) -> Pill("${Itinerary.costRange(min, max, trip.currency)} per person (estimate)", Icons.Default.Payments) }
    if (days.isEmpty()) {
        EmptyState(Icons.Default.Map, "A blank itinerary", if (canPlanWithAi) "Let AI suggest an itinerary, or add your arrival point and the places you want to visit." else "Add your arrival point, then the places you want to visit.", if (detail.isOrganizer) "Add the first stop" else null) { editing = null; form = true }
        if (canPlanWithAi) Button(onClick = { planner = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Forest)) { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Plan it with AI") }
        if (form) StopForm(vm, detail, editing) { form = false }; return
    }

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
    // A stop with any coordinates found on the device; the day's stops this way go to the navigation screen's list.
    fun locate(s: Stop): Stop = geocoded[s.id]?.takeIf { !Itinerary.hasPin(s) }?.let { s.copy(latitude = it.first, longitude = it.second) } ?: s
    val dayStops = current.stops.map(::locate)
    // Journeys across the whole trip (each stop after the first is reached from the one before it). Stepping to a
    // journey on another day switches the day shown; picking another day by hand closes the journey card.
    val allStops = remember(days) { days.flatMap { it.stops } }
    var focusedId by rememberSaveable(trip.id) { mutableStateOf<Long?>(null) }
    val focusedIndex = allStops.indexOfFirst { it.id == focusedId }.takeIf { it > 0 && allStops[it] in current.stops }
    fun focusJourney(index: Int) { val stop = allStops.getOrNull(index) ?: return; days.indexOfFirst { stop in it.stops }.takeIf { it >= 0 }?.let { selected = it }; focusedId = stop.id }
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
            } else TripMap(pins, Modifier.fillMaxSize(), Forest, focus = focusedIndex?.let { listOf(allStops[it - 1].id, allStops[it].id) } ?: emptyList()) { pin ->
                current.stops.find { it.id == pin.id }?.let { stop -> navigateTo(context, stop.copy(latitude = pin.latitude, longitude = pin.longitude), dayStops, trip.timezone, trip.id) }
            }
        }
        if (focusedIndex != null) JourneyCard(allStops[focusedIndex - 1], allStops[focusedIndex], focusedIndex, allStops.size - 1, { focusJourney(focusedIndex - 1) }, { focusJourney(focusedIndex + 1) }, { focusedId = null }, { url -> openUrl(context, url) }) { allStops[focusedIndex].let { s -> navigateTo(context, locate(s), dayStops, trip.timezone, trip.id) } }
        else Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Route for ${Itinerary.dayLabel(current.key)}", fontWeight = FontWeight.Bold)
                val missing = current.stops.count { it.location.isNotBlank() } - pins.size
                Text(if (missing > 0) "${pins.size} on the map · $missing still locating" else if (hasGoogleMaps) "Tap a pin, then its name, to navigate there" else "Tap a place below to navigate there", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (navigate != null) OutlinedButton(onClick = { openUrl(context, navigate) }, shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Directions, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Day route") }
        }
        // Start stepping through the day's journeys (or the trip's first one when the day has a single stop).
        if (focusedIndex == null && allStops.size > 1) TextButton(onClick = { focusJourney(allStops.indexOf(current.stops.first()).coerceAtLeast(1)) }, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)) {
            Icon(Icons.Default.Route, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Step through journeys")
        }
    }

    Column {
        current.stops.forEachIndexed { index, stop ->
            TimelineStop(stop, current.stops.getOrNull(index - 1), index == current.stops.lastIndex, trip, detail.isOrganizer && !vm.busy, geocoded[stop.id], { url -> openUrl(context, url) }, { located -> navigateTo(context, located, dayStops, trip.timezone, trip.id) }, { vm.toggleStopDone(stop) }) { editing = stop; form = true }
        }
    }
    if (form) StopForm(vm, detail, editing) { form = false }
}

/** The focused journey under the map: "Journey N of M", previous / next / close always in the same place, and directions. */
/** AI trip planner: describe the trip, untick what you don't want, add the rest. Nothing is saved until "Add". */
@Composable private fun AiPlannerSheet(vm: TravelViewModel, detail: TripDetail, dismiss: () -> Unit) {
    var prompt by rememberSaveable { mutableStateOf(vm.pendingAiPrompt ?: "") }
    val keep = remember(vm.suggestions) { mutableStateListOf<Boolean>().apply { repeat(vm.suggestions.size) { add(true) } } }
    // Opened from "create trip" with a request: start suggesting straight away.
    LaunchedEffect(Unit) { if (vm.pendingAiPrompt != null && vm.suggestions.isEmpty()) vm.suggest(prompt) }
    val approved = vm.suggestions.filterIndexed { i, _ -> keep.getOrElse(i) { true } }
    fun clock(local: String) = runCatching { java.time.LocalDateTime.parse(local).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)) }.getOrDefault(local.takeLast(5))
    FormSheet("Plan ${detail.trip.destination} with AI", vm, { if (!vm.planning) { vm.clearSuggestions(); dismiss() } }, "Describe the trip; you approve what gets added.") {
        Field("What kind of trip is it?", prompt, { prompt = it }, singleLine = false, supporting = "e.g. Beaches and waterfalls, budget-friendly, local food, arriving by ferry on day 1 around 3 PM")
        Button(onClick = { vm.suggest(prompt) }, enabled = !vm.planning && !vm.busy && prompt.trim().length >= 3, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Forest)) {
            if (vm.planning) { CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Planning… this can take a minute") }
            else { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (vm.suggestions.isEmpty()) "Suggest an itinerary" else "Suggest again") }
        }
        if (vm.suggestions.isNotEmpty()) {
            Text("Untick anything you don't want. You can edit each activity after it's added.", color = Muted, style = MaterialTheme.typography.bodySmall)
            vm.suggestions.withIndex().groupBy { it.value.startsAt.take(10) }.forEach { (date, entries) ->
                Text(runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)) }.getOrDefault(date), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                entries.forEach { (i, s) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { keep[i] = !keep[i] }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Checkbox(keep.getOrElse(i) { true }, { keep[i] = it })
                        Column(Modifier.weight(1f).padding(top = 10.dp).alpha(if (keep.getOrElse(i) { true }) 1f else 0.45f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${clock(s.startsAt)} · ${s.title}", fontWeight = FontWeight.SemiBold)
                            s.location?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                            val meta = listOfNotNull(transportOptions.find { it.first == s.transport }?.second, Itinerary.costRange(s.costMinMinor, s.costMaxMinor, detail.trip.currency)?.let { "$it/person" }).joinToString(" · ")
                            if (meta.isNotBlank()) Text(meta, color = Moss, style = MaterialTheme.typography.labelMedium)
                            s.description?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            Submit("Add ${approved.size} ${if (approved.size == 1) "activity" else "activities"} to the itinerary", vm.busy, approved.isNotEmpty(), Icons.Default.Check) { vm.addSuggestions(approved, dismiss) }
        }
    }
}

@Composable private fun JourneyCard(from: Stop, to: Stop, number: Int, total: Int, previous: () -> Unit, next: () -> Unit, close: () -> Unit, open: (String) -> Unit, navigate: () -> Unit) {
    val mode = transportOptions.find { it.first == to.transport }?.second ?: "Transport to be decided"
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("JOURNEY $number OF $total · ${mode.uppercase()}", Modifier.weight(1f), color = Moss, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = previous, enabled = number > 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous journey") }
            IconButton(onClick = next, enabled = number < total) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next journey") }
            IconButton(onClick = close) { Icon(Icons.Default.Close, "Close journey") }
        }
        Text("${from.title} → ${to.title}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        if (to.location.isNotBlank()) Text(to.location, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val directions = if (Itinerary.samePlace(from, to)) null else Itinerary.directionsUrl(from, to)
        if (directions == null) Text("Same spot as the previous stop, no travel needed.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Navigate: turn-by-turn from where you are now (in the app when possible). Route: the planned leg in Google Maps.
            if (to.location.isNotBlank()) Button(onClick = navigate, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Forest)) { Icon(Icons.Default.Navigation, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Navigate") }
            if (directions != null) OutlinedButton(onClick = { open(directions) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Directions, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Route") }
        }
    }
}

@Composable private fun TimelineStop(stop: Stop, previous: Stop?, last: Boolean, trip: Trip, canEdit: Boolean, guessed: Pair<Double, Double>?, open: (String) -> Unit, navigate: (Stop) -> Unit, toggleDone: () -> Unit, edit: () -> Unit) {
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
            Box(Modifier.size(30.dp).clip(CircleShape).background(if (stop.done) Forest else Sage), contentAlignment = Alignment.Center) {
                if (stop.done) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) else Icon(kindIcon(Itinerary.kind(stop.title)), null, tint = Forest, modifier = Modifier.size(16.dp))
            }
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(Line))
        }
        Card(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f).padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(stop.title, Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = if (stop.done) Muted else Color.Unspecified, textDecoration = if (stop.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                    Icon(if (stop.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, if (stop.done) "Done. Tap to undo" else "Mark done", tint = if (stop.done) Forest else Muted, modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = toggleDone).padding(6.dp))
                    if (canEdit) Icon(Icons.Default.Edit, "Edit stop", tint = Muted, modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = edit).padding(7.dp))
                }
                // Tapping the place starts navigation from where you are now.
                val go = Itinerary.navigateUrl(located)
                if (go != null) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF1F5EC)).clickable(onClickLabel = "Navigate to ${stop.location}") { navigate(located) }.padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
    var create by remember { mutableStateOf(false) }; var selectedId by remember { mutableStateOf<Long?>(null) }; var breakdown by remember { mutableStateOf(false) }
    // Same rule as the server's balances: every recorded bill counts in full, paid to the merchant or not.
    val total = detail.expenses.filter { it.status in listOf("open", "completed", "closed") }.sumOf { it.totalMinor }
    // Part of those bills not yet paid to the merchant (e.g. only a down payment so far).
    val pending = detail.expenses.filter { it.status in listOf("open", "completed", "closed") }.sumOf { (it.totalMinor - it.payments.sumOf { p -> p.amountMinor }).coerceAtLeast(0) }
    Row(verticalAlignment = Alignment.Bottom) {
        Text("Shared expenses", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (!detail.trip.cancelled) FilledTonalButton(onClick = { create = true }, enabled = !vm.busy) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add") }
    }
    Card(colors = CardDefaults.cardColors(containerColor = Forest), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Trip spending", color = Color(0xFFCDDEBC)); Text(money(total, detail.trip.currency), color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(if (pending > 0) "${money(pending, detail.trip.currency)} not yet paid to merchants" else "All recorded bills are paid", color = Color(0xFFCDDEBC), style = MaterialTheme.typography.bodySmall)
        val net = detail.balances.find { it.memberId == detail.currentMemberId }?.netMinor ?: 0
        Surface(onClick = { breakdown = true }, shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Your balance", color = Color(0xFFCDDEBC), style = MaterialTheme.typography.labelMedium); Text(money(net, detail.trip.currency), color = Color.White, fontWeight = FontWeight.Bold) }
                Text("Details", color = Color.White, style = MaterialTheme.typography.labelLarge); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White)
            }
        }
    } }
    if (breakdown) BalanceBreakdownSheet(detail) { breakdown = false }
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
    var form by remember { mutableStateOf(false) }; var breakdown by remember { mutableStateOf(false) }
    val mine = detail.balances.find { it.memberId == detail.currentMemberId }?.netMinor ?: 0
    if (breakdown) BalanceBreakdownSheet(detail) { breakdown = false }
    Text("Keep it fair", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Card(onClick = { breakdown = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = if (mine < 0) Color(0xFFFFF0EC) else Sage)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when { mine < 0 -> "You owe"; mine > 0 -> "You're owed"; else -> "You're all settled" }, color = if (mine < 0) Danger else ForestDark, fontWeight = FontWeight.SemiBold)
            Text(if (mine == 0L) "🎉" else money(abs(mine), detail.trip.currency), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = if (mine < 0) Danger else ForestDark)
            Text("Tap for what you will pay and receive", color = Forest, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text("Reimbursements count once the recipient confirms them.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (detail.members.size > 1) Submit("Record money you sent", vm.busy, icon = Icons.AutoMirrored.Filled.Send) { form = true }
    InfoCard("Everyone") {
        detail.balances.forEach { balance -> Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(balance.name, balance.memberId == detail.currentMemberId); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (balance.memberId == detail.currentMemberId) "${balance.name} (you)" else balance.name, fontWeight = FontWeight.Medium)
                val dues = detail.duesOf(balance.memberId)
                if (dues.owesMinor > 0 || dues.owedMinor > 0) Text("Still owes ${money(dues.owesMinor, detail.trip.currency)} · Owed ${money(dues.owedMinor, detail.trip.currency)}", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
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
    var viewing by remember { mutableStateOf<Long?>(null) }
    var editMine by remember { mutableStateOf(false) }
    val me = detail.members.find { it.id == detail.currentMemberId }
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text("Your barkada", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("${detail.members.size} traveler${if (detail.members.size == 1) "" else "s"} · tap someone for their contact", color = Muted)
        }
        FilledTonalButton(onClick = { editMine = true; viewing = detail.currentMemberId }) { Text("My details") }
    }
    if (me != null && me.phone == null && me.emergencyPhone == null) Surface(onClick = { editMine = true; viewing = me.id }, shape = RoundedCornerShape(14.dp), color = Color(0xFFFFF7F4), border = BorderStroke(1.dp, Color(0xFFE0A98F))) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Add your number and emergency contact", fontWeight = FontWeight.SemiBold, color = Color(0xFF7D3424))
            Text("So your barkada can reach you, or someone close to you, if anything happens.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A5A4B))
        }
    }
    if (detail.isOrganizer) Submit("Invite friends", vm.busy, icon = Icons.Default.PersonAdd, click = vm::invite)
    InfoCard {
        detail.members.forEachIndexed { index, member ->
            if (index > 0) HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { editMine = false; viewing = member.id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(member.name, member.id == detail.currentMemberId, url = member.avatarUrl); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(member.name, fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(if (member.id == detail.currentMemberId) "You" else null, member.phone ?: "No number yet").joinToString(" · "), color = Moss, style = MaterialTheme.typography.bodySmall)
                }
                if (member.emergencyPhone != null) Icon(Icons.Default.HealthAndSafety, "Has emergency contact", tint = Color(0xFFB0553F), modifier = Modifier.size(18.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
            }
        }
    }
    detail.members.find { it.id == viewing }?.let { TravelerSheet(vm, it, it.id == detail.currentMemberId, editMine) { viewing = null } }
}

/** Opens a downloaded document in another app (gallery, PDF viewer) through the app's FileProvider. */
private fun openDocument(context: Context, document: TravelerDocument, bytes: ByteArray) {
    val extension = when { document.mimeType == "application/pdf" -> "pdf"; document.mimeType == "image/png" -> "png"; document.mimeType == "image/webp" -> "webp"; else -> "jpg" }
    val file = java.io.File(context.cacheDir, "documents").apply { mkdirs() }.resolve("document-${document.id}.$extension").apply { writeBytes(bytes) }
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, document.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: ActivityNotFoundException) { Toast.makeText(context, "No app on this phone can open this file.", Toast.LENGTH_LONG).show() }
}

/** Boarding passes, IDs, bank QR codes on a traveller's card. You see shared ones (and the organizer everyone's); you manage your own. */
@Composable private fun DocumentsSection(vm: TravelViewModel, member: Member, isMe: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(member.id) { vm.loadDocuments() }
    val docs = vm.documents.filter { it.memberId == member.id }
    var adding by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf("boarding_pass") }
    var label by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf("private") }
    var picked by remember { mutableStateOf<Uri?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) picked = uri }

    Text("DOCUMENTS", color = Moss, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    if (vm.documentsLoading && docs.isEmpty()) repeat(2) { DocumentSkeleton() }
    else if (docs.isEmpty()) Text(if (isMe) "Keep your boarding pass, ID or bank QR here for the trip." else "No documents shared with you.", color = Muted)
    docs.forEach { doc ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp)).clickable { vm.withDocumentFile(doc) { openDocument(context, doc, it) } }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Sage), contentAlignment = Alignment.Center) {
                val bytes = vm.documentFiles[doc.filePath]
                val thumb = remember(bytes) { bytes?.let { b -> runCatching { android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() }.getOrNull() } }
                if (doc.isImage) LaunchedEffect(doc.filePath) { vm.withDocumentFile(doc) {} }
                if (thumb != null) androidx.compose.foundation.Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(if (doc.isImage) Icons.Default.Image else Icons.Default.Description, null, tint = Forest)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${TravelerDocument.typeName(doc.type)} · ${if (doc.visibility == "trip") "Everyone on the trip" else if (isMe) "You and the organizer" else "Private"}", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (doc.mine) {
                IconButton(onClick = { vm.toggleDocumentVisibility(doc) }, enabled = !vm.busy) { Icon(if (doc.visibility == "trip") Icons.Default.VisibilityOff else Icons.Default.Visibility, if (doc.visibility == "trip") "Make private" else "Share with everyone on the trip", tint = Forest) }
                IconButton(onClick = { vm.deleteDocument(doc) }, enabled = !vm.busy) { Icon(Icons.Default.Delete, "Remove document", tint = Danger) }
            }
        }
    }
    if (isMe && !adding) OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add a document") }
    if (isMe && adding) {
        Choice("Type", type, TravelerDocument.TYPES, { type = it; visibility = if (it == "bank_qr") "trip" else "private" })
        Field("Name (optional)", label, { label = it }, supporting = "e.g. Cebu Pacific MNL–DGT, GCash")
        Text("Who can see it", fontWeight = FontWeight.SemiBold)
        listOf("private" to "Only me and the organizer", "trip" to "Everyone on the trip").forEach { (key, text) ->
            Row(Modifier.fillMaxWidth().clickable { visibility = key }, verticalAlignment = Alignment.CenterVertically) { RadioButton(visibility == key, { visibility = key }); Text(text) }
        }
        OutlinedButton(onClick = { pick.launch(arrayOf("image/*", "application/pdf")) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (picked == null) "Choose a photo or PDF" else "File chosen · change")
        }
        Submit(if (preparing) "Preparing…" else "Upload", vm.busy || preparing, picked != null, Icons.Default.Upload) {
            val uri = picked ?: return@Submit
            preparing = true
            scope.launch {
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                // Photos are downsized (QR codes stay sharp at 2400px); PDFs go as they are, up to 10 MB.
                val bytes = if (mime.startsWith("image/")) Photos.jpeg(context, uri, maxSide = 2400)
                    else withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
                preparing = false
                when {
                    bytes == null -> Toast.makeText(context, "That file couldn't be opened.", Toast.LENGTH_LONG).show()
                    bytes.size > 10 * 1024 * 1024 -> Toast.makeText(context, "The file can be up to 10 MB.", Toast.LENGTH_LONG).show()
                    mime.startsWith("image/") -> vm.uploadDocument(type, label, visibility, bytes, "image/jpeg", "document.jpg") { adding = false; label = ""; picked = null }
                    else -> vm.uploadDocument(type, label, visibility, bytes, "application/pdf", "document.pdf") { adding = false; label = ""; picked = null }
                }
            }
        }
        TextButton(onClick = { adding = false; picked = null }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

/** A traveller's contact and emergency details; your own can be edited here, including your photo. */
@Composable private fun TravelerSheet(vm: TravelViewModel, member: Member, isMe: Boolean, startEditing: Boolean, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(isMe && startEditing) }
    var phone by rememberSaveable { mutableStateOf(member.phone.orEmpty()) }; var other by rememberSaveable { mutableStateOf(member.otherContact.orEmpty()) }
    var emName by rememberSaveable { mutableStateOf(member.emergencyName.orEmpty()) }; var emRel by rememberSaveable { mutableStateOf(member.emergencyRelationship.orEmpty()) }; var emPhone by rememberSaveable { mutableStateOf(member.emergencyPhone.orEmpty()) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { Photos.jpeg(context, uri, maxSide = 800)?.let { vm.uploadAvatar(it) } ?: Toast.makeText(context, "That photo couldn't be opened.", Toast.LENGTH_LONG).show() }
    }
    fun dial(number: String) = runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' }))) }
    FormSheet(member.name, vm, dismiss, if (isMe) "Your details · only people on your trips see these" else "Contact & emergency details") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box {
                Avatar(member.name, isMe, size = 96, url = member.avatarUrl)
                if (isMe) SmallFloatingActionButton(onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp), containerColor = Forest, contentColor = Color.White) { Icon(Icons.Default.PhotoCamera, "Change profile picture", Modifier.size(18.dp)) }
            }
        }
        if (isMe && member.avatarUrl != null) TextButton(onClick = vm::removeAvatar, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Remove photo", color = Danger) }
        if (!editing) {
            Text("CONTACT", color = Moss, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            member.phone?.let { OutlinedButton(onClick = { dial(it) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(it, Modifier.weight(1f)); Text("Call", color = Moss) } }
            member.otherContact?.let { IconText(Icons.Default.Chat, it, Ink) }
            if (member.phone == null && member.otherContact == null) Text(if (isMe) "Add a number so your barkada can reach you." else "No contact details yet.", color = Muted)
            Text("EMERGENCY CONTACT", color = Danger, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            if (member.emergencyName != null || member.emergencyPhone != null) {
                Text(listOfNotNull(member.emergencyName, member.emergencyRelationship).joinToString(" · "), fontWeight = FontWeight.SemiBold)
                member.emergencyPhone?.let { Button(onClick = { dial(it) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Icon(Icons.Default.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Call $it") } }
            } else Text(if (isMe) "Add someone the group can call if something happens to you." else "Not added yet.", color = Muted)
            DocumentsSection(vm, member, isMe)
            if (isMe) Submit("Edit my details", vm.busy, icon = Icons.Default.Edit) { editing = true }
        } else {
            Field("Mobile number", phone, { phone = it }, leading = Icons.Default.Call, capitalize = false, supporting = "e.g. +63 917 123 4567")
            Field("Other contact", other, { other = it }, leading = Icons.Default.Chat, supporting = "e.g. Messenger: @jamie, Viber")
            Text("Emergency contact", fontWeight = FontWeight.Bold, color = Danger)
            Field("Name", emName, { emName = it }, leading = Icons.Default.Person)
            Field("Relationship", emRel, { emRel = it }, supporting = "e.g. Mother, Partner")
            Field("Phone", emPhone, { emPhone = it }, leading = Icons.Default.Call, capitalize = false)
            Submit("Save", vm.busy, icon = Icons.Default.Check) { vm.updateProfile(ProfileDraft(phone, other, emName, emRel, emPhone)) { editing = false } }
            TextButton(onClick = { editing = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

/** Tumblr-style photo feed for the trip. Everyone on the trip can post photos with a caption and leave hearts. */
@Composable private fun PhotosTab(vm: TravelViewModel, detail: TripDetail) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    // Full-screen gallery: the post's photos and the one that was tapped.
    var viewing by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var commentsFor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(detail.trip.id) { if (vm.postsLoadedFor != detail.trip.id) vm.loadPosts() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> if (uris.isNotEmpty()) picked = uris }

    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text("Photos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Moments shared by the barkada", color = Muted)
        }
    }
    val camera = rememberPostCamera { uri -> picked = picked + uri }
    var highlightOpen by remember { mutableStateOf(false) }
    if (vm.posts.isNotEmpty()) OutlinedButton(onClick = { highlightOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
        Icon(Icons.Default.Movie, null, Modifier.size(18.dp), tint = Forest); Spacer(Modifier.width(6.dp)); Text("Day highlight", color = Forest)
    }
    if (highlightOpen) HighlightPlayer(vm) { highlightOpen = false }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !vm.busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Forest)) {
            Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Choose photos")
        }
        OutlinedButton(onClick = camera, enabled = !vm.busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp), tint = Forest); Spacer(Modifier.width(6.dp)); Text("Take photo", color = Forest)
        }
    }
    if (vm.postsLoadedFor == detail.trip.id && vm.posts.isEmpty()) EmptyState(Icons.Default.PhotoCamera, "No photos yet", "Be the first to share a moment from the trip.")
    if (vm.postsLoading && vm.posts.isEmpty()) repeat(2) { PostSkeleton() }
    vm.posts.forEach { post ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Line)) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(post.authorName, url = post.authorAvatarUrl, size = 38); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text(post.authorName, fontWeight = FontWeight.SemiBold); Text(postTime(post.createdAt), color = Muted, style = MaterialTheme.typography.bodySmall) }
                if (post.canDelete) IconButton(onClick = { vm.deletePost(post) }, enabled = !vm.busy) { Icon(Icons.Default.Delete, "Delete post", tint = Muted) }
            }
            PhotoGrid(post.photos) { url -> viewing = post.photos.map { it.url } to post.photos.indexOfFirst { it.url == url }.coerceAtLeast(0) }
            post.caption?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge) }
            Row(Modifier.padding(start = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.toggleLike(post) }) { Icon(if (post.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (post.liked) "Remove heart" else "Add heart", tint = if (post.liked) Color(0xFFD0454C) else Muted) }
                Text("${post.likesCount} ${if (post.likesCount == 1) "heart" else "hearts"}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { commentsFor = post.id }) {
                    Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(18.dp), tint = Muted); Spacer(Modifier.width(6.dp))
                    Text(if (post.commentsCount == 0) "Comment" else "${post.commentsCount} ${if (post.commentsCount == 1) "comment" else "comments"}", color = Muted)
                }
            }
        }
    }
    if (vm.postsNextBefore != null) OutlinedButton(onClick = { vm.loadPosts(more = true) }, enabled = !vm.postsLoading, modifier = Modifier.fillMaxWidth()) { Text(if (vm.postsLoading) "Loading…" else "Load more") }
    if (picked.isNotEmpty()) PostComposer(vm, picked, { picked = it }) { picked = emptyList() }
    viewing?.let { (urls, start) -> PhotoViewer(urls, start) { viewing = null } }
    vm.posts.find { it.id == commentsFor }?.let { post -> CommentsSheet(vm, post) { commentsFor = null } }
}

private fun postTime(iso: String): String = runCatching {
    val time = OffsetDateTime.parse(iso)
    val minutes = java.time.Duration.between(time, OffsetDateTime.now()).toMinutes()
    when { minutes < 1 -> "just now"; minutes < 60 -> "${minutes}m ago"; minutes < 1440 -> "${minutes / 60}h ago"; else -> time.atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ENGLISH)) }
}.getOrDefault("")

@Composable private fun PhotoGrid(photos: List<PostPhoto>, open: (String) -> Unit) {
    val shown = photos.take(4)
    when (shown.size) {
        0 -> Unit
        1 -> { val p = shown[0]; val ratio = if (p.width != null && p.height != null && p.height > 0) (p.width.toFloat() / p.height).coerceIn(0.6f, 1.9f) else 4f / 3f; PhotoTile(p, Modifier.fillMaxWidth().aspectRatio(ratio), 0, open) }
        2 -> Row(Modifier.fillMaxWidth().aspectRatio(2f), horizontalArrangement = Arrangement.spacedBy(3.dp)) { shown.forEach { PhotoTile(it, Modifier.weight(1f).fillMaxHeight(), 0, open) } }
        3 -> Row(Modifier.fillMaxWidth().aspectRatio(1.5f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            PhotoTile(shown[0], Modifier.weight(2f).fillMaxHeight(), 0, open)
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) { PhotoTile(shown[1], Modifier.weight(1f).fillMaxWidth(), 0, open); PhotoTile(shown[2], Modifier.weight(1f).fillMaxWidth(), 0, open) }
        }
        else -> Column(Modifier.fillMaxWidth().aspectRatio(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf(0, 2).forEach { start -> Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                PhotoTile(shown[start], Modifier.weight(1f).fillMaxHeight(), 0, open); PhotoTile(shown[start + 1], Modifier.weight(1f).fillMaxHeight(), if (start == 2) photos.size - 4 else 0, open)
            } }
        }
    }
}

/** A photo post's comments in a bottom sheet: read them, add one, remove ones you're allowed to. */
@Composable private fun CommentsSheet(vm: TravelViewModel, post: Post, dismiss: () -> Unit) {
    var body by rememberSaveable(post.id) { mutableStateOf("") }
    LaunchedEffect(post.id) { vm.loadComments(post) }
    FormSheet(if (post.commentsCount == 0) "Comments" else "${post.commentsCount} ${if (post.commentsCount == 1) "comment" else "comments"}", vm, dismiss, "On ${post.authorName}'s post") {
        if (vm.commentsLoading && vm.comments.isEmpty()) repeat(2) {
            Row(verticalAlignment = Alignment.CenterVertically) { Shimmer(Modifier.size(32.dp).clip(CircleShape)); Spacer(Modifier.width(10.dp)); Shimmer(Modifier.fillMaxWidth(0.7f).height(36.dp).clip(RoundedCornerShape(14.dp))) }
        } else if (vm.comments.isEmpty()) Text("No comments yet. Say something!", color = Muted)
        vm.comments.forEach { comment ->
            Row(verticalAlignment = Alignment.Top) {
                Avatar(comment.authorName, size = 32, url = comment.authorAvatarUrl); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Surface(color = Color(0xFFF3F6EF), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { Text(comment.authorName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge); Text(comment.body) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, top = 2.dp)) {
                        Text(postTime(comment.createdAt), color = Muted, style = MaterialTheme.typography.labelSmall)
                        if (comment.canDelete) TextButton(onClick = { vm.deleteComment(post, comment) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Remove", color = Danger, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(body, { if (it.length <= 1000) body = it }, Modifier.weight(1f), placeholder = { Text("Add a comment…") }, shape = RoundedCornerShape(20.dp), maxLines = 4)
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { vm.addComment(post, body) { body = "" } }, enabled = body.isNotBlank() && !vm.commentSending, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Forest)) {
                if (vm.commentSending) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Filled.Send, "Post comment", tint = Color.White)
            }
        }
    }
}

/** Swipeable full-screen gallery of a post's photos, starting at [start]. */
@Composable private fun PhotoViewer(urls: List<String>, start: Int, close: () -> Unit) {
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = start) { urls.size }
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            androidx.compose.foundation.pager.HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    NetworkImage(model = urls[page], contentDescription = "Photo ${page + 1} of ${urls.size}", contentScale = ContentScale.Fit, dark = true, modifier = Modifier.fillMaxWidth())
                }
            }
            if (urls.size > 1) {
                Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(50), modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp)) {
                    Text("${pager.currentPage + 1} / ${urls.size}", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
                Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(urls.size) { i -> Box(Modifier.size(7.dp).clip(CircleShape).background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.35f))) }
                }
            }
            IconButton(onClick = close, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))) {
                Icon(Icons.Default.Close, "Close photo", tint = Color.White)
            }
        }
    }
}

@Composable private fun PhotoTile(photo: PostPhoto, modifier: Modifier, extra: Int, open: (String) -> Unit) {
    Box(modifier.background(Sage).clickable { open(photo.url) }) {
        NetworkImage(model = photo.url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (extra > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) { Text("+$extra", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
    }
}

@Composable private fun PostComposer(vm: TravelViewModel, picked: List<Uri>, change: (List<Uri>) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var caption by rememberSaveable { mutableStateOf("") }
    var preparing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    val addMore = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> if (uris.isNotEmpty()) change((picked + uris).take(10)) }
    val camera = rememberPostCamera { uri -> change((picked + uri).take(10)) }
    editing?.let { index -> picked.getOrNull(index)?.let { uri -> PhotoEditorSheet(vm, uri, { edited -> change(picked.mapIndexed { i, u -> if (i == index) edited else u }); editing = null }) { editing = null } } }
    FormSheet("Post photos", vm, dismiss, "${picked.size} photo${if (picked.size == 1) "" else "s"} · tap a photo to edit · everyone on the trip can see this") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(picked, key = { _, uri -> uri.toString() }) { index, uri ->
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).clickable { editing = index }) {
                    NetworkImage(model = uri, contentDescription = "Edit photo ${index + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Icon(Icons.Default.Close, "Remove photo", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).clickable { change(picked.filterIndexed { i, _ -> i != index }) }.padding(3.dp))
                    Row(Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(3.dp)); Text("Edit", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (picked.size < 10) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { addMore.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Add more") }
            OutlinedButton(onClick = camera, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PhotoCamera, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Camera") }
        }
        Field("Caption", caption, { caption = it }, singleLine = false, supporting = "Say something about these moments")
        Submit(if (preparing) "Preparing photos…" else "Post ${picked.size} photo${if (picked.size == 1) "" else "s"}", vm.busy || preparing, picked.isNotEmpty(), Icons.AutoMirrored.Filled.Send) {
            scope.launch {
                preparing = true
                val jpegs = picked.mapNotNull { Photos.jpeg(context, it, maxSide = 2000) }
                preparing = false
                if (jpegs.isEmpty()) Toast.makeText(context, "Those photos couldn't be opened.", Toast.LENGTH_LONG).show() else vm.createPost(caption, jpegs, dismiss)
            }
        }
    }
}

// ---- Photo editor (before posting) ----------------------------------------------------------------------

private val photoFilters = listOf("original" to "Original", "warm" to "Warm", "cool" to "Cool", "vivid" to "Vivid", "bw" to "B&W", "vintage" to "Vintage", "fade" to "Fade")
/** Ready-made AI edits (keep the photo real) and art styles; keys match the server's templates. */
private val aiEdits = listOf("enhance" to "Enhance", "brighten" to "Fix lighting", "blue-sky" to "Blue sky", "sunset-sky" to "Sunset sky", "remove-people" to "Remove people behind", "tropical" to "Tropical colours")
private val aiStyles = listOf("golden-hour" to "Golden hour", "postcard" to "Vintage postcard", "watercolor" to "Watercolor", "cartoon" to "Cartoon", "anime" to "Anime", "sketch" to "Sketch")

/** Colour adjustments for a filter preset plus brightness / contrast / saturation (1 = unchanged). */
private fun photoMatrix(filter: String, brightness: Float, contrast: Float, saturation: Float): android.graphics.ColorMatrix {
    val m = android.graphics.ColorMatrix()
    val base = when (filter) { "vivid" -> 1.4f; "bw" -> 0f; "vintage" -> 0.8f; "fade" -> 0.8f; "cool" -> 0.9f; else -> 1f }
    m.setSaturation(base * saturation)
    val c = contrast * when (filter) { "vivid" -> 1.1f; "bw" -> 1.12f; "fade" -> 0.85f; "vintage" -> 0.92f; else -> 1f }
    val b = (brightness - 1f) * 255f + when (filter) { "fade" -> 20f; "vintage" -> 12f; "warm" -> 6f; "cool" -> 8f; else -> 0f }
    val t = (1f - c) * 128f + b
    m.postConcat(android.graphics.ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    when (filter) {
        "warm" -> m.postConcat(android.graphics.ColorMatrix(floatArrayOf(1.08f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0.9f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        "cool" -> m.postConcat(android.graphics.ColorMatrix(floatArrayOf(0.92f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        "vintage" -> m.postConcat(android.graphics.ColorMatrix(floatArrayOf(1.05f, 0.1f, 0f, 0f, 0f, 0.05f, 1f, 0.05f, 0f, 0f, 0f, 0.1f, 0.85f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    }
    return m
}

/** The photo with its rotation and colour adjustments applied, as a new bitmap. */
private fun renderPhoto(source: android.graphics.Bitmap, rotation: Int, matrix: android.graphics.ColorMatrix): android.graphics.Bitmap {
    val rotated = if (rotation % 360 == 0) source else android.graphics.Bitmap.createBitmap(source, 0, 0, source.width, source.height, android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true)
    val out = android.graphics.Bitmap.createBitmap(rotated.width, rotated.height, android.graphics.Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).drawBitmap(rotated, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply { colorFilter = android.graphics.ColorMatrixColorFilter(matrix) })
    return out
}

private fun jpegBytes(bitmap: android.graphics.Bitmap, maxSide: Int = 2000): ByteArray {
    val longest = maxOf(bitmap.width, bitmap.height)
    val scaled = if (longest > maxSide) android.graphics.Bitmap.createScaledBitmap(bitmap, bitmap.width * maxSide / longest, bitmap.height * maxSide / longest, true) else bitmap
    return java.io.ByteArrayOutputStream().also { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
}

/**
 * Edits one photo before posting: rotate, filters, brightness / contrast / saturation, and AI quick fixes or art
 * styles (3 AI edits every 4 hours, shown here). "Use photo" saves the result to a cache file and hands back its Uri.
 */
@Composable private fun PhotoEditorSheet(vm: TravelViewModel, uri: Uri, done: (Uri) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val history = remember { mutableStateListOf<android.graphics.Bitmap>() }
    var rotation by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("original") }
    var brightness by remember { mutableFloatStateOf(1f) }; var contrast by remember { mutableFloatStateOf(1f) }; var saturation by remember { mutableFloatStateOf(1f) }
    var tab by remember { mutableIntStateOf(0) }
    var prompt by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(uri) {
        bitmap = Photos.jpeg(context, uri, maxSide = 2000)?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bitmap == null) { Toast.makeText(context, "That photo couldn't be opened.", Toast.LENGTH_LONG).show(); dismiss() }
    }
    LaunchedEffect(tab) { if (tab == 1) vm.loadAiQuota() }
    val matrix = photoMatrix(filter, brightness, contrast, saturation)
    fun resetAdjustments() { rotation = 0; filter = "original"; brightness = 1f; contrast = 1f; saturation = 1f }
    fun runAi(style: String?, request: String?) {
        val current = bitmap ?: return
        scope.launch {
            val jpeg = withContext(kotlinx.coroutines.Dispatchers.Default) { jpegBytes(renderPhoto(current, rotation, matrix), maxSide = 1600) }
            vm.aiEdit(jpeg, style, request) { result ->
                android.graphics.BitmapFactory.decodeByteArray(result, 0, result.size)?.let { history.add(current); bitmap = it; resetAdjustments(); prompt = "" }
            }
        }
    }
    val quota = vm.aiQuota
    val noAiLeft = quota?.remaining == 0
    FormSheet("Edit photo", vm, { if (!vm.aiEditing && !saving) dismiss() }) {
        Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C2622)), contentAlignment = Alignment.Center) {
            bitmap?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { rotationZ = rotation.toFloat() }, contentScale = ContentScale.Fit,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(matrix.array))) } ?: CircularProgressIndicator(color = Color.White)
            if (vm.aiEditing) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = Color.White); Spacer(Modifier.height(8.dp)); Text("AI is editing… this can take a minute", color = Color.White) }
            }
        }
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = Forest) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Adjust") }, icon = { Icon(Icons.Default.Tune, null) })
            Tab(tab == 1, { tab = 1 }, text = { Text("AI edit") }, icon = { Icon(Icons.Default.AutoAwesome, null) })
        }
        if (tab == 0) {
            OutlinedButton(onClick = { rotation = (rotation + 90) % 360 }) { Icon(Icons.Default.RotateRight, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Rotate") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { photoFilters.forEach { (key, label) -> FilterChip(filter == key, { filter = key }, label = { Text(label) }) } }
            Text("Brightness", style = MaterialTheme.typography.labelLarge); Slider(brightness, { brightness = it }, valueRange = 0.5f..1.5f)
            Text("Contrast", style = MaterialTheme.typography.labelLarge); Slider(contrast, { contrast = it }, valueRange = 0.5f..1.5f)
            Text("Saturation", style = MaterialTheme.typography.labelLarge); Slider(saturation, { saturation = it }, valueRange = 0f..2f)
        } else {
            quota?.let { q ->
                val wait = q.resetsInSeconds.let { s -> if (s >= 3600) "${s / 3600}h ${(s % 3600 + 59) / 60}m" else "${maxOf(1, (s + 59) / 60)} min" }
                Surface(color = if (noAiLeft) Color(0xFFFFF0EB) else Sage, shape = RoundedCornerShape(50)) {
                    Text(if (noAiLeft) "No AI edits left · next in $wait" else "${q.remaining} of ${q.limit} AI edits left" + if (q.remaining < q.limit) " · resets in $wait" else "",
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = if (noAiLeft) Danger else Forest, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
            Text("QUICK FIXES", color = Moss, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { aiEdits.forEach { (key, label) -> AssistChip({ runAi(key, null) }, { Text(label) }, enabled = !vm.aiEditing && !noAiLeft) } }
            Text("ART STYLES", color = Moss, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { aiStyles.forEach { (key, label) -> AssistChip({ runAi(key, null) }, { Text(label) }, enabled = !vm.aiEditing && !noAiLeft) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(prompt, { if (it.length <= 200) prompt = it }, Modifier.weight(1f), placeholder = { Text("Or describe a change, e.g. remove the car on the left") }, enabled = !vm.aiEditing && !noAiLeft, shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { runAi(null, prompt) }, enabled = prompt.isNotBlank() && !vm.aiEditing && !noAiLeft, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Forest)) { Icon(Icons.Default.AutoAwesome, "Run AI edit", tint = Color.White) }
            }
            if (history.isNotEmpty()) TextButton(onClick = { bitmap = history.removeAt(history.lastIndex); resetAdjustments() }, enabled = !vm.aiEditing) { Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Undo last AI edit") }
            Text("Each AI edit counts toward ${quota?.limit ?: 3} every 4 hours.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Submit(if (saving) "Saving…" else "Use photo", vm.aiEditing || saving, bitmap != null, Icons.Default.Check) {
            val current = bitmap ?: return@Submit
            saving = true
            scope.launch {
                val file = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    java.io.File(context.cacheDir, "camera/edited-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs(); writeBytes(jpegBytes(renderPhoto(current, rotation, matrix))) }
                }
                saving = false
                done(Uri.fromFile(file))
            }
        }
    }
}

/**
 * Full-screen day highlight: cover, the day's stops with their photos (slow zoom, crossfades, captions like
 * "7:00 AM  Cambugahay Falls"), outro; story progress bars and music. Tap to pause; day chips switch days.
 */
@Composable private fun HighlightPlayer(vm: TravelViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadHighlight() }
    val h = vm.highlight
    // Parts: 0 = cover, 1..n = slides, n+1 = outro.
    val durations = h?.let { listOf(it.coverDuration) + it.slides.map { s -> s.duration } + listOf(it.outroDuration) } ?: emptyList()
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
        if (!playing || durations.isEmpty()) return@LaunchedEffect
        val remaining = ((1f - progress.value) * durations[index] * 1000).toInt()
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        if (index < durations.lastIndex) index++ else { playing = false; index = 0; runCatching { player.pause(); player.seekTo(0) } }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = dismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0D1512))) {
            if (h == null || vm.highlightLoading) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White); Spacer(Modifier.height(10.dp)); Text("Putting the day together…", color = Color.White)
            } else if (h.slides.isEmpty()) Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No photos for this day yet", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Post photos during the day and they'll appear here with the itinerary.", color = Color.White.copy(alpha = 0.8f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } else {
                Crossfade(index, animationSpec = tween(600), label = "highlight", modifier = Modifier.fillMaxSize().clickable { playing = !playing }) { part ->
                    val slide = h.slides.getOrNull(part - 1)
                    val zoom = remember(part) { Animatable(1.05f) }
                    LaunchedEffect(part, playing) { if (playing) zoom.animateTo(1.17f, tween((durations[part] * 1000).toInt(), easing = LinearEasing)) }
                    Box(Modifier.fillMaxSize()) {
                        val outro = slide == null && part != 0
                        // The outro is a plain background, so no photo is shown twice.
                        if (outro) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Forest, Color(0xFF0D1512)))))
                        else NetworkImage(model = slide?.photoUrl ?: (h.coverUrl ?: h.basePhotoUrl), contentDescription = slide?.caption, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value; translationX = (if (part % 2 == 0) 1 else -1) * 40f * (zoom.value - 1.11f) * 8 })
                        if (!outro) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (part == 0) 0.85f else 0.75f)), startY = if (part == 0) 600f else 900f)))
                        Column(Modifier.align(Alignment.BottomStart).padding(start = 28.dp, end = 28.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when {
                                slide != null -> {
                                    Surface(color = Forest.copy(alpha = 0.92f), shape = RoundedCornerShape(50)) { Text(slide.time, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = Color(0xFFE8F2D8), fontWeight = FontWeight.Bold) }
                                    Text(slide.title, color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineMedium)
                                    slide.location?.let { Text(it.split(",").take(2).joinToString(","), color = Color.White.copy(alpha = 0.85f)) }
                                }
                                part == 0 -> {
                                    val c = h.cover
                                    Text("HIGHLIGHTS · " + (c?.eyebrow ?: "Day ${h.dayNumber}").uppercase(), color = Color(0xFFCFE0A5), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
                                    Text(c?.title ?: h.title, color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineLarge)
                                    Text(c?.date ?: h.subtitle, color = Color.White.copy(alpha = 0.95f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                                    Box(Modifier.padding(vertical = 4.dp).width(40.dp).height(2.dp).background(Color.White.copy(alpha = 0.45f)))
                                    c?.destination?.let { Text(it, color = Color.White, fontWeight = FontWeight.SemiBold) }
                                    c?.route?.let { Text(it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium) }
                                    c?.stats?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color(0xFFE8F2D8), style = MaterialTheme.typography.bodySmall) }
                                }
                                else -> Column(Modifier.fillMaxWidth().padding(bottom = 160.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("That was Day ${h.dayNumber}", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineMedium)
                                    Text(h.cover?.title ?: h.title, color = Color.White.copy(alpha = 0.85f))
                                    Text("BarkadaKasKas", color = Color(0xFFCFE0A5), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                // Story progress bars.
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    durations.indices.forEach { i -> LinearProgressIndicator(progress = { if (i < index) 1f else if (i == index) progress.value else 0f }, modifier = Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)), color = Color.White, trackColor = Color.White.copy(alpha = 0.35f)) }
                }
                if (!playing) Box(Modifier.align(Alignment.Center).size(84.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)).clickable { playing = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, "Play highlight", tint = Forest, modifier = Modifier.size(48.dp))
                }
            }
            IconButton(onClick = dismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 18.dp, end = 6.dp)) { Icon(Icons.Default.Close, "Close highlight", tint = Color.White) }
            if (h != null) Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (h.days.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(h.days) { _, day -> FilterChip(day == h.date, { playing = false; vm.loadHighlight(day) }, label = { Text(runCatching { LocalDate.parse(day).format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)) }.getOrDefault(day)) },
                        colors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedContainerColor = Color.White, selectedLabelColor = Forest)) }
                }
                if (h.slides.isNotEmpty()) OutlinedButton(onClick = vm::makeHighlightCover, enabled = !vm.highlightCovering, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                    if (vm.highlightCovering) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp) else Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (h.coverUrl != null) "Remake AI cover" else "AI cover")
                }
            }
        }
    }
}

/** "Take photo" for posts: each shot goes to its own cache file, handed back as a Uri. */
@Composable private fun rememberPostCamera(onPhoto: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved -> pending?.let { if (saved) onPhoto(it) }; pending = null }
    return {
        val file = java.io.File(context.cacheDir, "camera/post-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        pending = uri
        runCatching { launcher.launch(uri) }.onFailure { Toast.makeText(context, "No camera app found on this phone.", Toast.LENGTH_LONG).show() }
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
        var aiPrompt by rememberSaveable { mutableStateOf("") }
        Field("Plan it with AI (optional)", aiPrompt, { aiPrompt = it }, leading = Icons.Default.AutoAwesome, singleLine = false, supporting = "Describe the trip and AI will suggest an itinerary for you to approve. Leave blank to add activities yourself.")
        Submit("Create trip", vm.busy, name.isNotBlank() && destination.isNotBlank() && end >= start, Icons.Default.Check) { vm.createTrip(TripDraft(name, destination, start, end, currency, zone), aiPrompt, dismiss) }
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
            // Payers stay editable until reimbursements are recorded against the bill (they went to those payers).
            if (expense.status in listOf("open", "completed") && detail.settlements.none { it.expenseId == expense.id }) OutlinedButton(onClick = { action = "payers" }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Change who paid") }
        }
        if (canManage || canDelete) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canManage && expense.status in listOf("open", "completed")) OutlinedButton(onClick = { action = "cancelled" }, enabled = !vm.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Icon(Icons.Default.Block, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Cancel") }
            if (canDelete) OutlinedButton(onClick = { action = "delete" }, enabled = !vm.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Icon(Icons.Default.Delete, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Delete") }
        }
        selectedShare?.let { share ->
            val who = detail.members.find { it.id == share.memberId }?.name ?: "Traveler"
            // Is there anyone who covered more than their share (so this traveller can pay them back)?
            val covered = expense.shares.filter { detail.isOrganizer || it.memberId == detail.currentMemberId }.sumOf { (it.coveredMinor - it.amountMinor).coerceAtLeast(0) }
            val hasRepayment = detail.settlements.any { it.expenseId == null && it.fromMemberId == share.memberId && it.status in listOf("pending", "confirmed") }
            if (covered <= 0 && !hasRepayment) {
                val paid = expense.payments.sumOf { it.amountMinor }
                val direct = minOf(share.remainingMinor, (expense.totalMinor - paid).coerceAtLeast(0))
                HorizontalDivider()
                Text("Nobody has paid $who's share to the merchant yet", fontWeight = FontWeight.Bold)
                Text(if (paid > 0) "The ${money(paid, detail.trip.currency)} recorded so far only covers the payer's own share, so there is nothing to pay back." else "No merchant payment is recorded, so there is nothing to pay back.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text("If $who paid the merchant directly, record it here. If someone paid the whole ${money(expense.totalMinor, detail.trip.currency)}, correct \"Change who paid\".", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (detail.isOrganizer && expense.status == "open" && direct > 0) Submit("${who.substringBefore(' ')} paid the merchant (${money(direct, detail.trip.currency)})", vm.busy) { vm.mutate({ selectedShare = null }) { repo, current -> repo.recordMerchantPayment(current.trip.id, expense, share.memberId, direct) } }
                TextButton(onClick = { selectedShare = null }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
                return@let
            }
            HorizontalDivider(); Text("Confirm payment for $who", fontWeight = FontWeight.Bold)
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
                "payers" -> PayersEditor(vm, detail, expense) { action = null }
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

/** Organizer corrects who paid the merchant and how much; leave it empty if nobody has paid yet. Shares stay as they are. */
@Composable private fun PayersEditor(vm: TravelViewModel, detail: TripDetail, expense: Expense, done: () -> Unit) {
    val rows = remember(expense.id, expense.version) {
        mutableStateListOf<Pair<String, String>>().apply {
            expense.payments.groupBy { it.memberId }.forEach { (id, list) -> add(id.toString() to java.math.BigDecimal(list.sumOf { it.amountMinor }).movePointLeft(2).toPlainString()) }
        }
    }
    val parsed = rows.map { (id, value) -> id.toLongOrNull() to amount(value) }
    val total = parsed.sumOf { it.second ?: 0L }
    val problem = when {
        parsed.any { it.first == null || it.second == null } -> "Pick a traveller and enter an amount on every row."
        parsed.map { it.first }.distinct().size != parsed.size -> "Each traveller can appear only once."
        total > expense.totalMinor -> "Payments can't add up to more than ${money(expense.totalMinor, detail.trip.currency)}."
        expense.status == "completed" && total != expense.totalMinor -> "A completed bill must stay paid in full (${money(expense.totalMinor, detail.trip.currency)})."
        else -> null
    }
    Text("Who paid the merchant?", fontWeight = FontWeight.Bold)
    Text("${money(total, detail.trip.currency)} of ${money(expense.totalMinor, detail.trip.currency)} paid. Remove every row if nobody has paid yet.", style = MaterialTheme.typography.bodySmall, color = Muted)
    rows.forEachIndexed { index, (id, value) ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1.3f)) { Choice("Payer", id, detail.members.map { it.id.toString() to it.name }, { rows[index] = it to value }) }
            Box(Modifier.weight(1f)) { Field("Amount", value, { rows[index] = id to it }, number = true) }
            IconButton(onClick = { rows.removeAt(index) }) { Icon(Icons.Default.Close, "Remove payer", tint = Muted) }
        }
    }
    TextButton(onClick = { rows.add((detail.members.firstOrNull { m -> rows.none { it.first == m.id.toString() } }?.id ?: detail.currentMemberId).toString() to "") }) { Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Add a payer") }
    problem?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodySmall) }
    Submit("Save payers", vm.busy, problem == null) {
        val payments = parsed.map { Payment(it.first!!, it.second!!) }
        vm.mutate(done) { repo, current -> repo.setExpensePayers(current.trip.id, expense, payments) }
    }
    TextButton(onClick = done, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

/** Bottom sheet behind "Your balance": the net split into what you will pay and receive, bill by bill. */
@Composable private fun BalanceBreakdownSheet(detail: TripDetail, dismiss: () -> Unit) {
    val currency = detail.trip.currency
    val net = detail.balances.find { it.memberId == detail.currentMemberId }?.netMinor ?: 0
    val dues = detail.duesOf(detail.currentMemberId)
    val lines = remember(detail) { BalanceBreakdown.of(detail) }
    fun name(id: Long) = detail.members.find { it.id == id }?.name ?: "Traveler"
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("YOUR BALANCE", color = Muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(money(net, currency), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = when { net < 0 -> Danger; net > 0 -> Forest; else -> Ink })
            Text(when { net > 0 -> "Overall, you will receive more than you pay."; net < 0 -> "Overall, you will pay more than you receive."; else -> "You are even overall." }, color = Muted)
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.NorthEast, null, tint = Danger, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("You will pay", Modifier.weight(1f), fontWeight = FontWeight.Bold); Text(money(dues.owesMinor, currency), fontWeight = FontWeight.Bold, color = Danger) }
                if (lines.pay.isEmpty()) Text("Nothing left to pay.", color = Muted)
                lines.pay.forEach { line ->
                    val to = line.toMemberIds.map(::name).joinToString(", ")
                    BreakdownRow(line.title, "Your share · to " + when { to.isEmpty() -> "the merchant (not paid yet)"; line.merchantUnpaid -> "$to and the merchant"; else -> to }, money(line.amountMinor, currency))
                }
            }
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.SouthWest, null, tint = Forest, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("You will receive", Modifier.weight(1f), fontWeight = FontWeight.Bold); Text(money(dues.owedMinor, currency), fontWeight = FontWeight.Bold, color = Forest) }
                if (lines.receive.isEmpty()) Text("Nobody owes you right now.", color = Muted)
                lines.receive.forEach { line ->
                    BreakdownRow(line.title, if (line.from.isEmpty()) "Others’ shares of a bill you covered" else line.from.joinToString(" · ") { "${name(it.memberId)} ${money(it.amountMinor, currency)}" }, money(line.amountMinor, currency))
                }
            }
            Text("Balance = what you will receive − what you will pay. Amounts update when payments are marked as paid.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun BreakdownRow(title: String, note: String, amount: String) {
    HorizontalDivider(color = Line)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(note, color = Muted, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.width(12.dp)); Text(amount, fontWeight = FontWeight.SemiBold)
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
            if (trip.coverUrl != null) NetworkImage(model = trip.coverUrl, contentDescription = "Trip cover photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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

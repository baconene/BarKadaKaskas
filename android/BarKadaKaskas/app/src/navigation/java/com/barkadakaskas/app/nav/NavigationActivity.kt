package com.barkadakaskas.app.nav

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Geocoder
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material.icons.filled.Fullscreen
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.barkadakaskas.app.data.LaravelRepository
import com.barkadakaskas.app.data.TokenStore
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Place
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.StylingOptions
import com.google.android.libraries.navigation.Waypoint
import java.util.Locale

private val Forest = Color(0xFF214E3E)
private val Moss = Color(0xFF698448)
private val Sage = Color(0xFFE7EFDD)
private val Cream = Color(0xFFFBFCF8)
private val Ink = Color(0xFF172F2A)
private val Muted = Color(0xFF6C7C72)
private val Line = Color(0xFFE0E7DC)
private val Danger = Color(0xFFA6573E)

/**
 * Turn-by-turn navigation inside the app with Google's Navigation SDK. Google's map and guidance fill the top;
 * below it, in the app's own style, a panel shows where you're heading (with End) and the day's itinerary, so the
 * next stop is one tap away. Shows Google's terms the first time. If navigation can't start (SDK not enabled for
 * the key, terms declined, no location permission, no route) the trip is handed to Google Maps instead.
 */
class NavigationActivity : ComponentActivity() {
    private lateinit var navigationView: NavigationView
    private var navigator: Navigator? = null
    private var navMap: com.google.android.gms.maps.GoogleMap? = null
    private var handedOff = false
    private var started = false

    private val stops = mutableStateListOf<InAppNavigation.NavStop>()
    private var tripId: Long? = null
    private var marking by mutableStateOf(false)
    /** The stop you arrived at, while the "mark as done?" question is showing. */
    private var askDone by mutableStateOf<Int?>(null)
    private var current by mutableIntStateOf(0)
    private var arrived by mutableStateOf(false)
    private var routing by mutableStateOf(true)
    /** Time and distance left to the current stop, shown in the panel instead of Google's own trip card. */
    private var remainingSeconds by mutableStateOf<Int?>(null)
    private var remainingMeters by mutableStateOf<Int?>(null)
    private val remainingListener = Navigator.RemainingTimeOrDistanceChangedListener { updateRemaining() }

    private val arrivalListener = Navigator.ArrivalListener {
        arrived = true
        navigator?.stopGuidance()
        // Offer to tick the stop off for everyone on the trip.
        val stop = stops.getOrNull(current)
        if (stop?.id != null && !stop.done && tripId != null) askDone = current
    }
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        when {
            hasLocationPermission() -> { askNotifications(); acceptTermsThenNavigate() }
            // "Approximate" location is hundreds of metres off: turn-by-turn needs precise location.
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> askForPreciseLocation()
            else -> handOff("Location permission is needed to navigate here.")
        }
    }
    /** Android 13+: lets the navigation service show its notification, which keeps guidance running with the screen off. */
    private val askNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // While navigating: the screen stays on, and the map uses the whole screen (status bar and the phone's
        // buttons hidden; a swipe from the edge shows them for a moment).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        tripId = intent.getLongExtra(InAppNavigation.EXTRA_TRIP, -1L).takeIf { it > 0 }
        stops += InAppNavigation.decode(intent.getStringExtra(InAppNavigation.EXTRA_ROUTE)).ifEmpty {
            // Opened for a single stop: show just that one.
            listOf(InAppNavigation.NavStop(intent.getStringExtra(InAppNavigation.EXTRA_TITLE) ?: "Destination", intent.getStringExtra(InAppNavigation.EXTRA_QUERY),
                intent.getDoubleExtra(InAppNavigation.EXTRA_LAT, Double.NaN).takeUnless { it.isNaN() }, intent.getDoubleExtra(InAppNavigation.EXTRA_LNG, Double.NaN).takeUnless { it.isNaN() },
                null, "", intent.getStringExtra(InAppNavigation.EXTRA_FALLBACK) ?: ""))
        }
        current = intent.getIntExtra(InAppNavigation.EXTRA_INDEX, 0).coerceIn(0, stops.lastIndex)

        navigationView = NavigationView(this)
        navigationView.onCreate(savedInstanceState)
        // Live traffic on the navigation map (the route itself is already coloured by traffic).
        navigationView.getMapAsync { map -> map.isTrafficEnabled = true; navMap = map }
        // Google's guidance in the app's colours: forest-green instruction banner, soft-green turn arrows.
        navigationView.setStylingOptions(StylingOptions()
            .primaryDayModeThemeColor(0xFF214E3E.toInt()).secondaryDayModeThemeColor(0xFF2F6250.toInt())
            .primaryNightModeThemeColor(0xFF15342A.toInt()).secondaryNightModeThemeColor(0xFF1E4537.toInt())
            .headerLargeManeuverIconColor(0xFFCFE0A5.toInt()).headerSmallManeuverIconColor(0xFFCFE0A5.toInt()))
        // Time, distance and arrival live in our panel; the speed limit sign stays, the extra speedometer goes.
        navigationView.setEtaCardEnabled(false)
        navigationView.setSpeedometerEnabled(false)
        navigationView.setSpeedLimitIconEnabled(true)
        val panel = ComposeView(this).apply { setContent { MaterialTheme { Panel() } } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(navigationView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        if (savedInstanceState == null) {
            if (hasLocationPermission()) { askNotifications(); acceptTermsThenNavigate() }
            else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    // ---- Panel under the map ------------------------------------------------------------------------------

    @Composable private fun Panel() {
        var expanded by remember { mutableStateOf(stops.size > 1) }
        // Three sizes: everything, a compact bar, or hidden (a slim strip, for the full map). Swipe down to shrink, up to grow.
        var size by remember { mutableIntStateOf(2) }   // 2 = full, 1 = compact, 0 = hidden
        val collapsed = size < 2
        // Arriving (or the "mark done?" question) brings the panel back.
        LaunchedEffect(arrived, askDone) { if ((arrived || askDone != null) && size == 0) size = 1 }
        // Resizing the map (panel shown/hidden) can make Google's camera stop following you; follow again.
        LaunchedEffect(size, expanded) { kotlinx.coroutines.delay(400); if (!routing && !arrived) followMe() }
        val swipe = Modifier.pointerInput(Unit) {
            var dragged = 0f
            detectVerticalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = { if (dragged > 40f) size = (size - 1).coerceAtLeast(0) else if (dragged < -40f) size = (size + 1).coerceAtMost(2) }) { change, dy -> change.consume(); dragged += dy }
        }
        if (size == 0) {
            // Hidden: a slim strip with the time left; tap or swipe up to bring the details back.
            Surface(color = Cream, shadowElevation = 12.dp, shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)) {
                Row(Modifier.fillMaxWidth().then(swipe).clickable(onClickLabel = "Show navigation details") { size = 1 }.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ExpandLess, null, tint = Forest)
                    Spacer(Modifier.width(6.dp))
                    val seconds = remainingSeconds; val meters = remainingMeters
                    Text(if (seconds != null && meters != null && !routing) "${duration(seconds)} · ${distance(meters)}" else stops[current].title, Modifier.weight(1f), color = Forest, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Details", color = Muted, style = MaterialTheme.typography.labelMedium)
                }
            }
            return
        }
        val stop = stops[current]
        val next = stops.drop(current + 1).firstOrNull { !it.done }?.let { stops.indexOf(it) }
        Surface(color = Cream, shadowElevation = 12.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 18.dp).padding(bottom = 12.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Handle: a wide touch area so the swipe is easy to hit.
                Box(Modifier.fillMaxWidth().height(28.dp).then(swipe).clickable(onClickLabel = if (collapsed) "Show more" else "Show less") { size = if (collapsed) 2 else 1 }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 44.dp, height = 5.dp).clip(CircleShape).background(Line))
                    // Hide the panel for the full map.
                    IconButton(onClick = { size = 0 }, modifier = Modifier.align(Alignment.CenterEnd).size(32.dp)) { Icon(Icons.Default.Fullscreen, "Hide details for the full map", tint = Muted) }
                }
                Column(swipe, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(when { arrived -> "YOU'VE ARRIVED"; routing -> "FINDING ROUTE…"; else -> "HEADING TO" }, Modifier.weight(1f), color = Moss, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Button(onClick = { finish() }, colors = ButtonDefaults.buttonColors(containerColor = Danger), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp), modifier = Modifier.heightIn(min = 36.dp)) {
                            Icon(Icons.Default.Close, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("End", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    val seconds = remainingSeconds; val meters = remainingMeters
                    if (!arrived && !routing && seconds != null && meters != null) Row(verticalAlignment = Alignment.Bottom) {
                        Text(duration(seconds), color = Forest, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(10.dp))
                        Text("${distance(meters)} · arrive ${arrival(seconds)}", color = Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 3.dp))
                    }
                    Text(stop.title, color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = if (collapsed) 1 else 2, overflow = TextOverflow.Ellipsis)
                    if (!collapsed) stop.location?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                askDone?.let { index ->
                    Surface(color = Sage, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = Forest, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
                                Text("Mark “${stops[index].title}” as done?", color = Ink, fontWeight = FontWeight.SemiBold)
                            }
                            Text("Everyone on the trip will see it ticked off.", color = Muted, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { markDone(index) }, enabled = !marking, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Forest), shape = RoundedCornerShape(12.dp)) {
                                    if (marking) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp) else Text("Mark done")
                                }
                                OutlinedButton(onClick = { askDone = null }, enabled = !marking, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Not now", color = Forest) }
                            }
                        }
                    }
                }
                if (arrived && next != null && askDone == null) Button(onClick = { switchTo(next) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = Forest), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Navigation, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Next: ${stops[next].title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!collapsed) SearchBox()
                if (!collapsed && stops.size > 1) {
                    HorizontalDivider(color = Line)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { expanded = !expanded }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Today's itinerary", Modifier.weight(1f), color = Ink, fontWeight = FontWeight.SemiBold)
                        Text("${current + 1} of ${stops.size}", color = Muted, style = MaterialTheme.typography.labelMedium)
                        Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess, if (expanded) "Hide itinerary" else "Show itinerary", tint = Muted)
                    }
                    AnimatedVisibility(expanded) {
                        val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), state = list) {
                            itemsIndexed(stops) { index, item -> StopRow(index, item, index == current) { switchTo(index) } }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun StopRow(index: Int, stop: InAppNavigation.NavStop, active: Boolean, open: () -> Unit) {
        val inApp = InAppNavigation.mode(stop.transport) != null && (stop.latitude != null || !stop.location.isNullOrBlank())
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (active) Sage else Color.Transparent).clickable(enabled = !active, onClick = open).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stop.time, Modifier.width(62.dp), color = if (active) Forest else Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Box(Modifier.size(26.dp).clip(CircleShape).background(if (active || stop.done) Forest else Color.White).border(1.dp, if (active || stop.done) Forest else Line, CircleShape), contentAlignment = Alignment.Center) {
                when {
                    stop.done -> Icon(Icons.Default.Check, "Done", tint = Color.White, modifier = Modifier.size(14.dp))
                    index == stops.lastIndex -> Icon(Icons.Default.Flag, null, tint = if (active) Color.White else Moss, modifier = Modifier.size(14.dp))
                    else -> Text("${index + 1}", color = if (active) Color.White else Moss, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stop.title, color = if (stop.done && !active) Muted else Ink, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                stop.location?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (active) Text("Now", color = Forest, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            else if (inApp) Icon(Icons.Default.Navigation, "Navigate here", tint = Forest, modifier = Modifier.size(18.dp))
            else Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in Google Maps", tint = Muted, modifier = Modifier.size(18.dp))
        }
    }

    /** Keeps the camera on the blue arrow (tilted, heading-up), e.g. after the map was resized by the panel. */
    private fun followMe() {
        navMap?.followMyLocation(com.google.android.gms.maps.GoogleMap.CameraPerspective.TILTED)
    }

    private fun updateRemaining() {
        val left = navigator?.currentTimeAndDistance ?: return
        remainingSeconds = left.seconds; remainingMeters = left.meters
    }

    private fun duration(seconds: Int): String = when {
        seconds < 60 -> "< 1 min"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 3600} hr ${(seconds % 3600) / 60} min"
    }

    private fun distance(meters: Int): String = if (meters < 1000) "$meters m" else String.format(Locale.ENGLISH, "%.1f km", meters / 1000.0)

    private fun arrival(seconds: Int): String = java.time.LocalTime.now().plusSeconds(seconds.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))

    /** Saves "done" on the server for the stop (same as ticking it in the itinerary) and updates the list. */
    private fun markDone(index: Int) {
        val trip = tripId ?: return
        val stopId = stops.getOrNull(index)?.id ?: return
        marking = true
        lifecycleScope.launch {
            val saved = runCatching { LaravelRepository(TokenStore(applicationContext)).setStopDone(trip, stopId, true) }.isSuccess
            marking = false
            if (saved) {
                stops[index] = stops[index].copy(done = true)
                askDone = null
            } else Toast.makeText(this@NavigationActivity, "Couldn't save. Check your connection and try again.", Toast.LENGTH_LONG).show()
        }
    }

    // ---- Place search ------------------------------------------------------------------------------------

    private var searching by mutableStateOf(false)
    private var searchResults by mutableStateOf<List<PlaceSearch.Place>>(emptyList())
    private var searchBusy by mutableStateOf(false)
    private var searchMessage by mutableStateOf<String?>(null)

    /** "Search a place" in the panel: find somewhere nearby and navigate there as a detour. */
    @Composable private fun SearchBox() {
        val focus = LocalFocusManager.current
        var query by remember { mutableStateOf("") }
        if (!searching) {
            OutlinedButton(onClick = { searching = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = Forest); Spacer(Modifier.width(6.dp)); Text("Search a place", color = Forest)
            }
            return
        }
        fun run() { if (query.isNotBlank()) { focus.clearFocus(); search(query) } }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp),
            placeholder = { Text("Gas station, pharmacy, an address…") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { IconButton(onClick = { searching = false; searchResults = emptyList(); searchMessage = null; query = "" }) { Icon(Icons.Default.Close, "Close search") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run() }))
        when {
            searchBusy -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest)
            searchMessage != null -> Text(searchMessage!!, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (searchResults.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
            itemsIndexed(searchResults) { _, place ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { goToPlace(place) }.padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, null, tint = Moss, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(place.name, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        place.address?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Icon(Icons.Default.Navigation, "Navigate here", tint = Forest, modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // Navigation only runs with location permission.
    private fun lastPosition(): Pair<Double, Double>? = runCatching {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstNotNullOfOrNull { manager.getLastKnownLocation(it) }?.let { it.latitude to it.longitude }
    }.getOrNull()

    private fun search(query: String) {
        searchBusy = true; searchMessage = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { PlaceSearch.search(query, lastPosition(), packageName) } }
            searchBusy = false
            result.onSuccess { searchResults = it; if (it.isEmpty()) searchMessage = "No places found for “$query”." }
                .onFailure { searchResults = emptyList(); searchMessage = "Search isn't available right now. ${it.message ?: ""}".trim() }
        }
    }

    /** Navigates to a searched place as a detour, listed right after the current stop (it isn't saved to the trip). */
    private fun goToPlace(place: PlaceSearch.Place) {
        val url = "https://www.google.com/maps/dir/?api=1&destination=${place.latitude},${place.longitude}&dir_action=navigate"
        val index = (current + 1).coerceAtMost(stops.size)
        stops.add(index, InAppNavigation.NavStop(place.name, place.address, place.latitude, place.longitude, null, "Detour", url))
        searching = false; searchResults = emptyList()
        current = index
        askDone = null
        route(index)
    }

    // ---- Navigation ---------------------------------------------------------------------------------------

    /** Turn-by-turn needs precise location; "approximate" alone isn't enough. */
    private fun hasLocationPermission() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) askNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Only approximate location was allowed: explain, and offer the app's settings where "Use precise location" is. */
    private fun askForPreciseLocation() {
        AlertDialog.Builder(this)
            .setTitle("Turn on precise location")
            .setMessage("Navigation needs your precise location. Approximate location can be hundreds of metres off, so directions would be wrong.\n\nIn the app's settings, open Permissions → Location and turn on \"Use precise location\".")
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
                finish()
            }
            .setNegativeButton("Use Google Maps") { _, _ -> handedOff = false; handOff("Precise location is off.") }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun acceptTermsThenNavigate() {
        if (NavigationApi.areTermsAccepted(application)) { startNavigator(); return }
        NavigationApi.showTermsAndConditionsDialog(this, "BarkadaKasKas", "Navigation by Google") { accepted ->
            if (accepted) startNavigator() else handOff("Google's navigation terms weren't accepted.")
        }
    }

    private fun startNavigator() {
        NavigationApi.getNavigator(this, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(ready: Navigator) {
                navigator = ready
                ready.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)
                ready.addArrivalListener(arrivalListener)
                ready.addRemainingTimeOrDistanceChangedListener(30, 100, remainingListener)
                route(current)
            }

            override fun onError(@NavigationApi.ErrorCode errorCode: Int) {
                handOff(when (errorCode) {
                    NavigationApi.ErrorCode.NOT_AUTHORIZED -> "In-app navigation isn't enabled for this app yet."
                    NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> "Google's navigation terms weren't accepted."
                    NavigationApi.ErrorCode.NETWORK_ERROR -> "No connection for in-app navigation."
                    NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING -> "Location permission is needed to navigate here."
                    else -> "In-app navigation couldn't start (error $errorCode)."
                })
            }
        })
    }

    /** Switches guidance to another stop of the day; stops the SDK can't route open in Google Maps instead. */
    private fun switchTo(index: Int) {
        val stop = stops.getOrNull(index) ?: return
        if (InAppNavigation.mode(stop.transport) == null || (stop.latitude == null && stop.location.isNullOrBlank())) {
            Toast.makeText(this, "Opening ${stop.title} in Google Maps.", Toast.LENGTH_SHORT).show()
            InAppNavigation.openGoogleMaps(this, stop.fallbackUrl)
            return
        }
        current = index
        askDone = null
        route(index)
    }

    private fun route(index: Int) {
        val stop = stops[index]
        arrived = false; routing = true
        if (stop.latitude != null && stop.longitude != null) { routeTo(index, stop.latitude, stop.longitude); return }
        geocodeThenRoute(index)
    }

    /** Stops typed as an address have no pin: look the address up on the device first. */
    @Suppress("DEPRECATION") // The listener variant is API 33+; this runs on a background thread.
    private fun geocodeThenRoute(index: Int) {
        val query = stops[index].location
        if (query.isNullOrBlank() || !Geocoder.isPresent()) { routeFailed("${stops[index].title} has no map location."); return }
        Thread {
            val found = runCatching { Geocoder(this, Locale.getDefault()).getFromLocationName(query, 1)?.firstOrNull() }.getOrNull()
            runOnUiThread {
                if (isFinishing || current != index) return@runOnUiThread
                if (found == null) routeFailed("Couldn't find “$query” on the map.") else routeTo(index, found.latitude, found.longitude)
            }
        }.start()
    }

    private fun routeTo(index: Int, latitude: Double, longitude: Double) {
        val navigator = navigator ?: return
        val stop = stops[index]
        val travelMode = when (InAppNavigation.mode(stop.transport) ?: runCatching { InAppNavigation.Mode.valueOf(intent.getStringExtra(InAppNavigation.EXTRA_MODE) ?: "") }.getOrNull()) {
            InAppNavigation.Mode.TWO_WHEELER -> RoutingOptions.TravelMode.TWO_WHEELER
            InAppNavigation.Mode.CYCLING -> RoutingOptions.TravelMode.CYCLING
            InAppNavigation.Mode.WALKING -> RoutingOptions.TravelMode.WALKING
            else -> RoutingOptions.TravelMode.DRIVING
        }
        val destination = Waypoint.builder().setLatLng(latitude, longitude).setTitle(stop.title).build()
        navigator.setDestination(destination, RoutingOptions().travelMode(travelMode)).setOnResultListener { status ->
            if (current != index) return@setOnResultListener
            if (status == Navigator.RouteStatus.OK) {
                routing = false; started = true
                navigator.startGuidance()
                updateRemaining()
                followMe()
            } else {
                routeFailed(if (status == Navigator.RouteStatus.NO_ROUTE_FOUND) "No route found to ${stop.title} in the app." else "In-app navigation couldn't find a route to ${stop.title}.")
            }
        }
    }

    /** The first route failing hands the trip to Google Maps; a later stop failing just says so. */
    private fun routeFailed(reason: String) {
        routing = false
        if (!started) handOff(reason) else Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
    }

    /** Says why in-app navigation can't start, then opens the trip in Google Maps (or closes) and leaves this screen. */
    private fun handOff(reason: String) {
        if (handedOff || isFinishing) return
        handedOff = true
        val fallback = stops.getOrNull(current)?.fallbackUrl?.ifBlank { null } ?: intent.getStringExtra(InAppNavigation.EXTRA_FALLBACK)
        AlertDialog.Builder(this)
            .setTitle("In-app navigation unavailable")
            .setMessage(reason)
            .setPositiveButton("Open Google Maps") { _, _ -> fallback?.let { InAppNavigation.openGoogleMaps(this, it) }; finish() }
            .setNegativeButton("Close") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onStart() { super.onStart(); navigationView.onStart() }
    override fun onResume() { super.onResume(); navigationView.onResume(); fullScreen() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) fullScreen() }
    private fun fullScreen() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    override fun onPause() { navigationView.onPause(); super.onPause() }
    override fun onStop() { navigationView.onStop(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); navigationView.onSaveInstanceState(outState) }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); navigationView.onConfigurationChanged(newConfig) }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); navigationView.onTrimMemory(level) }

    override fun onDestroy() {
        navigator?.let {
            it.removeArrivalListener(arrivalListener)
            it.removeRemainingTimeOrDistanceChangedListener(remainingListener)
            it.stopGuidance()
            it.clearDestinations()
            it.cleanup()
        }
        navigationView.onDestroy()
        super.onDestroy()
    }
}

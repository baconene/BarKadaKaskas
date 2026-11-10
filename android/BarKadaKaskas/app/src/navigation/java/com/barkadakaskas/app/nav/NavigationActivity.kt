package com.barkadakaskas.app.nav

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Geocoder
import android.os.Bundle
import android.view.ViewGroup
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) acceptTermsThenNavigate() else handOff("Location permission is needed to navigate here.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        navigationView.getMapAsync { map -> map.isTrafficEnabled = true }
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
            if (hasLocationPermission()) acceptTermsThenNavigate()
            else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    // ---- Panel under the map ------------------------------------------------------------------------------

    @Composable private fun Panel() {
        var expanded by remember { mutableStateOf(stops.size > 1) }
        val stop = stops[current]
        val next = stops.drop(current + 1).firstOrNull { !it.done }?.let { stops.indexOf(it) }
        Surface(color = Cream, shadowElevation = 12.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp).padding(top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(Line))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(when { arrived -> "YOU'VE ARRIVED"; routing -> "FINDING ROUTE…"; else -> "HEADING TO" }, color = Moss, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        val seconds = remainingSeconds; val meters = remainingMeters
                        if (!arrived && !routing && seconds != null && meters != null) Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(duration(seconds), color = Forest, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.width(8.dp))
                            Text("${distance(meters)} · arrive ${arrival(seconds)}", color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 3.dp))
                        }
                        Text(stop.title, color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        stop.location?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { finish() }, colors = ButtonDefaults.buttonColors(containerColor = Danger), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
                        Icon(Icons.Default.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("End", fontWeight = FontWeight.SemiBold)
                    }
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
                if (stops.size > 1) {
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

    // ---- Navigation ---------------------------------------------------------------------------------------

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

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
    override fun onResume() { super.onResume(); navigationView.onResume() }
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

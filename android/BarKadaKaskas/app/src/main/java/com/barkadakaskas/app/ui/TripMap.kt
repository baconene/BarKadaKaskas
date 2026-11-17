package com.barkadakaskas.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.graphics.RectF
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.barkadakaskas.app.BuildConfig
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker as OsmMarker
import org.osmdroid.views.overlay.Polyline as OsmPolyline
import java.io.File
import kotlinx.coroutines.launch

/** One numbered pin on the trip map. [approximate] pins come from geocoding a typed address. */
data class MapPin(val id: Long, val number: Int, val title: String, val subtitle: String, val latitude: Double, val longitude: Double, val approximate: Boolean = false)

/** True when the build has a Google Maps SDK key (MAPS_API_KEY); otherwise the map uses OpenStreetMap. */
val hasGoogleMaps: Boolean get() = BuildConfig.MAPS_API_KEY.isNotBlank()

/**
 * In-app map of a day's stops: numbered pins joined in visiting order.
 * Tapping a pin shows its name; tapping that bubble calls [onNavigate] (Google Maps only).
 * [focus] lists pin ids to zoom to (a journey's start and end); empty fits the whole day.
 */
@Composable fun TripMap(pins: List<MapPin>, modifier: Modifier = Modifier, routeColor: Color = Color(0xFF214E3E), focus: List<Long> = emptyList(), onNavigate: (MapPin) -> Unit = {}) {
    if (hasGoogleMaps) GoogleTripMap(pins, modifier, routeColor, focus, onNavigate) else OsmTripMap(pins, modifier, routeColor, focus)
}

/** The pins the camera should frame: the focused ones when any are on the map, otherwise all of them. */
private fun framed(pins: List<MapPin>, focus: List<Long>): List<MapPin> = pins.filter { it.id in focus }.ifEmpty { pins }

private fun numberedPin(context: Context, number: Int, fill: Int, approximate: Boolean): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (34 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = size / 2f
    canvas.drawCircle(radius, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    canvas.drawCircle(radius, radius, radius - 3 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; alpha = if (approximate) 170 else 255 })
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 14 * density; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    canvas.drawText(number.toString(), radius, radius - (text.descent() + text.ascent()) / 2, text)
    return bitmap
}

/** "You" on the map: a top-down orange sports car (pointing up; the marker is rotated to the heading). */
private fun carIcon(context: Context): Bitmap {
    val d = context.resources.displayMetrics.density
    val w = 26 * d; val h = 46 * d
    val bitmap = Bitmap.createBitmap((w + 6 * d).toInt(), (h + 6 * d).toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bitmap); c.translate(3 * d, 3 * d)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0x55000000; c.drawRoundRect(RectF(1 * d, 2 * d, w + 1 * d, h + 2 * d), 9 * d, 9 * d, paint) // shadow
    paint.color = android.graphics.Color.parseColor("#1C1C1C") // wheels
    listOf(8f, 33f).forEach { y -> c.drawRoundRect(RectF(-1.5f * d, y * d, 4 * d, (y + 9) * d), 2 * d, 2 * d, paint); c.drawRoundRect(RectF(w - 4 * d, y * d, w + 1.5f * d, (y + 9) * d), 2 * d, 2 * d, paint) }
    paint.color = android.graphics.Color.parseColor("#FF8000"); c.drawRoundRect(RectF(1.5f * d, 0f, w - 1.5f * d, h), 10 * d, 10 * d, paint) // body
    paint.color = android.graphics.Color.parseColor("#22313A"); c.drawRoundRect(RectF(5 * d, 12 * d, w - 5 * d, 21 * d), 4 * d, 4 * d, paint) // windscreen
    c.drawRoundRect(RectF(6 * d, 31 * d, w - 6 * d, 37 * d), 3 * d, 3 * d, paint) // rear window
    paint.color = android.graphics.Color.WHITE; c.drawRect(w / 2 - 1.5f * d, 1 * d, w / 2 + 1.5f * d, 11 * d, paint); c.drawRect(w / 2 - 1.5f * d, 22 * d, w / 2 + 1.5f * d, h - 10 * d, paint) // stripe
    paint.color = android.graphics.Color.parseColor("#FFF4C2"); c.drawCircle(6 * d, 3 * d, 2 * d, paint); c.drawCircle(w - 6 * d, 3 * d, 2 * d, paint) // headlights
    return bitmap
}

/** Follows the device's location (GPS or network) while [enabled]; null until the first fix. */
/**
 * GPS and network fixes arrive from both providers; keep a new one only if it is clearly newer or at least as
 * accurate, so a rough network reading doesn't make the car jump away from a good GPS position.
 */
internal fun isBetterFix(candidate: Location, current: Location?): Boolean {
    if (current == null) return true
    val age = candidate.time - current.time
    if (age > 15_000) return true          // much newer: the old fix is stale
    if (age < -15_000) return false        // much older
    val accuracyDelta = candidate.accuracy - current.accuracy
    return when {
        accuracyDelta <= 0 -> true                                          // more (or as) accurate
        age > 0 && accuracyDelta <= 30 && candidate.provider == current.provider -> true // newer, similar accuracy
        else -> false
    }
}

@SuppressLint("MissingPermission") // Only called once location permission is granted; failures are ignored.
@Composable private fun rememberMyLocation(context: Context, enabled: Boolean): Location? {
    var location by remember { mutableStateOf<Location?>(null) }
    DisposableEffect(enabled) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(update: Location) { if (isBetterFix(update, location)) location = update }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        if (enabled) runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }.forEach { provider ->
                manager.getLastKnownLocation(provider)?.let { if (isBetterFix(it, location)) location = it }
                manager.requestLocationUpdates(provider, 3000L, 5f, listener, Looper.getMainLooper())
            }
        }
        onDispose { runCatching { manager.removeUpdates(listener) } }
    }
    return if (enabled) location else null
}

private fun hasLocationPermission(context: Context) =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable private fun GoogleTripMap(pins: List<MapPin>, modifier: Modifier, routeColor: Color, focus: List<Long>, onNavigate: (MapPin) -> Unit) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val camera = rememberCameraPositionState {
        // Start over the first pin, or the Philippines, until the map has loaded and can fit all pins.
        position = CameraPosition.fromLatLngZoom(pins.firstOrNull()?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(12.88, 121.77), 11f)
    }
    var loaded by remember { mutableStateOf(false) }
    var showMe by remember { mutableStateOf(hasLocationPermission(context)) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted -> showMe = granted.values.any { it } }
    val me = rememberMyLocation(context, showMe)
    val scope = rememberCoroutineScope()
    val carState = rememberMarkerState()
    LaunchedEffect(me) { me?.let { carState.position = LatLng(it.latitude, it.longitude) } }

    LaunchedEffect(pins, loaded, focus) {
        if (!loaded || pins.isEmpty()) return@LaunchedEffect
        val shown = framed(pins, focus)
        val update = if (shown.size == 1) CameraUpdateFactory.newLatLngZoom(LatLng(shown[0].latitude, shown[0].longitude), 15f)
        else CameraUpdateFactory.newLatLngBounds(LatLngBounds.builder().apply { shown.forEach { include(LatLng(it.latitude, it.longitude)) } }.build(), (56 * density).toInt())
        runCatching { camera.animate(update, 600) }
    }

    Box(modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            // Live traffic; "you" is drawn as the car below once there is a fix (Google's blue dot until then).
            properties = MapProperties(isMyLocationEnabled = showMe && me == null, isTrafficEnabled = true),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = showMe && me == null),
            onMapLoaded = { loaded = true },
        ) {
            if (pins.size > 1) Polyline(
                points = pins.map { LatLng(it.latitude, it.longitude) }, color = routeColor.copy(alpha = 0.8f), width = 6 * density,
                jointType = JointType.ROUND, startCap = RoundCap(), endCap = RoundCap(),
            )
            pins.forEach { pin ->
                key(pin.id, pin.latitude, pin.longitude) {
                    val icon = remember(pin.number, pin.approximate) { BitmapDescriptorFactory.fromBitmap(numberedPin(context, pin.number, routeColor.toArgb(), pin.approximate)) }
                    Marker(
                        state = rememberMarkerState(position = LatLng(pin.latitude, pin.longitude)),
                        title = "${pin.number}. ${pin.title}",
                        snippet = if (pin.approximate) "Approximate · tap to navigate here" else "Tap to navigate here",
                        icon = icon,
                        anchor = Offset(0.5f, 0.5f),
                        onInfoWindowClick = { onNavigate(pin) },
                    )
                }
            }
            // Created here, inside the map: BitmapDescriptorFactory crashes if used before Google Maps has started.
            if (me != null) Marker(state = carState, icon = remember { BitmapDescriptorFactory.fromBitmap(carIcon(context)) }, title = "You", anchor = Offset(0.5f, 0.5f), flat = true, rotation = if (me.hasBearing()) me.bearing else 0f, zIndex = 2f)
        }
        if (me != null) SmallFloatingActionButton(
            onClick = { scope.launch { runCatching { camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(me.latitude, me.longitude), 16f), 600) } } },
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp), containerColor = Color.White, contentColor = routeColor,
        ) { Icon(Icons.Default.NearMe, "Show where I am") }
        if (!showMe) SmallFloatingActionButton(
            onClick = { askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp), containerColor = Color.White, contentColor = routeColor,
        ) { Icon(Icons.Default.MyLocation, "Show my location") }
    }
}

private fun configureOsm(context: Context) {
    val config = Configuration.getInstance()
    if (config.userAgentValue == context.packageName) return
    config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    // OpenStreetMap's tile policy requires an identifying user agent.
    config.userAgentValue = context.packageName
    config.osmdroidBasePath = File(context.cacheDir, "osmdroid")
    config.osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
}

/** Fallback when no Google Maps key is configured. */
@Composable private fun OsmTripMap(pins: List<MapPin>, modifier: Modifier, routeColor: Color, focus: List<Long>) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        configureOsm(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            // Scaling 256px tiles up to the screen density is what made the map look pixelated.
            isTilesScaledToDpi = false
            minZoomLevel = 3.0
            controller.setZoom(12.0)
        }
    }
    DisposableEffect(lifecycle, map) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_RESUME -> map.onResume(); Lifecycle.Event.ON_PAUSE -> map.onPause(); else -> Unit }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose { lifecycle.removeObserver(observer); map.onPause(); map.onDetach() }
    }
    Box(modifier) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize(), update = { view ->
            view.overlays.clear()
            val points = pins.map { GeoPoint(it.latitude, it.longitude) }
            if (points.size > 1) view.overlays += OsmPolyline(view).apply {
                setPoints(points)
                outlinePaint.color = routeColor.toArgb()
                outlinePaint.strokeWidth = 6 * context.resources.displayMetrics.density
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.alpha = 200
            }
            pins.forEach { pin ->
                view.overlays += OsmMarker(view).apply {
                    position = GeoPoint(pin.latitude, pin.longitude)
                    title = "${pin.number}. ${pin.title}"
                    snippet = if (pin.approximate) "${pin.subtitle} (approximate)" else pin.subtitle
                    icon = BitmapDrawable(context.resources, numberedPin(context, pin.number, routeColor.toArgb(), pin.approximate))
                    setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_CENTER)
                }
            }
            view.invalidate()
            val shown = framed(pins, focus).map { GeoPoint(it.latitude, it.longitude) }
            view.post {
                when {
                    shown.size == 1 -> { view.controller.setZoom(15.0); view.controller.setCenter(shown.first()) }
                    shown.size > 1 -> runCatching {
                        view.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(shown).increaseByScale(1.35f), false, (48 * context.resources.displayMetrics.density).toInt())
                    }
                }
            }
        })
    }
}

/**
 * A day's map for the trip presentation: numbered stops joined in visiting order (dashed for boats and planes), and
 * the day's photos pinned as round thumbnails where they were taken, or around the stop they belong to. Stops without
 * a saved pin are looked up from their address. Tapping a photo calls [onPhoto]. Google Maps only (null otherwise).
 */
@Composable fun PresentationDayMap(stops: List<com.barkadakaskas.app.domain.DeckStop>, photos: List<com.barkadakaskas.app.domain.DeckMapPhoto>, modifier: Modifier = Modifier, focus: Int? = null, onPhoto: (com.barkadakaskas.app.domain.DeckMapPhoto) -> Unit = {}) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    // Where each stop is: its saved pin, else its address looked up on the phone.
    val positions by produceState<List<LatLng?>?>(null, stops) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            stops.map { s ->
                if (s.lat != null && s.lng != null) LatLng(s.lat, s.lng)
                else s.location?.let { address -> runCatching { @Suppress("DEPRECATION") android.location.Geocoder(context).getFromLocationName(address, 1)?.firstOrNull()?.let { LatLng(it.latitude, it.longitude) } }.getOrNull() }
            }
        }
    }
    // Round photo thumbnails for the pins (loaded once, then drawn as marker icons).
    val thumbs by produceState<Map<String, Bitmap>>(emptyMap(), photos) {
        val loader = coil.ImageLoader(context)
        val size = (52 * density).toInt()
        value = photos.mapNotNull { photo ->
            val drawable = runCatching { loader.execute(coil.request.ImageRequest.Builder(context).data(photo.url).size(size).allowHardware(false).build()).drawable }.getOrNull()
            val source = (drawable as? BitmapDrawable)?.bitmap ?: return@mapNotNull null
            photo.url to roundPhoto(source, size, density)
        }.toMap()
    }
    val points = positions
    val photoPoints = remember(points, photos) {
        val perStop = mutableMapOf<Int, Int>()
        photos.map { photo ->
            if (photo.lat != null && photo.lng != null) LatLng(photo.lat, photo.lng)
            else photo.stop?.let { n -> points?.getOrNull(stops.indexOfFirst { it.n == n })?.let { base ->
                val k = perStop.getOrDefault(n, 0).also { perStop[n] = it + 1 }
                val angle = k * 2 * Math.PI / 5 - Math.PI / 2
                LatLng(base.latitude + 0.0009 * kotlin.math.sin(angle) + 0.0006, base.longitude + 0.0011 * kotlin.math.cos(angle))
            } }
        }
    }
    val all = (points.orEmpty() + photoPoints).filterNotNull()
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(all.firstOrNull() ?: LatLng(12.88, 121.77), 11f) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(loaded, all, focus) {
        if (!loaded || all.isEmpty()) return@LaunchedEffect
        // A tapped stop: zoom to it; otherwise frame the whole day.
        val stopAt = focus?.let { n -> points?.getOrNull(stops.indexOfFirst { it.n == n }) }
        if (stopAt != null) { runCatching { camera.animate(CameraUpdateFactory.newLatLngZoom(stopAt, 16f), 500) }; return@LaunchedEffect }
        val update = if (all.distinct().size == 1) CameraUpdateFactory.newLatLngZoom(all[0], 15f)
        else CameraUpdateFactory.newLatLngBounds(LatLngBounds.builder().apply { all.forEach { include(it) } }.build(), (48 * density).toInt())
        runCatching { camera.animate(update, 500) }
    }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = camera,
        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false),
        onMapLoaded = { loaded = true },
    ) {
        val stopPoints = points.orEmpty()
        for (i in 1 until stops.size) {
            val from = stopPoints.getOrNull(i - 1); val to = stopPoints.getOrNull(i)
            if (from == null || to == null || from == to) continue
            val water = stops[i].transport in listOf("ferry", "flight")
            Polyline(points = listOf(from, to), color = if (water) Color(0xFF2F7FA6) else Color(0xFF214E3E), width = 6 * density, geodesic = water,
                pattern = if (water) listOf(com.google.android.gms.maps.model.Dash(20 * density), com.google.android.gms.maps.model.Gap(12 * density)) else null,
                jointType = JointType.ROUND, startCap = RoundCap(), endCap = RoundCap())
        }
        stops.forEachIndexed { i, stop ->
            val at = stopPoints.getOrNull(i) ?: return@forEachIndexed
            key("stop", stop.n, at) {
                val icon = remember(stop.n) { BitmapDescriptorFactory.fromBitmap(numberedPin(context, stop.n, 0xFFD9643A.toInt(), false)) }
                Marker(state = rememberMarkerState(position = at), title = "${stop.n}. ${stop.title}", icon = icon, anchor = Offset(0.5f, 0.5f), zIndex = 1f)
            }
        }
        photos.forEachIndexed { i, photo ->
            val at = photoPoints.getOrNull(i) ?: return@forEachIndexed
            val bitmap = thumbs[photo.url] ?: return@forEachIndexed
            key("photo", photo.url, at) {
                val icon = remember(bitmap) { BitmapDescriptorFactory.fromBitmap(bitmap) }
                Marker(state = rememberMarkerState(position = at), title = photo.caption, icon = icon, anchor = Offset(0.5f, 1f), zIndex = 2f, onClick = { onPhoto(photo); true })
            }
        }
    }
}

/** A photo as a round pin: white ring, a small pointer underneath. */
private fun roundPhoto(source: Bitmap, size: Int, density: Float): Bitmap {
    val pointer = (8 * density).toInt()
    val out = Bitmap.createBitmap(size, size + pointer, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val r = size / 2f
    canvas.drawPath(android.graphics.Path().apply { moveTo(r - pointer, size - pointer / 2f); lineTo(r + pointer, size - pointer / 2f); lineTo(r, (size + pointer).toFloat()); close() }, white)
    canvas.drawCircle(r, r, r, white)
    val inner = r - 3 * density
    val scale = maxOf(inner * 2 / source.width, inner * 2 / source.height)
    val shader = android.graphics.BitmapShader(source, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
        setLocalMatrix(android.graphics.Matrix().apply { setScale(scale, scale); postTranslate(r - source.width * scale / 2, r - source.height * scale / 2) })
    }
    canvas.drawCircle(r, r, inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
    return out
}

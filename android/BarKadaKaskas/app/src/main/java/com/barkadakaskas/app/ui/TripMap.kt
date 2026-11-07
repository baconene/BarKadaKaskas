package com.barkadakaskas.app.ui

import android.Manifest
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

/** One numbered pin on the trip map. [approximate] pins come from geocoding a typed address. */
data class MapPin(val id: Long, val number: Int, val title: String, val subtitle: String, val latitude: Double, val longitude: Double, val approximate: Boolean = false)

/** True when the build has a Google Maps SDK key (MAPS_API_KEY); otherwise the map uses OpenStreetMap. */
val hasGoogleMaps: Boolean get() = BuildConfig.MAPS_API_KEY.isNotBlank()

/**
 * In-app map of a day's stops: numbered pins joined in visiting order.
 * Tapping a pin shows its name; tapping that bubble calls [onNavigate] (Google Maps only).
 */
@Composable fun TripMap(pins: List<MapPin>, modifier: Modifier = Modifier, routeColor: Color = Color(0xFF214E3E), onNavigate: (MapPin) -> Unit = {}) {
    if (hasGoogleMaps) GoogleTripMap(pins, modifier, routeColor, onNavigate) else OsmTripMap(pins, modifier, routeColor)
}

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

private fun hasLocationPermission(context: Context) =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable private fun GoogleTripMap(pins: List<MapPin>, modifier: Modifier, routeColor: Color, onNavigate: (MapPin) -> Unit) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val camera = rememberCameraPositionState {
        // Start over the first pin, or the Philippines, until the map has loaded and can fit all pins.
        position = CameraPosition.fromLatLngZoom(pins.firstOrNull()?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(12.88, 121.77), 11f)
    }
    var loaded by remember { mutableStateOf(false) }
    var showMe by remember { mutableStateOf(hasLocationPermission(context)) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted -> showMe = granted.values.any { it } }

    LaunchedEffect(pins, loaded) {
        if (!loaded || pins.isEmpty()) return@LaunchedEffect
        val update = if (pins.size == 1) CameraUpdateFactory.newLatLngZoom(LatLng(pins[0].latitude, pins[0].longitude), 15f)
        else CameraUpdateFactory.newLatLngBounds(LatLngBounds.builder().apply { pins.forEach { include(LatLng(it.latitude, it.longitude)) } }.build(), (56 * density).toInt())
        runCatching { camera.animate(update, 600) }
    }

    Box(modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(isMyLocationEnabled = showMe),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = showMe),
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
        }
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
@Composable private fun OsmTripMap(pins: List<MapPin>, modifier: Modifier, routeColor: Color) {
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
            view.post {
                when {
                    points.size == 1 -> { view.controller.setZoom(15.0); view.controller.setCenter(points.first()) }
                    points.size > 1 -> runCatching {
                        view.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(points).increaseByScale(1.35f), false, (48 * context.resources.displayMetrics.density).toInt())
                    }
                }
            }
        })
    }
}

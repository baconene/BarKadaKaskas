package com.barkadakaskas.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/** One numbered pin on the trip map. [approximate] pins come from geocoding a typed address. */
data class MapPin(val number: Int, val title: String, val subtitle: String, val latitude: Double, val longitude: Double, val approximate: Boolean = false)

private fun configureOsm(context: Context) {
    val config = Configuration.getInstance()
    if (config.userAgentValue == context.packageName) return
    config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    // OpenStreetMap's tile policy requires an identifying user agent.
    config.userAgentValue = context.packageName
    config.osmdroidBasePath = File(context.cacheDir, "osmdroid")
    config.osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
}

private fun numberedPin(context: Context, number: Int, fill: Int, approximate: Boolean): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val size = (34 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = size / 2f
    canvas.drawCircle(radius, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    canvas.drawCircle(radius, radius, radius - 3 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; alpha = if (approximate) 170 else 255 })
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 14 * density; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    canvas.drawText(number.toString(), radius, radius - (text.descent() + text.ascent()) / 2, text)
    return BitmapDrawable(context.resources, bitmap)
}

/**
 * In-app map of a day's stops: numbered pins joined in visiting order.
 * Uses OpenStreetMap tiles, so it works without a Google Maps API key.
 */
@Composable fun TripMap(pins: List<MapPin>, modifier: Modifier = Modifier, routeColor: Color = Color(0xFF214E3E)) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        configureOsm(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
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
        AndroidView(factory = { map }, update = { view ->
            view.overlays.clear()
            val points = pins.map { GeoPoint(it.latitude, it.longitude) }
            if (points.size > 1) view.overlays += Polyline(view).apply {
                setPoints(points)
                outlinePaint.color = routeColor.toArgb()
                outlinePaint.strokeWidth = 7 * context.resources.displayMetrics.density
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.alpha = 200
            }
            pins.forEach { pin ->
                view.overlays += Marker(view).apply {
                    position = GeoPoint(pin.latitude, pin.longitude)
                    title = "${pin.number}. ${pin.title}"
                    snippet = if (pin.approximate) "${pin.subtitle} (approximate)" else pin.subtitle
                    icon = numberedPin(context, pin.number, routeColor.toArgb(), pin.approximate)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
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

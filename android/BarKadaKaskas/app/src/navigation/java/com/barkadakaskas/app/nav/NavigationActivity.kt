package com.barkadakaskas.app.nav

import android.Manifest
import android.app.AlertDialog
import android.location.Geocoder
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.Waypoint

/**
 * Turn-by-turn navigation inside the app with Google's Navigation SDK, from the traveller's location to one stop.
 * Shows Google's terms the first time. Anything that stops navigation from starting (SDK not enabled for the key,
 * terms declined, no location permission, no route) hands the trip to Google Maps instead.
 */
class NavigationActivity : ComponentActivity() {
    private lateinit var navigationView: NavigationView
    private var navigator: Navigator? = null
    private var handedOff = false
    private val arrivalListener = Navigator.ArrivalListener {
        Toast.makeText(this, "You've arrived at ${intent.getStringExtra(InAppNavigation.EXTRA_TITLE) ?: "your stop"}", Toast.LENGTH_LONG).show()
        navigator?.stopGuidance()
    }
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) acceptTermsThenNavigate() else handOff("Location permission is needed to navigate here.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigationView = NavigationView(this)
        navigationView.onCreate(savedInstanceState)
        // Live traffic on the navigation map (the route itself is already coloured by traffic).
        navigationView.getMapAsync { map -> map.isTrafficEnabled = true }
        val density = resources.displayMetrics.density
        val end = Button(this).apply {
            text = "End"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply { cornerRadius = 24 * density; setColor(Color.parseColor("#A6573E")) }
            setOnClickListener { finish() }
        }
        setContentView(FrameLayout(this).apply {
            addView(navigationView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(end, FrameLayout.LayoutParams((96 * density).toInt(), (48 * density).toInt(), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, (16 * density).toInt(), (120 * density).toInt()) })
        })
        if (savedInstanceState == null) {
            if (hasLocationPermission()) acceptTermsThenNavigate()
            else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

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
                route(ready)
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

    private fun route(navigator: Navigator) {
        val latitude = intent.getDoubleExtra(InAppNavigation.EXTRA_LAT, Double.NaN)
        val longitude = intent.getDoubleExtra(InAppNavigation.EXTRA_LNG, Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) { geocodeThenRoute(navigator); return }
        routeTo(navigator, latitude, longitude)
    }

    /** Stops typed as an address have no pin: look the address up on the device first. */
    @Suppress("DEPRECATION") // The listener variant is API 33+; this runs on a background thread.
    private fun geocodeThenRoute(navigator: Navigator) {
        val query = intent.getStringExtra(InAppNavigation.EXTRA_QUERY)
        if (query.isNullOrBlank() || !Geocoder.isPresent()) { handOff("This stop has no map location."); return }
        Thread {
            val found = runCatching { Geocoder(this, Locale.getDefault()).getFromLocationName(query, 1)?.firstOrNull() }.getOrNull()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (found == null) handOff("Couldn't find “$query” on the map.") else routeTo(navigator, found.latitude, found.longitude)
            }
        }.start()
    }

    private fun routeTo(navigator: Navigator, latitude: Double, longitude: Double) {
        val destination = Waypoint.builder().setLatLng(latitude, longitude).setTitle(intent.getStringExtra(InAppNavigation.EXTRA_TITLE) ?: "Destination").build()
        val travelMode = when (runCatching { InAppNavigation.Mode.valueOf(intent.getStringExtra(InAppNavigation.EXTRA_MODE) ?: "") }.getOrNull()) {
            InAppNavigation.Mode.TWO_WHEELER -> RoutingOptions.TravelMode.TWO_WHEELER
            InAppNavigation.Mode.CYCLING -> RoutingOptions.TravelMode.CYCLING
            InAppNavigation.Mode.WALKING -> RoutingOptions.TravelMode.WALKING
            else -> RoutingOptions.TravelMode.DRIVING
        }
        navigator.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)
        navigator.setDestination(destination, RoutingOptions().travelMode(travelMode)).setOnResultListener { status ->
            if (status == Navigator.RouteStatus.OK) {
                navigator.addArrivalListener(arrivalListener)
                navigator.startGuidance()
            } else {
                handOff(if (status == Navigator.RouteStatus.NO_ROUTE_FOUND) "No route found in the app." else "In-app navigation couldn't find a route.")
            }
        }
    }

    /** Says why in-app navigation can't start, then opens the trip in Google Maps (or closes) and leaves this screen. */
    private fun handOff(reason: String) {
        if (handedOff || isFinishing) return
        handedOff = true
        val fallback = intent.getStringExtra(InAppNavigation.EXTRA_FALLBACK)
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
            it.stopGuidance()
            it.clearDestinations()
            it.cleanup()
        }
        navigationView.onDestroy()
        super.onDestroy()
    }
}

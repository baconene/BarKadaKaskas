package com.barkadakaskas.app.nav

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.barkadakaskas.app.BuildConfig

/**
 * Turn-by-turn navigation inside the app (Google Navigation SDK) when it can do the trip; otherwise Google Maps.
 * Needs a destination with coordinates and a mode the SDK routes: driving, two-wheeler, cycling or walking.
 * Public transport, ferries and flights still go to Google Maps.
 */
object InAppNavigation {
    const val EXTRA_LAT = "lat"; const val EXTRA_LNG = "lng"; const val EXTRA_TITLE = "title"; const val EXTRA_MODE = "mode"; const val EXTRA_FALLBACK = "fallback"

    enum class Mode { DRIVING, TWO_WHEELER, CYCLING, WALKING }

    /** The SDK travel mode for one of the app's transport values, or null when only Google Maps can route it. */
    fun mode(transport: String?): Mode? = when (transport) {
        null, "", "car", "van", "taxi", "other" -> Mode.DRIVING
        "motorcycle", "tricycle" -> Mode.TWO_WHEELER
        "bicycle" -> Mode.CYCLING
        "walking" -> Mode.WALKING
        else -> null // transit, ferry, flight
    }

    val enabled: Boolean get() = BuildConfig.IN_APP_NAVIGATION && BuildConfig.MAPS_API_KEY.isNotBlank()

    fun canNavigate(latitude: Double?, longitude: Double?, transport: String?): Boolean = enabled && latitude != null && longitude != null && mode(transport) != null

    /** Navigates to the place in the app when possible, otherwise opens [fallbackUrl] in Google Maps. */
    fun navigate(context: Context, latitude: Double?, longitude: Double?, title: String, transport: String?, fallbackUrl: String) {
        if (!canNavigate(latitude, longitude, transport)) { openGoogleMaps(context, fallbackUrl); return }
        context.startActivity(Intent(context, NavigationActivity::class.java)
            .putExtra(EXTRA_LAT, latitude!!).putExtra(EXTRA_LNG, longitude!!).putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_MODE, mode(transport)!!.name).putExtra(EXTRA_FALLBACK, fallbackUrl)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Google Maps (or a browser) for [url]. */
    fun openGoogleMaps(context: Context, url: String) {
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(maps) } catch (_: ActivityNotFoundException) {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { }
        }
    }
}

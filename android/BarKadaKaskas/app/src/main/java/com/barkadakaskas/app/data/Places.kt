package com.barkadakaskas.app.data

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a typed address into map coordinates with the platform geocoder (no API key).
 * Stops picked from place search on the web already carry coordinates; this covers typed ones.
 */
object Places {
    private val cache = ConcurrentHashMap<String, Pair<Double, Double>>()
    private val misses = ConcurrentHashMap.newKeySet<String>()

    /** Appends the trip destination when the address does not mention it, to avoid matches in other countries. */
    fun query(location: String, destination: String): String {
        val place = location.trim()
        val area = destination.trim()
        return if (area.isBlank() || place.contains(area.substringBefore(',').trim(), ignoreCase = true)) place else "$place, $area"
    }

    @Suppress("DEPRECATION") // The listener variant is API 33+; this runs on Dispatchers.IO.
    suspend fun lookup(context: Context, query: String): Pair<Double, Double>? {
        val key = query.trim().lowercase(Locale.ROOT)
        if (key.isBlank() || key in misses) return null
        cache[key]?.let { return it }
        if (!Geocoder.isPresent()) return null
        return withContext(Dispatchers.IO) {
            val found = runCatching { Geocoder(context, Locale.getDefault()).getFromLocationName(query, 1)?.firstOrNull() }.getOrNull()
            if (found == null) { misses += key; null } else (found.latitude to found.longitude).also { cache[key] = it }
        }
    }
}

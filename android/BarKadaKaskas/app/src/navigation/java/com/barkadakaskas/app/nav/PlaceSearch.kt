package com.barkadakaskas.app.nav

import com.barkadakaskas.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds places by name or kind ("gas station", "Jollibee", an address) with Google's Places API (New) Text Search,
 * using the app's Maps key, biased to [near] (the traveller's position) when known. Runs on the calling thread.
 */
object PlaceSearch {
    data class Place(val name: String, val address: String?, val latitude: Double, val longitude: Double)

    fun search(query: String, near: Pair<Double, Double>?, packageName: String): List<Place> {
        val body = JSONObject().put("textQuery", query.trim()).put("maxResultCount", 8)
        near?.let { (lat, lng) -> body.put("locationBias", JSONObject().put("circle", JSONObject().put("center", JSONObject().put("latitude", lat).put("longitude", lng)).put("radius", 20000.0))) }
        val connection = URL("https://places.googleapis.com/v1/places:searchText").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("X-Goog-Api-Key", BuildConfig.MAPS_API_KEY)
            connection.setRequestProperty("X-Goog-FieldMask", "places.displayName,places.formattedAddress,places.location")
            // Lets a key restricted to this Android app be accepted.
            connection.setRequestProperty("X-Android-Package", packageName)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException(runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull() ?: "Search failed (HTTP $status).")
            val places = JSONObject(text).optJSONArray("places") ?: JSONArray()
            return (0 until places.length()).mapNotNull { i ->
                val p = places.getJSONObject(i)
                val location = p.optJSONObject("location") ?: return@mapNotNull null
                Place(p.optJSONObject("displayName")?.optString("text").orEmpty().ifBlank { "Place" }, p.optString("formattedAddress").ifBlank { null }, location.getDouble("latitude"), location.getDouble("longitude"))
            }
        } finally { connection.disconnect() }
    }
}

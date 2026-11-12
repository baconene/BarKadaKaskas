package com.barkadakaskas.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Prepares a picked photo for upload: honours camera rotation, limits the longest side and re-encodes as JPEG. */
/** Where and when a photo was taken, from its EXIF data (local time "yyyy-MM-dd HH:mm:ss"). */
data class PhotoPlace(val latitude: Double?, val longitude: Double?, val takenAt: String?)

object Photos {
    /** Reads the photo's own GPS position and capture time before it is resized (resizing drops EXIF). */
    suspend fun place(context: Context, uri: Uri): PhotoPlace = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = android.media.ExifInterface(input)
                // The framework ExifInterface fills a float pair (androidx's has a latLong property instead).
                val pair = FloatArray(2)
                val latLong = if (exif.getLatLong(pair) && !(pair[0] == 0f && pair[1] == 0f)) doubleArrayOf(pair[0].toDouble(), pair[1].toDouble()) else null
                val raw = exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(android.media.ExifInterface.TAG_DATETIME)
                val taken = raw?.trim()?.takeIf { Regex("^\\d{4}:\\d\\d:\\d\\d \\d\\d:\\d\\d:\\d\\d$").matches(it) }?.let { it.substring(0, 4) + "-" + it.substring(5, 7) + "-" + it.substring(8) }
                PhotoPlace(latLong?.get(0), latLong?.get(1), taken)
            }
        }.getOrNull() ?: PhotoPlace(null, null, null)
    }

    /** The phone's most recent location fix (GPS or network) from the last 15 minutes, if location is allowed. */
    fun lastLocation(context: Context): Pair<Double, Double>? = runCatching {
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(android.location.LocationManager::class.java)
        manager.getProviders(true).mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 15 * 60_000 }
            .minByOrNull { it.accuracy }?.let { it.latitude to it.longitude }
    }.getOrNull()

    suspend fun jpeg(context: Context, uri: Uri, maxSide: Int = 1600): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // ImageDecoder applies EXIF orientation and can downsample while decoding.
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    val longest = max(info.size.width, info.size.height)
                    if (longest > maxSide) {
                        val scale = maxSide.toFloat() / longest
                        decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@runCatching null
                val longest = max(decoded.width, decoded.height)
                if (longest <= maxSide) decoded else Bitmap.createScaledBitmap(decoded, decoded.width * maxSide / longest, decoded.height * maxSide / longest, true)
            }
            ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
        }.getOrNull()
    }
}

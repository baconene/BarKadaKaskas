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
/** A video to post: the file as it is, a JPEG still from it, and its length. */
class VideoUpload(val bytes: ByteArray, val mimeType: String, val extension: String, val poster: ByteArray, val durationMs: Long)

/** Where and when a photo was taken, from its EXIF data (local time "yyyy-MM-dd HH:mm:ss"). */
data class PhotoPlace(val latitude: Double?, val longitude: Double?, val takenAt: String?)

object Photos {
    const val MAX_VIDEO_BYTES = 50L * 1024 * 1024

    fun isVideo(context: Context, uri: Uri): Boolean = context.contentResolver.getType(uri)?.startsWith("video/") == true || uri.toString().endsWith(".mp4")

    /** Reads a picked or recorded video (up to 50 MB) with a still from about its first second; null if it can't be read or is too big. */
    suspend fun video(context: Context, uri: Uri): VideoUpload? = withContext(Dispatchers.IO) {
        runCatching {
            val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (size > MAX_VIDEO_BYTES) return@withContext null
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val frame = retriever.getFrameAtTime(minOf(1_000_000L, duration * 500L), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@withContext null
                val scale = minOf(1f, 1280f / max(frame.width, frame.height))
                val still = if (scale < 1f) Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt(), (frame.height * scale).roundToInt(), true) else frame
                val poster = ByteArrayOutputStream().also { still.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
                if (bytes.size > MAX_VIDEO_BYTES) return@withContext null
                val mime = context.contentResolver.getType(uri)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
                val extension = when (mime) { "video/quicktime" -> "mov"; "video/webm" -> "webm"; "video/3gpp" -> "3gp"; else -> "mp4" }
                VideoUpload(bytes, mime, extension, poster, duration)
            } finally { runCatching { retriever.release() } }
        }.getOrNull()
    }

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

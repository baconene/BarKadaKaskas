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
object Photos {
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

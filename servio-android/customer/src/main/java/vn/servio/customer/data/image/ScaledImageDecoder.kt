package vn.servio.customer.data.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes a picked or captured image upright and at most [maxEdge] px on the long edge.
 * Shared by the upload (FileRepository) and the CS-08 thumbnails. Blocking: call it off the main thread.
 */
object ScaledImageDecoder {

    fun decode(resolver: ContentResolver, uri: Uri, maxEdge: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF rotation itself.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val (width, height) = scaledSize(info.size.width, info.size.height, maxEdge)
                decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        // API 26–27: sample down on decode, scale to the exact size, then rotate as the EXIF orientation says.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Cannot read image bounds")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val sampled = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Cannot decode image")
        val (width, height) = scaledSize(sampled.width, sampled.height, maxEdge)
        val matrix = orientationMatrix(resolver, uri)
        if (width == sampled.width && height == sampled.height && matrix.isIdentity) return sampled
        matrix.preScale(width.toFloat() / sampled.width, height.toFloat() / sampled.height)
        return Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true)
            .also { if (it !== sampled) sampled.recycle() }
    }

    private fun scaledSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val longEdge = max(width, height)
        if (longEdge <= maxEdge) return width to height
        val ratio = maxEdge.toFloat() / longEdge
        return (width * ratio).roundToInt().coerceAtLeast(1) to (height * ratio).roundToInt().coerceAtLeast(1)
    }

    /** Rotation/flip from the EXIF orientation tag; identity when there is none or it cannot be read. */
    private fun orientationMatrix(resolver: ContentResolver, uri: Uri): Matrix {
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        return Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
    }
}

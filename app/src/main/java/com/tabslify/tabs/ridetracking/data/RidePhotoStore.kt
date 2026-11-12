package com.tabslify.tabs.ridetracking.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

object RidePhotoStore {

    private const val NEAREST_POINT_TOLERANCE_MS = 10 * 60_000L

    fun photosDir(context: Context, rideId: String): File =
        File(context.filesDir, "rides/$rideId/photos").apply { mkdirs() }

    fun newPhotoFile(context: Context, rideId: String): File =
        File(photosDir(context, rideId), "photo_${System.currentTimeMillis()}.jpg")

    fun resolve(context: Context, photo: RidePhoto): File =
        File(photosDir(context, photo.rideId), photo.path)

    suspend fun importUri(context: Context, uri: Uri, target: File): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                target.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
        }

    fun optimize(file: File, maxDimension: Int = 2048, quality: Int = 85) {
        if (!file.exists() || file.length() <= 0L) return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return
        val scaled = if (decoded.width > maxDimension || decoded.height > maxDimension) {
            val ratio = min(
                maxDimension.toFloat() / decoded.width,
                maxDimension.toFloat() / decoded.height
            )
            val width = (decoded.width * ratio).toInt().coerceAtLeast(1)
            val height = (decoded.height * ratio).toInt().coerceAtLeast(1)
            decoded.scale(width, height).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            FileOutputStream(temp).use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
            }
        }
        scaled.recycle()
        if (temp.length() <= 0L) {
            temp.delete()
            return
        }
        val backup = File(file.parentFile, "${file.name}.orig")
        if (!file.renameTo(backup)) {
            temp.delete()
            return
        }
        if (temp.renameTo(file)) {
            backup.delete()
        } else {
            backup.renameTo(file)
            temp.delete()
        }
    }

    fun captureTimestamp(file: File, fallback: Long = System.currentTimeMillis()): Long {
        val raw = runCatching {
            ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        }.getOrNull()
        if (raw.isNullOrBlank()) return fallback
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time ?: fallback
        }.getOrDefault(fallback)
    }

    fun dimensions(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth to options.outHeight
    }

    fun nearestPoint(points: List<RidePoint>, timestamp: Long): Pair<Int, RidePoint>? {
        if (points.isEmpty()) return null
        var bestIndex = -1
        var bestDelta = Long.MAX_VALUE
        points.forEachIndexed { index, point ->
            val delta = abs(point.timestamp - timestamp)
            if (delta < bestDelta) {
                bestDelta = delta
                bestIndex = index
            }
        }
        if (bestIndex < 0 || bestDelta > NEAREST_POINT_TOLERANCE_MS) return null
        return bestIndex to points[bestIndex]
    }

    fun photoDateLabel(timestamp: Long): String =
        SimpleDateFormat("dd. MMM · HH:mm", Locale.getDefault()).format(Date(timestamp))
}

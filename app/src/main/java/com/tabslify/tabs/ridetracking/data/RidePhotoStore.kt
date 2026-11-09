package com.tabslify.tabs.ridetracking.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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

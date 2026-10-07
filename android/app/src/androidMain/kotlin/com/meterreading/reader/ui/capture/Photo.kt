package com.meterreading.reader.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Loads a captured photo for display, scaled down and turned the right way up. */
@Composable
fun rememberPhoto(file: File?, maxSize: Int = 1200): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file) {
        value = file?.let { withContext(Dispatchers.IO) { decodeUpright(it, maxSize)?.asImageBitmap() } }
    }
    return bitmap
}

/**
 * FR-008.5: makes the photo ready to upload, in place. Turned upright (so no EXIF rotation is
 * needed), long edge at most [maxEdge] px, JPEG at the highest quality that fits [maxBytes].
 * Call off the main thread.
 */
fun shrinkForUpload(file: File, maxEdge: Int = 1600, maxBytes: Int = 500_000) {
    val upright = decodeUpright(file, maxEdge) ?: return
    val edge = max(upright.width, upright.height)
    val scaled = if (edge <= maxEdge) {
        upright
    } else {
        val f = maxEdge.toFloat() / edge
        Bitmap.createScaledBitmap(upright, (upright.width * f).roundToInt(), (upright.height * f).roundToInt(), true)
    }
    var quality = 85
    var bytes: ByteArray
    do {
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bytes = out.toByteArray()
        quality -= 10
    } while (bytes.size > maxBytes && quality >= 45)
    val temp = File(file.path + ".tmp")
    temp.writeBytes(bytes)
    if (!temp.renameTo(file)) {
        file.writeBytes(bytes)
        temp.delete()
    }
}

private fun decodeUpright(file: File, maxSize: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val degrees = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return bitmap
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/**
 * FR-033.2: writes [lines] (property, unit, time, place, inspector) on a dark band at the bottom of the
 * photo, so the evidence carries them even when looked at outside the system. Call off the main thread,
 * after [shrinkForUpload].
 */
fun stampPhoto(file: File, lines: List<String>) {
    val source = BitmapFactory.decodeFile(file.path) ?: return
    val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(bitmap)
    val textSize = (bitmap.width / 34f).coerceAtLeast(14f)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        this.textSize = textSize
        typeface = android.graphics.Typeface.MONOSPACE
    }
    val lineHeight = textSize * 1.3f
    val band = lineHeight * lines.size + textSize * 0.8f
    val shade = android.graphics.Paint().apply { color = android.graphics.Color.argb(150, 0, 0, 0) }
    canvas.drawRect(0f, bitmap.height - band, bitmap.width.toFloat(), bitmap.height.toFloat(), shade)
    lines.forEachIndexed { i, line ->
        canvas.drawText(line, textSize * 0.6f, bitmap.height - band + lineHeight * (i + 1), paint)
    }
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
    val temp = File(file.path + ".tmp")
    temp.writeBytes(out.toByteArray())
    if (!temp.renameTo(file)) {
        file.writeBytes(out.toByteArray())
        temp.delete()
    }
}

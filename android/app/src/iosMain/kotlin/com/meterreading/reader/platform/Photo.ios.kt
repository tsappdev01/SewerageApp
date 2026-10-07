package com.meterreading.reader.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.max
import kotlin.math.roundToInt

// Photos are made upright when taken (Camera.ios.kt draws them with UIKit), so everything here can use
// Skia, the drawing library Compose already brings to iOS.

actual suspend fun loadPhoto(file: File, maxSize: Int): ImageBitmap? {
    val image = runCatching { Image.makeFromEncoded(file.readBytes()) }.getOrNull() ?: return null
    return scaled(image, maxSize).toComposeImageBitmap()
}

actual fun shrinkForUpload(file: File, maxEdge: Int, maxBytes: Int) {
    val image = runCatching { Image.makeFromEncoded(file.readBytes()) }.getOrNull() ?: return
    val small = scaled(image, maxEdge)
    var quality = 85
    var bytes: ByteArray
    do {
        bytes = small.encodeToData(EncodedImageFormat.JPEG, quality)?.bytes ?: return
        quality -= 10
    } while (bytes.size > maxBytes && quality >= 45)
    replace(file, bytes)
}

actual fun stampPhoto(file: File, lines: List<String>) {
    val image = runCatching { Image.makeFromEncoded(file.readBytes()) }.getOrNull() ?: return
    val surface = Surface.makeRasterN32Premul(image.width, image.height)
    val canvas = surface.canvas
    canvas.drawImage(image, 0f, 0f)
    val textSize = (image.width / 34f).coerceAtLeast(14f)
    val typeface = FontMgr.default.matchFamilyStyle("Menlo", FontStyle.NORMAL) ?: FontMgr.default.matchFamilyStyle(null, FontStyle.NORMAL)
    val font = Font(typeface, textSize)
    val lineHeight = textSize * 1.3f
    val band = lineHeight * lines.size + textSize * 0.8f
    canvas.drawRect(Rect.makeLTRB(0f, image.height - band, image.width.toFloat(), image.height.toFloat()), Paint().apply { color = Color.makeARGB(150, 0, 0, 0) })
    val text = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    lines.forEachIndexed { i, line ->
        canvas.drawString(line, textSize * 0.6f, image.height - band + lineHeight * (i + 1), font, text)
    }
    val bytes = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 82)?.bytes ?: return
    replace(file, bytes)
}

actual fun encodeJpeg(image: ImageBitmap, quality: Int): ByteArray =
    Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.JPEG, quality)?.bytes ?: ByteArray(0)

/** [image] with its long edge at most [maxEdge] pixels. */
private fun scaled(image: Image, maxEdge: Int): Image {
    val edge = max(image.width, image.height)
    if (edge <= maxEdge) return image
    val f = maxEdge.toFloat() / edge
    val w = (image.width * f).roundToInt().coerceAtLeast(1)
    val h = (image.height * f).roundToInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawImageRect(image, Rect.makeWH(w.toFloat(), h.toFloat()))
    return surface.makeImageSnapshot()
}

private fun replace(file: File, bytes: ByteArray) {
    val temp = File(file.path + ".tmp")
    temp.writeBytes(bytes)
    if (!temp.renameTo(file)) {
        file.writeBytes(bytes)
        temp.delete()
    }
}

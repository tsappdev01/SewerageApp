package com.meterreading.reader.ui.capture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import com.meterreading.reader.platform.File
import com.meterreading.reader.platform.ioDispatcher
import com.meterreading.reader.platform.loadPhoto
import kotlinx.coroutines.withContext

/** Loads a captured photo for display, scaled down and turned the right way up. */
@Composable
fun rememberPhoto(file: File?, maxSize: Int = 1200): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file) {
        value = file?.let { withContext(ioDispatcher) { runCatching { loadPhoto(it, maxSize) }.getOrNull() } }
    }
    return bitmap
}

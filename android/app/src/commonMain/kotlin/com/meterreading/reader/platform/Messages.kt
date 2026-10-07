package com.meterreading.reader.platform

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Short messages at the bottom of the screen (like Android's toast), shown by the app's root. */
object Messages {
    private val _shown = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val shown: SharedFlow<String> = _shown.asSharedFlow()

    fun show(text: String) {
        _shown.tryEmit(text)
    }
}

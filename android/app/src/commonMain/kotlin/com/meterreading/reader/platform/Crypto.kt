package com.meterreading.reader.platform

import okio.ByteString.Companion.toByteString

/** SHA-256 of [bytes] in upper-case hex, as the server compares photo uploads (X-Content-SHA256). */
fun sha256Hex(bytes: ByteArray): String = bytes.toByteString().sha256().hex().uppercase()

/** Compares in constant time, so the time taken does not tell how much matched. */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

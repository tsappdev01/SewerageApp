package com.meterreading.reader.data

/** The shared code's file type for a JVM file made in a test (temp directories and the like). */
fun java.io.File.common(): com.meterreading.reader.platform.File = com.meterreading.reader.platform.File(path)

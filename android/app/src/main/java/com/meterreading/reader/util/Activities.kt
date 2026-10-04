package com.meterreading.reader.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** The activity behind a Compose context, for APIs that need one (camera, company sign-in). */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

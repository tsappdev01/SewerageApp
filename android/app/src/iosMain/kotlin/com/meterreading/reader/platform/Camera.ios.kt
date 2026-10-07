@file:OptIn(ExperimentalForeignApi::class)

package com.meterreading.reader.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meterreading.reader.resources.*
import com.meterreading.reader.settings.AppServices
import com.meterreading.reader.ui.components.BigButton
import com.meterreading.reader.ui.components.SpeakButton
import com.meterreading.reader.ui.theme.AppColors
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.darwin.NSObject

/**
 * FR-008.1/.2 on iOS: the system camera (no gallery). The hint and, for meter numbers, the white box
 * are shown first; "Take photo" opens the camera. The photo is drawn upright, saved as JPEG in the app's
 * private storage and shrunk for upload. The simulator has no camera: it says so.
 */
@Composable
actual fun CameraCapture(hint: String, speakText: String, showFrame: Boolean, onCaptured: (File) -> Unit) {
    val scope = rememberCoroutineScope()
    val captured = rememberUpdatedState(onCaptured)
    var failed by remember { mutableStateOf(false) }
    val available = remember { UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera) }
    val picker = remember {
        CameraPicker { image ->
            scope.launch {
                val file = withContext(ioDispatcher) { save(image) }
                if (file == null) failed = true else captured.value(file)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(AppColors.CameraBackground).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { SpeakButton(speakText) }
        if (showFrame) {
            Box(
                Modifier.fillMaxWidth(0.85f).aspectRatio(3f).border(4.dp, Color.White, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("0 0 0 0 0", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.headlineMedium) }
        } else {
            Icon(Icons.Rounded.PhotoCamera, null, tint = Color.White, modifier = Modifier.size(96.dp))
        }
        Text(
            if (failed) stringResource(Res.string.camera_error) else if (!available) "This device has no camera." else hint,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        BigButton(stringResource(Res.string.take_photo), { failed = false; picker.open() }, icon = Icons.Rounded.PhotoCamera, enabled = available)
    }
}

/** Saves the photo upright (pixels turned, not an orientation flag) and shrinks it for upload. */
private fun save(image: UIImage): File? {
    val size = image.size
    val upright = UIGraphicsImageRenderer(size = size).imageWithActions { _ ->
        size.useContents { image.drawInRect(CGRectMake(0.0, 0.0, width, height)) }
    }
    val data = UIImageJPEGRepresentation(upright, 0.9) ?: return null
    val file = File(AppServices.capturesDir, "${randomUuid()}.jpg")
    file.parentFile?.mkdirs()
    file.writeBytes(data.toByteArray())
    runCatching { shrinkForUpload(file) }
    return file
}

private class CameraPicker(private val onImage: (UIImage) -> Unit) {
    private val delegate = object : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
        override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
            val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
            picker.dismissViewControllerAnimated(true, null)
            if (image != null) onImage(image)
        }

        override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
            picker.dismissViewControllerAnimated(true, null)
        }
    }

    fun open() {
        val sourceCamera = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
        if (!UIImagePickerController.isSourceTypeAvailable(sourceCamera)) return
        val controller = UIImagePickerController()
        controller.sourceType = sourceCamera
        controller.delegate = delegate
        topViewController()?.presentViewController(controller, animated = true, completion = null)
    }
}

/** The screen on top, to show system screens (camera, settings) over the app. */
internal fun topViewController(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

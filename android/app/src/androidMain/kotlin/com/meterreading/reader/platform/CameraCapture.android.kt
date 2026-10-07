package com.meterreading.reader.platform

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meterreading.reader.resources.*
import com.meterreading.reader.settings.AppServices
import com.meterreading.reader.ui.components.BigButton
import com.meterreading.reader.ui.components.CircleIconButton
import com.meterreading.reader.ui.components.SpeakButton
import com.meterreading.reader.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen in-app camera (FR-008.1/.2: no gallery). [showFrame] draws the white box the
 * reader puts the meter's numbers in. The photo is written to app-private storage.
 * TODO(FR-008.4/.6): blur and exposure check, encrypted file. Resizing is done (shrinkForUpload).
 */
@Composable
actual fun CameraCapture(hint: String, speakText: String, showFrame: Boolean, onCaptured: (File) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.PhotoCamera, null, tint = AppColors.Navy, modifier = Modifier.size(96.dp))
            Text(stringResource(Res.string.camera_permission), style = MaterialTheme.typography.titleLarge)
            BigButton(stringResource(Res.string.allow_camera), { permission.launch(Manifest.permission.CAMERA) }, icon = Icons.Rounded.PhotoCamera)
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    LaunchedEffect(flashMode) { imageCapture.flashMode = flashMode }

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.CameraBackground),
    ) {
        Box(Modifier.weight(1f)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            if (showFrame) {
                Canvas(Modifier.fillMaxSize()) {
                    val frameWidth = size.width * 0.8f
                    val frameHeight = frameWidth * 0.32f
                    val left = (size.width - frameWidth) / 2
                    val top = (size.height - frameHeight) / 2
                    val radius = CornerRadius(16.dp.toPx())
                    val cutout = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(Offset.Zero, size))
                        addRoundRect(RoundRect(left, top, left + frameWidth, top + frameHeight, radius))
                    }
                    drawPath(cutout, Color.Black.copy(alpha = 0.5f))
                    drawRoundRect(Color.White, Offset(left, top), Size(frameWidth, frameHeight), radius, style = Stroke(4.dp.toPx()))
                }
            }
            Row(
                Modifier
                    .padding(12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (failed) stringResource(Res.string.camera_error) else hint,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                SpeakButton(speakText)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(
                icon = when (flashMode) {
                    ImageCapture.FLASH_MODE_ON -> Icons.Rounded.FlashOn
                    ImageCapture.FLASH_MODE_OFF -> Icons.Rounded.FlashOff
                    else -> Icons.Rounded.FlashAuto
                },
                description = stringResource(Res.string.flash),
                onClick = {
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                        ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                        else -> ImageCapture.FLASH_MODE_AUTO
                    }
                },
                container = Color(0xFF2A2D31),
                content = Color.White,
                bordered = false,
                size = 56.dp,
            )
            Surface(
                onClick = {
                    if (busy) return@Surface
                    busy = true
                    failed = false
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val file = java.io.File(AppServices.capturesDir.path, "${randomUuid()}.jpg").apply { parentFile?.mkdirs() }
                    imageCapture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(file).build(),
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                scope.launch {
                                    // FR-008.5: shrink before use, so uploads are small and quick.
                                    withContext(Dispatchers.IO) { runCatching { shrinkForUpload(File(file.path)) } }
                                    busy = false
                                    onCaptured(File(file.path))
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                busy = false
                                failed = true
                            }
                        },
                    )
                },
                shape = CircleShape,
                color = if (busy) Color.Gray else Color.White,
                modifier = Modifier
                    .size(88.dp)
                    .border(5.dp, AppColors.CameraBackground, CircleShape)
                    .border(9.dp, Color.White, CircleShape),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PhotoCamera, stringResource(Res.string.take_photo), tint = AppColors.Navy, modifier = Modifier.size(34.dp))
                }
            }
            Spacer(Modifier.size(56.dp))
        }
    }
}

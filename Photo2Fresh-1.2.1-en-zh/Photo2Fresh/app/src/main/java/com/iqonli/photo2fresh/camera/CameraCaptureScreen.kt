package com.iqonli.photo2fresh.camera

import com.iqonli.photo2fresh.i18n.AppLanguage
import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

@Composable
fun CameraCaptureScreen(
    onCaptured: (Uri) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner, previewView) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                    cameraProvider = provider
                    imageCapture = capture
                    error = null
                } catch (exception: Exception) {
                    error = exception.message ?: "无法启动相机"
                }
            },
            mainExecutor,
        )
        onDispose {
            imageCapture = null
            cameraProvider?.unbindAll()
            cameraProvider = null
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        IconButton(
            onClick = onClose,
            enabled = !capturing,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
                .size(48.dp)
                .background(Color.Black.copy(alpha = 0.42f), CircleShape),
        ) {
            Canvas(modifier = Modifier.size(25.dp)) {
                val strokeWidth = 2.5.dp.toPx()
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.78f, size.height * 0.18f),
                    end = Offset(size.width * 0.30f, size.height * 0.50f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.30f, size.height * 0.50f),
                    end = Offset(size.width * 0.78f, size.height * 0.82f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }

        error?.let { message ->
            Text(
                text = AppLanguage.text(message),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 18.dp, start = 72.dp, end = 18.dp)
                    .background(Color.Black.copy(alpha = 0.58f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp)
                .size(82.dp)
                .background(Color.White.copy(alpha = 0.96f), CircleShape)
                .clickable(enabled = !capturing && imageCapture != null) {
                    val capture = imageCapture
                    if (capture == null) {
                        error = "相机尚未就绪"
                    } else {
                        capturing = true
                        error = null
                        capturePhoto(
                            context = context,
                            imageCapture = capture,
                            onSuccess = {
                                capturing = false
                                onCaptured(it)
                            },
                            onFailure = {
                                capturing = false
                                error = it
                            },
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (capturing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                )
            } else {
                Canvas(modifier = Modifier.size(66.dp)) {
                    drawCircle(
                        color = primaryColor,
                        radius = size.minDimension / 2f - 2.dp.toPx(),
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.55f),
                        radius = size.minDimension / 2f - 3.dp.toPx(),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
        }
    }
}

private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onSuccess: (Uri) -> Unit,
    onFailure: (String) -> Unit,
) {
    val directory = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(directory, "capture_${System.currentTimeMillis()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(file).build()
    imageCapture.takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                onSuccess(uri)
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
                onFailure(exception.message ?: "拍摄失败")
            }
        },
    )
}

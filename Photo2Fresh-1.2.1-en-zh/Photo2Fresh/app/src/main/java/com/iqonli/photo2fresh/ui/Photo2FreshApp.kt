package com.iqonli.photo2fresh.ui

import com.iqonli.photo2fresh.i18n.AppLanguage
import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.RadioButton
import com.iqonli.photo2fresh.i18n.UiLanguage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iqonli.photo2fresh.camera.CameraCaptureScreen
import com.iqonli.photo2fresh.regression.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.FilterChip
import com.iqonli.photo2fresh.config.RuntimeParameters
import com.iqonli.photo2fresh.image.PreparedImage
import com.iqonli.photo2fresh.inference.ModelStatus
import com.iqonli.photo2fresh.pipeline.DetectionViewModel
import com.iqonli.photo2fresh.pipeline.PipelineState
import com.iqonli.photo2fresh.pipeline.ProcessingStage
import com.iqonli.photo2fresh.pipeline.StageOutputImage
import com.iqonli.photo2fresh.pipeline.allDetectionPhases
import com.iqonli.photo2fresh.pipeline.originalImageUriOrNull
import com.iqonli.photo2fresh.ui.theme.Photo2freshTheme
import kotlin.math.abs
import com.iqonli.photo2fresh.util.decodePreviewBitmap
import com.iqonli.photo2fresh.util.saveImageToGallery
import com.iqonli.photo2fresh.util.shareImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

private enum class InterfaceMode { USER, TEST }
private enum class UserDestination { HOME, PROCESSING, RESULT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Photo2FreshApp(
    detectionViewModel: DetectionViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by detectionViewModel.state.collectAsState()
    val runtimeParameters by detectionViewModel.runtimeParameters.collectAsState()
    val selectedMeat by detectionViewModel.selectedMeat.collectAsState()
    val selectedTemperature by detectionViewModel.selectedTemperature.collectAsState()
    val conditionSelected = selectedMeat != null && selectedTemperature != null
    val conditionControls: @Composable () -> Unit = {
        ConditionSelectionCard(selectedMeat, selectedTemperature, state !is PipelineState.Processing,
            detectionViewModel::selectMeat, detectionViewModel::selectTemperature)
    }
    val cropEditState by detectionViewModel.cropEditState.collectAsState()
    var interfaceMode by rememberSaveable { mutableStateOf(InterfaceMode.USER) }
    var userDestination by rememberSaveable { mutableStateOf(UserDestination.HOME) }
    var showCamera by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showCropEditor by rememberSaveable { mutableStateOf(false) }
    var fullScreenImage by remember { mutableStateOf<StageOutputImage?>(null) }
    var imageActions by remember { mutableStateOf<StageOutputImage?>(null) }
    var showModePasswordDialog by rememberSaveable { mutableStateOf(false) }
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraPermissionMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            detectionViewModel.selectImage(uri)
            userDestination = UserDestination.HOME
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted = granted
        if (granted) {
            cameraPermissionMessage = null
            showCamera = true
        } else {
            cameraPermissionMessage = "未获得相机权限；仍可从相册选择照片。"
        }
    }

    fun openCamera() {
        if (cameraPermissionGranted) {
            showCamera = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun pickPhoto() {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun startUserDetection() {
        userDestination = UserDestination.PROCESSING
        detectionViewModel.startDetectionPreparation()
    }

    fun enterTestMode() {
        interfaceMode = InterfaceMode.TEST
    }

    fun returnToUserMode() {
        interfaceMode = InterfaceMode.USER
        userDestination = when (state) {
            is PipelineState.Success -> UserDestination.RESULT
            is PipelineState.Processing -> UserDestination.PROCESSING
            else -> UserDestination.HOME
        }
    }

    LaunchedEffect(state, interfaceMode) {
        if (interfaceMode == InterfaceMode.USER && userDestination == UserDestination.PROCESSING) {
            when (state) {
                is PipelineState.Success,
                is PipelineState.Failure,
                is PipelineState.WaitingForModels -> userDestination = UserDestination.RESULT
                else -> Unit
            }
        }
    }

    if (showCamera && cameraPermissionGranted) {
        BackHandler { showCamera = false }
        CameraCaptureScreen(
            onCaptured = { uri ->
                showCamera = false
                detectionViewModel.selectImage(uri)
                userDestination = UserDestination.HOME
            },
            onClose = { showCamera = false },
        )
        return
    }

    if (showCropEditor) {
        val crop = cropEditState
        if (crop != null) {
            CropEditorScreen(
                state = crop,
                onChange = detectionViewModel::updateCropEditState,
                onReset = detectionViewModel::resetCropEditState,
                onApply = {
                    detectionViewModel.applyCrop { success ->
                        if (success) {
                            showCropEditor = false
                            userDestination = UserDestination.HOME
                        }
                    }
                },
                onCancel = { showCropEditor = false },
            )
            return
        }
    }

    if (showAbout) {
        AboutScreen(onBack = { showAbout = false })
        return
    }

    fullScreenImage?.let { image ->
        FullScreenImageDialog(
            image = image,
            onClose = { fullScreenImage = null },
            onLongPress = { imageActions = image },
        )
    }
    imageActions?.let { image ->
        ImageActionsDialog(image = image, onDismiss = { imageActions = null })
    }
    if (showModePasswordDialog) {
        ModePasswordDialog(
            onDismiss = { showModePasswordDialog = false },
            onAuthenticated = {
                showModePasswordDialog = false
                enterTestMode()
            },
        )
    }
    val poseFallbackPrompt = state as? PipelineState.PoseFallbackPrompt
    if (interfaceMode == InterfaceMode.TEST && poseFallbackPrompt != null) {
        PoseFallbackDialog(
            message = poseFallbackPrompt.message,
            onForceContinue = {
                detectionViewModel.startDetectionPreparation(
                    testMode = true,
                    forceWithoutPose = true,
                )
            },
            onCancel = detectionViewModel::declinePoseFallback,
        )
    }

    when (interfaceMode) {
        InterfaceMode.USER -> when (userDestination) {
            UserDestination.HOME -> UserHomeScreen(
                state = state,
                conditionControls = conditionControls,
                conditionSelected = conditionSelected,
                cameraPermissionMessage = cameraPermissionMessage,
                onCamera = ::openCamera,
                onPick = ::pickPhoto,
                onCrop = { if (cropEditState != null) showCropEditor = true },
                onRun = ::startUserDetection,
                onAbout = { showAbout = true },
                onRequestModeSwitch = { showModePasswordDialog = true },
                onDecodeFailure = detectionViewModel::reportFailure,
            )

            UserDestination.PROCESSING -> UserProgressScreen(
                state = state as? PipelineState.Processing,
                onCancel = {
                    detectionViewModel.cancelDetection()
                    userDestination = UserDestination.HOME
                },
            )

            UserDestination.RESULT -> UserResultScreen(
                state = state,
                onBack = { userDestination = UserDestination.HOME },
                onRequestModeSwitch = { showModePasswordDialog = true },
                onDecodeFailure = detectionViewModel::reportFailure,
            )
        }

        InterfaceMode.TEST -> TestInterfaceScreen(
            deploymentCheck = detectionViewModel.deploymentCheck.collectAsStateWithLifecycle().value,
            onDeploymentCheck = detectionViewModel::runDeploymentCheck,
            state = state,
            conditionControls = conditionControls,
            conditionSelected = conditionSelected,
            runtimeParameters = runtimeParameters,
            cameraPermissionMessage = cameraPermissionMessage,
            onCamera = ::openCamera,
            onPick = ::pickPhoto,
            onCrop = { if (cropEditState != null) showCropEditor = true },
            onRun = { detectionViewModel.startDetectionPreparation(testMode = true) },
            onReset = detectionViewModel::reset,
            onParametersChange = detectionViewModel::updateRuntimeParameters,
            onParametersReset = detectionViewModel::resetRuntimeParameters,
            onOpenImage = { fullScreenImage = it },
            onImageActions = { imageActions = it },
            onReturnUserInterface = ::returnToUserMode,
            onAbout = { showAbout = true },
            onDecodeFailure = detectionViewModel::reportFailure,
        )
    }
}

@Composable
private fun ModeSwitchTitle(
    text: String,
    onTenTaps: () -> Unit,
) {
    var tapCount by remember { mutableStateOf(0) }
    var lastTapAt by remember { mutableStateOf(0L) }

    Text(
        text = AppLanguage.text(text),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
            val now = SystemClock.elapsedRealtime()
            tapCount = if (now - lastTapAt > MODE_SWITCH_TAP_RESET_MS) 1 else tapCount + 1
            lastTapAt = now
            if (tapCount >= MODE_SWITCH_TAP_COUNT) {
                tapCount = 0
                onTenTaps()
            }
        },
    )
}

@Composable
private fun ModePasswordDialog(
    onDismiss: () -> Unit,
    onAuthenticated: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text(AppLanguage.text("输入密码")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        invalid = false
                    },
                    singleLine = true,
                    label = { Text(AppLanguage.text("密码")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = invalid,
                )
                if (invalid) {
                    Text(
                        text = AppLanguage.text("密码错误"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (sha256Hex(password.trim()) == MODE_PASSWORD_SHA256) {
                        password = ""
                        invalid = false
                        onAuthenticated()
                    } else {
                        invalid = true
                    }
                },
                enabled = password.isNotBlank(),
            ) {
                Text(AppLanguage.text("确认"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(AppLanguage.text("取消")) }
        },
    )
}

@Composable
private fun PoseFallbackDialog(
    message: String,
    onForceContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text(AppLanguage.text("检测卡定位模型检测失败")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AppLanguage.text("是否强制继续？"))
                Text(
                    AppLanguage.text(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    AppLanguage.text("继续后将把原始方向校正图直接交给传感区域分割模型，不执行检测卡定位透视矫正。"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onForceContinue,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text(AppLanguage.text("是")) }
        },
        dismissButton = {
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text(AppLanguage.text("否")) }
        },
    )
}

private fun sha256Hex(value: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val APP_TITLE = "photo2fresh v1.2.1"

@Composable
private fun ThemeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(AppLanguage.text(text))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        ),
        topBar = {
            TopAppBar(
                title = {
                    SelectionContainer {
                        Text(AppLanguage.text("关于"), fontWeight = FontWeight.SemiBold)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(AppLanguage.text(APP_TITLE), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(AppLanguage.text("作者：12345"))
                    Text(AppLanguage.text("mail：3120143962@qq.com"))
                    Text(AppLanguage.text("使用MIT许可证。"))
                }
            }
            ThemeButton(text = "返回", onClick = onBack, modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserHomeScreen(
    state: PipelineState,
    conditionControls: @Composable () -> Unit,
    conditionSelected: Boolean,
    cameraPermissionMessage: String?,
    onCamera: () -> Unit,
    onPick: () -> Unit,
    onCrop: () -> Unit,
    onRun: () -> Unit,
    onAbout: () -> Unit,
    onRequestModeSwitch: () -> Unit,
    onDecodeFailure: (String) -> Unit,
) {
    val imageUri = state.originalImageUriOrNull()
    val canRun = conditionSelected && imageUri != null && state !is PipelineState.Processing

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        ),
        topBar = {
            TopAppBar(
                title = {
                    ModeSwitchTitle(
                        text = APP_TITLE,
                        onTenTaps = onRequestModeSwitch,
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            conditionControls()
            UserPhotoSurface(
                uriString = imageUri,
                onDecodeFailure = onDecodeFailure,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ThemeButton(
                    text = "拍摄照片",
                    onClick = onCamera,
                    enabled = state !is PipelineState.Processing,
                    modifier = Modifier.weight(1f),
                )
                ThemeButton(
                    text = "从相册选择",
                    onClick = onPick,
                    enabled = state !is PipelineState.Processing,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ThemeButton(
                    text = "裁剪",
                    onClick = onCrop,
                    enabled = imageUri != null && state !is PipelineState.Processing,
                    modifier = Modifier.weight(1f),
                )
                ThemeButton(
                    text = "运行检测",
                    onClick = onRun,
                    enabled = canRun,
                    modifier = Modifier.weight(1f),
                )
            }
            ThemeButton(
                text = "关于",
                onClick = onAbout,
                enabled = state !is PipelineState.Processing,
                modifier = Modifier.fillMaxWidth(),
            )
            cameraPermissionMessage?.let {
                Text(AppLanguage.text(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun UserProgressScreen(
    state: PipelineState.Processing?,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val phaseIndex = state?.phase?.ordinal ?: 0
    val progress by smoothProgress(state)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape),
            )
            Text(
                text = AppLanguage.text("${phaseIndex + 1}/${allDetectionPhases.size}"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun smoothProgress(state: PipelineState.Processing?) = produceState(
    initialValue = 0.2f,
    key1 = state?.phase,
    key2 = state?.phaseStartedElapsedRealtimeMs,
    key3 = state?.estimatedPhaseDurationMs,
) {
    if (state == null) {
        value = 0.2f
        return@produceState
    }
    val phaseCount = allDetectionPhases.size.toFloat()
    val base = state.phase.ordinal / phaseCount
    val estimate = state.estimatedPhaseDurationMs
    if (estimate == null || estimate <= 0L) {
        value = (state.phase.ordinal + 1) / phaseCount
        return@produceState
    }
    while (true) {
        val elapsed = SystemClock.elapsedRealtime() - state.phaseStartedElapsedRealtimeMs
        val phaseFraction = (elapsed.toFloat() / estimate.toFloat()).coerceIn(0f, 0.96f)
        value = (base + phaseFraction / phaseCount).coerceIn(0f, 0.996f)
        delay(50)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserResultScreen(
    state: PipelineState,
    onBack: () -> Unit,
    onRequestModeSwitch: () -> Unit,
    onDecodeFailure: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val success = state as? PipelineState.Success
    val imageUri = state.originalImageUriOrNull()
    val first = success?.prediction

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        ),
        topBar = {
            TopAppBar(
                title = {
                    ModeSwitchTitle(
                        text = APP_TITLE,
                        onTenTaps = onRequestModeSwitch,
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            UserPhotoSurface(
                uriString = imageUri,
                onDecodeFailure = onDecodeFailure,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            when {
                first != null -> {
                    Text(AppLanguage.text(first.condition.label), style = MaterialTheme.typography.titleMedium)
                    TvbnRangeBar(value = first.tvbn)
                    Text(
                        text = AppLanguage.text("%s (%.1fmg/100g)".format(first.freshness, first.tvbn)),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text(AppLanguage.text("已储存时长：${first.timeText()}"), style = MaterialTheme.typography.titleLarge)
                }

                state is PipelineState.WaitingForModels -> Text(
                    text = AppLanguage.text(if (state.stageImages.isNotEmpty()) "图像处理完成，回归模型尚未就绪" else "检测失败：模型文件不可用"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                )

                state is PipelineState.Failure -> Text(
                    text = AppLanguage.text("检测失败：${state.message}"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )

                else -> Unit
            }
            ThemeButton(
                text = "返回",
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun UserPhotoSurface(
    uriString: String?,
    onDecodeFailure: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (uriString == null) {
            BouncyEmptyPhotoPlaceholder()
        } else {
            PreviewBitmap(
                uri = Uri.parse(uriString),
                contentDescription = AppLanguage.text("用户实拍图"),
                onDecodeFailure = onDecodeFailure,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestInterfaceScreen(
    deploymentCheck: String,
    onDeploymentCheck: () -> Unit,
    state: PipelineState,
    conditionControls: @Composable () -> Unit,
    conditionSelected: Boolean,
    runtimeParameters: RuntimeParameters,
    cameraPermissionMessage: String?,
    onCamera: () -> Unit,
    onPick: () -> Unit,
    onCrop: () -> Unit,
    onRun: () -> Unit,
    onReset: () -> Unit,
    onParametersChange: (RuntimeParameters) -> Unit,
    onParametersReset: () -> Unit,
    onOpenImage: (StageOutputImage) -> Unit,
    onImageActions: (StageOutputImage) -> Unit,
    onReturnUserInterface: () -> Unit,
    onAbout: () -> Unit,
    onDecodeFailure: (String) -> Unit,
) {
    var showLanguageDialog by remember { mutableStateOf(false) }
    if (showLanguageDialog) LanguageSelectionDialog(onDismiss = { showLanguageDialog = false })
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        ),
        topBar = {
            TopAppBar(
                title = {
                    Text(AppLanguage.text(APP_TITLE), fontWeight = FontWeight.SemiBold)
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            conditionControls()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ThemeButton(
                    text = "拍摄照片",
                    onClick = onCamera,
                    enabled = state !is PipelineState.Processing,
                    modifier = Modifier.weight(1f),
                )
                ThemeButton(
                    text = "从相册选择",
                    onClick = onPick,
                    enabled = state !is PipelineState.Processing,
                    modifier = Modifier.weight(1f),
                )
            }

            cameraPermissionMessage?.let { Text(AppLanguage.text(it), color = MaterialTheme.colorScheme.error) }

            TestImageArea(
                state = state,
                onOpenImage = onOpenImage,
                onImageActions = onImageActions,
                onDecodeFailure = onDecodeFailure,
            )

            ThemeButton(
                text = "部署自检（六工况）", onClick = onDeploymentCheck,
                enabled = state !is PipelineState.Processing && !deploymentCheck.startsWith("正在"),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(AppLanguage.text(deploymentCheck), style = MaterialTheme.typography.bodySmall)

            RuntimeParametersCard(
                parameters = runtimeParameters,
                enabled = state !is PipelineState.Processing,
                onChange = onParametersChange,
                onReset = onParametersReset,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeButton(
                    text = "裁剪", onClick = onCrop,
                    enabled = state.originalImageUriOrNull() != null && state !is PipelineState.Processing,
                    modifier = Modifier.weight(1f),
                )
                ThemeButton(
                    text = "切换语言", onClick = { showLanguageDialog = true },
                    modifier = Modifier.weight(1f),
                )
            }
            ActionArea(state = state, onStart = onRun, onReset = onReset, conditionSelected = conditionSelected)
            TestProcessingOrResultCard(state)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ThemeButton(
                    text = "返回用户界面",
                    onClick = onReturnUserInterface,
                    enabled = state !is PipelineState.Processing,
                    modifier = Modifier.weight(1f),
                )
                ThemeButton(
                    text = "关于",
                    onClick = onAbout,
                    enabled = state !is PipelineState.Processing,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TestImageArea(
    state: PipelineState,
    onOpenImage: (StageOutputImage) -> Unit,
    onImageActions: (StageOutputImage) -> Unit,
    onDecodeFailure: (String) -> Unit,
) {
    val images = if (state is PipelineState.Success) {
        listOf(StageOutputImage("用户实拍图", state.originalImageUri)) + state.stageImages
    } else if (state is PipelineState.WaitingForModels && state.stageImages.isNotEmpty()) {
        listOf(StageOutputImage("用户实拍图", state.preparedImage.originalUri)) + state.stageImages
    } else {
        state.originalImageUriOrNull()?.let { listOf(StageOutputImage("用户实拍图", it)) }.orEmpty()
    }

    if (images.isEmpty()) {
        ImagePreview(
            uriString = null,
            onOpenImage = onOpenImage,
            onImageActions = onImageActions,
            onDecodeFailure = onDecodeFailure,
        )
    } else {
        StageImagePager(
            images = images,
            onOpenImage = onOpenImage,
            onImageActions = onImageActions,
            onDecodeFailure = onDecodeFailure,
        )
    }
}

@Composable
private fun StageImagePager(
    images: List<StageOutputImage>,
    onOpenImage: (StageOutputImage) -> Unit,
    onImageActions: (StageOutputImage) -> Unit,
    onDecodeFailure: (String) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { images.size })

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val image = images[page]
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .photoGesture(
                            onTap = { onOpenImage(image) },
                            onLongPress = { onImageActions(image) },
                        ),
                ) {
                    PreviewBitmap(
                        uri = Uri.parse(image.imageUri),
                        contentDescription = image.title,
                        onDecodeFailure = onDecodeFailure,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(AppLanguage.text(images[pagerState.currentPage].title), fontWeight = FontWeight.Medium)
            Text(
                AppLanguage.text("${pagerState.currentPage + 1}/${images.size}  左右滑动查看"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            images.indices.forEach { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (index == pagerState.currentPage) 9.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }
    }
}

@Composable
private fun BouncyEmptyPhotoPlaceholder() {
    val context = LocalContext.current
    // force[0/1] are screen-space pseudo-gravity values. Accelerometer data is preferable here:
    // unlike TYPE_GRAVITY it also contains the short impulses needed for game-like shake inertia.
    val force = remember { floatArrayOf(0f, 0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val sensor = accelerometer ?: gravitySensor
        var initialized = false
        var gravityX = 0f
        var gravityY = 0f

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 2) return
                val rawX = event.values[0]
                val rawY = event.values[1]

                if (!initialized) {
                    gravityX = rawX
                    gravityY = rawY
                    initialized = true
                }

                // Estimate the slow gravity/tilt component. The remaining high-frequency component
                // is device shaking; applying the opposite inertial force makes the label oscillate
                // in the same way as a loose object in a simple game physics engine.
                gravityX += (rawX - gravityX) * EMPTY_TEXT_GRAVITY_FILTER
                gravityY += (rawY - gravityY) * EMPTY_TEXT_GRAVITY_FILTER
                val linearX = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) rawX - gravityX else 0f
                val linearY = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) rawY - gravityY else 0f

                val shakeX = if (abs(linearX) >= EMPTY_TEXT_SHAKE_DEAD_ZONE) linearX else 0f
                val shakeY = if (abs(linearY) >= EMPTY_TEXT_SHAKE_DEAD_ZONE) linearY else 0f

                // Android accelerometer/gravity readings point opposite the physical falling
                // direction. X is also reversed directly; device +Y points toward the top of the
                // display, while pixel +Y points downward. This is intentionally the opposite of
                // the old implementation, which made the text appear to float uphill.
                val targetX = -gravityX - shakeX * EMPTY_TEXT_SHAKE_GAIN
                val targetY = gravityY + shakeY * EMPTY_TEXT_SHAKE_GAIN
                force[0] += (targetX - force[0]) * EMPTY_TEXT_FORCE_RESPONSE
                force[1] += (targetY - force[1]) * EMPTY_TEXT_FORCE_RESPONSE
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) {
            // This decorative effect needs only game-rate updates. FASTEST requests 0 us and
            // throws SecurityException on Android 12+ without high-rate sensor permission.
            try {
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            } catch (exception: SecurityException) {
                android.util.Log.w("Photo2Fresh", "Motion animation disabled: sensor access denied", exception)
            }
        }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    LaunchedEffect(Unit) {
        val fixedStep = 1f / EMPTY_TEXT_PHYSICS_HZ
        var lastFrame = 0L
        var accumulator = 0f
        var x = 0f
        var y = 0f
        var velocityX = 0f
        var velocityY = 0f

        while (true) {
            val frameNanos = withFrameNanos { it }
            if (lastFrame == 0L) lastFrame = frameNanos
            val elapsed = ((frameNanos - lastFrame).toDouble() / 1_000_000_000.0)
                .toFloat()
                .coerceIn(0f, 0.05f)
            lastFrame = frameNanos
            accumulator += elapsed

            var steps = 0
            while (accumulator >= fixedStep && steps < 8) {
                velocityX += force[0] * EMPTY_TEXT_ACCELERATION * fixedStep
                velocityY += force[1] * EMPTY_TEXT_ACCELERATION * fixedStep
                velocityX *= EMPTY_TEXT_VELOCITY_DAMPING
                velocityY *= EMPTY_TEXT_VELOCITY_DAMPING
                velocityX = velocityX.coerceIn(-EMPTY_TEXT_MAX_SPEED, EMPTY_TEXT_MAX_SPEED)
                velocityY = velocityY.coerceIn(-EMPTY_TEXT_MAX_SPEED, EMPTY_TEXT_MAX_SPEED)
                x += velocityX * fixedStep
                y += velocityY * fixedStep

                if (x < -EMPTY_TEXT_BOUND_PX) {
                    x = -EMPTY_TEXT_BOUND_PX
                    velocityX = kotlin.math.abs(velocityX) * EMPTY_TEXT_BOUNCE
                } else if (x > EMPTY_TEXT_BOUND_PX) {
                    x = EMPTY_TEXT_BOUND_PX
                    velocityX = -kotlin.math.abs(velocityX) * EMPTY_TEXT_BOUNCE
                }
                if (y < -EMPTY_TEXT_BOUND_PX) {
                    y = -EMPTY_TEXT_BOUND_PX
                    velocityY = kotlin.math.abs(velocityY) * EMPTY_TEXT_BOUNCE
                } else if (y > EMPTY_TEXT_BOUND_PX) {
                    y = EMPTY_TEXT_BOUND_PX
                    velocityY = -kotlin.math.abs(velocityY) * EMPTY_TEXT_BOUNCE
                }
                accumulator -= fixedStep
                steps++
            }
            offsetX = x
            offsetY = y
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            AppLanguage.text("尚未选择图片"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer(
                translationX = offsetX,
                translationY = offsetY,
            ),
        )
    }
}

@Composable
private fun ImagePreview(
    uriString: String?,
    onOpenImage: (StageOutputImage) -> Unit,
    onImageActions: (StageOutputImage) -> Unit,
    onDecodeFailure: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .photoGesture(
                onTap = { uriString?.let { onOpenImage(StageOutputImage("用户实拍图", it)) } },
                onLongPress = { uriString?.let { onImageActions(StageOutputImage("用户实拍图", it)) } },
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (uriString == null) {
            BouncyEmptyPhotoPlaceholder()
        } else {
            PreviewBitmap(Uri.parse(uriString), "检测照片", onDecodeFailure)
        }
    }
}

private sealed interface PreviewLoadState {
    data object Loading : PreviewLoadState
    data class Loaded(val bitmap: Bitmap) : PreviewLoadState
    data class Failed(val message: String) : PreviewLoadState
}

@Composable
private fun PreviewBitmap(
    uri: Uri,
    contentDescription: String,
    onDecodeFailure: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    var loadState by remember(uri) { mutableStateOf<PreviewLoadState>(PreviewLoadState.Loading) }

    LaunchedEffect(uri) {
        loadState = PreviewLoadState.Loading
        loadState = try {
            val bitmap = withContext(Dispatchers.IO) { context.contentResolver.decodePreviewBitmap(uri) }
            PreviewLoadState.Loaded(bitmap)
        } catch (exception: Exception) {
            PreviewLoadState.Failed(exception.message ?: "图片读取失败")
        }
    }

    DisposableEffect(loadState) {
        val loadedBitmap = (loadState as? PreviewLoadState.Loaded)?.bitmap
        onDispose { loadedBitmap?.recycle() }
    }

    when (val current = loadState) {
        PreviewLoadState.Loading -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(36.dp))
        }

        is PreviewLoadState.Loaded -> Image(
            bitmap = current.bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier.fillMaxSize(),
            contentScale = contentScale,
        )

        is PreviewLoadState.Failed -> {
            LaunchedEffect(current.message) { onDecodeFailure(current.message) }
            Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(AppLanguage.text(current.message), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }
}

private enum class PhotoGestureEnd { RELEASED, MOVED, CANCELLED }

private fun Modifier.photoGesture(
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
): Modifier = pointerInput(onTap, onLongPress) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val pointerId = down.id
        val startPosition = down.position
        val moveTolerance = viewConfiguration.touchSlop * HOLD_MOVE_TOLERANCE_MULTIPLIER

        val endedBeforeLongPress = withTimeoutOrNull(LONG_PRESS_MS) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == pointerId }
                    ?: return@withTimeoutOrNull PhotoGestureEnd.CANCELLED
                if (!change.pressed) return@withTimeoutOrNull PhotoGestureEnd.RELEASED
                if ((change.position - startPosition).getDistance() > moveTolerance) {
                    return@withTimeoutOrNull PhotoGestureEnd.MOVED
                }
            }
        }

        if (endedBeforeLongPress == null) {
            onLongPress()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (!change.pressed) break
            }
        } else if (endedBeforeLongPress == PhotoGestureEnd.RELEASED) {
            onTap()
        }
    }
}

@Composable
private fun FullScreenImageDialog(
    image: StageOutputImage,
    onClose: () -> Unit,
    onLongPress: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var translationX by remember { mutableFloatStateOf(0f) }
    var translationY by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .photoGesture(
                    onTap = onClose,
                    onLongPress = onLongPress,
                )
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        translationX += pan.x
                        translationY += pan.y
                        if (scale <= 1.01f) {
                            translationX = 0f
                            translationY = 0f
                        }
                    }
                },
        ) {
            PreviewBitmap(
                uri = Uri.parse(image.imageUri),
                contentDescription = image.title,
                onDecodeFailure = {},
                modifier = Modifier.graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = translationX,
                    translationY = translationY,
                ),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun ImageActionsDialog(image: StageOutputImage, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var pendingSave by remember { mutableStateOf(false) }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingSave) {
            saveAndToast(context, image)
        } else if (!granted) {
            Toast.makeText(context, AppLanguage.text("没有存储权限"), Toast.LENGTH_SHORT).show()
        }
        pendingSave = false
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppLanguage.text(image.title)) },
        text = { Text(AppLanguage.text("保存到相册或通过其他应用分享。")) },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                onClick = {
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingSave = true
                        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        saveAndToast(context, image)
                        onDismiss()
                    }
                },
            ) { Text(AppLanguage.text("保存")) }
        },
        dismissButton = {
            Row {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    onClick = {
                        runCatching { shareImage(context, Uri.parse(image.imageUri)) }
                            .onFailure { Toast.makeText(context, AppLanguage.text(it.message ?: "分享失败"), Toast.LENGTH_SHORT).show() }
                        onDismiss()
                    },
                ) { Text(AppLanguage.text("分享")) }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text(AppLanguage.text("取消")) }
            }
        },
    )
}

private fun saveAndToast(context: android.content.Context, image: StageOutputImage) {
    val fileName = "Photo2Fresh_${image.title}_${System.currentTimeMillis()}.jpg"
    saveImageToGallery(context, Uri.parse(image.imageUri), fileName)
        .onSuccess { Toast.makeText(context, AppLanguage.text("图片已保存"), Toast.LENGTH_SHORT).show() }
        .onFailure { Toast.makeText(context, AppLanguage.text(it.message ?: "保存失败"), Toast.LENGTH_SHORT).show() }
}

@Composable
private fun RuntimeParametersCard(
    parameters: RuntimeParameters,
    enabled: Boolean,
    onChange: (RuntimeParameters) -> Unit,
    onReset: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val warnings = parameterWarnings(parameters)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(AppLanguage.text("模型参数"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                ThemeButton(
                    text = if (expanded) "收起" else "调整",
                    onClick = { expanded = !expanded },
                    enabled = enabled,
                )
            }
            if (expanded) {
                FloatParameterEditor(
                    label = "检测卡定位模型 conf",
                    value = parameters.poseConfidence,
                    sliderRange = 0f..1f,
                    enabled = enabled,
                    decimals = 3,
                ) { onChange(parameters.copy(poseConfidence = it)) }
                FloatParameterEditor(
                    label = "传感区域分割模型 candidate_conf",
                    value = parameters.segCandidateConfidence,
                    sliderRange = 0f..0.05f,
                    enabled = enabled,
                    decimals = 4,
                ) { onChange(parameters.copy(segCandidateConfidence = it)) }
                FloatParameterEditor("传感区域分割模型 IoU", parameters.segIouThreshold, 0f..1f, enabled, 2) {
                    onChange(parameters.copy(segIouThreshold = it))
                }
                FloatParameterEditor("传感区域分割模型 mask", parameters.segMaskThreshold, 0.05f..0.95f, enabled, 2) {
                    onChange(parameters.copy(segMaskThreshold = it))
                }
                IntParameterField("传感区域分割模型 max_det", parameters.segMaxDetections, enabled, 1..500) {
                    onChange(parameters.copy(segMaxDetections = it))
                }
                FloatParameterEditor("颜色提取与校正模块 ROI", parameters.rainbowRoiScale.toFloat(), 0.10f..0.90f, enabled, 3) {
                    onChange(parameters.copy(rainbowRoiScale = it.toDouble()))
                }
                IntParameterField("RANSAC 迭代", parameters.rainbowRansacIterations, enabled, 100..100_000) {
                    onChange(parameters.copy(rainbowRansacIterations = it))
                }
                FloatParameterEditor("残差/步长", parameters.rainbowResidualRatio.toFloat(), 0.08f..0.80f, enabled, 2) {
                    onChange(parameters.copy(rainbowResidualRatio = it.toDouble()))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        IntParameterField("最少内点", parameters.rainbowMinInliers, enabled, 1..9) {
                            onChange(parameters.copy(rainbowMinInliers = it))
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        IntParameterField("最多补全", parameters.rainbowMaxRecover, enabled, 0..9) {
                            onChange(parameters.copy(rainbowMaxRecover = it))
                        }
                    }
                }
                FloatParameterEditor("校正强度", parameters.calibrationStrength.toFloat(), 0f..1f, enabled, 2) {
                    onChange(parameters.copy(calibrationStrength = it.toDouble()))
                }
                FloatParameterEditor("增益下限", parameters.gainMin.toFloat(), 0.05f..2f, enabled, 2) {
                    onChange(parameters.copy(gainMin = it.toDouble()))
                }
                FloatParameterEditor("增益上限", parameters.gainMax.toFloat(), 1f..10f, enabled, 2) {
                    onChange(parameters.copy(gainMax = it.toDouble()))
                }

                if (warnings.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(AppLanguage.text("参数警告（数值仍会应用）"), fontWeight = FontWeight.SemiBold)
                            warnings.forEach { warning ->
                                Text(AppLanguage.text(warning), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                ThemeButton(
                    text = "恢复默认参数",
                    onClick = onReset,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    AppLanguage.text("参数仅对本次程序运行有效；重新启动后恢复默认值。手动输入超出常用范围的数值只会提示，不会自动改写。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FloatParameterEditor(
    label: String,
    value: Float,
    sliderRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    decimals: Int,
    onValueChange: (Float) -> Unit,
) {
    var text by remember(label) { mutableStateOf("%.${decimals}f".format(value)) }
    var editing by remember(label) { mutableStateOf(false) }
    val parsed = text.toFloatOrNull()
    val sliderValue = if (value.isFinite()) value.coerceIn(sliderRange.start, sliderRange.endInclusive) else sliderRange.start
    val localWarning = when {
        parsed == null -> "输入内容尚不能解析为数值；当前模型值未改变"
        !parsed.isFinite() -> "非有限数值已应用；运行检测时可能失败"
        parsed < sliderRange.start || parsed > sliderRange.endInclusive -> "超出滑条常用范围，仍已应用"
        else -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(AppLanguage.text(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    text = raw
                    raw.toFloatOrNull()?.let(onValueChange)
                },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .weight(0.78f)
                    .onFocusChanged { focus ->
                        editing = focus.isFocused
                        if (!focus.isFocused) text = "%.${decimals}f".format(value)
                    },
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = {
                text = "%.${decimals}f".format(it)
                onValueChange(it)
            },
            valueRange = sliderRange,
            enabled = enabled,
        )
        if (localWarning != null) {
            Text(AppLanguage.text(localWarning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    LaunchedEffect(value, editing) {
        if (!editing && value.isFinite()) {
            text = "%.${decimals}f".format(value)
        }
    }
}

@Composable
private fun IntParameterField(
    label: String,
    value: Int,
    enabled: Boolean,
    commonRange: IntRange,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(label) { mutableStateOf(value.toString()) }
    var editing by remember(label) { mutableStateOf(false) }
    val parsed = text.toIntOrNull()
    val warning = when {
        parsed == null -> "输入内容尚不能解析为整数；当前模型值未改变"
        parsed !in commonRange -> "超出常用范围 ${commonRange.first}..${commonRange.last}，仍已应用"
        else -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                raw.toIntOrNull()?.let(onValueChange)
            },
            label = { Text(AppLanguage.text(label)) },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focus ->
                    editing = focus.isFocused
                    if (!focus.isFocused) text = value.toString()
                },
        )
        if (warning != null) {
            Text(AppLanguage.text(warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    LaunchedEffect(value, editing) {
        if (!editing) {
            text = value.toString()
        }
    }
}

private fun parameterWarnings(parameters: RuntimeParameters): List<String> = buildList {
    if (!parameters.poseConfidence.isFinite() || parameters.poseConfidence !in 0f..1f) add("检测卡定位模型 conf 正常范围为 0..1")
    if (!parameters.segCandidateConfidence.isFinite() || parameters.segCandidateConfidence !in 0f..1f) add("传感区域分割模型 candidate_conf 正常范围为 0..1")
    if (!parameters.segIouThreshold.isFinite() || parameters.segIouThreshold !in 0f..1f) add("传感区域分割模型 IoU 正常范围为 0..1")
    if (!parameters.segMaskThreshold.isFinite() || parameters.segMaskThreshold <= 0f || parameters.segMaskThreshold >= 1f) add("传感区域分割模型 mask 必须位于 0 与 1 之间")
    if (parameters.segMaxDetections <= 0) add("传感区域分割模型 max_det 应大于 0")
    if (!parameters.rainbowRoiScale.isFinite() || parameters.rainbowRoiScale !in 0.0..1.0) add("颜色提取与校正模块 ROI 正常范围为 0..1")
    if (parameters.rainbowRansacIterations <= 0) add("RANSAC 迭代次数应大于 0")
    if (!parameters.rainbowResidualRatio.isFinite() || parameters.rainbowResidualRatio <= 0.0) add("残差/步长应大于 0")
    if (parameters.rainbowMinInliers !in 1..9) add("最少内点正常范围为 1..9")
    if (parameters.rainbowMaxRecover !in 0..9) add("最多补全正常范围为 0..9")
    if (!parameters.calibrationStrength.isFinite() || parameters.calibrationStrength !in 0.0..1.0) add("校正强度正常范围为 0..1")
    if (!parameters.gainMin.isFinite() || parameters.gainMin <= 0.0) add("增益下限应为正数")
    if (!parameters.gainMax.isFinite() || parameters.gainMax <= 0.0) add("增益上限应为正数")
    if (parameters.gainMin.isFinite() && parameters.gainMax.isFinite() && parameters.gainMin > parameters.gainMax) add("增益下限大于增益上限")
}

@Composable
private fun ActionArea(state: PipelineState, onStart: () -> Unit, onReset: () -> Unit, conditionSelected: Boolean) {
    val canStart = conditionSelected && state.originalImageUriOrNull() != null && state !is PipelineState.Processing
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemeButton(
            text = "清空",
            onClick = onReset,
            enabled = state !is PipelineState.Idle && state !is PipelineState.Processing,
            modifier = Modifier.weight(1f),
        )
        ThemeButton(
            text = "运行检测",
            onClick = onStart,
            enabled = canStart,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TestProcessingOrResultCard(state: PipelineState) {
    when (state) {
        PipelineState.Idle,
        is PipelineState.Ready -> Unit
        is PipelineState.Processing -> TestProcessingCard(state)
        is PipelineState.WaitingForModels -> WaitingForModelsCard(state)
        is PipelineState.PoseFallbackPrompt -> Unit
        is PipelineState.Success -> TestResultCard(state)
        is PipelineState.Failure -> FailureCard(state.message)
    }
}

@Composable
private fun TestProcessingCard(state: PipelineState.Processing) {
    val phaseIndex = state.phase.ordinal
    val progress by smoothProgress(state)
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(AppLanguage.text("正在检测"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(AppLanguage.text(stageText(state.stage)))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                AppLanguage.text("${phaseIndex + 1}/${allDetectionPhases.size}"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WaitingForModelsCard(state: PipelineState.WaitingForModels) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(AppLanguage.text("模型文件不可用"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            PreparedImageSummary(state.preparedImage)
            state.condition?.let { Text(AppLanguage.text(it.label)) }
            state.debugBundleUri?.let { RegressionSampleButton(it) }
            state.diagnostics.filter { it.status != ModelStatus.LOADABLE }.forEach { diagnostic ->
                Text(AppLanguage.text("${diagnostic.displayName}：${diagnostic.assetPath}"))
                diagnostic.error?.let { Text(AppLanguage.text(it), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun PreparedImageSummary(image: PreparedImage) {
    Text(AppLanguage.text("图片尺寸：${image.width} × ${image.height}"))
}

@Composable
private fun TestResultCard(state: PipelineState.Success) {
    val prediction = state.prediction
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(AppLanguage.text("检测完成 · ${prediction.condition.label}"), fontWeight = FontWeight.SemiBold)
            TvbnRangeBar(prediction.tvbn)
            Text(AppLanguage.text("新鲜度：${prediction.freshness}"), style = MaterialTheme.typography.titleMedium)
            Text(AppLanguage.text("已储存时长：${prediction.timeText()}"), style = MaterialTheme.typography.titleLarge)
            Text(AppLanguage.text("模型版本：${prediction.modelVersion}"), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text(AppLanguage.text("色块 ${state.rainbowGrid.inliers}/9，补全 ${state.rainbowGrid.recovered}，重分配 ${state.rainbowGrid.reassigned}"))
            Text(AppLanguage.text("灰度参考：${state.rainbowGrid.graySampleSource}"))
            state.roiGains.forEach { (name, gain) -> Text(AppLanguage.text("$name 增益：" + gain.joinToString { "%.6f".format(java.util.Locale.ROOT, it) }), style = MaterialTheme.typography.bodySmall) }
            if (state.rainbowQualityFlags.isNotEmpty()) Text(AppLanguage.text("检测提示：${state.rainbowQualityFlags.joinToString()}"), style = MaterialTheme.typography.bodySmall)
            state.debugBundleUri?.let { RegressionSampleButton(it) }
        }
    }
}

@Composable
private fun RegressionSampleButton(uri: String) {
    val context = LocalContext.current
    ThemeButton("导出输入对照包", onClick = {
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(android.content.Intent.EXTRA_STREAM, Uri.parse(uri))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, AppLanguage.text("导出输入对照包")))
        }.onFailure { android.widget.Toast.makeText(context, AppLanguage.text("导出失败：${it.message}"), android.widget.Toast.LENGTH_LONG).show() }
    })
}

@Composable
private fun LanguageSelectionDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppLanguage.text("选择语言")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(UiLanguage.ENGLISH to "English", UiLanguage.CHINESE to "中文").forEach { (language, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            AppLanguage.select(language)
                            onDismiss()
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = AppLanguage.current == language, onClick = {
                            AppLanguage.select(language)
                            onDismiss()
                        })
                        Text(label)
                    }
                }
                Text(AppLanguage.text("仅本次运行有效，重新打开程序后恢复英文。"))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(AppLanguage.text("取消")) } },
    )
}

@Composable
private fun ConditionSelectionCard(
    meat: MeatType?, temperature: StorageTemperature?, enabled: Boolean,
    onMeat: (MeatType) -> Unit, onTemperature: (StorageTemperature) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(AppLanguage.text("检测工况"), fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MeatType.entries.forEach { value ->
                    val selected = meat == value
                    FilterChip(
                        selected = selected, onClick = { onMeat(value) }, enabled = enabled,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                        label = { Text(AppLanguage.text(value.label), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StorageTemperature.entries.forEach { value ->
                    val selected = temperature == value
                    FilterChip(
                        selected = selected, onClick = { onTemperature(value) }, enabled = enabled,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                        label = { Text(AppLanguage.text(value.label), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FailureCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(AppLanguage.text("处理失败"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(AppLanguage.text(message))
        }
    }
}

@Composable
private fun TvbnRangeBar(value: Float, modifier: Modifier = Modifier) {
    val fraction = tvbnVisualFraction(value)
    val rightLabel = if (value > TVBN_ACCEPTABLE_LIMIT) {
    "%.1f".format(value)
} else {
    "MAX"
}
    val pointerColor = MaterialTheme.colorScheme.primary
    val barBrush = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF78DC99),
            Color(0xFFDDDF73),
            Color(0xFFFF6666),
        ),
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(82.dp)) {
        val labelWidth = 140.dp
        val pointerWidth = 14.dp
        val x = maxWidth * fraction
        val labelX = (x - labelWidth / 2).coerceIn(0.dp, (maxWidth - labelWidth).coerceAtLeast(0.dp))
        val pointerX = (x - pointerWidth / 2).coerceIn(0.dp, (maxWidth - pointerWidth).coerceAtLeast(0.dp))

        Text(
            text = AppLanguage.text("%.1fmg/100g".format(value)),
            modifier = Modifier.offset(x = labelX, y = 0.dp).size(labelWidth, 24.dp),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
            color = pointerColor,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = 27.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(barBrush),
        )

        Canvas(
            modifier = Modifier
                .offset(x = pointerX, y = 43.dp)
                .size(pointerWidth, 12.dp),
        ) {
            val triangle = Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(0f, size.height)
                lineTo(size.width, size.height)
                close()
            }
            drawPath(triangle, pointerColor)
        }

        val labelStyle = MaterialTheme.typography.labelSmall
        val labelWidthSmall = 48.dp
        Text(
            AppLanguage.text("0"),
            modifier = Modifier.offset(x = 0.dp, y = 61.dp).size(labelWidthSmall, 18.dp),
            style = labelStyle,
            textAlign = TextAlign.Start,
        )
        Text(
            AppLanguage.text("15"),
            modifier = Modifier
                .offset(x = maxWidth / 3f - labelWidthSmall / 2, y = 61.dp)
                .size(labelWidthSmall, 18.dp),
            style = labelStyle,
            textAlign = TextAlign.Center,
        )
        Text(
            AppLanguage.text("20"),
            modifier = Modifier
                .offset(x = maxWidth * 2f / 3f - labelWidthSmall / 2, y = 61.dp)
                .size(labelWidthSmall, 18.dp),
            style = labelStyle,
            textAlign = TextAlign.Center,
        )
        Text(
    AppLanguage.text(rightLabel),
    modifier = Modifier
        .offset(x = (maxWidth - labelWidthSmall).coerceAtLeast(0.dp), y = 61.dp)
        .size(labelWidthSmall, 18.dp),
    style = labelStyle,
    textAlign = TextAlign.End,
)
    }
}

private fun tvbnVisualFraction(value: Float): Float {
    val normalizedValue = value.coerceAtLeast(TVBN_MIN)

    return when {
        normalizedValue <= TVBN_FRESH_LIMIT -> {
            // 0—15 对应左侧 1/3
            (normalizedValue / TVBN_FRESH_LIMIT) / 3f
        }

        normalizedValue <= TVBN_ACCEPTABLE_LIMIT -> {
            // 15—20 对应中间 1/3
            1f / 3f +
                ((normalizedValue - TVBN_FRESH_LIMIT) /
                    (TVBN_ACCEPTABLE_LIMIT - TVBN_FRESH_LIMIT)) / 3f
        }

        else -> {
            // 大于20时，当前值就是动态最大值，直接定位到最右端
            1f
        }
    }.coerceIn(0f, 1f)
}


private fun stageText(stage: ProcessingStage): String = when (stage) {
    ProcessingStage.NORMALIZING_ORIENTATION -> "正在校正图片方向"
    ProcessingStage.RUNNING_PANCAKE -> "正在定位检测卡"
    ProcessingStage.RECTIFYING -> "正在校正拍摄角度"
    ProcessingStage.RUNNING_HYDRALISK -> "正在识别传感区域"
    ProcessingStage.RUNNING_RAINBOW -> "正在提取并校正颜色"
    ProcessingStage.RUNNING_REGRESSION -> "正在预测 TVB-N 与已储存时长"
}

@Preview(showBackground = true)
@Composable
private fun TvbnRangePreview() {
    Photo2freshTheme {
        Column(Modifier.padding(20.dp)) {
            TvbnRangeBar(18.3f)
        }
    }
}

private const val LONG_PRESS_MS = 600L
private const val HOLD_MOVE_TOLERANCE_MULTIPLIER = 2.5f
private const val MODE_SWITCH_TAP_COUNT = 10
private const val MODE_SWITCH_TAP_RESET_MS = 5000L
private const val MODE_PASSWORD_SHA256 = "80e84536f6353484f4f997ee6f25313c4d9cd445a216a91a243beaa35dfcbb33"
private const val TVBN_MIN = 0f
private const val TVBN_FRESH_LIMIT = 15f
private const val TVBN_ACCEPTABLE_LIMIT = 20f
private const val TVBN_MAX = 80f
private const val EMPTY_TEXT_PHYSICS_HZ = 120f
private const val EMPTY_TEXT_BOUND_PX = 100f
private const val EMPTY_TEXT_ACCELERATION = 520f
private const val EMPTY_TEXT_BOUNCE = 0.80f
private const val EMPTY_TEXT_VELOCITY_DAMPING = 0.996f
private const val EMPTY_TEXT_MAX_SPEED = 1800f
private const val EMPTY_TEXT_GRAVITY_FILTER = 0.08f
private const val EMPTY_TEXT_FORCE_RESPONSE = 0.42f
private const val EMPTY_TEXT_SHAKE_GAIN = 2.6f
private const val EMPTY_TEXT_SHAKE_DEAD_ZONE = 0.10f

package com.iqonli.photo2fresh.ui

import com.iqonli.photo2fresh.i18n.AppLanguage
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.iqonli.photo2fresh.pipeline.CropEditState
import com.iqonli.photo2fresh.util.decodePreviewBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropEditorScreen(
    state: CropEditState,
    onChange: (CropEditState) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(enabled = !state.isApplying, onBack = onCancel)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        ),
        topBar = {
            TopAppBar(title = { Text(AppLanguage.text("裁剪")) })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CropImageCanvas(
                uriString = state.originalUri,
                state = state,
                onChange = onChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            Text(
                text = AppLanguage.text("旋转  %.1f°".format(state.rotationDegrees)),
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RotationWheel(
                angle = state.rotationDegrees,
                onAngleChange = { angle -> onChange(state.copy(rotationDegrees = angle)) },
                enabled = !state.isApplying,
                modifier = Modifier.fillMaxWidth(),
            )

            state.errorMessage?.let { message ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(AppLanguage.text(message), modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onReset,
                    enabled = !state.isApplying,
                    modifier = Modifier.weight(1f),
                ) { Text(AppLanguage.text("还原")) }
                Button(
                    onClick = onCancel,
                    enabled = !state.isApplying,
                    modifier = Modifier.weight(1f),
                ) { Text(AppLanguage.text("取消")) }
                Button(
                    onClick = onApply,
                    enabled = !state.isApplying,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isApplying) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(AppLanguage.text("完成"))
                    }
                }
            }
        }
    }
}

private sealed interface CropPreviewState {
    data object Loading : CropPreviewState
    data class Loaded(val bitmap: Bitmap) : CropPreviewState
    data class Failed(val message: String) : CropPreviewState
}

@Composable
private fun CropImageCanvas(
    uriString: String,
    state: CropEditState,
    onChange: (CropEditState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var previewState by remember(uriString) { mutableStateOf<CropPreviewState>(CropPreviewState.Loading) }

    LaunchedEffect(uriString) {
        previewState = CropPreviewState.Loading
        previewState = runCatching {
            withContext(Dispatchers.IO) {
                context.contentResolver.decodePreviewBitmap(Uri.parse(uriString))
            }
        }.fold(
            onSuccess = { CropPreviewState.Loaded(it) },
            onFailure = { CropPreviewState.Failed(it.message ?: "图片读取失败") },
        )
    }

    DisposableEffect(previewState) {
        val bitmap = (previewState as? CropPreviewState.Loaded)?.bitmap
        onDispose { bitmap?.recycle() }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when (val preview = previewState) {
            CropPreviewState.Loading -> CircularProgressIndicator()
            is CropPreviewState.Failed -> Text(AppLanguage.text(preview.message), color = Color.White)
            is CropPreviewState.Loaded -> CropInteractiveCanvas(
                bitmap = preview.bitmap,
                state = state,
                onChange = onChange,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private enum class CropDragTarget {
    NONE,
    MOVE,
    TOP_LEFT,
    TOP,
    TOP_RIGHT,
    RIGHT,
    BOTTOM_RIGHT,
    BOTTOM,
    BOTTOM_LEFT,
    LEFT,
}

private data class CropDisplayGeometry(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val bitmapScale: Float,
) {
    fun cropRect(state: CropEditState): Rect = Rect(
        left = left + state.left * width,
        top = top + state.top * height,
        right = left + state.right * width,
        bottom = top + state.bottom * height,
    )
}

@Composable
private fun CropInteractiveCanvas(
    bitmap: Bitmap,
    state: CropEditState,
    onChange: (CropEditState) -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val currentState by rememberUpdatedState(state)
    val currentOnChange by rememberUpdatedState(onChange)
    val primary = MaterialTheme.colorScheme.primary
    val imageBitmap: ImageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(canvasSize, bitmap.width, bitmap.height, state.rotationDegrees) {
                if (canvasSize.width <= 0 || canvasSize.height <= 0) return@pointerInput
                val geometry = cropDisplayGeometry(
                    canvasSize.width.toFloat(),
                    canvasSize.height.toFloat(),
                    bitmap.width.toFloat(),
                    bitmap.height.toFloat(),
                )
                var target = CropDragTarget.NONE
                detectDragGestures(
                    onDragStart = { position ->
                        target = hitTestCropTarget(position, geometry.cropRect(currentState))
                    },
                    onDragEnd = { target = CropDragTarget.NONE },
                    onDragCancel = { target = CropDragTarget.NONE },
                ) { _, dragAmount ->
                    if (target == CropDragTarget.NONE) return@detectDragGestures
                    val dx = dragAmount.x / geometry.width.coerceAtLeast(1f)
                    val dy = dragAmount.y / geometry.height.coerceAtLeast(1f)
                    currentOnChange(adjustCrop(currentState, target, dx, dy))
                }
            },
    ) {
        val geometry = cropDisplayGeometry(
            size.width,
            size.height,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
        )
        val cropRect = geometry.cropRect(state)

        // The crop frame stays fixed while the image rotates around the selected region center.
        // This matches the usual gallery-editor interaction: once a region is selected, changing
        // the angle no longer makes that region orbit around the full-image center.
        withTransform({
            rotate(state.rotationDegrees, pivot = cropRect.center)
        }) {
            drawImage(
                image = imageBitmap,
                dstOffset = IntOffset(geometry.left.toInt(), geometry.top.toInt()),
                dstSize = IntSize(geometry.width.toInt().coerceAtLeast(1), geometry.height.toInt().coerceAtLeast(1)),
            )
        }
        val shade = Color.Black.copy(alpha = 0.52f)
        drawRect(shade, topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(size.width, cropRect.top.coerceAtLeast(0f)))
        drawRect(
            shade,
            topLeft = Offset(0f, cropRect.bottom.coerceAtMost(size.height)),
            size = androidx.compose.ui.geometry.Size(size.width, (size.height - cropRect.bottom).coerceAtLeast(0f)),
        )
        drawRect(
            shade,
            topLeft = Offset(0f, cropRect.top),
            size = androidx.compose.ui.geometry.Size(cropRect.left.coerceAtLeast(0f), cropRect.height),
        )
        drawRect(
            shade,
            topLeft = Offset(cropRect.right, cropRect.top),
            size = androidx.compose.ui.geometry.Size((size.width - cropRect.right).coerceAtLeast(0f), cropRect.height),
        )

        drawRect(Color.White, topLeft = cropRect.topLeft, size = cropRect.size, style = Stroke(width = 2.5f))
        val thirdX1 = cropRect.left + cropRect.width / 3f
        val thirdX2 = cropRect.left + cropRect.width * 2f / 3f
        val thirdY1 = cropRect.top + cropRect.height / 3f
        val thirdY2 = cropRect.top + cropRect.height * 2f / 3f
        val gridColor = Color.White.copy(alpha = 0.55f)
        drawLine(gridColor, Offset(thirdX1, cropRect.top), Offset(thirdX1, cropRect.bottom), strokeWidth = 1f)
        drawLine(gridColor, Offset(thirdX2, cropRect.top), Offset(thirdX2, cropRect.bottom), strokeWidth = 1f)
        drawLine(gridColor, Offset(cropRect.left, thirdY1), Offset(cropRect.right, thirdY1), strokeWidth = 1f)
        drawLine(gridColor, Offset(cropRect.left, thirdY2), Offset(cropRect.right, thirdY2), strokeWidth = 1f)

        listOf(cropRect.topLeft, cropRect.topRight, cropRect.bottomLeft, cropRect.bottomRight).forEach { point ->
            drawCircle(Color.White, radius = 10f, center = point)
            drawCircle(primary, radius = 6f, center = point)
        }

        // Mid-edge handles are intentionally subtle. The whole border has a larger invisible hit
        // target, so users can resize from any point on an edge rather than only the four corners.
        val edgeHandleHalf = 24f
        drawLine(Color.White, Offset(cropRect.center.x - edgeHandleHalf, cropRect.top), Offset(cropRect.center.x + edgeHandleHalf, cropRect.top), 5f)
        drawLine(primary, Offset(cropRect.center.x - edgeHandleHalf, cropRect.top), Offset(cropRect.center.x + edgeHandleHalf, cropRect.top), 2.5f)
        drawLine(Color.White, Offset(cropRect.center.x - edgeHandleHalf, cropRect.bottom), Offset(cropRect.center.x + edgeHandleHalf, cropRect.bottom), 5f)
        drawLine(primary, Offset(cropRect.center.x - edgeHandleHalf, cropRect.bottom), Offset(cropRect.center.x + edgeHandleHalf, cropRect.bottom), 2.5f)
        drawLine(Color.White, Offset(cropRect.left, cropRect.center.y - edgeHandleHalf), Offset(cropRect.left, cropRect.center.y + edgeHandleHalf), 5f)
        drawLine(primary, Offset(cropRect.left, cropRect.center.y - edgeHandleHalf), Offset(cropRect.left, cropRect.center.y + edgeHandleHalf), 2.5f)
        drawLine(Color.White, Offset(cropRect.right, cropRect.center.y - edgeHandleHalf), Offset(cropRect.right, cropRect.center.y + edgeHandleHalf), 5f)
        drawLine(primary, Offset(cropRect.right, cropRect.center.y - edgeHandleHalf), Offset(cropRect.right, cropRect.center.y + edgeHandleHalf), 2.5f)
    }
}

private fun cropDisplayGeometry(
    canvasWidth: Float,
    canvasHeight: Float,
    bitmapWidth: Float,
    bitmapHeight: Float,
): CropDisplayGeometry {
    val scale = min(
        canvasWidth / bitmapWidth.coerceAtLeast(1f),
        canvasHeight / bitmapHeight.coerceAtLeast(1f),
    ) * 0.96f
    val width = bitmapWidth * scale
    val height = bitmapHeight * scale
    return CropDisplayGeometry(
        left = (canvasWidth - width) / 2f,
        top = (canvasHeight - height) / 2f,
        width = width,
        height = height,
        bitmapScale = scale,
    )
}

private fun hitTestCropTarget(position: Offset, rect: Rect): CropDragTarget {
    val corners = listOf(
        CropDragTarget.TOP_LEFT to rect.topLeft,
        CropDragTarget.TOP_RIGHT to rect.topRight,
        CropDragTarget.BOTTOM_LEFT to rect.bottomLeft,
        CropDragTarget.BOTTOM_RIGHT to rect.bottomRight,
    )
    val nearest = corners.minByOrNull { (_, point) -> distance(position, point) }
    if (nearest != null && distance(position, nearest.second) <= CROP_HANDLE_TOUCH_RADIUS_PX) {
        return nearest.first
    }

    val r = CROP_EDGE_TOUCH_RADIUS_PX
    val insideHorizontalSpan = position.x in (rect.left - r)..(rect.right + r)
    val insideVerticalSpan = position.y in (rect.top - r)..(rect.bottom + r)
    val topDistance = abs(position.y - rect.top)
    val bottomDistance = abs(position.y - rect.bottom)
    val leftDistance = abs(position.x - rect.left)
    val rightDistance = abs(position.x - rect.right)

    val edgeCandidates = buildList {
        if (insideHorizontalSpan && topDistance <= r) add(CropDragTarget.TOP to topDistance)
        if (insideHorizontalSpan && bottomDistance <= r) add(CropDragTarget.BOTTOM to bottomDistance)
        if (insideVerticalSpan && leftDistance <= r) add(CropDragTarget.LEFT to leftDistance)
        if (insideVerticalSpan && rightDistance <= r) add(CropDragTarget.RIGHT to rightDistance)
    }
    edgeCandidates.minByOrNull { it.second }?.let { return it.first }

    return if (position.x in rect.left..rect.right && position.y in rect.top..rect.bottom) {
        CropDragTarget.MOVE
    } else {
        CropDragTarget.NONE
    }
}

private fun distance(a: Offset, b: Offset): Float = sqrt(
    (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y),
)

private fun adjustCrop(
    state: CropEditState,
    target: CropDragTarget,
    dx: Float,
    dy: Float,
): CropEditState {
    var left = state.left
    var top = state.top
    var right = state.right
    var bottom = state.bottom

    when (target) {
        CropDragTarget.MOVE -> {
            val width = right - left
            val height = bottom - top
            left = (left + dx).coerceIn(0f, 1f - width)
            top = (top + dy).coerceIn(0f, 1f - height)
            right = left + width
            bottom = top + height
        }
        CropDragTarget.TOP_LEFT -> {
            left = (left + dx).coerceIn(0f, right - MIN_CROP_FRACTION)
            top = (top + dy).coerceIn(0f, bottom - MIN_CROP_FRACTION)
        }
        CropDragTarget.TOP -> {
            top = (top + dy).coerceIn(0f, bottom - MIN_CROP_FRACTION)
        }
        CropDragTarget.TOP_RIGHT -> {
            right = (right + dx).coerceIn(left + MIN_CROP_FRACTION, 1f)
            top = (top + dy).coerceIn(0f, bottom - MIN_CROP_FRACTION)
        }
        CropDragTarget.RIGHT -> {
            right = (right + dx).coerceIn(left + MIN_CROP_FRACTION, 1f)
        }
        CropDragTarget.BOTTOM_LEFT -> {
            left = (left + dx).coerceIn(0f, right - MIN_CROP_FRACTION)
            bottom = (bottom + dy).coerceIn(top + MIN_CROP_FRACTION, 1f)
        }
        CropDragTarget.BOTTOM_RIGHT -> {
            right = (right + dx).coerceIn(left + MIN_CROP_FRACTION, 1f)
            bottom = (bottom + dy).coerceIn(top + MIN_CROP_FRACTION, 1f)
        }
        CropDragTarget.BOTTOM -> {
            bottom = (bottom + dy).coerceIn(top + MIN_CROP_FRACTION, 1f)
        }
        CropDragTarget.LEFT -> {
            left = (left + dx).coerceIn(0f, right - MIN_CROP_FRACTION)
        }
        CropDragTarget.NONE -> Unit
    }
    return state.copy(left = left, top = top, right = right, bottom = bottom, errorMessage = null)
}

@Composable
private fun RotationWheel(
    angle: Float,
    onAngleChange: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val currentAngle by rememberUpdatedState(angle)
    val currentOnAngleChange by rememberUpdatedState(onAngleChange)
    val primary = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier = modifier
            .height(70.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures { _, dragAmount ->
                    val next = (currentAngle - dragAmount.x * ROTATION_DEGREES_PER_PX)
                        .coerceIn(CropEditState.MIN_ROTATION_DEGREES, CropEditState.MAX_ROTATION_DEGREES)
                    currentOnAngleChange(if (abs(next) < 0.05f) 0f else next)
                }
            },
    ) {
        val centerX = size.width / 2f
        val baselineY = size.height * 0.58f
        for (tick in -45..45) {
            val x = centerX + (tick - angle) * ROTATION_TICK_SPACING_PX
            if (x < -20f || x > size.width + 20f) continue
            val major = tick % 5 == 0
            val height = if (major) 26f else 14f
            drawLine(
                color = tickColor.copy(alpha = if (major) 0.8f else 0.45f),
                start = Offset(x, baselineY - height / 2f),
                end = Offset(x, baselineY + height / 2f),
                strokeWidth = if (major) 2f else 1f,
            )
        }
        drawLine(
            color = primary,
            start = Offset(centerX, 7f),
            end = Offset(centerX, size.height - 7f),
            strokeWidth = 4f,
        )
    }
}

private const val MIN_CROP_FRACTION = 0.08f
private const val CROP_HANDLE_TOUCH_RADIUS_PX = 52f
private const val CROP_EDGE_TOUCH_RADIUS_PX = 34f
private const val ROTATION_DEGREES_PER_PX = 0.09f
private const val ROTATION_TICK_SPACING_PX = 8f

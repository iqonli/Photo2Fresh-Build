package com.iqonli.photo2fresh.pipeline

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iqonli.photo2fresh.config.RuntimeParameters
import com.iqonli.photo2fresh.config.WorkflowConfig
import com.iqonli.photo2fresh.image.CropBitmapProcessor
import com.iqonli.photo2fresh.image.ImageNormalizer
import com.iqonli.photo2fresh.image.PipelineBitmapIO
import com.iqonli.photo2fresh.image.PoseDebugRenderer
import com.iqonli.photo2fresh.image.SegmentationDebugRenderer
import com.iqonli.photo2fresh.inference.HydraliskSegEngine
import com.iqonli.photo2fresh.regression.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.iqonli.photo2fresh.inference.ModelStatus
import com.iqonli.photo2fresh.inference.OnnxModelInspector
import com.iqonli.photo2fresh.inference.PancakePoseEngine
import com.iqonli.photo2fresh.inference.PancakePoseResult
import com.iqonli.photo2fresh.opencv.PerspectiveRectifier
import com.iqonli.photo2fresh.rainbow.CatchTheRainbowEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetectionViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    val workflowConfig: WorkflowConfig = WorkflowConfig.load(context)
    private val stageTimingStore = StageTimingStore(context)
    private val engineLock = Any()

    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val _runtimeParameters = MutableStateFlow(RuntimeParameters.from(workflowConfig))
    val runtimeParameters: StateFlow<RuntimeParameters> = _runtimeParameters.asStateFlow()

    private val _cropEditState = MutableStateFlow<CropEditState?>(null)
    val cropEditState: StateFlow<CropEditState?> = _cropEditState.asStateFlow()

    private val _modelDiagnostics = MutableStateFlow<
        List<com.iqonli.photo2fresh.inference.ModelDiagnostic>
        >(emptyList())
    val modelDiagnostics: StateFlow<List<com.iqonli.photo2fresh.inference.ModelDiagnostic>> =
        _modelDiagnostics.asStateFlow()

    private var processingJob: Job? = null
    private var prewarmJob: Job? = null
    @Volatile private var modelsPrewarmedSuccessfully: Boolean = false
    private var poseEngine: PancakePoseEngine? = null
    private var segEngine: HydraliskSegEngine? = null
    private var rainbowEngine: CatchTheRainbowEngine? = null
    private var rainbowEngineConfig: com.iqonli.photo2fresh.config.RainbowConfig? = null
    private var imageRegressor: YoloImageRegressor? = null
    private val pipelineMutex = Mutex()
    private val _selectedMeat = MutableStateFlow<MeatType?>(null)
    val selectedMeat: StateFlow<MeatType?> = _selectedMeat.asStateFlow()
    private val _selectedTemperature = MutableStateFlow<StorageTemperature?>(null)
    val selectedTemperature: StateFlow<StorageTemperature?> = _selectedTemperature.asStateFlow()
    val selectedCondition: RegressionCondition? get() {
        val meat = _selectedMeat.value ?: return null
        val temperature = _selectedTemperature.value ?: return null
        return RegressionCondition(meat, temperature)
    }

    fun selectMeat(value: MeatType) {
        if (_state.value !is PipelineState.Processing) _selectedMeat.value = value
    }

    fun selectTemperature(value: StorageTemperature) {
        if (_state.value !is PipelineState.Processing) _selectedTemperature.value = value
    }

    init {
        // Load and retain all ONNX sessions immediately, but never block first composition. If the
        // user starts detection before this finishes, that run joins the same prewarm job instead of
        // loading a second set of sessions in parallel. A full inspector pass is only needed when
        // prewarming itself fails.
        prewarmJob = viewModelScope.launch(Dispatchers.IO) {
            val prewarm = runCatching {
                getPoseEngine()
                getSegEngine()
            }
            modelsPrewarmedSuccessfully = prewarm.isSuccess
            if (prewarm.isFailure) {
                _modelDiagnostics.value = OnnxModelInspector.inspectUpstream(context, workflowConfig)
            }
        }
    }

    fun selectImage(uri: Uri) {
        processingJob?.cancel()
        val uriString = uri.toString()
        _cropEditState.value = CropEditState(originalUri = uriString)
        _state.value = PipelineState.Ready(imageUri = uriString)
    }

    fun updateCropEditState(value: CropEditState) {
        val current = _cropEditState.value ?: return
        if (current.isApplying || current.originalUri != value.originalUri) return
        _cropEditState.value = value.normalized().copy(
            appliedUri = current.appliedUri,
            isApplying = false,
            errorMessage = null,
        )
    }

    fun resetCropEditState() {
        val current = _cropEditState.value ?: return
        if (current.isApplying) return
        _cropEditState.value = current.copy(
            left = 0f,
            top = 0f,
            right = 1f,
            bottom = 1f,
            rotationDegrees = 0f,
            errorMessage = null,
        )
    }

    fun applyCrop(onFinished: (Boolean) -> Unit = {}) {
        val snapshot = _cropEditState.value ?: return
        if (snapshot.isApplying) return
        _cropEditState.value = snapshot.copy(isApplying = true, errorMessage = null)
        viewModelScope.launch {
            var normalizedSource: Bitmap? = null
            var cropped: Bitmap? = null
            try {
                val outputUri = withContext(Dispatchers.IO) {
                    val prepared = ImageNormalizer.prepare(context, Uri.parse(snapshot.originalUri))
                    normalizedSource = PipelineBitmapIO.decode(context, prepared.normalizedUri)
                    cropped = CropBitmapProcessor.apply(requireNotNull(normalizedSource), snapshot)
                    PipelineBitmapIO.saveCropJpeg(
                        context = context,
                        bitmap = requireNotNull(cropped),
                        quality = workflowConfig.rectification.jpegQuality,
                    )
                }
                _cropEditState.value = snapshot.copy(
                    appliedUri = outputUri,
                    isApplying = false,
                    errorMessage = null,
                )
                _state.value = PipelineState.Ready(imageUri = outputUri)
                onFinished(true)
            } catch (exception: Exception) {
                _cropEditState.value = snapshot.copy(
                    isApplying = false,
                    errorMessage = exception.message ?: "裁剪失败",
                )
                onFinished(false)
            } finally {
                normalizedSource?.recycle()
                cropped?.recycle()
            }
        }
    }

    fun updateRuntimeParameters(value: RuntimeParameters) {
        if (_state.value is PipelineState.Processing) return
        // Test parameters are intentionally stored verbatim. The UI warns about abnormal values;
        // it does not silently replace them with a clamped value.
        _runtimeParameters.value = value
    }

    fun resetRuntimeParameters() {
        if (_state.value is PipelineState.Processing) return
        _runtimeParameters.value = RuntimeParameters.from(workflowConfig)
    }

    private val _deploymentCheck = MutableStateFlow("尚未执行部署自检")
    val deploymentCheck: StateFlow<String> = _deploymentCheck.asStateFlow()
    private var checkJob: Job? = null

    fun runDeploymentCheck() {
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch(Dispatchers.Default) {
            pipelineMutex.withLock {
                _deploymentCheck.value = "正在执行部署自检…"
                try {
                    val results = MeatDeploymentSelfCheck.run(context)
                    _deploymentCheck.value = "部署自检通过（${results.size}/12）"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { _deploymentCheck.value = "部署自检失败：${e.message}" }
            }
        }
    }

    fun refreshModelDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            _modelDiagnostics.value = OnnxModelInspector.inspectRequiredModels(context, workflowConfig, selectedCondition)
        }
    }

    /**
     * Run the five-stage pipeline.
     *
     * [testMode] enables the Pose-failure confirmation state. [forceWithoutPose] is only used after
     * that confirmation: the EXIF-normalized original bitmap is sent directly to Hydralisk and no
     * perspective rectification is performed.
     */
    fun startDetectionPreparation(
        testMode: Boolean = false,
        forceWithoutPose: Boolean = false,
    ) {
        val imageUri = _state.value.originalImageUriOrNull() ?: return
        if (_state.value is PipelineState.Processing) return
        if (forceWithoutPose && !testMode) {
            reportFailure("跳过定位仅限测试界面")
            return
        }
        val condition = selectedCondition ?: run {
            reportFailure("请先选择肉类和储存温度")
            return
        }
        val activeConfig = _runtimeParameters.value.applyTo(workflowConfig)

        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            pipelineMutex.withLock {
            var normalizedBitmap: Bitmap? = null
            var rectifiedBitmap: Bitmap? = null
            var poseDebugBitmap: Bitmap? = null
            var segmentationDebugBitmap: Bitmap? = null
            var rainbowDebugBitmap: Bitmap? = null
            try {
                val phase1Start = beginPhase(
                    imageUri = imageUri,
                    phase = DetectionPhase.PREPARING_AND_POSE,
                    stage = ProcessingStage.NORMALIZING_ORIENTATION,
                )
                val prepared = withContext(Dispatchers.IO) {
                    ImageNormalizer.prepare(context, Uri.parse(imageUri))
                }
                prewarmJob?.join()
                if (!modelsPrewarmedSuccessfully) {
                    val diagnostics = _modelDiagnostics.value.takeIf { it.isNotEmpty() }
                        ?: withContext(Dispatchers.IO) {
                            OnnxModelInspector.inspectUpstream(context, workflowConfig)
                        }.also { _modelDiagnostics.value = it }

                    if (!diagnostics.all { it.status == ModelStatus.LOADABLE }) {
                        _state.value = PipelineState.WaitingForModels(
                            imageUri = imageUri,
                            preparedImage = prepared,
                            diagnostics = diagnostics,
                        )
                        return@withLock
                    }
                }

                normalizedBitmap = withContext(Dispatchers.IO) {
                    PipelineBitmapIO.decode(context, prepared.normalizedUri)
                }

                var pose: PancakePoseResult? = null
                var poseDebugUri: String? = null
                if (!forceWithoutPose) {
                    updateInternalStage(prepared.normalizedUri, ProcessingStage.RUNNING_PANCAKE)
                    try {
                        pose = withContext(Dispatchers.Default) {
                            getPoseEngine().infer(requireNotNull(normalizedBitmap), activeConfig.pose)
                        }
                        require(requireNotNull(pose).keypoints.size >= 4) { "检测卡定位模型返回的关键点不足 4 个" }
                    } catch (exception: Exception) {
                        if (exception is CancellationException) throw exception
                        if (testMode) {
                            _state.value = PipelineState.PoseFallbackPrompt(
                                imageUri = prepared.originalUri,
                                message = exception.message ?: "检测卡定位模型检测失败",
                            )
                            return@withLock
                        }
                        throw exception
                    }

                    poseDebugBitmap = withContext(Dispatchers.Default) {
                        PoseDebugRenderer.render(requireNotNull(normalizedBitmap), requireNotNull(pose))
                    }
                    poseDebugUri = withContext(Dispatchers.IO) {
                        PipelineBitmapIO.saveJpeg(
                            context = context,
                            bitmap = requireNotNull(poseDebugBitmap),
                            prefix = "pancake_pose",
                            quality = activeConfig.rectification.jpegQuality,
                        )
                    }
                }
                finishPhase(DetectionPhase.PREPARING_AND_POSE, phase1Start)

                val phase2Start = beginPhase(
                    imageUri = prepared.normalizedUri,
                    phase = DetectionPhase.RECTIFICATION,
                    stage = ProcessingStage.RECTIFYING,
                )
                val rectifiedUri: String
                if (forceWithoutPose) {
                    rectifiedBitmap = requireNotNull(normalizedBitmap).copy(Bitmap.Config.ARGB_8888, false)
                    rectifiedUri = prepared.normalizedUri
                } else {
                    val rectification = try {
                        withContext(Dispatchers.Default) {
                            PerspectiveRectifier.rectify(
                                sourceBitmap = requireNotNull(normalizedBitmap),
                                orderedCorners = requireNotNull(pose).keypoints.take(4).map { it.point },
                                config = activeConfig.rectification,
                            )
                        }
                    } catch (exception: Exception) {
                        if (exception is CancellationException) throw exception
                        if (testMode) {
                            _state.value = PipelineState.PoseFallbackPrompt(
                                imageUri = prepared.originalUri,
                                message = "检测卡定位结果无法完成透视矫正：${exception.message ?: "未知错误"}",
                            )
                            return@withLock
                        }
                        throw exception
                    }
                    rectifiedBitmap = rectification.bitmap
                    rectifiedUri = withContext(Dispatchers.IO) {
                        PipelineBitmapIO.saveJpeg(
                            context = context,
                            bitmap = requireNotNull(rectifiedBitmap),
                            prefix = "rectified",
                            quality = activeConfig.rectification.jpegQuality,
                        )
                    }
                }
                finishPhase(DetectionPhase.RECTIFICATION, phase2Start)

                val phase3Start = beginPhase(
                    imageUri = rectifiedUri,
                    phase = DetectionPhase.SEGMENTATION,
                    stage = ProcessingStage.RUNNING_HYDRALISK,
                )
                val candidates = withContext(Dispatchers.Default) {
                    getSegEngine().infer(requireNotNull(rectifiedBitmap), activeConfig.segmentation)
                }
                val counts = candidates
                    .groupingBy { candidate ->
                        activeConfig.segmentation.classNames.getOrElse(candidate.classId) {
                            "class_${candidate.classId}"
                        }
                    }
                    .eachCount()
                    .toSortedMap()
                segmentationDebugBitmap = withContext(Dispatchers.Default) {
                    SegmentationDebugRenderer.render(
                        source = requireNotNull(rectifiedBitmap),
                        candidates = candidates,
                        classNames = activeConfig.segmentation.classNames,
                    )
                }
                val segmentationDebugUri = withContext(Dispatchers.IO) {
                    PipelineBitmapIO.saveJpeg(
                        context = context,
                        bitmap = requireNotNull(segmentationDebugBitmap),
                        prefix = "hydralisk_seg",
                        quality = activeConfig.rectification.jpegQuality,
                    )
                }
                finishPhase(DetectionPhase.SEGMENTATION, phase3Start)

                val phase4Start = beginPhase(
                    imageUri = rectifiedUri,
                    phase = DetectionPhase.RAINBOW,
                    stage = ProcessingStage.RUNNING_RAINBOW,
                )
                val rainbow = withContext(Dispatchers.Default) {
                    getRainbowEngine(activeConfig).process(requireNotNull(rectifiedBitmap), candidates)
                }
                rainbowDebugBitmap = rainbow.debugBitmap
                val debugUri = withContext(Dispatchers.IO) {
                    PipelineBitmapIO.saveJpeg(
                        context = context,
                        bitmap = requireNotNull(rainbowDebugBitmap),
                        prefix = "catch_the_rainbow_debug",
                        quality = activeConfig.rectification.jpegQuality,
                    )
                }

                val regressionImages = withContext(Dispatchers.Default) { RoiImagePreprocessor.prepare(rainbow.rawRois) }
                val inputUris = withContext(Dispatchers.IO) {
                    fun save(image: RgbImage, prefix: String): String {
                        val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
                        return try { PipelineBitmapIO.savePng(context, bitmap, prefix) } finally { bitmap.recycle() }
                    }
                    buildList {
                        if (testMode) rainbow.rawRois.forEach { add(StageOutputImage("${it.name} 原始 ROI", save(it.image, "raw_${it.name}"))) }
                        add(StageOutputImage("九区域校正拼图 192×192", save(regressionImages.mosaic192, "mosaic192")))
                        add(StageOutputImage("模型实际输入 224×224", save(regressionImages.input224, "input224")))
                    }
                }
                val debugBundleUri = if (testMode) withContext(Dispatchers.IO) {
                    RegressionDebugExporter.export(context, rainbow.rawRois, regressionImages, condition, rainbow.qualityFlags, rainbow.geometryJson, requireNotNull(rectifiedBitmap))
                } else null
                val stageImages = if (forceWithoutPose) {
                    listOf(
                        StageOutputImage("方向校正", prepared.normalizedUri),
                        StageOutputImage("检测卡定位模型（已强制跳过）", prepared.normalizedUri),
                        StageOutputImage("未透视矫正", prepared.normalizedUri),
                        StageOutputImage("传感区域分割模型", segmentationDebugUri),
                        StageOutputImage("颜色提取与校正模块", debugUri),
                    )
                } else {
                    listOf(
                        StageOutputImage("方向校正", prepared.normalizedUri),
                        StageOutputImage("检测卡定位模型", requireNotNull(poseDebugUri)),
                        StageOutputImage("透视矫正", rectifiedUri),
                        StageOutputImage("传感区域分割模型", segmentationDebugUri),
                        StageOutputImage("颜色提取与校正模块", debugUri),
                    )
                }


                finishPhase(DetectionPhase.RAINBOW, phase4Start)
                val allStageImages = stageImages + inputUris
                val phase5Start = beginPhase(
                    imageUri = inputUris.last().imageUri,
                    phase = DetectionPhase.REGRESSION,
                    stage = ProcessingStage.RUNNING_REGRESSION,
                )
                val prediction = try {
                    withContext(Dispatchers.Default) { getImageRegressor().predict(regressionImages, condition) }
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                    val diagnostics = withContext(Dispatchers.IO) { OnnxModelInspector.inspectRegression(context, activeConfig, condition) }
                    _modelDiagnostics.value = diagnostics
                    if (diagnostics.any { it.status != ModelStatus.LOADABLE }) {
                        _state.value = PipelineState.WaitingForModels(imageUri, prepared, diagnostics, allStageImages, condition, debugBundleUri)
                        return@withLock
                    }
                    throw exception
                }
                finishPhase(DetectionPhase.REGRESSION, phase5Start)

                _state.value = PipelineState.Success(
                    originalImageUri = prepared.originalUri,
                    stageImages = allStageImages,
                    preparedImage = prepared,
                    poseConfidence = pose?.confidence ?: Float.NaN,
                    keypointConfidences = pose?.keypoints?.map { it.confidence }.orEmpty(),
                    candidateCount = candidates.size,
                    candidateCountsByClass = counts,
                    rainbowGrid = rainbow.grid,
                    correctedColors = rainbow.colors,
                    rainbowQualityFlags = rainbow.qualityFlags,
                    prediction = prediction,
                    roiGains = rainbow.rawRois.associate { it.name to it.gains.toList() },
                    debugBundleUri = debugBundleUri,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                _state.value = PipelineState.Failure(
                    imageUri = imageUri,
                    message = exception.message ?: "检测流水线失败",
                )
            } finally {
                normalizedBitmap?.recycle()
                poseDebugBitmap?.recycle()
                rectifiedBitmap?.recycle()
                segmentationDebugBitmap?.recycle()
                rainbowDebugBitmap?.recycle()
            }
            }
        }
    }

    fun declinePoseFallback() {
        val prompt = _state.value as? PipelineState.PoseFallbackPrompt ?: return
        _state.value = PipelineState.Failure(
            imageUri = prompt.imageUri,
            message = "检测卡定位模型检测失败：${prompt.message}",
        )
    }

    private fun beginPhase(
        imageUri: String,
        phase: DetectionPhase,
        stage: ProcessingStage,
    ): Long {
        val started = SystemClock.elapsedRealtime()
        _state.value = PipelineState.Processing(
            imageUri = imageUri,
            stage = stage,
            phase = phase,
            phaseStartedElapsedRealtimeMs = started,
            estimatedPhaseDurationMs = stageTimingStore.estimatedDurationMs(phase),
        )
        return started
    }

    private fun updateInternalStage(imageUri: String, stage: ProcessingStage) {
        val current = _state.value as? PipelineState.Processing ?: return
        _state.value = current.copy(imageUri = imageUri, stage = stage)
    }

    private fun finishPhase(phase: DetectionPhase, startedAt: Long) {
        stageTimingStore.record(phase, SystemClock.elapsedRealtime() - startedAt)
    }

    private fun getPoseEngine(): PancakePoseEngine = synchronized(engineLock) {
        poseEngine ?: PancakePoseEngine(context, workflowConfig.pose).also { poseEngine = it }
    }

    private fun getSegEngine(): HydraliskSegEngine = synchronized(engineLock) {
        segEngine ?: HydraliskSegEngine(context, workflowConfig.segmentation).also { segEngine = it }
    }

    private fun getRainbowEngine(config: WorkflowConfig): CatchTheRainbowEngine = synchronized(engineLock) {
        if (rainbowEngine == null || rainbowEngineConfig != config.rainbow) {
            rainbowEngine = CatchTheRainbowEngine(config.rainbow)
            rainbowEngineConfig = config.rainbow
        }
        requireNotNull(rainbowEngine)
    }

    private fun getImageRegressor(): YoloImageRegressor = synchronized(engineLock) {
        imageRegressor ?: YoloImageRegressor(context, workflowConfig.regression).also { imageRegressor = it }
    }

    fun cancelDetection() {
        processingJob?.cancel()
        processingJob = null
        val imageUri = _state.value.originalImageUriOrNull()
        _state.value = if (imageUri == null) PipelineState.Idle else PipelineState.Ready(imageUri)
    }

    fun reset() {
        processingJob?.cancel()
        processingJob = null
        _cropEditState.value = null
        _state.value = PipelineState.Idle
    }

    fun reportFailure(message: String) {
        processingJob?.cancel()
        _state.value = PipelineState.Failure(
            imageUri = _state.value.originalImageUriOrNull(),
            message = message,
        )
    }

    override fun onCleared() {
        val running = processingJob
        val checking = checkJob
        checking?.cancel()
        val warming = prewarmJob
        running?.cancel()
        warming?.cancel()
        // Native ONNX calls may finish after cancellation. Close sessions only after they return.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            running?.join()
            warming?.join()
            checking?.join()
            synchronized(engineLock) {
                poseEngine?.close(); poseEngine = null
                segEngine?.close(); segEngine = null
                rainbowEngine = null
                imageRegressor?.close(); imageRegressor = null
            }
        }
        super.onCleared()
    }
}

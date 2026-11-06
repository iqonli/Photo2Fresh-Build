package com.iqonli.photo2fresh.inference

import ai.onnxruntime.OnnxTensor
import android.content.Context
import android.graphics.Bitmap
import com.iqonli.photo2fresh.config.PoseConfig
import java.io.Closeable

class PancakePoseEngine(
    context: Context,
    private val baseConfig: PoseConfig,
) : Closeable {
    private val session = YoloOnnxSession(
        context = context,
        assetPath = baseConfig.model.assetPath,
        inputSize = baseConfig.model.inputSize,
        padValue = baseConfig.model.letterboxValue,
    )

    fun infer(bitmap: Bitmap, config: PoseConfig = baseConfig): PancakePoseResult = session.run(bitmap) { result, transform ->
        require(result.size() == 1) { "检测卡定位模型应有一个输出，实际 ${result.size()}" }
        val tensor = result.get(0) as? OnnxTensor ?: error("检测卡定位模型 output0 不是张量")
        val shape = tensor.tensorShape()
        require(shape.contentEquals(longArrayOf(1, 300, 21))) {
            "检测卡定位模型输出形状应为 [1,300,21]，实际 ${shape.contentToString()}"
        }
        YoloEndToEndDecoder.decodePose(
            data = tensor.copyFloatArray(),
            transform = transform,
            confidenceThreshold = config.model.confidence,
            keypointCount = config.keypointCount,
        )
    }

    override fun close() = session.close()
}

package com.iqonli.photo2fresh.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable

class YoloOnnxSession(
    context: Context,
    assetPath: String,
    private val inputSize: Int,
    private val padValue: Int = 114,
) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions()
    private val session: OrtSession
    val inputName: String

    init {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
session = environment.createSession(bytes, sessionOptions)
        require(session.inputNames.size == 1) {
            "当前 YOLO 接口只支持单输入模型，实际为 ${session.inputNames}"
        }
        inputName = session.inputNames.first()
    }

    fun <T> run(
        bitmap: Bitmap,
        consume: (result: OrtSession.Result, transform: LetterboxResult) -> T,
    ): T {
        val transform = YoloPreprocessor.bitmapToNchwFloat(bitmap, inputSize, padValue)
        OnnxTensor.createTensor(
            environment,
            transform.tensor,
            longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()),
        ).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { result ->
                return consume(result, transform)
            }
        }
    }

    override fun close() {
        session.close()
        sessionOptions.close()
    }
}

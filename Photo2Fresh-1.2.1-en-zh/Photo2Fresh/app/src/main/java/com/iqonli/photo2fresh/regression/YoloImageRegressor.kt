package com.iqonli.photo2fresh.regression

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** One frozen frontend and at most one condition-specific pair of independent task tails. */
class YoloImageRegressor(private val context: Context, config: RegressionConfig) : Closeable {
    private val bundle = RegressionBundle.load(context, config)
    private val environment = OrtEnvironment.getEnvironment()
    private var closed = false
    private var frontend: LoadedGraph? = null
    private var currentCondition: RegressionCondition? = null
    private var currentRoute: LoadedGraph? = null

    @Synchronized
    fun predict(images: RegressionImages, condition: RegressionCondition): FreshnessPrediction {
        check(!closed) { "回归引擎已关闭" }
        if (frontend == null) frontend = load(bundle.frontend, longArrayOf(1, 3, 224, 224), listOf(longArrayOf(1, 512, 7, 7)))
        if (currentCondition != condition || currentRoute == null) {
            val next = load(bundle.routes.getValue(condition), longArrayOf(1, 512, 7, 7), listOf(longArrayOf(1, 2)))
            currentRoute?.close()
            currentRoute = next
            currentCondition = condition
        }
        val front = requireNotNull(frontend)
        val route = requireNotNull(currentRoute)
        val values = images.nchw()
        val buffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(values).rewind()
        OnnxTensor.createTensor(environment, buffer, longArrayOf(1, 3, 224, 224)).use { image ->
            front.session.run(mapOf(front.spec.inputName to image)).use { frontResult ->
                val feature = frontResult.get(front.spec.outputNames.single()).orElseThrow { IllegalStateException("缺少公共前端输出") } as OnnxTensor
                route.session.run(mapOf(route.spec.inputName to feature)).use { result ->
                    val tensor = result.get(route.spec.outputNames.single()).orElseThrow { IllegalStateException("缺少 prediction 输出") } as OnnxTensor
                    val data = requireNotNull(tensor.floatBuffer)
                    require(data.remaining() == 2) { "回归输出必须是 [1,2]" }
                    // Physical units and Softplus are already embedded in the deployed graph.
                    return FreshnessPrediction(condition, data.get(), data.get(), bundle.modelVersion)
                }
            }
        }
    }

    private fun load(spec: RegressionGraph, inputShape: LongArray, outputShapes: List<LongArray>): LoadedGraph {
        require(spec.sha256.matches(Regex("[0-9a-f]{64}"))) { "模型清单尚未填写 SHA-256：${spec.assetPath}" }
        val bytes = context.assets.open(spec.assetPath).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash == spec.sha256) { "模型文件与清单校验不符：${spec.assetPath}" }
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            val session = environment.createSession(bytes, options)
            try {
                require(session.inputNames == setOf(spec.inputName)) { "回归输入节点与清单不符" }
                require(session.outputNames.toSet() == spec.outputNames.toSet() && spec.outputNames.size == outputShapes.size) { "回归输出节点与清单不符" }
                fun verify(info: Any?, shape: LongArray) {
                    val tensor = info as? TensorInfo ?: error("回归节点不是张量")
                    require(tensor.type == OnnxJavaType.FLOAT && tensor.shape.contentEquals(shape)) { "回归节点类型或形状不符：$tensor" }
                }
                verify(session.inputInfo.getValue(spec.inputName).info, inputShape)
                spec.outputNames.forEachIndexed { i, name -> verify(session.outputInfo.getValue(name).info, outputShapes[i]) }
                return LoadedGraph(spec, session, options)
            } catch (e: Exception) { session.close(); throw e }
        } catch (e: Exception) { options.close(); throw e }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        currentRoute?.close(); currentRoute = null; currentCondition = null
        frontend?.close(); frontend = null
    }

    private data class LoadedGraph(val spec: RegressionGraph, val session: OrtSession, val options: OrtSession.SessionOptions) : Closeable {
        override fun close() { session.close(); options.close() }
    }
}

package com.iqonli.photo2fresh.inference

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import com.iqonli.photo2fresh.config.WorkflowConfig

object OnnxModelInspector {
    fun inspectUpstream(context: Context, config: WorkflowConfig): List<ModelDiagnostic> = listOf(
        inspect(context, "检测卡定位模型", config.pose.model.assetPath),
        inspect(context, "传感区域分割模型", config.segmentation.model.assetPath),
    )

    fun inspectRequiredModels(
        context: Context,
        config: WorkflowConfig,
        condition: com.iqonli.photo2fresh.regression.RegressionCondition? = null,
    ): List<ModelDiagnostic> = inspectUpstream(context, config) + inspectRegression(context, config, condition)

    fun inspectRegression(
        context: Context,
        config: WorkflowConfig,
        condition: com.iqonli.photo2fresh.regression.RegressionCondition? = null,
    ): List<ModelDiagnostic> = try {
        val bundle = com.iqonli.photo2fresh.regression.RegressionBundle.load(context, config.regression)
        val graphs = listOf("公共图像特征网络" to bundle.frontend) + bundle.routes.entries
            .filter { condition == null || it.key == condition }.map { "${it.key.label}回归模型" to it.value }
        graphs.map { (name, graph) ->
            val model = inspect(context, name, graph.assetPath)
            if (model.status != ModelStatus.LOADABLE) model else try {
                require(graph.sha256.matches(Regex("[0-9a-f]{64}"))) { "缺少模型 SHA-256" }
                val bytes = context.assets.open(graph.assetPath).use { it.readBytes() }
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                require(hash == graph.sha256) { "模型文件哈希与清单不符" }
                require(model.inputs.map { it.name } == listOf(graph.inputName)) { "模型输入节点不符" }
                require(model.outputs.map { it.name }.toSet() == graph.outputNames.toSet()) { "模型输出节点不符" }
                model
            } catch (e: Exception) { model.copy(status = ModelStatus.INVALID, error = e.message) }
        }
    } catch (e: Exception) {
        listOf(ModelDiagnostic("六工况回归清单", config.regression.manifestAssetPath, ModelStatus.INVALID, error = e.message))
    }

    private fun inspect(context: Context, displayName: String, assetPath: String): ModelDiagnostic {
        if (!assetExists(context, assetPath)) {
            return ModelDiagnostic(displayName, assetPath, ModelStatus.MISSING)
        }

        return try {
            val modelBytes = context.assets.open(assetPath).use { it.readBytes() }
            val environment = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { options ->
                environment.createSession(modelBytes, options).use { session ->
                    ModelDiagnostic(
                        displayName = displayName,
                        assetPath = assetPath,
                        status = ModelStatus.LOADABLE,
                        inputs = session.inputInfo.map { (name, info) -> info.toDescriptor(name) },
                        outputs = session.outputInfo.map { (name, info) -> info.toDescriptor(name) },
                    )
                }
            }
        } catch (exception: Exception) {
            ModelDiagnostic(
                displayName = displayName,
                assetPath = assetPath,
                status = ModelStatus.INVALID,
                error = exception.message ?: exception.javaClass.simpleName,
            )
        }
    }

    private fun NodeInfo.toDescriptor(name: String): TensorDescriptor {
        val tensor = info as? TensorInfo
        return if (tensor == null) {
            TensorDescriptor(name, "非张量", info.toString())
        } else {
            TensorDescriptor(
                name = name,
                shape = tensor.shape.joinToString(prefix = "[", postfix = "]"),
                type = tensor.type.toString(),
            )
        }
    }

    private fun assetExists(context: Context, assetPath: String): Boolean {
        val slash = assetPath.lastIndexOf('/')
        val folder = if (slash >= 0) assetPath.substring(0, slash) else ""
        val name = if (slash >= 0) assetPath.substring(slash + 1) else assetPath
        return context.assets.list(folder)?.contains(name) == true
    }
}

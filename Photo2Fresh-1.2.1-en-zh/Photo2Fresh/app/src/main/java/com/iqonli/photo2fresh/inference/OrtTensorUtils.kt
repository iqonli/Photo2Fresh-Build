package com.iqonli.photo2fresh.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo

internal fun OnnxTensor.copyFloatArray(): FloatArray {
    val source = requireNotNull(floatBuffer) { "ONNX 张量无法读取为 float" }.duplicate()
    source.rewind()
    return FloatArray(source.remaining()).also(source::get)
}

internal fun OnnxTensor.tensorShape(): LongArray {
    return (info as? TensorInfo)?.shape
        ?: error("ONNX 输出不是普通张量")
}

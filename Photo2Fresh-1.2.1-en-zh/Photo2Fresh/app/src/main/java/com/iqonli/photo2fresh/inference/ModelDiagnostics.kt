package com.iqonli.photo2fresh.inference

data class TensorDescriptor(
    val name: String,
    val shape: String,
    val type: String,
)

data class ModelDiagnostic(
    val displayName: String,
    val assetPath: String,
    val status: ModelStatus,
    val inputs: List<TensorDescriptor> = emptyList(),
    val outputs: List<TensorDescriptor> = emptyList(),
    val error: String? = null,
)

enum class ModelStatus {
    MISSING,
    LOADABLE,
    INVALID,
}

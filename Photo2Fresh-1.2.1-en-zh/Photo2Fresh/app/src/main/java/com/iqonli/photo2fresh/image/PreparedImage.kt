package com.iqonli.photo2fresh.image

data class PreparedImage(
    val originalUri: String,
    val normalizedUri: String,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val exifOrientation: Int,
    val decodeSampleSize: Int,
)

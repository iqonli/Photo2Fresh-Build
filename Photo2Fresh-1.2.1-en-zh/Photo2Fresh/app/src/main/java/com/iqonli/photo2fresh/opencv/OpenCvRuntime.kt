package com.iqonli.photo2fresh.opencv

import org.opencv.android.OpenCVLoader

object OpenCvRuntime {
    @Volatile
    var initialized: Boolean = false
        private set

    @Volatile
    var error: String? = null
        private set

    fun initialize() {
        if (initialized) return
        try {
            initialized = OpenCVLoader.initLocal()
            if (!initialized) error = "OpenCVLoader.initLocal() 返回 false"
        } catch (exception: Throwable) {
            initialized = false
            error = exception.message ?: exception.javaClass.simpleName
        }
    }
}

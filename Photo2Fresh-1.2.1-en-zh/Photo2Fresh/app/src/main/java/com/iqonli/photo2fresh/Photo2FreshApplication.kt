package com.iqonli.photo2fresh

import android.app.Application
import com.iqonli.photo2fresh.opencv.OpenCvRuntime

class Photo2FreshApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OpenCvRuntime.initialize()
    }
}

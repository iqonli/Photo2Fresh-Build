package com.iqonli.photo2fresh

import android.os.Bundle
import com.iqonli.photo2fresh.i18n.AppLanguage
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.iqonli.photo2fresh.ui.Photo2FreshApp
import com.iqonli.photo2fresh.ui.theme.Photo2freshTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) AppLanguage.reset()
        enableEdgeToEdge()
        setContent {
            Photo2freshTheme {
                Photo2FreshApp()
            }
        }
    }
    override fun onDestroy() {
        if (isFinishing) AppLanguage.reset()
        super.onDestroy()
    }
}

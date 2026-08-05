package io.github.gordonlu.lumadepth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.gordonlu.lumadepth.ui.LumaDepthApp
import io.github.gordonlu.lumadepth.ui.theme.LumaDepthTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LumaDepthTheme {
                LumaDepthApp()
            }
        }
    }
}

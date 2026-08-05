package io.github.gordonlu.lumadepth.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.gordonlu.lumadepth.ui.editor.EditorScreen
import io.github.gordonlu.lumadepth.ui.home.HomeScreen

private enum class AppScreen { HOME, EDITOR }

@Composable
fun LumaDepthApp() {
    var screen by rememberSaveable { mutableStateOf(AppScreen.HOME) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    when (screen) {
        AppScreen.HOME -> HomeScreen(
            onPhotoPicked = { uri ->
                selectedUri = uri
                screen = AppScreen.EDITOR
            },
        )
        AppScreen.EDITOR -> {
            val uri = selectedUri
            if (uri != null) {
                EditorScreen(
                    uri = uri,
                    onBack = { screen = AppScreen.HOME },
                )
            } else {
                HomeScreen(onPhotoPicked = {})
            }
        }
    }
}

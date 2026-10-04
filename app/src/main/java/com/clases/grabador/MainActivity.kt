package com.clases.grabador

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

sealed class Screen {
    object Library : Screen()
    object Recorder : Screen()
    data class Player(val rel: String) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

@Composable
fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    var folder by remember { mutableStateOf("") }

    BackHandler(enabled = screen !is Screen.Library || folder.isNotEmpty()) {
        if (screen !is Screen.Library) screen = Screen.Library else folder = ""
    }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            when (val s = screen) {
                is Screen.Library -> LibraryScreen(
                    folder = folder,
                    onFolder = { folder = it },
                    onRecord = { screen = Screen.Recorder },
                    onPlay = { screen = Screen.Player(it) }
                )
                is Screen.Recorder -> RecorderScreen(
                    initialFolder = folder,
                    onBack = { target -> folder = target; screen = Screen.Library }
                )
                is Screen.Player -> PlayerScreen(rel = s.rel, onBack = { screen = Screen.Library })
            }
        }
    }
}

package com.photoai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.photoai.app.ui.screens.EditScreen
import com.photoai.app.ui.screens.LandingScreen
import com.photoai.app.ui.screens.PromptsEditorScreen
import com.photoai.app.ui.screens.SettingsScreen
import com.photoai.app.ui.theme.PhotoAITheme
import com.photoai.app.ui.viewmodel.MainViewModel
import com.photoai.app.ui.viewmodel.Screen

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loadProcessingPreferences(this)
        setContent {
            PhotoAITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {

                    when (val screen = viewModel.currentScreen.value) {
                        is Screen.Landing -> {
                            LandingScreen(
                                onImageSelected = { uri -> 
                                    uri?.let { viewModel.setSelectedImage(it) }
                                }
                            )
                        }
                        is Screen.Edit -> {
                            EditScreen(
                                imageUri = screen.imageUri,
                                onSettingsClick = { viewModel.navigateToSettings() },
                                onBackClick = { viewModel.navigateBack() },
                                viewModel = viewModel
                            )
                        }
                        is Screen.Settings -> {
                            SettingsScreen(
                                onBackClick = { viewModel.navigateBack() },
                                onEditPromptsClick = { viewModel.navigateToPromptsEditor() },
                                viewModel = viewModel
                            )
                        }
                        is Screen.PromptsEditor -> {
                            PromptsEditorScreen(
                                onBackClick = { viewModel.navigateBack() },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

package com.photoai.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photoai.app.ui.screens.*
import com.photoai.app.ui.theme.PhotoAITheme
import com.photoai.app.ui.viewmodel.MainViewModel

sealed class Screen {
    object Landing : Screen()
    data class Edit(val imageUri: Uri) : Screen()
    data class Result(val originalUri: Uri, val editedUrl: String) : Screen()
    object Settings : Screen()
    object PromptsEditor : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhotoAITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: MainViewModel = viewModel()
                    var currentScreen by remember { mutableStateOf<Screen>(Screen.Landing) }

                    // Monitor editedImageUrl and navigate to Result screen when it becomes available
                    LaunchedEffect(viewModel.editedImageUrl.value) {
                        viewModel.editedImageUrl.value?.let { editedUrl ->
                            if (currentScreen is Screen.Edit) {
                                val imageUri = (currentScreen as Screen.Edit).imageUri
                                currentScreen = Screen.Result(imageUri, editedUrl)
                            }
                        }
                    }
                    
                    when (val screen = currentScreen) {
                        is Screen.Landing -> {
                            LandingScreen(
                                onImageSelected = { uri ->
                                    uri?.let {
                                        viewModel.setSelectedImage(it)
                                        currentScreen = Screen.Edit(it)
                                    }
                                }
                            )
                        }
                        is Screen.Edit -> {
                            EditScreen(
                                imageUri = screen.imageUri,
                                onSettingsClick = { currentScreen = Screen.Settings },
                                onBackClick = { currentScreen = Screen.Landing },
                                viewModel = viewModel
                            )
                        }
                        is Screen.Result -> {
                            ResultScreen(
                                originalImageUri = screen.originalUri,
                                editedImageUrl = screen.editedUrl,
                                onBackClick = { currentScreen = Screen.Edit(screen.originalUri) },
                                onSave = { bitmap -> viewModel.saveEditedImage(this, bitmap) },
                                onShare = { viewModel.shareEditedImage() }
                            )
                        }
                        is Screen.Settings -> {
                            SettingsScreen(
                                onBackClick = {
                                    currentScreen = when {
                                        viewModel.selectedImageUri.value != null -> Screen.Edit(viewModel.selectedImageUri.value!!)
                                        else -> Screen.Landing
                                    }
                                },
                                onEditPromptsClick = { currentScreen = Screen.PromptsEditor },
                                viewModel = viewModel
                            )
                        }
                        is Screen.PromptsEditor -> {
                            PromptsEditorScreen(
                                onBackClick = { currentScreen = Screen.Settings },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

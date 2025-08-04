package com.photoai.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import coil.compose.rememberAsyncImagePainter
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.photoai.app.ui.viewmodel.MainViewModel
import com.photoai.app.utils.createImageFile
import com.photoai.app.utils.uriToBitmap
import com.photoai.app.utils.urlToBitmap
import com.photoai.app.utils.PromptsLoader
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Observe ViewModel state
    val selectedImageUri by viewModel.selectedImageUri
    val customPrompt by viewModel.customPrompt
    val editedImageUrl by viewModel.editedImageUrl
    val isProcessing by viewModel.isProcessing
    val errorMessage by viewModel.errorMessage
    val showOriginal by viewModel.showOriginal
    val saveMessage by viewModel.saveMessage
    
    // Camera permission
    val cameraPermissionState = rememberPermissionState(
        android.Manifest.permission.CAMERA
    )
    
    // Photo capture
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            if (success && photoUri != null) {
                viewModel.setSelectedImage(photoUri)
            }
        }
    )
    
    // Gallery picker
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            viewModel.setSelectedImage(uri)
        }
    )
    
    // Load category names from JSON resource
    val categoryNames = remember { PromptsLoader.getCategoryNames() }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Title
        Text(
            text = "Photo AI Assistant",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        // Image selection buttons
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Select or Capture Image",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Camera button
                    OutlinedButton(
                        onClick = {
                            if (cameraPermissionState.status.isGranted) {
                                val imageFile = createImageFile(context)
                                photoUri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    imageFile
                                )
                                cameraLauncher.launch(photoUri)
                            } else {
                                cameraPermissionState.launchPermissionRequest()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Camera")
                    }
                    
                    // Gallery button
                    OutlinedButton(
                        onClick = {
                            galleryLauncher.launch("image/*")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Gallery")
                    }
                }
                
                // Camera permission rationale
                if (cameraPermissionState.status.shouldShowRationale) {
                    Text(
                        text = "Camera permission is needed to take photos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        // Selected image display with toggle and save functionality
        selectedImageUri?.let { uri ->
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (showOriginal) "Original Image" else "Edited Image",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        
                        // Toggle and Save buttons
                        if (editedImageUrl != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Toggle button
                                OutlinedButton(
                                    onClick = { viewModel.toggleImageView() },
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text(
                                        text = if (showOriginal) "Show Edited" else "Show Original",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                
                                // Save button (only show for edited image)
                                if (!showOriginal) {
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                val bitmap = urlToBitmap(editedImageUrl!!)
                                                bitmap?.let {
                                                    viewModel.saveEditedImage(context, it)
                                                }
                                            }
                                        },
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Text(
                                            text = "Save",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Display the appropriate image
                    val imageToShow = if (showOriginal || editedImageUrl == null) uri.toString() else editedImageUrl!!
                    
                    // Debug logging
                    if (editedImageUrl != null && !showOriginal) {
                        android.util.Log.d("MainScreen", "Displaying edited image URL (first 100 chars): ${editedImageUrl!!.take(100)}")
                        android.util.Log.d("MainScreen", "Is data URL: ${editedImageUrl!!.startsWith("data:image/")}")
                    }
                    
                    Image(
                        painter = rememberAsyncImagePainter(
                            model = imageToShow,
                            onError = { error ->
                                android.util.Log.e("MainScreen", "Image loading error: ${error.result.throwable}")
                            }
                        ),
                        contentDescription = if (showOriginal) "Original image" else "Edited image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
        
        // AI Prompts section
        if (selectedImageUri != null) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "AI Image Editor",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    // Category selector
                    Text(
                        text = "Category:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    
                    // State for category and prompt selection
                    var expandedCategory by remember { mutableStateOf(false) }
                    var selectedCategory by remember { mutableStateOf("Select a category...") }
                    var expandedDropdown by remember { mutableStateOf(false) }
                    var selectedPrompt by remember { mutableStateOf("Select a prompt...") }
                    
                    // Dropdown for category selection
                    ExposedDropdownMenuBox(
                        expanded = expandedCategory,
                        onExpandedChange = { expandedCategory = !expandedCategory },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedCategory,
                            onValueChange = { },
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCategory) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            label = { Text("Choose a category") }
                        )
                        
                        ExposedDropdownMenu(
                            expanded = expandedCategory,
                            onDismissRequest = { expandedCategory = false }
                        ) {
                            categoryNames.forEach { categoryName ->
                                DropdownMenuItem(
                                    text = { Text(categoryName) },
                                    onClick = {
                                        selectedCategory = categoryName
                                        expandedCategory = false
                                        // Reset prompt selection when category changes
                                        selectedPrompt = "Select a prompt..."
                                    }
                                )
                            }
                        }
                    }
                    
                    // Predefined prompts for selected category
                    Text(
                        text = "Available Prompts:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    
                    // Get prompts for the selected category
                    val categoryPrompts = remember(selectedCategory) {
                        if (selectedCategory != "Select a category...") {
                            PromptsLoader.getPromptsForCategory(context, selectedCategory)
                        } else {
                            emptyList()
                        }
                    }
                    
                    // Dropdown for prompts in selected category
                    ExposedDropdownMenuBox(
                        expanded = expandedDropdown,
                        onExpandedChange = { expandedDropdown = !expandedDropdown },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedPrompt,
                            onValueChange = { },
                            readOnly = true,
                            enabled = categoryPrompts.isNotEmpty(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            label = { Text(if (categoryPrompts.isNotEmpty()) "Choose a prompt" else "Select a category first") }
                        )
                        
                        ExposedDropdownMenu(
                            expanded = expandedDropdown,
                            onDismissRequest = { expandedDropdown = false }
                        ) {
                            categoryPrompts.forEach { promptCategory ->
                                DropdownMenuItem(
                                    text = { Text(promptCategory.name) },
                                    onClick = {
                                        selectedPrompt = promptCategory.name
                                        expandedDropdown = false
                                        selectedImageUri?.let { uri ->
                                            viewModel.editImage(context, uri, promptCategory.prompt)
                                        }
                                    },
                                    enabled = !isProcessing && selectedImageUri != null
                                )
                            }
                        }
                    }
                    
                    Divider()
                    
                    // Custom prompt
                    Text(
                        text = "Custom Edit Prompt:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    
                    OutlinedTextField(
                        value = customPrompt,
                        onValueChange = { viewModel.updateCustomPrompt(it) },
                        label = { Text("Enter your edit prompt (e.g., 'Turn this person into a dragon')") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                    
                    Button(
                        onClick = {
                            if (customPrompt.isNotBlank()) {
                                selectedImageUri?.let { uri ->
//                                    val bitmap = uriToBitmap(context, uri)
//                                    if (bitmap != null) {
//                                        viewModel.editImage(bitmap, customPrompt)
//                                    }
                                     viewModel.editImage(context, uri, customPrompt)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = customPrompt.isNotBlank() && !isProcessing && selectedImageUri != null
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isProcessing) "Processing..." else "Edit Image")
                    }
                }
            }
        }
        
        // Error Message
        errorMessage?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Error",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.clearError() }
                    ) {
                        Text("Dismiss")
                    }
                }
            }
        }
        
        // Save Success Message
        saveMessage?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Success",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.clearSaveMessage() }
                    ) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}

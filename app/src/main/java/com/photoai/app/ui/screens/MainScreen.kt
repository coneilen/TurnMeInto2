package com.photoai.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.photoai.app.ui.viewmodel.MainViewModel
import com.photoai.app.ui.viewmodel.CommandSuggestion
import com.photoai.app.utils.createImageFile
import com.photoai.app.utils.urlToBitmap
import com.photoai.app.utils.PromptsLoader
import com.photoai.app.data.PromptCategory
import kotlinx.coroutines.launch
import android.content.Intent
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import com.photoai.app.ui.theme.PastelPrimary
import com.photoai.app.ui.theme.PastelSecondary

@Composable
private fun CommandsDropdownMenu(
    commands: List<CommandSuggestion>,
    onCommandSelected: (CommandSuggestion) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                color = Color(0xFFFFFFFF).copy(alpha = 0.95f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier
                .width(250.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            commands.forEach { command ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            onCommandSelected(command)
                            onDismiss()
                        }
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = command.command,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF7FC7D9)
                    )
                    Text(
                        text = command.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8B7D6B)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HelpDialog(
    commands: List<CommandSuggestion>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        containerColor = Color(0xFFFFFFFF).copy(alpha = 0.95f),
        title = {
            Text(
                text = "📚 Available Commands",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8E7CC3)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                commands.forEach { command ->
                    Column {
                        Text(
                            text = command.command,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF7FC7D9)
                        )
                        Text(
                            text = command.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF8B7D6B)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFC7EACD)
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = "👍 Got it!",
                    color = Color(0xFF2D5016),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val currentPage by viewModel.currentPage
    val saveMessage by viewModel.saveMessage
    val downsizeImages by viewModel.downsizeImages
    val inputFidelity by viewModel.inputFidelity
    val quality by viewModel.quality
    
    // Load preferences on startup
    LaunchedEffect(Unit) {
        viewModel.loadProcessingPreferences(context)
    }
    
    // Navigation state
    var showPromptsEditor by remember { mutableStateOf(false) }
    
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
    var categoryNames by remember { mutableStateOf<List<String>>(emptyList()) }
    
    LaunchedEffect(Unit) {
        categoryNames = PromptsLoader.getCategoryNames(context)
    }
    
    // Share function
    fun shareEditedImage() {
        coroutineScope.launch {
            try {
                android.util.Log.d("MainScreen", "Starting share process...")
                val bitmap = urlToBitmap(editedImageUrl!!)
                bitmap?.let { bmp ->
                    android.util.Log.d("MainScreen", "Bitmap converted successfully: ${bmp.width}x${bmp.height}")
                    
                    // Create a temporary file to share
                    val shareFile = File(context.cacheDir, "shared_image_${System.currentTimeMillis()}.jpg")
                    android.util.Log.d("MainScreen", "Creating share file: ${shareFile.absolutePath}")
                    
                    val fos = FileOutputStream(shareFile)
                    val compressed = bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                    fos.close()
                    
                    if (!compressed) {
                        android.util.Log.e("MainScreen", "Failed to compress bitmap")
                        return@let
                    }
                    
                    android.util.Log.d("MainScreen", "File created successfully, size: ${shareFile.length()} bytes")
                    
                    // Create share intent
                    val shareUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        shareFile
                    )
                    
                    android.util.Log.d("MainScreen", "Share URI created: $shareUri")
                    
                    val shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        putExtra(Intent.EXTRA_TEXT, "Check out my AI-edited photo from Photo AI Assistant!")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    
                    // Verify that there are apps that can handle this intent
                    val packageManager = context.packageManager
                    val activities = packageManager.queryIntentActivities(shareIntent, 0)
                    
                    if (activities.isNotEmpty()) {
                        android.util.Log.d("MainScreen", "Found ${activities.size} apps that can handle share intent")
                        val chooserIntent = Intent.createChooser(shareIntent, "Share edited image")
                        context.startActivity(chooserIntent)
                    } else {
                        android.util.Log.e("MainScreen", "No apps found that can handle share intent")
                    }
                } ?: run {
                    android.util.Log.e("MainScreen", "Failed to convert URL to bitmap")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainScreen", "Error sharing image: ${e.message}", e)
                // You could show a toast or error message here
            }
        }
    }
    
    // Show PromptsEditorScreen if editing prompts
    if (showPromptsEditor) {
        PromptsEditorScreen(
            onBackClick = { showPromptsEditor = false },
            modifier = modifier
        )
        return
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFE8E4F3),
                        Color(0xFFD1C4E9),
                        Color(0xFFF8E1FF)
                    )
                )
            )
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Title with edit prompts button
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFFFFFFF).copy(alpha = 0.9f)
            ),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "🎨 Photo AI Assistant",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF8E7CC3),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { showPromptsEditor = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFB3BA)
                    ),
                    shape = RoundedCornerShape(25.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("✨ Edit Prompts", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
        
        // Image selection buttons
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFFFFFFF).copy(alpha = 0.95f)
            ),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "📸 Select or Capture Image",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF7FC7D9)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Camera button
                    Button(
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
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFA8D8EA)
                        ),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Camera", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    
                    // Gallery button
                    Button(
                        onClick = {
                            galleryLauncher.launch("image/*")
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFB5EAD7)
                        ),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Gallery", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                
                // Camera permission rationale
                if (cameraPermissionState.status.shouldShowRationale) {
                    Text(
                        text = "📷 Camera permission is needed to take photos",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFC09F)
                    )
                }
            }
        }
        
        // Selected image display with toggle and save functionality
        selectedImageUri?.let { uri ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFFFFF).copy(alpha = 0.95f)
                ),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    // Title for the panel
                    Text(
                    text = if (currentPage == 0) "📷 Original Image" else "✨ Edited Image",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (currentPage == 0) Color(0xFF7FC7D9) else Color(0xFFFFB3BA),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    )
                    
                // Action buttons
                if (editedImageUrl != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.handleChatCommand(context, "/share") },
                            modifier = Modifier
                                .weight(1f)
                                .height(45.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD4ADFC)
                            ),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Share",
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share", color = Color.White, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { viewModel.handleChatCommand(context, "/save") },
                            modifier = Modifier
                                .weight(1f)
                                .height(45.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFC7EACD)
                            ),
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Text(
                                text = "💾 Save",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF2D5016),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                    
                    // Display the appropriate image with processing overlay
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    ) {
                        val pagerState = rememberPagerState(
                            pageCount = { if (editedImageUrl != null) 2 else 1 }
                        )
                        
                        // Keep ViewModel's currentPage in sync with pager
                        LaunchedEffect(pagerState.currentPage) {
                            viewModel.setCurrentPage(pagerState.currentPage)
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { viewModel.toggleFullScreenMode() }
                        ) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                Color(0xFFE17055).copy(alpha = 0.1f),
                                                Color(0xFF74B9FF).copy(alpha = 0.1f)
                                            )
                                        )
                                    )
                            ) { page ->
                                Image(
                                    painter = rememberAsyncImagePainter(
                                        model = if (page == 0) uri else editedImageUrl,
                                        onError = { error ->
                                            android.util.Log.e("MainScreen", "Image loading error: ${error.result.throwable}")
                                        }
                                    ),
                                    contentDescription = if (page == 0) "Original image" else "Edited image",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            
                            if (editedImageUrl != null) {
                                Row(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    repeat(pagerState.pageCount) { page ->
                                        Box(
                                            Modifier
                                                .size(8.dp)
                                                .background(
                                                    color = if (page == pagerState.currentPage) 
                                                        MaterialTheme.colorScheme.primary 
                                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                                    shape = CircleShape
                                                )
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Processing overlay
                        if (isProcessing) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                Color(0xFFE8E4F3).copy(alpha = 0.8f),
                                                Color(0xFFD1C4E9).copy(alpha = 0.9f)
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(60.dp),
                                        strokeWidth = 6.dp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "✨ Creating magic...",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color(0xFF8E7CC3),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // AI Prompts section
        if (selectedImageUri != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFFFFF).copy(alpha = 0.95f)
                ),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "🤖 AI Image Editor",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFB19CD9)
                    )
                    
                    // Category selector
                    Text(
                        text = "🎭 Category:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF8B7D6B)
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
                        text = "🎨 Available Prompts:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF8B7D6B)
                    )
                    
                    // Get prompts for the selected category
    var categoryPrompts by remember { mutableStateOf<List<PromptCategory>>(emptyList()) }

    // Load prompts for selected category
    LaunchedEffect(selectedCategory) {
        categoryPrompts = if (selectedCategory != "Select a category...") {
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
                                        // Set the custom prompt text instead of immediately running the AI
                                        viewModel.updateCustomPrompt(
                                            prompt = promptCategory.prompt,
                                            category = selectedCategory,
                                            promptName = promptCategory.name
                                        )
                                    },
                                    enabled = !isProcessing && selectedImageUri != null
                                )
                            }
                        }
                    }
                    
                    Divider()
                    
                    // Custom prompt
                    Text(
                        text = "✍️ Custom Edit Prompt:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF8B7D6B)
                    )
                    
                    OutlinedTextField(
                        value = customPrompt,
                        onValueChange = { input ->
                            if (input.startsWith("/")) {
                                viewModel.handleChatCommand(context, input)
                                if (input == "/help") {
                                    viewModel.updateCustomPrompt("", null, null)
                                } else {
                                    viewModel.updateCustomPrompt(input, null, null)
                                }
                            } else {
                                viewModel.updateCustomPrompt(input, null, null)
                            }
                        },
                        label = { Text("Enter your edit prompt (e.g., 'Turn this person into a dragon')") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        leadingIcon = if (customPrompt.isEmpty()) {
                            {
                                var showCommands by remember { mutableStateOf(false) }
                                Box(
                                    modifier = Modifier.padding(start = 12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(PastelPrimary, CircleShape)
                                            .clickable { showCommands = true },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "/",
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }

                                    if (showCommands) {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 40.dp)
                                                .width(300.dp)
                                                .background(
                                                    color = Color(0xFFFFFFFF),
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .padding(8.dp)
                                        ) {
                                            Column(
                                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                viewModel.availableCommands.forEach { command ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                showCommands = false
                                                                viewModel.handleChatCommand(context, command.command)
                                                            }
                                                            .padding(8.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = command.command,
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = Color(0xFF7FC7D9)
                                                        )
                                                        Text(
                                                            text = command.description,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = Color(0xFF8B7D6B)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else null,
                        trailingIcon = if (customPrompt.isNotEmpty()) {
                            {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 12.dp)
                                        .size(32.dp)
                                        .background(PastelSecondary, CircleShape)
                                        .clickable { viewModel.updateCustomPrompt("", null, null) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Clear prompt",
                                        tint = Color.White
                                    )
                                }
                            }
                        } else null
                    )
                    
                                        // Image processing options
                    Column {
                        Text(
                            text = "🔧 Processing Options:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF8B7D6B)
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Downsize toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Downsize image",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF8B7D6B)
                            )
                            
                            Switch(
                                checked = downsizeImages,
                                onCheckedChange = { viewModel.setDownsizeImages(context, it) },
                                enabled = !isProcessing
                            )
                        }
                        
                        Text(
                            text = if (downsizeImages) "⚡ Faster processing, lower quality" else "🎯 Full resolution, slower processing",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8B7D6B).copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Input Fidelity toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Input fidelity",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF8B7D6B)
                            )
                            
                            Switch(
                                checked = inputFidelity == "high",
                                onCheckedChange = { 
                                    viewModel.setInputFidelity(context, if (it) "high" else "low")
                                },
                                enabled = !isProcessing
                            )
                        }
                        
                        Text(
                            text = if (inputFidelity == "high") "🔍 High detail preservation" else "📷 Standard detail",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8B7D6B).copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Quality selection
                        Column {
                            Text(
                                text = "Output quality",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF8B7D6B)
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("low", "medium", "high").forEach { qualityOption ->
                                    FilterChip(
                                        onClick = { 
                                            viewModel.setQuality(context, qualityOption)
                                        },
                                        label = { 
                                            Text(
                                                text = qualityOption.replaceFirstChar { it.uppercase() },
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        },
                                        selected = quality == qualityOption,
                                        enabled = !isProcessing,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            
                            Text(
                                text = when (quality) {
                                    "high" -> "� Best quality, slowest processing"
                                    "medium" -> "⚖️ Balanced quality and speed"
                                    else -> "⚡ Fastest processing, basic quality"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8B7D6B).copy(alpha = 0.7f),
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                            )
                        }
                    }
                    
                    Button(
                        onClick = {
                            if (customPrompt.isNotBlank()) {
                                // Use current image as input
                                selectedImageUri?.let { uri ->
                                    viewModel.editImage(context, uri, customPrompt)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        enabled = customPrompt.isNotBlank() && !isProcessing && selectedImageUri != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF4C2C2)
                        ),
                        shape = RoundedCornerShape(25.dp)
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 3.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Text(
                            text = if (isProcessing) "🎨 Creating magic..." else "✨ Edit Image",
                            color = Color(0xFF8B5A3C),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    
                    // Processing note
                    if (isProcessing) {
                        Text(
                            text = "📱 Screen will stay awake during processing",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8B7D6B).copy(alpha = 0.8f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        
        // Error Message
        errorMessage?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFD3D3).copy(alpha = 0.9f)
                ),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = "⚠️ Oops!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.clearError() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White
                        ),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        Text("👍 Got it!", color = Color(0xFFD64545), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        
        // Save Success Message
        saveMessage?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFD4EACD).copy(alpha = 0.9f)
                ),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = "🎉 Success!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.clearSaveMessage() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White
                        ),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        Text("🙌 Awesome!", color = Color(0xFF2D5016), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    
    // Show help dialog when needed
    // Show help dialog when needed
    if (viewModel.showHelpDialog.value) {
        HelpDialog(
            commands = viewModel.availableCommands,
            onDismiss = { viewModel.showHelpDialog.value = false },
            modifier = Modifier
        )
    }
    
    // Open fullscreen mode when needed
    if (viewModel.isFullScreenMode.value) {
        FullScreenImageDialog(
            originalUri = selectedImageUri!!,
            editedUri = editedImageUrl?.let { Uri.parse(it) },
            onDismiss = { viewModel.toggleFullScreenMode() },
            currentPage = viewModel.currentPage.value,
            onPageChanged = { viewModel.setCurrentPage(it) }
        )
    }
}

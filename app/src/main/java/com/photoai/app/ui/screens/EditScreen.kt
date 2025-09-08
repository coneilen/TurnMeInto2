package com.photoai.app.ui.screens

import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.DisposableEffect
import android.view.WindowManager
import android.app.Activity
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.*
import com.photoai.app.ui.theme.PastelPrimary
import com.photoai.app.ui.theme.PastelSecondary
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.photoai.app.utils.PromptsLoader
import com.photoai.app.data.PromptCategory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EditScreen(
    imageUri: Uri,
    onSettingsClick: () -> Unit,
    onBackClick: () -> Unit,
    viewModel: com.photoai.app.ui.viewmodel.MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    DisposableEffect(viewModel.isProcessing.value) {
        if (activity != null) {
            if (viewModel.isProcessing.value) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    
    var selectedCategory by remember { mutableStateOf("art") }
    var categoryPrompts by remember { mutableStateOf<List<PromptCategory>>(emptyList()) }
    var categoryNames by remember { mutableStateOf<List<String>>(emptyList()) }
    // Track current prompt name to maintain context when editing
    var currentPromptName by remember { mutableStateOf<String?>(null) }

    // Load categories and prompts
    LaunchedEffect(selectedCategory) {
        categoryPrompts = PromptsLoader.getPromptsForCategory(context, selectedCategory)
    }
    LaunchedEffect(Unit) {
        categoryNames = PromptsLoader.getCategoryNames(context)
    }
    
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel.errorMessage.value) {
        viewModel.errorMessage.value?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    snackbarData = data,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBackClick,
                        enabled = !viewModel.isAnyLoading.value
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = if (viewModel.isAnyLoading.value) 
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else 
                                MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    IconButton(
                        onClick = onSettingsClick,
                        enabled = !viewModel.isAnyLoading.value
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }


                // Image Preview with Pager (70% of remaining height)
                val pagerState = rememberPagerState(
                    pageCount = { if (viewModel.editedImageUrl.value != null) 2 else 1 }
                )
                
                Box(
                    modifier = Modifier
                        .weight(0.7f)
                        .fillMaxWidth()
                ) {
                    
                    // Keep ViewModel's currentPage in sync with pager
                    LaunchedEffect(pagerState.currentPage) {
                        viewModel.setCurrentPage(pagerState.currentPage)
                    }

                    // Ensure edited image is shown when OpenAI call returns
                    LaunchedEffect(viewModel.editedImageUrl.value) {
                        if (viewModel.editedImageUrl.value != null) {
                            pagerState.animateScrollToPage(1)
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .clickable(
                                enabled = !viewModel.isAnyLoading.value
                            ) { 
                                viewModel.toggleFullScreenMode() 
                            },
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize()
                            ) { page ->
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = if (page == 0) imageUri else viewModel.editedImageUrl.value,
                                        contentDescription = if (page == 0) "Original image" else "Edited image",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }

                            // Add page indicator if we have both images
                            if (viewModel.editedImageUrl.value != null) {
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
                            
                            // Loading overlay (person counting or prompt generation)
                            if (viewModel.isLoadingPersonCount.value || viewModel.isGeneratingPrompts.value) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.7f))
                                        .animateContentSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Card(
                                        modifier = Modifier.padding(32.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                                        ),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(24.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(48.dp),
                                                strokeWidth = 4.dp
                                            )
                                            Text(
                                                text = viewModel.loadingMessage.value ?: "Processing...",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }

                            // Processing overlay
                            if (viewModel.isProcessing.value) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.7f))
                                        .animateContentSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Card(
                                        modifier = Modifier.padding(32.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                                        ),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(24.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(48.dp),
                                                strokeWidth = 4.dp
                                            )
                                            Text(
                                                text = "Processing image...",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (viewModel.isFullScreenMode.value) {
                    FullScreenImageDialog(
                        originalUri = imageUri,
                        editedUri = viewModel.editedImageUrl.value?.let { Uri.parse(it) },
                        onDismiss = { viewModel.toggleFullScreenMode() },
                        currentPage = viewModel.currentPage.value,
                        onPageChanged = { viewModel.setCurrentPage(it) }
                    )
                }
                
                // Prompt Selection Area (30% of remaining height)
                Column(
                    modifier = Modifier
                        .weight(0.3f)
                        .fillMaxWidth()
                ) {
                    // Category Selection
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                    ) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(categoryNames) { category ->
                            FilterChip(
                                selected = category == selectedCategory,
                                onClick = { selectedCategory = category },
                                enabled = !viewModel.isAnyLoading.value,
                                    label = { 
                                        Text(
                                            text = category,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                    }
                    
                    // Prompt Selection
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        items(categoryPrompts) { prompt ->
                            ElevatedFilterChip(
                                selected = viewModel.customPrompt.value == prompt.prompt,
                                enabled = !viewModel.isAnyLoading.value,
                                onClick = { 
                                    currentPromptName = prompt.name
                                    viewModel.updateCustomPrompt(
                                        prompt = prompt.prompt,
                                        category = selectedCategory.lowercase(),
                                        promptName = prompt.name
                                    )
                                },
                                label = { 
                                    Text(
                                        text = prompt.name,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                colors = FilterChipDefaults.elevatedFilterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onSecondary
                                )
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Chat Input
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = viewModel.customPrompt.value,
                            onValueChange = { prompt ->
                                if (prompt.startsWith("/")) {
                                    if (prompt in viewModel.availableCommands.map { it.command }) {
                                        viewModel.handleChatCommand(context, prompt)
                                        viewModel.updateCustomPrompt("")
                                        currentPromptName = null
                                    } else {
                                        viewModel.showCommandSuggestions.value = true
                                        viewModel.updateCustomPrompt(prompt, null, null)
                                        currentPromptName = null
                                    }
                                } else {
                                    viewModel.showCommandSuggestions.value = false
                                    // Maintain category and promptName context when manually editing
                                    viewModel.updateCustomPrompt(prompt, selectedCategory.lowercase(), currentPromptName)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Enter your prompt...") },
                            maxLines = 1,
                            singleLine = true,
                            enabled = !viewModel.isProcessing.value,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            leadingIcon = if (viewModel.customPrompt.value.isEmpty()) {
                                {
                                    Box(
                                        modifier = Modifier
                                            .padding(start = 12.dp)
                                            .size(32.dp)
                                            .background(PastelPrimary, CircleShape)
                                            .clickable { 
                                                viewModel.updateCustomPrompt("/", null, null)
                                                viewModel.showCommandSuggestions.value = true
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "/",
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                }
                            } else null,
                            trailingIcon = if (viewModel.customPrompt.value.isNotEmpty()) {
                                {
                                    Box(
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .size(32.dp)
                                            .background(PastelSecondary, CircleShape)
                                            .clickable(enabled = !viewModel.isProcessing.value) {
                                                viewModel.updateCustomPrompt("")
                                                currentPromptName = null
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Clear,
                                            contentDescription = "Clear text",
                                            tint = Color.White
                                        )
                                    }
                                }
                            } else null
                        )

                        // Command Suggestions
                        AnimatedVisibility(
                            visible = viewModel.showCommandSuggestions.value,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    items(viewModel.availableCommands) { suggestion ->
                                        SuggestionChip(
                                onClick = {
                                    if (!viewModel.isAnyLoading.value) {
                                        viewModel.handleChatCommand(context, suggestion.command)
                                        viewModel.updateCustomPrompt("")
                                    }
                                                currentPromptName = null
                                                viewModel.showCommandSuggestions.value = false
                                            },
                                            label = { Text(suggestion.command) },
                                            modifier = Modifier.animateContentSize()
                                        )
                                    }
                                }
                                // Escape button
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 8.dp)
                                        .size(32.dp)
                                        .background(PastelSecondary, CircleShape)
                                    .clickable(
                                        enabled = !viewModel.isAnyLoading.value
                                    ) { 
                                        viewModel.showCommandSuggestions.value = false
                                        viewModel.updateCustomPrompt("", null, null)
                                    },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Cancel command suggestions",
                                        tint = Color.White
                                    )
                                }
                            }
                        }

                        FloatingActionButton(
                            onClick = {
                                if (viewModel.customPrompt.value.isNotBlank() && !viewModel.isProcessing.value) {
                                    // Use currently displayed image based on pager state
                                    if (pagerState.currentPage == 0) {
                                        viewModel.editImage(
                                            context = context,
                                            uri = imageUri,
                                            prompt = viewModel.customPrompt.value,
                                            isEditingEditedImage = false
                                        )
                                    } else {
                                        viewModel.editedImageUrl.value?.let { editedUrl ->
                                            viewModel.editImage(
                                                context = context,
                                                uri = Uri.parse(editedUrl),
                                                prompt = viewModel.customPrompt.value,
                                                isEditingEditedImage = true
                                            )
                                        }
                                    }
                                }
                            },
                            containerColor = if (!viewModel.isProcessing.value && viewModel.customPrompt.value.isNotBlank())
                                MaterialTheme.colorScheme.primary
                            else 
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Send",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }

        // Help Dialog
        if (viewModel.showHelpDialog.value) {
            AlertDialog(
                onDismissRequest = { viewModel.showHelpDialog.value = false },
                title = {
                    Text(
                        text = "Available Commands",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        viewModel.availableCommands.forEach { command ->
                            Column {
                                Text(
                                    text = command.command,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = command.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.showHelpDialog.value = false }) {
                        Text("Close")
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

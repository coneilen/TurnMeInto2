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
import com.photoai.app.ui.components.SparkleProcessingOverlay
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
        viewModel.errorMessage.value?.let { msg ->
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.clearError()
            }
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
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back
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
                    Spacer(modifier = Modifier.width(4.dp))
                    // Settings
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
                    Spacer(modifier = Modifier.weight(1f))
                    // Person count / detection status chips
                    when {
                        viewModel.isLoadingPersonCount.value -> {
                            AssistChip(
                                onClick = {},
                                enabled = false,
                                label = { Text("Detecting...") },
                                leadingIcon = {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            )
                        }
                        viewModel.personCountError.value != null -> {
                            AssistChip(
                                onClick = { if (!viewModel.isAnyLoading.value) viewModel.retryPersonDetection() },
                                label = { Text("Retry detection") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Retry"
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    labelColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            )
                        }
                        viewModel.personCount.value != null -> {
                            val count = viewModel.personCount.value!!
                            AssistChip(
                                onClick = { /* reserved for future manual override dialog */ },
                                label = { Text("People: $count") },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (count > 1)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.secondaryContainer,
                                    labelColor = if (count > 1)
                                        MaterialTheme.colorScheme.onPrimary
                                    else
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (count > 1) Icons.Default.Group else Icons.Default.Person,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                }


                // Image Preview with Pager (70% of remaining height)
                val pagerState = rememberPagerState(
                    pageCount = { 1 + viewModel.editedImageUrls.value.size }
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

                    // Ensure newest edited image is shown when history changes
                    LaunchedEffect(viewModel.editedImageUrls.value.size) {
                        val size = viewModel.editedImageUrls.value.size
                        if (size > 0) {
                            pagerState.animateScrollToPage(size)
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
                            val dims = viewModel.currentImageDimensions.value
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize()
                            ) { page ->
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = if (page == 0) imageUri else viewModel.editedImageUrls.value[page - 1],
                                        contentDescription = if (page == 0) "Original image" else "Edited image",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }

                            if (dims != null) {
                                Text(
                                    text = "${dims.first} x ${dims.second}",
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(12.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // Add page indicator if we have both images
                            if (viewModel.editedImageUrls.value.isNotEmpty()) {
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

                            // Slideshow controls (bottom-right)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp)
                            ) {
                                when {
                                    viewModel.isGeneratingSlideshow.value -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(48.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            strokeWidth = 4.dp
                                        )
                                    }
                                    viewModel.slideshowVideoUri.value != null -> {
                                        FloatingActionButton(
                                            onClick = { viewModel.viewSlideshow(context) },
                                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                            shape = CircleShape
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "View slideshow",
                                                tint = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                    }
                                    !viewModel.isAnyLoading.value &&
                                            viewModel.editedImageUrls.value.isNotEmpty() &&
                                            viewModel.currentPage.value > 0 -> {
                                        FloatingActionButton(
                                            onClick = { viewModel.handleChatCommand(context, "/slideshow") },
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Movie,
                                                contentDescription = "Generate slideshow",
                                                tint = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                    }
                                }
                            }
                            
                            // Loading overlay (person counting, prompt generation, or multi-person prompts)
                            if (viewModel.isLoadingPersonCount.value || viewModel.isGeneratingPrompts.value || viewModel.isGeneratingMultiPersonPrompts.value) {
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
                                            if (viewModel.isGeneratingMultiPersonPrompts.value) {
                                                // Progress indicator for multi-person prompts
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    CircularProgressIndicator(
                                                        progress = viewModel.promptGenerationProgress.value,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(48.dp),
                                                        strokeWidth = 4.dp
                                                    )
                                                    Text(
                                                        text = "Generating multi-person prompts...",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = "${(viewModel.promptGenerationProgress.value * 100).toInt()}%",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            } else {
                                                // Standard loading indicator
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
                            }

                            // Processing overlay
                            if (viewModel.isProcessing.value) {
                                SparkleProcessingOverlay()
                            }
                        }
                    }
                }

                if (viewModel.isFullScreenMode.value) {
                    FullScreenImageDialog(
                        originalUri = imageUri,
                        editedUris = viewModel.editedImageUrls.value.map { Uri.parse(it) },
                        onDismiss = { viewModel.toggleFullScreenMode() },
                        currentPage = viewModel.currentPage.value,
                        onPageChanged = { viewModel.setCurrentPage(it) },
                        slideshowUri = viewModel.slideshowVideoUri.value,
                        onViewSlideshow = { viewModel.viewSlideshow(context) },
                        isGeneratingSlideshow = viewModel.isGeneratingSlideshow.value
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
                                    viewModel.editCurrentImage(context, viewModel.customPrompt.value)
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

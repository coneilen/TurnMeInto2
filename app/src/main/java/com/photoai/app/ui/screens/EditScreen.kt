package com.photoai.app.ui.screens

import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.photoai.app.utils.PromptsLoader

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
    val categoryPrompts = remember(selectedCategory) {
        PromptsLoader.getPromptsForCategory(context, selectedCategory)
    }
    val categoryNames = remember { PromptsLoader.getCategoryNames(context) }
    
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
                // Rest of the content...
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    IconButton(onClick = onSettingsClick) {
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
                            .clickable { viewModel.toggleFullScreenMode() },
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
                                onClick = { viewModel.updateCustomPrompt(prompt.prompt) },
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
                                    if (prompt in listOf("/share", "/save")) {
                                        viewModel.handleChatCommand(context, prompt)
                                        viewModel.updateCustomPrompt("")
                                    } else {
                                        viewModel.updateCustomPrompt(prompt)
                                    }
                                } else {
                                    viewModel.updateCustomPrompt(prompt)
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
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
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
    }
}

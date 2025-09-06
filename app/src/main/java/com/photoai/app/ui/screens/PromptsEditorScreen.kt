package com.photoai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.photoai.app.data.PromptsData
import com.photoai.app.data.FlexiblePromptsData
import com.photoai.app.utils.PromptsLoader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptsEditorScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var prompts by remember { mutableStateOf<Map<String, List<PromptsData.Prompt>>>(emptyMap()) }
    var basePrompt by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var showEditPromptDialog by remember { mutableStateOf(false) }
    var showEditBasePromptDialog by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("") }
    var selectedPromptIndex by remember { mutableStateOf(-1) }
    var editingPrompt by remember { mutableStateOf(PromptsData.Prompt("", "")) }
    var isAddingNewPrompt by remember { mutableStateOf(false) }
    
    // Load prompts and base prompt
    LaunchedEffect(Unit) {
        try {
            val loadedPrompts = PromptsLoader.loadPrompts(context)
            prompts = loadedPrompts.prompts
            basePrompt = PromptsLoader.getBasePrompt(context)
            isLoading = false
        } catch (e: Exception) {
            isLoading = false
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Edit Prompts",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showAddCategoryDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Category")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Base Prompt Section
        BasePromptCard(
            basePrompt = basePrompt,
            onEditBasePrompt = { showEditBasePromptDialog = true },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                prompts.forEach { (category, promptsList) ->
                    item {
                        CategoryCard(
                            category = category,
                            prompts = promptsList,
                            onAddPrompt = {
                                selectedCategory = category
                                editingPrompt = PromptsData.Prompt("", "")
                                isAddingNewPrompt = true
                                showEditPromptDialog = true
                            },
                            onEditPrompt = { index, prompt ->
                                selectedCategory = category
                                selectedPromptIndex = index
                                editingPrompt = prompt.copy()
                                isAddingNewPrompt = false
                                showEditPromptDialog = true
                            },
                            onDeletePrompt = { index ->
                                coroutineScope.launch {
                                    val updatedList = promptsList.toMutableList().apply {
                                        removeAt(index)
                                    }
                                    val updatedPrompts = prompts.toMutableMap().apply {
                                        this[category] = updatedList
                                    }
                                    prompts = updatedPrompts
                                    PromptsLoader.savePrompts(context, FlexiblePromptsData(updatedPrompts))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    
    // Add Category Dialog
    if (showAddCategoryDialog) {
        AddCategoryDialog(
            onDismiss = { showAddCategoryDialog = false },
            onConfirm = { categoryName ->
                coroutineScope.launch {
                    val updatedPrompts = prompts.toMutableMap().apply {
                        this[categoryName] = emptyList()
                    }
                    prompts = updatedPrompts
                    PromptsLoader.savePrompts(context, FlexiblePromptsData(updatedPrompts))
                    showAddCategoryDialog = false
                }
            }
        )
    }
    
    // Edit Base Prompt Dialog
    if (showEditBasePromptDialog) {
        EditBasePromptDialog(
            basePrompt = basePrompt,
            onDismiss = { showEditBasePromptDialog = false },
            onConfirm = { newBasePrompt ->
                coroutineScope.launch {
                    PromptsLoader.saveBasePrompt(context, newBasePrompt)
                    basePrompt = newBasePrompt
                    showEditBasePromptDialog = false
                }
            }
        )
    }
    
    // Edit Prompt Dialog
    if (showEditPromptDialog) {
        EditPromptDialog(
            prompt = editingPrompt,
            isAddingNew = isAddingNewPrompt,
            onDismiss = { showEditPromptDialog = false },
            onConfirm = { updatedPrompt ->
                coroutineScope.launch {
                    val categoryPrompts = prompts[selectedCategory]?.toMutableList() ?: mutableListOf()
                    
                    if (isAddingNewPrompt) {
                        categoryPrompts.add(updatedPrompt)
                    } else {
                        categoryPrompts[selectedPromptIndex] = updatedPrompt
                    }
                    
                    val updatedPrompts = prompts.toMutableMap().apply {
                        this[selectedCategory] = categoryPrompts
                    }
                    prompts = updatedPrompts
                    PromptsLoader.savePrompts(context, FlexiblePromptsData(updatedPrompts))
                    showEditPromptDialog = false
                }
            }
        )
    }
}

@Composable
fun CategoryCard(
    category: String,
    prompts: List<PromptsData.Prompt>,
    onAddPrompt: () -> Unit,
    onEditPrompt: (Int, PromptsData.Prompt) -> Unit,
    onDeletePrompt: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
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
                    text = category.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onAddPrompt) {
                    Icon(Icons.Default.Add, contentDescription = "Add Prompt")
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            prompts.forEachIndexed { index, prompt ->
                PromptItem(
                    prompt = prompt,
                    onEdit = { onEditPrompt(index, prompt) },
                    onDelete = { onDeletePrompt(index) }
                )
                
                if (index < prompts.size - 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun PromptItem(
    prompt: PromptsData.Prompt,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = prompt.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Text(
                text = prompt.prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var categoryName by remember { mutableStateOf("") }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "Add New Category",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = categoryName,
                    onValueChange = { categoryName = it },
                    label = { Text("Category Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(categoryName.trim()) },
                        enabled = categoryName.trim().isNotEmpty()
                    ) {
                        Text("Add")
                    }
                }
            }
        }
    }
}

@Composable
fun EditPromptDialog(
    prompt: PromptsData.Prompt,
    isAddingNew: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (PromptsData.Prompt) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf(prompt.name) }
    var promptText by remember { mutableStateOf(prompt.prompt) }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = if (isAddingNew) "Add New Prompt" else "Edit Prompt",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Prompt Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    label = { Text("Prompt Text") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { 
                            onConfirm(PromptsData.Prompt(name.trim(), promptText.trim()))
                        },
                        enabled = name.trim().isNotEmpty() && promptText.trim().isNotEmpty()
                    ) {
                        Text(if (isAddingNew) "Add" else "Save")
                    }
                }
            }
        }
    }
}

@Composable
fun BasePromptCard(
    basePrompt: String,
    onEditBasePrompt: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
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
                    text = "🎯 Base Prompt",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                IconButton(onClick = onEditBasePrompt) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit Base Prompt",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "This prompt is automatically added before all user prompts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = basePrompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun EditBasePromptDialog(
    basePrompt: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var promptText by remember { mutableStateOf(basePrompt) }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "Edit Base Prompt",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "This prompt will be automatically added before all user prompts when calling the AI service.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    label = { Text("Base Prompt Text") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 10
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { 
                            onConfirm(promptText.trim())
                        },
                        enabled = promptText.trim().isNotEmpty()
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

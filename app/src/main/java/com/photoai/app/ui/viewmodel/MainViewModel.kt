package com.photoai.app.ui.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photoai.app.api.OpenAIService
import com.photoai.app.utils.PromptsLoader
import com.photoai.app.utils.urlToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// Available chat commands with descriptions
data class CommandSuggestion(
    val command: String,
    val description: String
)

sealed class Screen {
    object Landing : Screen()
    data class Edit(val imageUri: Uri) : Screen()
    object Settings : Screen()
    object PromptsEditor : Screen()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val openAIService = OpenAIService.getInstance()
    private var wakeLock: PowerManager.WakeLock? = null

    // New states for prompt generation tracking
    var promptGenerationProgress = mutableStateOf(1f)
        private set

    var isGeneratingMultiPersonPrompts = mutableStateOf(false)
        private set

    // List of available commands
    val availableCommands = listOf(
        CommandSuggestion("/share", "Share the current image"),
        CommandSuggestion("/save", "Save image to gallery"),
        CommandSuggestion("/clear_prompts", "Clear prompts cache"),
        CommandSuggestion("/help", "Show available commands")
    )

    var showHelpDialog = mutableStateOf(false)
        private set

    var showCommandSuggestions = mutableStateOf(false)
        private set

    var currentScreen = mutableStateOf<Screen>(Screen.Landing)
        private set

    var personCount = mutableStateOf<Int?>(null)
        private set

    var isLoadingPersonCount = mutableStateOf(false)
        private set

    // Combined state for all loading states
    val isAnyLoading = derivedStateOf {
        isLoadingPersonCount.value || isGeneratingPrompts.value || isProcessing.value
    }

    var loadingMessage = mutableStateOf<String?>(null)
        private set

    var isGeneratingPrompts = mutableStateOf(false)
        private set

    var personCountError = mutableStateOf<String?>(null)
        private set

    var selectedImageUri = mutableStateOf<Uri?>(null)
        private set

    var customPrompt = mutableStateOf("")
        private set

    // Track the currently selected category and prompt name
    private var currentCategory: String? = null
    private var currentPromptName: String? = null

    // History of edited image URIs (as String for easier persistence if needed)
    var editedImageUrls = mutableStateOf(listOf<String>())
        private set

    var isProcessing = mutableStateOf(false)
        private set

    var errorMessage = mutableStateOf<String?>(null)
        private set

    var currentPage = mutableStateOf(0)
        private set

    var isFullScreenMode = mutableStateOf(false)
        private set

    var saveMessage = mutableStateOf<String?>(null)
        private set

    var downsizeImages = mutableStateOf(true)
        private set

    var inputFidelity = mutableStateOf("low") // "low" or "high"
        private set

    var quality = mutableStateOf("low") // "low", "medium", or "high"
        private set

    fun setSelectedImage(uri: Uri?) {
        selectedImageUri.value = uri
        // Clear previous results when new image is selected
        editedImageUrls.value = emptyList()
        errorMessage.value = null
        currentPage.value = 0
        saveMessage.value = null
        personCount.value = null
        personCountError.value = null

        uri?.let {
            currentScreen.value = Screen.Edit(it)
            detectPersonCount(it)
        }
    }

    private fun detectPersonCount(uri: Uri) {
        viewModelScope.launch {
            try {
                isLoadingPersonCount.value = true
                loadingMessage.value = "Processing input image"
                personCountError.value = null

                // Acquire wake lock for API call
                acquireWakeLock(getApplication())

                val result = openAIService.detectPersons(getApplication(), uri)

                result.fold(
                    onSuccess = { count ->
                        personCount.value = count
                        personCountError.value = null
                        // Update loading message while generating prompts
                        if (count > 1) {
                            loadingMessage.value = "Generating multi-person prompts"
                        }
                    },
                    onFailure = { error ->
                        personCountError.value = error.message ?: "Failed to detect persons in image"
                        personCount.value = null
                    }
                )

                // If multiple people detected, ensure multi-person prompts are generated
                if (personCount.value != null && personCount.value!! > 1) {
                    try {
                        // Setup progress tracking
                        isGeneratingMultiPersonPrompts.value = true
                        PromptsLoader.setGenerationCallback { progress ->
                            promptGenerationProgress.value = progress
                            if (progress >= 1f) {
                                isGeneratingMultiPersonPrompts.value = false
                            }
                        }

                        // Load prompts - allow expiry check here since it's initial load
                        PromptsLoader.loadPrompts(getApplication(), forceRegenerate = false, ignoreCacheExpiry = false)
                    } catch (e: Exception) {
                        android.util.Log.e("MainViewModel", "Failed to generate multi-person prompts: ${e.message}")
                        isGeneratingMultiPersonPrompts.value = false
                        promptGenerationProgress.value = 1f
                    }
                }
            } catch (e: Exception) {
                personCountError.value = e.message ?: "An unexpected error occurred"
                personCount.value = null
            } finally {
                isLoadingPersonCount.value = false
                loadingMessage.value = null
                releaseWakeLock()
            }
        }
    }

    fun navigateToSettings() {
        currentScreen.value = Screen.Settings
    }

    fun navigateBack() {
        currentScreen.value = when (currentScreen.value) {
            is Screen.Edit -> Screen.Landing
            is Screen.Settings -> {
                if (selectedImageUri.value != null) Screen.Edit(selectedImageUri.value!!)
                else Screen.Landing
            }
            is Screen.PromptsEditor -> Screen.Settings
            else -> Screen.Landing
        }
    }

    fun navigateToPromptsEditor() {
        currentScreen.value = Screen.PromptsEditor
    }

    fun updateCustomPrompt(prompt: String, category: String? = null, promptName: String? = null) {
        viewModelScope.launch {
            // Check if the prompt is a command
            if (prompt.startsWith("/")) {
                handleChatCommand(getApplication(), prompt)
                if (prompt == "/help") {
                    // Clear the prompt field after showing help
                    customPrompt.value = ""
                    return@launch
                }
            }

            // Store the original values
            currentCategory = category
            currentPromptName = promptName

            // Check if we need to use multi-person prompt - and don't use cache if we just cleared prompts
            if (personCount.value != null && personCount.value!! > 1 && category != null && promptName != null) {
                android.util.Log.d("MainViewModel", "Multiple people detected, getting multi-person prompt")
                try {
                    // Setup progress tracking first
                    isGeneratingMultiPersonPrompts.value = true
                    PromptsLoader.setGenerationCallback { progress ->
                        promptGenerationProgress.value = progress
                        if (progress >= 1f) {
                            isGeneratingMultiPersonPrompts.value = false
                        }
                    }

                    // Use cached prompts unless they don't exist
                    PromptsLoader.loadPrompts(getApplication(), forceRegenerate = false, ignoreCacheExpiry = true)

                    // Use cached multi-person prompt
                    val multiPersonPrompt = PromptsLoader.getMultiPersonPrompt(category, promptName)
                    if (multiPersonPrompt != null) {
                        android.util.Log.d("MainViewModel", "Using multi-person prompt: ${multiPersonPrompt.take(50)}...")
                        customPrompt.value = multiPersonPrompt
                    } else {
                        android.util.Log.d("MainViewModel", "Multi-person prompt not available, using original")
                        customPrompt.value = prompt
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainViewModel", "Error getting multi-person prompt: ${e.message}")
                    customPrompt.value = prompt
                }
            } else {
                android.util.Log.d("MainViewModel", "Using original prompt")
                customPrompt.value = prompt
            }

            android.util.Log.d("MainViewModel", "Updated custom prompt. Category: $category, Prompt name: $promptName, Using multi-person: ${personCount.value != null && personCount.value!! > 1}")
        }
    }

    private suspend fun getAppropriatePrompt(context: Context, prompt: String): String {
        android.util.Log.d("MainViewModel", "Getting appropriate prompt. Person count: ${personCount.value}, Category: $currentCategory, Prompt name: $currentPromptName")

        // Get base prompt first
        val basePrompt = PromptsLoader.getBasePrompt(context)

        // If no category or prompt name, return base prompt + original prompt
        if (currentCategory == null || currentPromptName == null) {
            android.util.Log.d("MainViewModel", "No category or prompt name available, using original prompt")
            return basePrompt + prompt
        }

        // Get multi-person prompt if needed
        val shouldUseMultiPersonPrompt = personCount.value != null && personCount.value!! > 1
        if (shouldUseMultiPersonPrompt) {
            android.util.Log.d("MainViewModel", "Multiple people detected, attempting to get multi-person prompt")
            try {
                // First ensure prompts are loaded and multi-person prompts are generated
                isGeneratingPrompts.value = true
                loadingMessage.value = "Generating prompts"

                try {
                    // Setup progress tracking if not already set
                    if (!isGeneratingMultiPersonPrompts.value) {
                        isGeneratingMultiPersonPrompts.value = true
                        PromptsLoader.setGenerationCallback { progress ->
                            promptGenerationProgress.value = progress
                            if (progress >= 1f) {
                                isGeneratingMultiPersonPrompts.value = false
                            }
                        }
                    }

                    // Use cached prompts unless they don't exist
                    PromptsLoader.loadPrompts(context, forceRegenerate = false, ignoreCacheExpiry = true)

                    // After prompts are loaded, check for multi-person version
                    val multiPersonPrompt = PromptsLoader.getMultiPersonPrompt(currentCategory!!, currentPromptName!!)
                    if (multiPersonPrompt != null) {
                        android.util.Log.d("MainViewModel", "Successfully found multi-person prompt")
                        return basePrompt + multiPersonPrompt
                    } else {
                        android.util.Log.e("MainViewModel", "Failed to get multi-person prompt despite multiple people")
                    }
                } finally {
                    isGeneratingPrompts.value = false
                    loadingMessage.value = null
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error getting multi-person prompt: ${e.message}")
            }
        }

        android.util.Log.d("MainViewModel", "Using original prompt as fallback")
        return basePrompt + prompt
    }

    fun setDownsizeImages(context: Context, downsize: Boolean) {
        downsizeImages.value = downsize
        PromptsLoader.saveDownsizeImages(context, downsize)
    }

    fun setInputFidelity(context: Context, fidelity: String) {
        inputFidelity.value = fidelity
        PromptsLoader.saveInputFidelity(context, fidelity)
    }

    fun setQuality(context: Context, qualityValue: String) {
        quality.value = qualityValue
        PromptsLoader.saveQuality(context, qualityValue)
    }

    fun loadProcessingPreferences(context: Context) {
        downsizeImages.value = PromptsLoader.getDownsizeImages(context)
        inputFidelity.value = PromptsLoader.getInputFidelity(context)
        quality.value = PromptsLoader.getQuality(context)
    }

    /**
     * Clear the multi-person prompts cache to force regeneration
     */
    fun clearMultiPersonPromptsCache(context: Context) {
        viewModelScope.launch {
            try {
                isGeneratingPrompts.value = true
                loadingMessage.value = "Generating prompts"

                // Clear cache and reset state
                PromptsLoader.clearMultiPersonPrompts(context)
                customPrompt.value = ""  // Reset prompt selection
                currentCategory = null
                currentPromptName = null

                // Force immediate regeneration of prompts if needed
                if (selectedImageUri.value != null && personCount.value != null && personCount.value!! > 1) {
                    isGeneratingPrompts.value = true
                    loadingMessage.value = "Regenerating prompts"

                    try {
                        // Setup progress tracking
                        isGeneratingMultiPersonPrompts.value = true
                        PromptsLoader.setGenerationCallback { progress ->
                            promptGenerationProgress.value = progress
                            if (progress >= 1f) {
                                isGeneratingMultiPersonPrompts.value = false
                            }
                        }

                        // Force regeneration after clearing cache
                        android.util.Log.d("MainViewModel", "Force regenerating prompts...")
                        PromptsLoader.loadPrompts(context, forceRegenerate = true, ignoreCacheExpiry = true)
                        android.util.Log.d("MainViewModel", "Prompts regenerated")

                        // Reset prompt selection
                        customPrompt.value = ""
                        currentCategory = null
                        currentPromptName = null
                    } catch (e: Exception) {
                        android.util.Log.e("MainViewModel", "Failed to regenerate prompts: ${e.message}")
                        errorMessage.value = "Failed to regenerate prompts: ${e.message}"
                    }
                } else {
                    android.util.Log.d("MainViewModel", "No active multi-person image, just clearing cache")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to regenerate prompts: ${e.message}")
                errorMessage.value = "Failed to regenerate prompts: ${e.message}"
            } finally {
                isGeneratingPrompts.value = false
                loadingMessage.value = null
                // Reset progress tracking
                PromptsLoader.setGenerationCallback(null)
                isGeneratingMultiPersonPrompts.value = false
                promptGenerationProgress.value = 1f
            }
        }
    }

    private fun acquireWakeLock(context: Context) {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "PhotoAI:ImageProcessing"
            )
            wakeLock?.acquire(15 * 60 * 1000L) // 15 minutes max
            android.util.Log.d("MainViewModel", "Wake lock acquired")
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    android.util.Log.d("MainViewModel", "Wake lock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to release wake lock: ${e.message}")
        }
    }

    fun setCurrentPage(page: Int) {
        currentPage.value = page
    }

    fun toggleFullScreenMode() {
        isFullScreenMode.value = !isFullScreenMode.value
    }

    /**
     * Returns the Uri of the image currently displayed (original or edited)
     */
    fun getCurrentImageUri(): Uri? {
        return if (currentPage.value == 0) {
            selectedImageUri.value
        } else {
            editedImageUrls.value.getOrNull(currentPage.value - 1)?.let { Uri.parse(it) }
        }
    }

    /**
     * Handle chat-like commands (share/save/etc) using the currently visible image.
     */
    fun handleChatCommand(context: Context, command: String) {
        when (command.lowercase()) {
            "/share" -> {
                viewModelScope.launch {
                    val uri = getCurrentImageUri()
                    uri?.let {
                        val bitmap = urlToBitmap(it.toString())
                        bitmap?.let { b -> shareImage(b) }
                    }
                }
            }
            "/save" -> {
                viewModelScope.launch {
                    val uri = getCurrentImageUri()
                    uri?.let {
                        val bitmap = urlToBitmap(it.toString())
                        bitmap?.let { b -> saveEditedImage(context, b) }
                    }
                }
            }
            "/clear_prompts" -> {
                clearMultiPersonPromptsCache(context)
            }
            "/help" -> {
                showHelpDialog.value = true
            }
        }
    }

    /**
     * Public entry point for editing the currently displayed image.
     * Implements branching logic:
     * - Editing original clears history.
     * - Editing non-latest truncates future edits.
     * - Editing latest appends new image.
     */
    fun editCurrentImage(context: Context, prompt: String) {
        val baseUri = if (currentPage.value == 0) {
            selectedImageUri.value
        } else {
            editedImageUrls.value.getOrNull(currentPage.value - 1)?.let { Uri.parse(it) }
        }

        if (baseUri == null) {
            errorMessage.value = "No image selected"
            return
        }

        // Branching rules
        if (currentPage.value == 0) {
            // Editing original: clear all history
            editedImageUrls.value = emptyList()
        } else if (currentPage.value != editedImageUrls.value.size) {
            // Editing an intermediate edit: truncate newer edits
            editedImageUrls.value = editedImageUrls.value.take(currentPage.value)
        }
        performEdit(context, baseUri, prompt, isEditingEditedImage = currentPage.value > 0)
    }

    /**
     * Legacy API kept for compatibility (not used by UI anymore).
     */
    fun editImage(context: Context, uri: Uri, prompt: String, isEditingEditedImage: Boolean = false) {
        // Determine if this uri corresponds to original or an edited image
        val pageIndex = if (uri == selectedImageUri.value) 0 else {
            val idx = editedImageUrls.value.indexOfFirst { it == uri.toString() }
            if (idx >= 0) idx + 1 else 0
        }
        currentPage.value = pageIndex
        editCurrentImage(context, prompt)
    }

    /**
     * Core edit implementation.
     */
    private fun performEdit(context: Context, uri: Uri, prompt: String, isEditingEditedImage: Boolean) {
        viewModelScope.launch {
            try {
                isProcessing.value = true
                errorMessage.value = null

                // Acquire wake lock to prevent screen from sleeping
                acquireWakeLock(context)

                // Get the appropriate prompt (includes base prompt) unless we are editing an edited image (use raw)
                val fullPrompt = if (isEditingEditedImage) {
                    prompt
                } else {
                    getAppropriatePrompt(context, prompt)
                }

                // Set high quality settings for edited images to prevent quality degradation
                val effectiveInputFidelity = if (isEditingEditedImage) "high" else inputFidelity.value
                val effectiveQuality = if (isEditingEditedImage) "high" else quality.value
                val effectiveDownsizeImage = if (isEditingEditedImage) false else downsizeImages.value

                val result = openAIService.editImage(
                    context = context,
                    uri = uri,
                    prompt = fullPrompt,
                    downsizeImage = effectiveDownsizeImage,
                    inputFidelity = effectiveInputFidelity,
                    quality = effectiveQuality,
                    isEditingEditedImage = isEditingEditedImage
                )

                result.fold(
                    onSuccess = { imageUrl ->
                        android.util.Log.d("MainViewModel", "Received image URL: ${imageUrl.take(100)}...")

                        // If it's a data URL, convert it to a temporary file for better Coil compatibility
                        if (imageUrl.startsWith("data:image/")) {
                            viewModelScope.launch {
                                val tempFileUri = convertDataUrlToTempFile(context, imageUrl)
                                val finalUriStr = tempFileUri?.toString() ?: imageUrl
                                appendNewEditedImage(finalUriStr)
                            }
                        } else {
                            appendNewEditedImage(imageUrl)
                        }
                    },
                    onFailure = { exception ->
                        errorMessage.value = exception.message ?: "An error occurred while editing the image"
                    }
                )
            } catch (e: Exception) {
                errorMessage.value = e.message ?: "An unexpected error occurred"
            } finally {
                isProcessing.value = false
                // Release wake lock when processing is complete
                releaseWakeLock()
            }
        }
    }

    private fun appendNewEditedImage(uriString: String) {
        editedImageUrls.value = editedImageUrls.value + uriString
        currentPage.value = editedImageUrls.value.size // move to newest page
    }

    fun saveEditedImage(context: Context, bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    saveImageToGallery(context, bitmap)
                }

                if (saved) {
                    saveMessage.value = "Image saved to gallery successfully!"
                } else {
                    saveMessage.value = "Failed to save image to gallery"
                }
            } catch (e: Exception) {
                saveMessage.value = "Error saving image: ${e.message}"
            }
        }
    }

    private suspend fun saveImageToGallery(context: Context, bitmap: Bitmap): Boolean {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "edited_image_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )

            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
                true
            } ?: false
        } catch (e: IOException) {
            false
        }
    }

    private suspend fun convertDataUrlToTempFile(context: Context, dataUrl: String): Uri? {
        return withContext(Dispatchers.IO) {
            try {
                // Extract base64 data
                val base64Data = dataUrl.substringAfter("base64,")
                val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)

                if (bitmap != null) {
                    // Create temporary file
                    val tempFile = File(context.cacheDir, "temp_edited_${System.currentTimeMillis()}.png")
                    val outputStream = FileOutputStream(tempFile)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.close()
                    bitmap.recycle()

                    android.util.Log.d("MainViewModel", "Created temp file: ${tempFile.absolutePath}")
                    Uri.fromFile(tempFile)
                } else {
                    android.util.Log.e("MainViewModel", "Failed to decode bitmap from data URL")
                    null
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error converting data URL to temp file: ${e.message}")
                null
            }
        }
    }

    fun clearError() {
        errorMessage.value = null
    }

    /**
     * Share whatever image (original or edited) is currently visible.
     */
    fun shareCurrentImage() {
        viewModelScope.launch {
            try {
                val uri = getCurrentImageUri()
                if (uri == null) {
                    errorMessage.value = "No image to share"
                    return@launch
                }
                val bitmap = urlToBitmap(uri.toString())
                bitmap?.let { bmp ->
                    shareImage(bmp)
                } ?: run {
                    errorMessage.value = "Failed to prepare image for sharing"
                }
            } catch (e: Exception) {
                errorMessage.value = "Error sharing image: ${e.message}"
            }
        }
    }

    // Deprecated single-edited-image share API retained for compatibility
    fun shareEditedImage() {
        shareCurrentImage()
    }

    private suspend fun shareImage(bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            try {
                // Create a temporary file to share
                val context = getApplication<Application>()
                val shareFile = File(context.cacheDir, "shared_image_${System.currentTimeMillis()}.jpg")
                FileOutputStream(shareFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }

                // Create share intent
                val shareUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )

                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    putExtra(Intent.EXTRA_TEXT, "Check out my AI-edited photo!")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                // Create chooser intent
                val chooserIntent = Intent.createChooser(shareIntent, "Share edited image")
                chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooserIntent)
            } catch (e: Exception) {
                throw Exception("Failed to share image: ${e.message}")
            }
        }
    }

    fun clearSaveMessage() {
        saveMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        // Ensure wake lock is released when ViewModel is destroyed
        releaseWakeLock()
    }
}

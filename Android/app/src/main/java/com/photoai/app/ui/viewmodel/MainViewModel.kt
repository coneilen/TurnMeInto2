package com.photoai.app.ui.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
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
import com.photoai.app.ml.EsganUpscaler
import com.photoai.app.ml.EsganOptions
import com.photoai.app.utils.PreparedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

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

    // List of available commands (added /slideshow)
    val availableCommands = listOf(
        CommandSuggestion("/share", "Share the current image"),
        CommandSuggestion("/save", "Save image to gallery"),
        CommandSuggestion("/slideshow", "Generate slideshow video of edits so far"),
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
        isLoadingPersonCount.value || isGeneratingPrompts.value || isProcessing.value || isGeneratingSlideshow.value
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

    // Currently displayed image dimensions (original or edited)
    var currentImageDimensions = mutableStateOf<Pair<Int, Int>?>(null)
        private set

    // Persist original (selected) source image dimensions for all subsequent iterative edits
    private var originalBaseDimensions: Pair<Int, Int>? = null

    // Track the currently selected category and prompt name
    private var currentCategory: String? = null
    private var currentPromptName: String? = null

    // History of edited image URIs (as String for easier persistence if needed)
    var editedImageUrls = mutableStateOf(listOf<String>())
        private set

    // Parallel list of user-entered prompts for each edited image (not including base prompt)
    var editedImagePrompts = mutableStateOf(listOf<String>())
        private set

    // Parallel list of padded (base-size) data URLs for iterative stability
    var paddedEditedImages = mutableStateOf(listOf<String>())
        private set

    // Base preparation metadata (stable frame) captured on first edit
    var basePreparedMeta: PreparedImage? = null

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

    // Slideshow states
    var slideshowVideoUri = mutableStateOf<Uri?>(null)
        private set
    var isGeneratingSlideshow = mutableStateOf(false)
        private set
    var slideshowError = mutableStateOf<String?>(null)
        private set

    fun setSelectedImage(uri: Uri?) {
        selectedImageUri.value = uri
        originalBaseDimensions = uri?.let { getImageDimensions(getApplication(), it) }
        // Clear previous results when new image is selected
        editedImageUrls.value = emptyList()
        editedImagePrompts.value = emptyList()
        paddedEditedImages.value = emptyList()
        basePreparedMeta = null
        errorMessage.value = null
        currentPage.value = 0
        saveMessage.value = null
        personCount.value = null
        personCountError.value = null
        slideshowVideoUri.value = null

        uri?.let {
            currentScreen.value = Screen.Edit(it)
            detectPersonCount(it)
        }
        updateCurrentImageDimensions()
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

    fun retryPersonDetection() {
        val uri = selectedImageUri.value ?: return
        if (isLoadingPersonCount.value) return
        personCountError.value = null
        detectPersonCount(uri)
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
        updateCurrentImageDimensions()
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
     * Update currently displayed image dimensions (original or edited).
     * Network (http/https) URIs are decoded off main thread.
     */
    fun updateCurrentImageDimensions() {
        val uri = getCurrentImageUri()
        if (uri == null) {
            currentImageDimensions.value = null
            return
        }
        if (uri.scheme == "http" || uri.scheme == "https") {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val bmp = urlToBitmap(uri.toString())
                    val dims = bmp?.let { Pair(it.width, it.height) }
                    withContext(Dispatchers.Main) {
                        currentImageDimensions.value = dims
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        currentImageDimensions.value = null
                    }
                }
            }
        } else {
            currentImageDimensions.value = getImageDimensions(getApplication(), uri)
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
            "/slideshow" -> {
                viewModelScope.launch {
                    generateSlideshow(context)
                }
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
            editedImagePrompts.value = emptyList()
            paddedEditedImages.value = emptyList()
            basePreparedMeta = null
        } else if (currentPage.value != editedImageUrls.value.size) {
            // Editing an intermediate edit: truncate newer edits
            editedImageUrls.value = editedImageUrls.value.take(currentPage.value)
            editedImagePrompts.value = editedImagePrompts.value.take(currentPage.value)
            paddedEditedImages.value = paddedEditedImages.value.take(currentPage.value)
        }
        // Invalidate existing slideshow if any
        slideshowVideoUri.value = null

        performEdit(context, baseUri, prompt, isEditingEditedImage = currentPage.value > 0, userEnteredPrompt = prompt)
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
    private fun performEdit(context: Context, uri: Uri, prompt: String, isEditingEditedImage: Boolean, userEnteredPrompt: String) {
        viewModelScope.launch {
            try {
                isProcessing.value = true
                // Capture original/base image dimensions (used for upscaling edited result)
                val baseDimensions = getImageDimensions(context, uri)
                val targetDimensions = if (isEditingEditedImage) {
                    // Use original source size (if known) when iterating on an edited image
                    originalBaseDimensions ?: baseDimensions
                } else {
                    baseDimensions
                }

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

                // Decide source mode: first edit uses Uri, iterative uses padded previous
                val paddedInput: String? = if (isEditingEditedImage) {
                    paddedEditedImages.value.getOrNull(currentPage.value - 1)
                } else null
                if (isEditingEditedImage && paddedInput == null) {
                    throw IllegalStateException("Missing padded input for iterative edit")
                }

                val advancedResult = openAIService.editImageAdvanced(
                    context = context,
                    uri = if (paddedInput == null) uri else null,
                    prompt = fullPrompt,
                    inputFidelity = effectiveInputFidelity,
                    quality = effectiveQuality,
                    isEditingEditedImage = isEditingEditedImage,
                    paddedInputDataUrl = paddedInput,
                    previousMeta = basePreparedMeta,
                    returnBoth = true,
                    returnPaddedOnly = false
                )

                advancedResult.fold(
                    onSuccess = { editRes ->
                        android.util.Log.d("MainViewModel", "Received image URL (restored): ${editRes.restoredDataUrl.take(80)}...")
                        // Capture base meta first time
                        if (basePreparedMeta == null) {
                            basePreparedMeta = editRes.meta
                        }
                        // Append padded (for future edits) AFTER branching logic already truncated
                        paddedEditedImages.value = paddedEditedImages.value + editRes.paddedDataUrl

                        val restoredUrl = editRes.restoredDataUrl
                        if (restoredUrl.startsWith("data:image/")) {
                            try {
                                loadingMessage.value = "Applying magic (finalizing)"
                                val tempFileUri = convertDataUrlToTempFile(
                                    context = context,
                                    dataUrl = restoredUrl,
                                    targetWidth = targetDimensions?.first,
                                    targetHeight = targetDimensions?.second,
                                    allowUpscale = !isEditingEditedImage // skip expensive upscale mid-iteration
                                )
                                val finalUriStr = tempFileUri?.toString() ?: restoredUrl
                                appendNewEditedImage(finalUriStr, userEnteredPrompt)
                            } catch (e: Exception) {
                                android.util.Log.e("MainViewModel", "Post-process failed: ${e.message}")
                                appendNewEditedImage(restoredUrl, userEnteredPrompt)
                            } finally {
                                if (loadingMessage.value?.startsWith("Applying magic") == true) {
                                    loadingMessage.value = null
                                }
                            }
                        } else {
                            appendNewEditedImage(restoredUrl, userEnteredPrompt)
                        }
                    },
                    onFailure = { ex ->
                        errorMessage.value = ex.message ?: "An error occurred while editing the image"
                    }
                )
            } catch (e: Exception) {
                errorMessage.value = e.message ?: "An unexpected error occurred"
            } finally {
                isProcessing.value = false
                releaseWakeLock()
            }
        }
    }

    private fun appendNewEditedImage(uriString: String, userPrompt: String) {
        editedImageUrls.value = editedImageUrls.value + uriString
        editedImagePrompts.value = editedImagePrompts.value + userPrompt
        currentPage.value = editedImageUrls.value.size // move to newest page
        // invalidate slideshow
        slideshowVideoUri.value = null
        updateCurrentImageDimensions()
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
            // Ensure export is at original resolution if initial prepare() downscaled.
            val exportBitmap = upscaleForExportIfNeeded(context, bitmap)

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
                    exportBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
                true
            } ?: false
        } catch (e: IOException) {
            false
        }
    }

    private suspend fun convertDataUrlToTempFile(
        context: Context,
        dataUrl: String,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        allowUpscale: Boolean = true
    ): Uri? {
        return withContext(Dispatchers.IO) {
            try {
                val base64Data = dataUrl.substringAfter("base64,")
                val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                var bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                if (bitmap != null) {
                    android.util.Log.d(
                        "ImagePipeline",
                        "phase=convertDataUrl decode=${bitmap.width}x${bitmap.height} target=${targetWidth ?: -1}x${targetHeight ?: -1}"
                    )
                }

                if (bitmap != null) {
                    // Attempt upscale if original/base dimensions are larger
                    if (targetWidth != null && targetHeight != null) {
                        // Try ESRGAN 2x multi-pass upscale first (tiled, artifact-suppressed)
                        if (allowUpscale && (targetWidth > bitmap.width || targetHeight > bitmap.height)) {
                            android.util.Log.d(
                                "ImagePipeline",
                                "phase=convertDataUrl_esrgan_attempt src=${bitmap.width}x${bitmap.height} target=${targetWidth}x${targetHeight}"
                            )
                            try {
                                val t0 = System.currentTimeMillis()
                                android.util.Log.d("ImagePipeline","phase=convertDataUrl_esrgan opts=artifactDenoise=false")
                                val esr = EsganUpscaler.upscaleToMatch(
                                    context = context,
                                    src = bitmap,
                                    targetW = targetWidth,
                                    targetH = targetHeight,
                                    options = EsganOptions(enableArtifactDenoise = false)
                                )
                                if (esr != bitmap) {
                                    bitmap.recycle()
                                    bitmap = esr
                                    android.util.Log.d(
                                        "ImagePipeline",
                                        "phase=convertDataUrl_esrgan_success ms=${System.currentTimeMillis() - t0} out=${bitmap.width}x${bitmap.height}"
                                    )
                                } else {
                                    android.util.Log.d(
                                        "ImagePipeline",
                                        "phase=convertDataUrl_esrgan_noop srcAlready=${bitmap.width}x${bitmap.height}"
                                    )
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("MainViewModel", "ESRGAN upscale failed: ${e.message}; falling back to legacy path")
                                android.util.Log.d(
                                    "ImagePipeline",
                                    "phase=convertDataUrl_esrgan_fail reason=${e.message ?: "error"}"
                                )
                                // Fallback legacy heuristic upscale
                                try {
                                    val legacy = upscaleMultiStepSharpen(bitmap, targetWidth, targetHeight)
                                    if (legacy != bitmap) {
                                        bitmap.recycle()
                                        bitmap = legacy
                                        android.util.Log.d(
                                            "ImagePipeline",
                                            "phase=convertDataUrl_legacy_success out=${bitmap.width}x${bitmap.height}"
                                        )
                                    } else {
                                        android.util.Log.d(
                                            "ImagePipeline",
                                            "phase=convertDataUrl_legacy_noop unchanged=${bitmap.width}x${bitmap.height}"
                                        )
                                    }
                                } catch (e2: Exception) {
                                    android.util.Log.e("MainViewModel", "Legacy upscale failed: ${e2.message}")
                                }
                            }
                        }
                    }

                    val tempFile = File(context.cacheDir, "temp_edited_${System.currentTimeMillis()}.png")
                    FileOutputStream(tempFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    bitmap.recycle()
                    val fileBytes = tempFile.length()
                    android.util.Log.d(
                        "ImagePipeline",
                        "phase=convertDataUrl_final final=${bitmap.width}x${bitmap.height} fileBytes=${fileBytes}"
                    )
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

    /**
     * Multi-step upscale with denoise + unsharp mask to improve perceived detail.
     */
    private fun upscaleMultiStepSharpen(edited: Bitmap, origW: Int, origH: Int): Bitmap {
        // Optional denoise first
        val source = denoiseIfNoisy(edited)
        val eW = source.width
        val eH = source.height
        if (eW >= origW || eH >= origH) return source

        val scale = minOf(origW.toFloat() / eW.toFloat(), origH.toFloat() / eH.toFloat())
        if (scale <= 1.05f) return source // negligible gain

        val targetW = (eW * scale).roundToInt().coerceAtMost(origW)
        val targetH = (eH * scale).roundToInt().coerceAtMost(origH)

        var current = source
        var currentW = eW
        var currentH = eH

        // Progressive doubling until close to target
        while (currentW * 2 < targetW && currentH * 2 < targetH) {
            val nextW = (currentW * 2).coerceAtMost(targetW)
            val nextH = (currentH * 2).coerceAtMost(targetH)
            val step = Bitmap.createScaledBitmap(current, nextW, nextH, true)
            if (current != source) current.recycle()
            current = step
            currentW = nextW
            currentH = nextH
        }

        // Final non-doubling step if needed
        if (currentW != targetW || currentH != targetH) {
            val finalStep = Bitmap.createScaledBitmap(current, targetW, targetH, true)
            if (current != source) current.recycle()
            current = finalStep
        }

        android.util.Log.d(
            "MainViewModel",
            "Upscaled (denoise=${source != edited}) from ${eW}x${eH} to ${current.width}x${current.height} (orig ${origW}x${origH})"
        )

        val sharpened = applyUnsharpMask(current, amount = 0.35f)
        if (sharpened != current) current.recycle()
        return sharpened
    }

    /**
     * Upscale an edited (restored) bitmap back to the original source resolution for export
     * (save/share/slideshow) if the initial prepare() downscaled it. Uses ESRGAN first, then
     * legacy multi-step sharpen fallback. Returns the same bitmap if no action needed.
     */
    private suspend fun upscaleForExportIfNeeded(context: Context, src: Bitmap): Bitmap {
        val meta = basePreparedMeta ?: return src
        if (meta.downscale >= 0.999f) return src // no original downscale
        // If already at original size, nothing to do
        if (src.width == meta.originalW && src.height == meta.originalH) return src
        // If at drawn size (downscaled) or some other size smaller than original, upscale
        return try {
            // Try ESRGAN first
            android.util.Log.d("ImagePipeline","phase=export_upscale_esrgan opts=artifactDenoise=false")
            val esr = EsganUpscaler.upscaleToMatch(
                context = context,
                src = src,
                targetW = meta.originalW,
                targetH = meta.originalH,
                options = EsganOptions(enableArtifactDenoise = false)
            )
            if (esr.width == meta.originalW && esr.height == meta.originalH) {
                android.util.Log.d(
                    "ImagePipeline",
                    "phase=export_upscale_esrgan success=${esr.width}x${esr.height}"
                )
                esr
            } else {
                // Fallback legacy sharpen path
                val legacy = upscaleMultiStepSharpen(src, meta.originalW, meta.originalH)
                android.util.Log.d(
                    "ImagePipeline",
                    "phase=export_upscale_fallback out=${legacy.width}x${legacy.height}"
                )
                legacy
            }
        } catch (e: Exception) {
            android.util.Log.w("ImagePipeline", "phase=export_upscale_esrgan_fail reason=${e.message}")
            try {
                val legacy = upscaleMultiStepSharpen(src, meta.originalW, meta.originalH)
                android.util.Log.d(
                    "ImagePipeline",
                    "phase=export_upscale_legacy out=${legacy.width}x${legacy.height}"
                )
                legacy
            } catch (e2: Exception) {
                android.util.Log.e("ImagePipeline", "phase=export_upscale_fail reason=${e2.message}")
                src
            }
        }
    }

    private fun denoiseIfNoisy(src: Bitmap): Bitmap {
        val noise = estimateNoiseLuma(src)
        return if (noise >= 8f) {
            android.util.Log.d("MainViewModel", "Denoising edited image before upscale (noise=$noise)")
            medianDenoise3x3(src)
        } else {
            android.util.Log.d("MainViewModel", "Skipping denoise (noise=$noise)")
            src
        }
    }

    private fun estimateNoiseLuma(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        if (w < 4 || h < 4) return 0f
        val stepX = (w / 32).coerceAtLeast(1)
        val stepY = (h / 32).coerceAtLeast(1)
        var acc = 0f
        var count = 0
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 1 until h - 1 step stepY) {
            for (x in 1 until w - 1 step stepX) {
                val c = pixels[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val l = (299 * r + 587 * g + 114 * b) / 1000
                val cR = pixels[y * w + (x + 1)]
                val cD = pixels[(y + 1) * w + x]
                val lR = (299 * ((cR shr 16) and 0xFF) + 587 * ((cR shr 8) and 0xFF) + 114 * (cR and 0xFF)) / 1000
                val lD = (299 * ((cD shr 16) and 0xFF) + 587 * ((cD shr 8) and 0xFF) + 114 * (cD and 0xFF)) / 1000
                acc += kotlin.math.abs(l - lR) + kotlin.math.abs(l - lD)
                count += 2
            }
        }
        return if (count == 0) 0f else acc / count
    }

    private fun medianDenoise3x3(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val result = IntArray(w * h)

        for (x in 0 until w) {
            result[x] = pixels[x]
            result[(h - 1) * w + x] = pixels[(h - 1) * w + x]
        }
        for (y in 0 until h) {
            result[y * w] = pixels[y * w]
            result[y * w + (w - 1)] = pixels[y * w + (w - 1)]
        }

        val rArr = IntArray(9)
        val gArr = IntArray(9)
        val bArr = IntArray(9)

        fun median9(a: IntArray): Int {
            for (i in 1 until 9) {
                val v = a[i]
                var j = i - 1
                while (j >= 0 && a[j] > v) {
                    a[j + 1] = a[j]
                    j--
                }
                a[j + 1] = v
            }
            return a[4]
        }

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var idx = 0
                for (dy in -1..1) {
                    val row = (y + dy) * w
                    for (dx in -1..1) {
                        val c = pixels[row + (x + dx)]
                        rArr[idx] = (c shr 16) and 0xFF
                        gArr[idx] = (c shr 8) and 0xFF
                        bArr[idx] = c and 0xFF
                        idx++
                    }
                }
                val mr = median9(rArr)
                val mg = median9(gArr)
                val mb = median9(bArr)
                result[y * w + x] = (0xFF shl 24) or (mr shl 16) or (mg shl 8) or mb
            }
        }

        out.setPixels(result, 0, w, 0, 0, w, h)
        return out
    }

    private fun applyUnsharpMask(src: Bitmap, amount: Float = 0.3f): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val original = IntArray(w * h)
        val blur = IntArray(w * h)
        out.getPixels(original, 0, w, 0, 0, w, h)

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var r = 0; var g = 0; var b = 0
                for (dy in -1..1) {
                    val row = (y + dy) * w
                    for (dx in -1..1) {
                        val c = original[row + (x + dx)]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                    }
                }
                val idx = y * w + x
                blur[idx] = (0xFF shl 24) or
                    ((r / 9) shl 16) or
                    ((g / 9) shl 8) or
                    (b / 9)
            }
        }

        for (i in original.indices) {
            val o = original[i]
            val bpx = blur[i]
            val orr = (o shr 16) and 0xFF
            val org = (o shr 8) and 0xFF
            val orb = o and 0xFF
            val br = (bpx shr 16) and 0xFF
            val bg = (bpx shr 8) and 0xFF
            val bb = bpx and 0xFF
            val nr = (orr * (1 + amount) - br * amount).coerceIn(0f, 255f).toInt()
            val ng = (org * (1 + amount) - bg * amount).coerceIn(0f, 255f).toInt()
            val nb = (orb * (1 + amount) - bb * amount).coerceIn(0f, 255f).toInt()
            original[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        out.setPixels(original, 0, w, 0, 0, w, h)
        return out
    }

    private fun getImageDimensions(context: Context, uri: Uri): Pair<Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                Pair(opts.outWidth, opts.outHeight)
            } else null
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to read image dimensions: ${e.message}")
            null
        }
    }

    fun clearError() {
        errorMessage.value = null
    }

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

    fun shareEditedImage() {
        shareCurrentImage()
    }

    private suspend fun shareImage(bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val exportBitmap = upscaleForExportIfNeeded(context, bitmap)
                val shareFile = File(context.cacheDir, "shared_image_${System.currentTimeMillis()}.jpg")
                FileOutputStream(shareFile).use { out ->
                    exportBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }

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

    /**
     * Launch an intent to view the slideshow video if available.
     */
    fun viewSlideshow(context: Context) {
        val uri = slideshowVideoUri.value ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            errorMessage.value = "Unable to open slideshow: ${e.message}"
        }
    }

    /**
     * Generate slideshow video (MP4) from original + edits up to currentPage (must include at least one edit).
     */
    private suspend fun generateSlideshow(context: Context) {
        if (isGeneratingSlideshow.value) return
        val original = selectedImageUri.value
        if (original == null) {
            slideshowError.value = "No original image selected"
            errorMessage.value = slideshowError.value
            return
        }
        if (currentPage.value == 0 || editedImageUrls.value.isEmpty()) {
            slideshowError.value = "Need at least one edit to build slideshow"
            errorMessage.value = slideshowError.value
            return
        }

        isGeneratingSlideshow.value = true
        slideshowError.value = null

        withContext(Dispatchers.Default) {
            val endPage = currentPage.value // inclusive page index
            val editCountIncluded = (endPage).coerceAtMost(editedImageUrls.value.size)
            val uris = mutableListOf<Uri>()
            val prompts = mutableListOf<String>()

            // Original first
            uris.add(original)
            prompts.add("Original")

            // Edits (user-entered prompts)
            for (i in 0 until editCountIncluded) {
                val u = editedImageUrls.value.getOrNull(i) ?: continue
                uris.add(Uri.parse(u))
                val p = editedImagePrompts.value.getOrNull(i) ?: ""
                prompts.add(p.ifBlank { "Edit ${i + 1}" })
            }

            try {
                val slides = uris.size
                if (slides < 2) {
                    slideshowError.value = "Need at least one edit to build slideshow"
                    errorMessage.value = slideshowError.value
                    return@withContext
                }

                // Load first bitmap to determine target size
                val firstBitmap = loadBitmapForSlideshow(context, uris[0]) ?: throw IllegalStateException("Failed to load first image")
                val maxDim = 1920
                val scale = minOf(maxDim.toFloat() / firstBitmap.width.toFloat(), maxDim.toFloat() / firstBitmap.height.toFloat(), 1f)
                val targetW = ensureEven((firstBitmap.width * scale).roundToInt().coerceAtLeast(2))
                val targetH = ensureEven((firstBitmap.height * scale).roundToInt().coerceAtLeast(2))
                val frameRate = 30
                val slideDurationSec = 5
                val framesPerSlide = frameRate * slideDurationSec
                firstBitmap.recycle()

                val cacheFile = File(context.cacheDir, "slideshow_${System.currentTimeMillis()}.mp4")
                if (cacheFile.exists()) cacheFile.delete()

                // Select an explicit color format the encoder supports (prefer planar, else semi-planar)
                val tempEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val caps = tempEncoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val supportsPlanar = caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
                val supportsSemi = caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                val selectedColorFormat = when {
                    supportsPlanar -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    supportsSemi -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                    else -> {
                        // Fallback: first supported 4:2:0 format
                        (caps.colorFormats.firstOrNull {
                            it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                            it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                        }) ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    }
                }
                tempEncoder.release()

                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetW, targetH).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, selectedColorFormat)
                    val bitrate = (targetW * targetH * 4).coerceAtMost(6_000_000)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
                }

                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                // Use byte-buffer (buffer queue) input mode ONLY (no surface) to avoid conflict
                encoder.start()

                val muxer = MediaMuxer(cacheFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var trackIndex = -1
                var muxerStarted = false

                val info = MediaCodec.BufferInfo()
                var presentationTimeUs = 0L

                // Paint resources
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = (targetH * 0.035f)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setShadowLayer(4f, 2f, 2f, 0xCC000000.toInt())
                }
                val bgPaint = Paint().apply {
                    color = 0x66000000
                    style = Paint.Style.FILL
                }
                val lineSpacing = textPaint.textSize * 1.2f
                val maxTextWidth = (targetW * 0.90f)

                fun composeSlide(src: Bitmap, prompt: String): Bitmap {
                    val canvasBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    val c = Canvas(canvasBitmap)
                    c.drawColor(Color.BLACK)

                    // Fit center (letterbox)
                    val scaleImg = minOf(
                        targetW.toFloat() / src.width.toFloat(),
                        targetH.toFloat() / src.height.toFloat()
                    )
                    val drawW = (src.width * scaleImg).roundToInt()
                    val drawH = (src.height * scaleImg).roundToInt()
                    val left = (targetW - drawW) / 2
                    val top = (targetH - drawH) / 2
                    val dst = Rect(left, top, left + drawW, top + drawH)
                    c.drawBitmap(src, null, dst, null)

                    // Prompt text area
                    val safePrompt = prompt.ifBlank { "Edit" }
                    val lines = wrapText(safePrompt, textPaint, maxTextWidth)
                    val boxHeight = (lineSpacing * lines.size) + (lineSpacing * 0.8f)
                    val boxTop = targetH - boxHeight
                    c.drawRect(0f, boxTop, targetW.toFloat(), targetH.toFloat(), bgPaint)
                    var y = boxTop + lineSpacing
                    for (ln in lines) {
                        c.drawText(ln, (targetW * 0.05f), y, textPaint)
                        y += lineSpacing
                    }
                    return canvasBitmap
                }

                for (i in 0 until slides) {
                    val bmp = loadBitmapForSlideshow(context, uris[i]) ?: continue
                    // Upscale each frame source back to original resolution if needed (export requirement),
                    // then apply slideshow-specific downscale cap.
                    val upscaled = upscaleForExportIfNeeded(context, bmp)
                    val downscaled = downscaleIfNeeded(upscaled, maxDim)
                    if (upscaled != bmp && downscaled != upscaled) {
                        // recycle intermediate if different objects
                        upscaled.recycle()
                    }
                    if (downscaled != bmp) bmp.recycle()
                    val slideBitmap = composeSlide(downscaled, prompts[i])
                    if (downscaled != bmp) downscaled.recycle()

                    val argbPixels = IntArray(slideBitmap.width * slideBitmap.height)
                    slideBitmap.getPixels(argbPixels, 0, slideBitmap.width, 0, 0, slideBitmap.width, slideBitmap.height)
                    val yuv = ByteArray(targetW * targetH * 3 / 2)
                    convertARGBToYUV(
                        argb = argbPixels,
                        width = targetW,
                        height = targetH,
                        out = yuv,
                        colorFormat = format.getInteger(MediaFormat.KEY_COLOR_FORMAT)
                    )

                    slideBitmap.recycle()

                    // Feed identical frame multiple times for slide duration
                    for (f in 0 until framesPerSlide) {
                        val inIndex = encoder.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buf = encoder.getInputBuffer(inIndex)!!
                            buf.clear()
                            buf.put(yuv)
                            encoder.queueInputBuffer(
                                inIndex,
                                0,
                                yuv.size,
                                presentationTimeUs,
                                0
                            )
                        }
                        presentationTimeUs += 1_000_000L / frameRate

                        loop@ while (true) {
                            val outIndex = encoder.dequeueOutputBuffer(info, 0)
                            when {
                                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break@loop
                                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    if (muxerStarted) {
                                        throw IllegalStateException("Format changed after muxer started")
                                    }
                                    val newFormat = encoder.outputFormat
                                    trackIndex = muxer.addTrack(newFormat)
                                    muxer.start()
                                    muxerStarted = true
                                }
                                outIndex >= 0 -> {
                                    if (!muxerStarted) {
                                        throw IllegalStateException("Muxer not started")
                                    }
                                    val outBuffer = encoder.getOutputBuffer(outIndex)!!
                                    if (info.size > 0) {
                                        outBuffer.position(info.offset)
                                        outBuffer.limit(info.offset + info.size)
                                        muxer.writeSampleData(trackIndex, outBuffer, info)
                                    }
                                    encoder.releaseOutputBuffer(outIndex, false)
                                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                        break@loop
                                    }
                                }
                            }
                        }
                    }
                }

                // Signal end of stream
                val inIndex = encoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    encoder.queueInputBuffer(
                        inIndex,
                        0,
                        0,
                        presentationTimeUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
                // Drain remaining
                drainEncoder(encoder, muxer, trackIndex) { muxerStarted }

                encoder.stop()
                encoder.release()
                muxer.stop()
                muxer.release()

                // Insert into MediaStore
                val resolver = context.contentResolver
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "slideshow_$timeStamp.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PhotoAI")
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        cacheFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    slideshowVideoUri.value = uri
                } else {
                    slideshowError.value = "Failed to insert slideshow into gallery"
                    errorMessage.value = slideshowError.value
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Slideshow generation failed: ${e.message}")
                slideshowError.value = "Slideshow failed: ${e.message}"
                errorMessage.value = slideshowError.value
            } finally {
                isGeneratingSlideshow.value = false
            }
        }
    }

    private fun drainEncoder(encoder: MediaCodec, muxer: MediaMuxer, trackIndex: Int, muxerStartedCheck: () -> Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(info, 10_000)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStartedCheck()) {
                    val track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                }
            } else if (outIndex >= 0) {
                val outBuffer: ByteBuffer = encoder.getOutputBuffer(outIndex) ?: continue
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    info.size = 0
                }
                if (info.size > 0 && muxerStartedCheck()) {
                    outBuffer.position(info.offset)
                    outBuffer.limit(info.offset + info.size)
                    muxer.writeSampleData(trackIndex, outBuffer, info)
                }
                encoder.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    break
                }
            }
        }
    }

    private fun ensureEven(v: Int) = if (v % 2 == 0) v else v - 1

    private fun downscaleIfNeeded(bmp: Bitmap, maxDim: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val largest = maxOf(w, h)
        if (largest <= maxDim) return bmp
        val scale = maxDim.toFloat() / largest.toFloat()
        val nw = (w * scale).roundToInt()
        val nh = (h * scale).roundToInt()
        return Bitmap.createScaledBitmap(bmp, nw, nh, true)
    }

    private fun loadBitmapForSlideshow(context: Context, uri: Uri): Bitmap? {
        return try {
            if (uri.scheme == "http" || uri.scheme == "https" || uri.toString().startsWith("data:image/")) {
                runBlockingIfNeeded {
                    urlToBitmap(uri.toString())
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to load bitmap for slideshow: ${e.message}")
            null
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (w in words) {
            val tentative = if (current.isEmpty()) w else current.toString() + " " + w
            if (paint.measureText(tentative) <= maxWidth) {
                if (current.isEmpty()) current.append(w) else {
                    current.append(" ").append(w)
                }
            } else {
                if (current.isNotEmpty()) {
                    lines.add(current.toString())
                }
                current = StringBuilder(w)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        // Limit lines to 3 with ellipsis
        if (lines.size > 3) {
            val limited = lines.take(3).toMutableList()
            val last = limited.last()
            val ellipsized = ellipsize(last, paint, maxWidth)
            limited[limited.lastIndex] = ellipsized
            return limited
        }
        return lines
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        var t = text
        if (paint.measureText(t) <= maxWidth) return t
        while (t.isNotEmpty() && paint.measureText("$t...") > maxWidth) {
            t = t.dropLast(1)
        }
        return if (t.isEmpty()) "..." else "$t..."
    }

    /**
     * Convert ARGB to the requested YUV420 format (planar I420 or semi-planar NV12/NV21 style).
     * We produce:
     *  - COLOR_FormatYUV420Planar: Y plane, then U plane, then V plane (I420)
     *  - COLOR_FormatYUV420SemiPlanar: Y plane, then interleaved UV (NV12)
     */
    private fun convertARGBToYUV(
        argb: IntArray,
        width: Int,
        height: Int,
        out: ByteArray,
        colorFormat: Int
    ) {
        val frameSize = width * height
        val qFrameSize = frameSize / 4
        val isPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        val isSemi = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + qFrameSize
        var uvIndex = frameSize // for semi-planar

        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[index]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                var y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                var u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                var v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                y = y.coerceIn(0, 255)
                u = u.coerceIn(0, 255)
                v = v.coerceIn(0, 255)

                out[yIndex++] = y.toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    if (isPlanar) {
                        out[uIndex++] = u.toByte()
                        out[vIndex++] = v.toByte()
                    } else if (isSemi) {
                        // Switched to NV21 (VU interleaved) to fix purple/green tint observed with previous UV order
                        out[uvIndex++] = v.toByte()
                        out[uvIndex++] = u.toByte()
                    }
                }
                index++
            }
        }
    }

    private fun runBlockingIfNeeded(block: suspend () -> Bitmap?): Bitmap? {
        return try {
            kotlinx.coroutines.runBlocking { block() }
        } catch (e: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        releaseWakeLock()
    }
}

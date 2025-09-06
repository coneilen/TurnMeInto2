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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val openAIService = OpenAIService.getInstance()
    private var wakeLock: PowerManager.WakeLock? = null
    
    var selectedImageUri = mutableStateOf<Uri?>(null)
        private set
    
    var customPrompt = mutableStateOf("")
        private set
    
    var editedImageUrl = mutableStateOf<String?>(null)
        private set
    
    var isProcessing = mutableStateOf(false)
        private set
    
    var errorMessage = mutableStateOf<String?>(null)
        private set
    
    var showOriginal = mutableStateOf(true)
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
        editedImageUrl.value = null
        errorMessage.value = null
        showOriginal.value = true
        saveMessage.value = null
    }
    
    fun updateCustomPrompt(prompt: String) {
        customPrompt.value = prompt
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
    
    private fun acquireWakeLock(context: Context) {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "PhotoAI:ImageProcessing"
            )
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max
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
    
    fun toggleImageView() {
        if (editedImageUrl.value != null) {
            showOriginal.value = !showOriginal.value
        }
    }
    
    fun editImage(context: Context, uri: Uri, prompt: String) {
        viewModelScope.launch {
            try {
                isProcessing.value = true
                errorMessage.value = null
                
                // Acquire wake lock to prevent screen from sleeping
                acquireWakeLock(context)
                
                val result = openAIService.editImage(context, uri, prompt, downsizeImages.value, inputFidelity.value, quality.value)
                
                result.fold(
                    onSuccess = { imageUrl ->
                        android.util.Log.d("MainViewModel", "Received image URL: ${imageUrl.take(100)}...")
                        
                        // If it's a data URL, convert it to a temporary file for better Coil compatibility
                        if (imageUrl.startsWith("data:image/")) {
                            viewModelScope.launch {
                                val tempFileUri = convertDataUrlToTempFile(context, imageUrl)
                                editedImageUrl.value = tempFileUri?.toString() ?: imageUrl
                                showOriginal.value = false // Show edited image when ready
                            }
                        } else {
                            editedImageUrl.value = imageUrl
                            showOriginal.value = false // Show edited image when ready
                        }
                    },
                    onFailure = { exception ->
                        errorMessage.value = exception.message ?: "An error occurred while editing the image"
                        editedImageUrl.value = null
                    }
                )
            } catch (e: Exception) {
                errorMessage.value = e.message ?: "An unexpected error occurred"
                editedImageUrl.value = null
            } finally {
                isProcessing.value = false
                // Release wake lock when processing is complete
                releaseWakeLock()
            }
        }
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
    
    fun shareEditedImage() {
        viewModelScope.launch {
            try {
                if (editedImageUrl.value == null) {
                    errorMessage.value = "No edited image to share"
                    return@launch
                }

                val bitmap = urlToBitmap(editedImageUrl.value!!)
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

package com.photoai.app.ui.viewmodel

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoai.app.api.OpenAIService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainViewModel : ViewModel() {
    private val openAIService = OpenAIService.getInstance()
    
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
    
    fun toggleImageView() {
        if (editedImageUrl.value != null) {
            showOriginal.value = !showOriginal.value
        }
    }
    
    fun editImage(context: Context, uri: Uri, prompt: String) {
        viewModelScope.launch {
            isProcessing.value = true
            errorMessage.value = null
            
            try {
                val result = openAIService.editImage(context, uri, prompt)
                
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
    
    fun clearSaveMessage() {
        saveMessage.value = null
    }
}

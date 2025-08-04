package com.photoai.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

fun createImageFile(context: Context): File {
    val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val storageDir: File = context.getExternalFilesDir(null) ?: context.filesDir
    return File.createTempFile(
        "JPEG_${timeStamp}_",
        ".jpg",
        storageDir
    )
}

fun uriToBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream?.close()
        
        // Handle image rotation
        val rotatedBitmap = handleImageRotation(context, uri, bitmap)
        
        // Resize if too large for better performance
        resizeBitmapIfNeeded(rotatedBitmap, 1024)
    } catch (e: Exception) {
        null
    }
}

private fun handleImageRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val exif = ExifInterface(inputStream!!)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED
        )
        inputStream.close()
        
        rotateBitmap(bitmap, orientation)
    } catch (e: Exception) {
        bitmap
    }
}

private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        else -> return bitmap
    }
    
    return try {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } catch (e: Exception) {
        bitmap
    }
}

private fun resizeBitmapIfNeeded(bitmap: Bitmap, maxSize: Int): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    
    if (width <= maxSize && height <= maxSize) {
        return bitmap
    }
    
    val ratio = if (width > height) {
        maxSize.toFloat() / width
    } else {
        maxSize.toFloat() / height
    }
    
    val newWidth = (width * ratio).toInt()
    val newHeight = (height * ratio).toInt()
    
    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}

fun resizeBitmapToHalf(bitmap: Bitmap): Bitmap {
    val newWidth = bitmap.width / 2
    val newHeight = bitmap.height / 2
    
    // Ensure minimum size of 64x64 pixels
    val finalWidth = if (newWidth < 64) 64 else newWidth
    val finalHeight = if (newHeight < 64) 64 else newHeight
    
    return Bitmap.createScaledBitmap(bitmap, finalWidth, finalHeight, true)
}

suspend fun urlToBitmap(imageUrl: String): Bitmap? {
    return withContext(Dispatchers.IO) {
        try {
            if (imageUrl.startsWith("data:image/")) {
                // Handle data URLs (base64 encoded images)
                val base64Data = imageUrl.substringAfter("base64,")
                val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            } else {
                // Handle regular HTTP URLs
                val url = URL(imageUrl)
                val inputStream = url.openConnection().getInputStream()
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                bitmap
            }
        } catch (e: Exception) {
            android.util.Log.e("FileUtils", "Error converting URL to bitmap: ${e.message}")
            null
        }
    }
}

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

// Add function to handle camera images with correct orientation
suspend fun uriToBitmapWithCorrectOrientation(context: Context, uri: Uri): Bitmap? {
    return withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            
            // Load the bitmap
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            if (bitmap == null) return@withContext null
            
            // Handle EXIF orientation for camera images
            val exifInputStream: InputStream? = contentResolver.openInputStream(uri)
            val exif = exifInputStream?.let { ExifInterface(it) }
            exifInputStream?.close()
            
            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, 
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL
            
            android.util.Log.d("FileUtils", "Image orientation: $orientation")
            android.util.Log.d("FileUtils", "Original bitmap size: ${bitmap.width}x${bitmap.height}")
            
            // Apply rotation if needed
            val rotatedBitmap = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> flipBitmap(bitmap, horizontal = true)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> flipBitmap(bitmap, horizontal = false)
                else -> bitmap
            }
            
            android.util.Log.d("FileUtils", "Final bitmap size: ${rotatedBitmap.width}x${rotatedBitmap.height}")
            
            // Clean up original bitmap if we created a new one
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }
            
            rotatedBitmap
            
        } catch (e: Exception) {
            android.util.Log.e("FileUtils", "Error loading bitmap with orientation: ${e.message}")
            null
        }
    }
}

private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun flipBitmap(bitmap: Bitmap, horizontal: Boolean): Bitmap {
    val matrix = Matrix().apply {
        if (horizontal) {
            preScale(-1f, 1f)
        } else {
            preScale(1f, -1f)
        }
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

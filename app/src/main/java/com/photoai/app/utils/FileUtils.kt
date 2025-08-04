package com.photoai.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

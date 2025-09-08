package com.photoai.app.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.gson.annotations.SerializedName
import com.photoai.app.BuildConfig
import com.photoai.app.utils.uriToBitmapWithCorrectOrientation
import com.photoai.app.utils.PromptsLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

// Data classes for OpenAI API responses
data class ImageEditResponse(
    @SerializedName("created") val created: Long,
    @SerializedName("data") val data: List<ImageData>
)

data class ImageData(
    @SerializedName("url") val url: String? = null,
    @SerializedName("b64_json") val b64Json: String? = null,
    @SerializedName("revised_prompt") val revisedPrompt: String? = null
)

// Retrofit interface for OpenAI API
interface OpenAIApi {
    /**
     * Creates an edited or extended image given an original image and a prompt.
     * 
     * @param authorization Bearer token for authentication
     * @param image The image to edit. Must be a valid PNG file, less than 4MB, and square.
     * @param mask An additional image whose fully transparent areas indicate where image should be edited. 
     *             Must be a valid PNG file, less than 4MB, and have the same dimensions as image.
     * @param prompt A text description of the desired image(s). The maximum length is 1000 characters.
     * @param model The model to use for image editing. Currently only "dall-e-2" is supported.
     * @param n The number of images to generate. Must be between 1 and 10. Defaults to 1.
     * @param size The size of the generated images. Must be one of "256x256", "512x512", "1024x1024", "1024x1536", or "1536x1024". Defaults to "1024x1024".
     * @param user A unique identifier representing your end-user, which can help OpenAI to monitor and detect abuse.
     * @param input_fidelity The fidelity of the input image. Accepts "low" or "high". Defaults to "low".
     * @param quality The quality of the generated image. Accepts "low" or "high". Defaults to "low".
     */
    @Multipart
    @POST("v1/images/edits")
    suspend fun createImageEdit(
        @Header("Authorization") authorization: String,
        @Part image: MultipartBody.Part,
        @Part mask: MultipartBody.Part? = null,
        @Part("prompt") prompt: okhttp3.RequestBody,
        @Part("model") model: okhttp3.RequestBody? = null,
        @Part("n") n: okhttp3.RequestBody? = null,
        @Part("size") size: okhttp3.RequestBody? = null,
        @Part("user") user: okhttp3.RequestBody? = null,
        @Part("input_fidelity") input_fidelity: okhttp3.RequestBody? = "low".toRequestBody("text/plain".toMediaType()),
        @Part("quality") quality: okhttp3.RequestBody? = "low".toRequestBody("text/plain".toMediaType()),
        @Part("moderation") moderation: okhttp3.RequestBody? = "low".toRequestBody("text/plain".toMediaType()),

    ): Response<ImageEditResponse>
}

class OpenAIService {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()
    
    private val api: OpenAIApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenAIApi::class.java)
    }
    
    private fun resizeBitmapToHalf(bitmap: Bitmap): Bitmap {
        val newWidth = bitmap.width / 2
        val newHeight = bitmap.height / 2
        
        // Ensure minimum size of 64x64 pixels
        val finalWidth = if (newWidth < 64) 64 else newWidth
        val finalHeight = if (newHeight < 64) 64 else newHeight
        
        // Create a new ARGB_8888 bitmap explicitly
        val resizedBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(resizedBitmap)
        
        // Scale the original bitmap and draw it onto the new ARGB_8888 bitmap
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
        val destRect = android.graphics.Rect(0, 0, finalWidth, finalHeight)
        canvas.drawBitmap(bitmap, srcRect, destRect, paint)
        
        return resizedBitmap
    }
    
    suspend fun editImage(
        context: Context, 
        uri: Uri, 
        prompt: String, 
        downsizeImage: Boolean = true,
        inputFidelity: String = "low", // "low" or "high"
        quality: String = "low", // "low", "medium", or "high"
        isEditingEditedImage: Boolean = false
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                if (BuildConfig.OPENAI_API_KEY.isBlank()) {
                    return@withContext Result.failure(
                        Exception("OpenAI API key not configured. Please set OPENAI_API_KEY environment variable.")
                    )
                }
                
                // Load the image as a bitmap with correct orientation first
                val originalBitmap = uriToBitmapWithCorrectOrientation(context, uri)
                    ?: return@withContext Result.failure(Exception("Unable to load and orient image file"))
                
                // Ensure the original bitmap is in ARGB_8888 format (RGBA)
                val rgbaOriginalBitmap = if (originalBitmap.config != Bitmap.Config.ARGB_8888) {
                    val rgbaBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    originalBitmap.recycle()
                    rgbaBitmap
                } else {
                    originalBitmap
                }
                
                // Conditionally resize the bitmap based on downsizeImage parameter
                val processedBitmap = if (downsizeImage) {
                    resizeBitmapToHalf(rgbaOriginalBitmap)
                } else {
                    // If not downsizing, still ensure minimum size requirements
                    val width = rgbaOriginalBitmap.width
                    val height = rgbaOriginalBitmap.height
                    
                    if (width < 64 || height < 64) {
                        // If image is too small, resize to minimum 64x64
                        val finalWidth = if (width < 64) 64 else width
                        val finalHeight = if (height < 64) 64 else height
                        
                        val resizedBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(resizedBitmap)
                        
                        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                        val srcRect = android.graphics.Rect(0, 0, width, height)
                        val destRect = android.graphics.Rect(0, 0, finalWidth, finalHeight)
                        canvas.drawBitmap(rgbaOriginalBitmap, srcRect, destRect, paint)
                        
                        if (rgbaOriginalBitmap != originalBitmap) {
                            rgbaOriginalBitmap.recycle()
                        }
                        resizedBitmap
                    } else {
                        // Image is already acceptable size, use as-is
                        rgbaOriginalBitmap
                    }
                }
                
                android.util.Log.d("OpenAIService", "Original size: ${rgbaOriginalBitmap.width}x${rgbaOriginalBitmap.height}")
                android.util.Log.d("OpenAIService", "Processed size: ${processedBitmap.width}x${processedBitmap.height}")
                android.util.Log.d("OpenAIService", "Downsize enabled: $downsizeImage")
                android.util.Log.d("OpenAIService", "Bitmap config: ${processedBitmap.config}")
                
                // Convert bitmap directly to PNG byte array to preserve RGBA
                val byteArrayOutputStream = ByteArrayOutputStream()
                val success = processedBitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
                
                if (!success) {
                    return@withContext Result.failure(Exception("Failed to compress bitmap to PNG"))
                }
                
                val pngByteArray = byteArrayOutputStream.toByteArray()
                byteArrayOutputStream.close()
                
                android.util.Log.d("OpenAIService", "PNG byte array size: ${pngByteArray.size} bytes")
                
                // Verify PNG header (should start with PNG signature)
                if (pngByteArray.size >= 8) {
                    val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                    val headerMatches = pngByteArray.take(8).toByteArray().contentEquals(pngSignature)
                    android.util.Log.d("OpenAIService", "PNG header valid: $headerMatches")
                }
                
                // Clean up bitmaps
                if (rgbaOriginalBitmap != processedBitmap) {
                    rgbaOriginalBitmap.recycle()
                }
                if (processedBitmap != originalBitmap) {
                    processedBitmap.recycle()
                }
                
                // Create multipart request body directly from byte array
                val imageRequestBody = pngByteArray.toRequestBody("image/png".toMediaType())
                val imagePart = MultipartBody.Part.createFormData("image", "image.png", imageRequestBody)
                
                // Get editable base prompt from PromptsLoader only if not editing an edited image
                val fullPrompt = if (isEditingEditedImage) {
                    prompt  // Use just the prompt without base prompt for edited images
                } else {
                    val basePrompt = PromptsLoader.getBasePrompt(context)
                    basePrompt + prompt  // Use base prompt + user prompt for original image
                }
                
                val promptBody = fullPrompt.toRequestBody("text/plain".toMediaType())
                // Note: Using "gpt-image-1" as specified. Standard OpenAI image editing typically uses "dall-e-2"
                val modelBody = "gpt-image-1".toRequestBody("text/plain".toMediaType())
                val nBody = "1".toRequestBody("text/plain".toMediaType())
                val sizeBody = when {
                    processedBitmap.width > processedBitmap.height -> "1536x1024".toRequestBody("text/plain".toMediaType())
                    processedBitmap.width < processedBitmap.height -> "1024x1536".toRequestBody("text/plain".toMediaType())
                    else -> "1024x1024".toRequestBody("text/plain".toMediaType())
                }
                val inputFidelityBody = inputFidelity.toRequestBody("text/plain".toMediaType())
                val qualityBody = quality.toRequestBody("text/plain".toMediaType())
                
                android.util.Log.d("OpenAIService", "Making API call with prompt: $prompt")
                android.util.Log.d("OpenAIService", "Model: gpt-image-1")
                android.util.Log.d("OpenAIService", "Input Fidelity: $inputFidelity")
                android.util.Log.d("OpenAIService", "Quality: $quality")
                android.util.Log.d("OpenAIService", "Size: ${if (processedBitmap.width > processedBitmap.height) "1536x1024" else if (processedBitmap.width < processedBitmap.height) "1024x1536" else "1024x1024"}")
                android.util.Log.d("OpenAIService", "Response format: b64_json")
                
                // Make API call
                val response = api.createImageEdit(
                    authorization = "Bearer ${BuildConfig.OPENAI_API_KEY}",
                    image = imagePart,
                    mask = null,
                    prompt = promptBody,
                    model = modelBody,
                    n = nBody,
                    size = sizeBody,
                    user = null,
                    input_fidelity = inputFidelityBody,
                    quality = qualityBody
                )
                
                if (response.isSuccessful) {
                    val responseBody = response.body()
                    android.util.Log.d("OpenAIService", "Response body: $responseBody")
                    android.util.Log.d("OpenAIService", "Created timestamp: ${responseBody?.created}")
                    android.util.Log.d("OpenAIService", "Data array size: ${responseBody?.data?.size}")
                    
                    val firstImage = responseBody?.data?.firstOrNull()
                    if (firstImage != null) {
                        android.util.Log.d("OpenAIService", "First image - URL: ${firstImage.url}, B64 length: ${firstImage.b64Json?.length}, Revised prompt: ${firstImage.revisedPrompt}")
                        
                        val b64Data = firstImage.b64Json
                        if (b64Data != null && b64Data.isNotBlank()) {
                            // For gpt-image-1 model, we get base64 data instead of URL
                            // We'll return the base64 data as a data URL for the UI to handle
                            val dataUrl = "data:image/png;base64,$b64Data"
                            Result.success(dataUrl)
                        } else {
                            Result.failure(Exception("Base64 image data is null or empty in response. URL present: ${firstImage.url != null}"))
                        }
                    } else {
                        val errorMsg = if (responseBody?.data?.isEmpty() == true) {
                            "OpenAI returned empty data array"
                        } else {
                            "No image data in response: ${responseBody?.data}"
                        }
                        Result.failure(Exception(errorMsg))
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    android.util.Log.e("OpenAIService", "API Error: ${response.code()} ${response.message()}")
                    android.util.Log.e("OpenAIService", "Error body: $errorBody")
                    Result.failure(Exception("API call failed: ${response.code()} ${response.message()}. Error: $errorBody"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: OpenAIService? = null
        
        fun getInstance(): OpenAIService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OpenAIService().also { INSTANCE = it }
            }
        }
    }
}

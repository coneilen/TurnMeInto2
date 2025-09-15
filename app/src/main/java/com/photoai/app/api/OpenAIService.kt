package com.photoai.app.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.PowerManager
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
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

// Data classes for OpenAI API responses
data class VisionResponse(
    @SerializedName("created") val created: Long,
    @SerializedName("choices") val choices: List<Choice>
)

data class Choice(
    @SerializedName("message") val message: Message,
    @SerializedName("index") val index: Int
)

data class Message(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class ChatRequest(
    @SerializedName("model") val model: String = "gpt-4o",
    @SerializedName("messages") val messages: List<ChatMessage>,
    @SerializedName("max_tokens") val maxTokens: Int = 300,
    @SerializedName("temperature") val temperature: Double = 0.7
)

data class ForegroundPeopleCount(
    @SerializedName("foreground_people_count") val foregroundPeopleCount: Int,
    @SerializedName("reason") val reason: String? = null
)

data class VisionRequest(
    @SerializedName("model") val model: String = "gpt-4o",
    @SerializedName("messages") val messages: List<VisionMessage>,
    @SerializedName("max_tokens") val maxTokens: Int = 300
)

data class VisionMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: List<VisionContent>
)

data class VisionContent(
    @SerializedName("type") val type: String,
    @SerializedName("text") val text: String? = null,
    @SerializedName("image_url") val imageUrl: Map<String, String>? = null
)

data class ChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

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
    @POST("v1/chat/completions")
    suspend fun generateChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: ChatRequest
    ): Response<VisionResponse>

    @POST("v1/chat/completions")
    suspend fun detectPersons(
        @Header("Authorization") authorization: String,
        @Body request: VisionRequest
    ): Response<VisionResponse>

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
    suspend fun generateMultiPersonPrompt(context: Context, prompt: String): Result<String> {
        android.util.Log.d("OpenAIService", "Generating multi-person prompt for: $prompt")
        
        // Acquire wake lock for the duration of the API call
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "PhotoAI:MultiPersonPrompt"
        ).apply {
            acquire(15 * 60 * 1000L) // 15 minutes max
        }
        return try {
            if (BuildConfig.OPENAI_API_KEY.isBlank()) {
                return Result.failure(Exception("OpenAI API key not configured"))
            }

            // Check cache first
            val cached = promptGenerationCache[prompt]
            val now = System.currentTimeMillis()

            if (cached != null && (now - cached.second) < CACHE_EXPIRY_MS) {
                android.util.Log.d("OpenAIService", "Using cached multi-person prompt: ${cached.first.take(50)}...")
                return Result.success(cached.first)
            }
            android.util.Log.d("OpenAIService", "Cache miss, generating new prompt")

            val request = ChatRequest(
                model = "gpt-4o",
                messages = listOf(
                    ChatMessage(
                        role = "system",
                        content = MULTI_PERSON_SYSTEM_PROMPT
                    ),
                    ChatMessage(
                        role = "user",
                        content = prompt
                    )
                )
            )

            val response = api.generateChatCompletion(
                authorization = "Bearer ${BuildConfig.OPENAI_API_KEY}",
                request = request
            )

            if (response.isSuccessful) {
                val multiPersonPrompt = response.body()?.choices?.firstOrNull()?.message?.content
                    ?: throw Exception("Invalid response format")
                
                android.util.Log.d("OpenAIService", "Generated prompt: ${multiPersonPrompt.take(50)}...")

                // Cache the result
                synchronized(promptGenerationCache) {
                    promptGenerationCache[prompt] = Pair(multiPersonPrompt, now)
                }
                android.util.Log.d("OpenAIService", "Successfully generated and cached multi-person prompt")
                try {
                    wakeLock.release()
                } catch (e: Exception) {
                    android.util.Log.e("OpenAIService", "Error releasing wake lock: ${e.message}")
                }
                Result.success(multiPersonPrompt)
            } else {
                val errorBody = response.errorBody()?.string() ?: "Unknown error"
                android.util.Log.e("OpenAIService", "API Error: ${response.code()} ${response.message()} - $errorBody")
                try {
                    wakeLock.release()
                } catch (e: Exception) {
                    android.util.Log.e("OpenAIService", "Error releasing wake lock: ${e.message}")
                }
                Result.failure(Exception("Failed to generate multi-person prompt: $errorBody"))
            }
        } catch (e: Exception) {
            android.util.Log.e("OpenAIService", "Error generating multi-person prompt", e)
            try {
                wakeLock.release()
            } catch (ex: Exception) {
                android.util.Log.e("OpenAIService", "Error releasing wake lock: ${ex.message}")
            }
            Result.failure(e)
        }
    }

    private val personCountCache = mutableMapOf<String, Pair<Int, Long>>()
    private val promptGenerationCache = mutableMapOf<String, Pair<String, Long>>()
    private val CACHE_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L // 7 days

    // System prompt for generating multi-person prompts
    private val MULTI_PERSON_SYSTEM_PROMPT = """
        Convert the given prompt to work with multiple people. Rules:
        1. Change singular references to plural (e.g., "this person" -> "these people", "them" -> "them all", "their" -> "their")
        2. Keep the same style and artistic intent but adapt it for multiple subjects
        3. Ensure all references to appearance, features, and characteristics apply to everyone
        4. Preserve specific theme instructions (movie/TV characters, art styles, etc.)
        5. Return ONLY the converted prompt, no explanations
        Examples:
        Input: "Make this person look like a sports team mascot, exaggerated, like a full-on cartoon mascot with a giant head and foam costume"
        Output: "Make these people look like sports team mascots, exaggerated, like full-on cartoon mascots with giant heads and foam costumes"
        
        Input: "Transform them into a glowing neon cyberpunk character with vibrant colors"
        Output: "Transform them all into glowing neon cyberpunk characters with vibrant colors"
        
        Input: "Make them look like an oil painting in the style of Rembrandt"
        Output: "Make them all look like they're in an oil painting in the style of Rembrandt"
    """.trimIndent()

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
        // Force high quality settings when editing an edited image to prevent quality degradation
        val effectiveInputFidelity = if (isEditingEditedImage) "high" else inputFidelity
        val effectiveQuality = if (isEditingEditedImage) "high" else quality
        val effectiveDownsizeImage = if (isEditingEditedImage) false else downsizeImage
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
                val processedBitmap = if (effectiveDownsizeImage) {
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
                android.util.Log.d("OpenAIService", "Downsize enabled: $effectiveDownsizeImage")
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
                    processedBitmap.width > processedBitmap.height -> "1536x1024"
                    processedBitmap.width < processedBitmap.height -> "1024x1536"
                    else -> "1024x1024"
                }

                android.util.Log.d("OpenAIService", "Making API call with prompt: $prompt")
                android.util.Log.d("OpenAIService", "Model: gpt-image-1")
                android.util.Log.d("OpenAIService", "Input Fidelity: $effectiveInputFidelity")
                android.util.Log.d("OpenAIService", "Quality: $effectiveQuality")
                android.util.Log.d("OpenAIService", "Size: $sizeBody")
                android.util.Log.d("OpenAIService", "Response format: b64_json")
                
                // Make API call
                val response = api.createImageEdit(
                    authorization = "Bearer ${BuildConfig.OPENAI_API_KEY}",
                    image = imagePart,
                    mask = null,
                    prompt = promptBody,
                    model = modelBody,
                    n = nBody,
                    size = sizeBody.toRequestBody("text/plain".toMediaType()),
                    user = null,
                    input_fidelity = effectiveInputFidelity.toRequestBody("text/plain".toMediaType()),
                    quality = effectiveQuality.toRequestBody("text/plain".toMediaType())
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

    private fun convertBitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val bytes = outputStream.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
    }

    suspend fun detectPersons(context: Context, uri: Uri): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                // Check cache first
                val key = uri.toString()
                val cached = personCountCache[key]
                val now = System.currentTimeMillis()

                if (cached != null && (now - cached.second) < CACHE_EXPIRY_MS) {
                    return@withContext Result.success(cached.first)
                }

                if (BuildConfig.OPENAI_API_KEY.isBlank()) {
                    return@withContext Result.failure(Exception("OpenAI API key not configured"))
                }

                // Load image and resize
                val originalBitmap = uriToBitmapWithCorrectOrientation(context, uri)
                    ?: return@withContext Result.failure(Exception("Unable to load image"))
                
                // Resize the bitmap to reduce upload size
                val resizedBitmap = resizeBitmapToHalf(originalBitmap)
                originalBitmap.recycle()

                // Convert to base64 and cleanup
                val base64Image = convertBitmapToBase64(resizedBitmap)
                resizedBitmap.recycle()

                val request = VisionRequest(
                    model = "gpt-4o",
                    messages = listOf(
                        VisionMessage(
                            role = "user",
                            content = listOf(
                                VisionContent(
                                    type = "text",
                                    text = "Count how many people are prominently in the foreground. A person is in the foreground if they are in focus and occupy a substantial portion of the frame (roughly upper-body size or larger), clearly closer than background patrons. Ignore reflections, posters, and partial silhouettes. Respond with ONLY a single number."
                                ),
                                VisionContent(
                                    type = "image_url",
                                    imageUrl = mapOf("url" to "data:image/jpeg;base64,$base64Image")
                                )
                            )
                        )
                    ),
                    maxTokens = 50
                )

                val response = api.detectPersons(
                    authorization = "Bearer ${BuildConfig.OPENAI_API_KEY}",
                    request = request
                )

                if (response.isSuccessful) {
                    val content = response.body()?.choices?.firstOrNull()?.message?.content
                        ?: throw Exception("Invalid response format")
                    
                    // Extract the number from the response
                    val number = content.trim().toIntOrNull()
                        ?: throw Exception("Failed to parse person count from response: $content")

                    // Cache the result
                    personCountCache[key] = Pair(number, now)
                    Result.success(number)
                } else {
                    android.util.Log.e("OpenAIService", "API Error: ${response.code()} ${response.message()} - ${response.errorBody()?.string()}")
                    Result.failure(Exception("API call failed: ${response.code()} ${response.message()}"))
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

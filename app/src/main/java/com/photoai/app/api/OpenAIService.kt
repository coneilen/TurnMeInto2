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
import com.photoai.app.utils.ImagePrep
import com.photoai.app.utils.PreparedImage
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

/**
 * Full edit result returning both the raw padded model output (base size) and the restored
 * (cropped + optionally upscaled) image, along with the preparation metadata used so that
 * iterative edits can reuse a stable coordinate frame.
 */
data class EditResult(
    val paddedDataUrl: String,
    val restoredDataUrl: String,
    val meta: PreparedImage
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

    // Prefix applied only when re-editing an already edited image to constrain changes
    private val EDITED_IMAGE_PROMPT_PREFIX = """
        Only apply the edits to the image in the given prompt. 
        Do not make any other changes. 
        Do not modify the brightness of the original image. 
        All people in the image are really puppets. 
        Prompt:
        """
    
    @Deprecated("Replaced by dynamic downscale; retains signature for compatibility.")
    private fun resizeBitmapToHalf(@Suppress("UNUSED_PARAMETER") bitmap: Bitmap): Bitmap {
        // Downscale largest dimension to at most 1024 to reduce bandwidth; preserve aspect.
        val maxDim = 1024
        val w = bitmap.width
        val h = bitmap.height
        val largest = kotlin.math.max(w, h)
        if (largest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / largest.toFloat()
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        if (scaled != bitmap) {
            // Do not recycle original here; caller manages lifecycle explicitly.
        }
        return scaled
    }
    
    /**
     * Advanced edit pipeline that supports:
     *  - First-pass edits from an original Uri (providing new PreparedImage meta)
     *  - Iterative edits from an already padded base-size data URL (reusing prior meta)
     *  - Returning BOTH padded and restored (cropped/upscaled) variants
     *
     * @param uri Original image Uri (first edit). Ignored if paddedInputDataUrl supplied.
     * @param paddedInputDataUrl A prior padded model output (data:image/png;base64,...) for iterative edits.
     * @param previousMeta The meta from the first preparation; required when paddedInputDataUrl is used.
     * @param isEditingEditedImage True if this is an iterative edit (affects fidelity/quality heuristics).
     */
    suspend fun editImageAdvanced(
        context: Context,
        uri: Uri? = null,
        prompt: String,
        inputFidelity: String = "low",
        quality: String = "low",
        isEditingEditedImage: Boolean = false,
        paddedInputDataUrl: String? = null,
        previousMeta: PreparedImage? = null,
        returnBoth: Boolean = true,
        returnPaddedOnly: Boolean = false
    ): Result<EditResult> {
        return withContext(Dispatchers.IO) {
            try {
                if (BuildConfig.OPENAI_API_KEY.isBlank()) {
                    return@withContext Result.failure(Exception("OpenAI API key not configured. Please set OPENAI_API_KEY environment variable."))
                }

                val effectiveInputFidelity = if (isEditingEditedImage) "low" else inputFidelity
                val effectiveQuality = if (isEditingEditedImage) "high" else quality

                // Acquire or construct padded bitmap + meta
                val sourceBitmap: Bitmap?
                var meta: PreparedImage? = null
                var paddedBitmap: Bitmap

                if (paddedInputDataUrl != null) {
                    // Iterative path: decode provided padded image (must have previousMeta)
                    if (previousMeta == null) {
                        return@withContext Result.failure(Exception("previousMeta required for iterative padded edit"))
                    }
                    val b64 = paddedInputDataUrl.substringAfter("base64,", "")
                    if (b64.isBlank()) {
                        return@withContext Result.failure(Exception("Invalid padded data URL"))
                    }
                    val decoded = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    val decodedBmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                        ?: return@withContext Result.failure(Exception("Failed to decode padded input image"))
                    paddedBitmap = if (decodedBmp.config != Bitmap.Config.ARGB_8888) {
                        val cpy = decodedBmp.copy(Bitmap.Config.ARGB_8888, false)
                        decodedBmp.recycle()
                        cpy
                    } else decodedBmp
                    meta = previousMeta
                    sourceBitmap = null
                    android.util.Log.d(
                        "ImagePipeline",
                        "phase=iterative_use_padded reuseMeta base=${meta.baseW}x${meta.baseH} contentRect=${meta.contentRect.left},${meta.contentRect.top},${meta.contentRect.right},${meta.contentRect.bottom}"
                    )
                } else {
                    // First-pass path: load from Uri and prepare
                    val loaded = uri?.let { uriToBitmapWithCorrectOrientation(context, it) }
                        ?: return@withContext Result.failure(Exception("Unable to load and orient image file"))
                    val src = if (loaded.config != Bitmap.Config.ARGB_8888) {
                        val converted = loaded.copy(Bitmap.Config.ARGB_8888, false)
                        loaded.recycle()
                        converted
                    } else loaded
                    sourceBitmap = src
                    android.util.Log.d(
                        "ImagePipeline",
                        "phase=editImage_load orig=${src.width}x${src.height} config=${src.config}"
                    )
                    val preparePair = ImagePrep.prepare(src)
                    paddedBitmap = preparePair.first
                    meta = preparePair.second
                    android.util.Log.d(
                        "ImagePipeline",
                        "phase=editImage_prepared orig=${src.width}x${src.height} target=${meta.targetW}x${meta.targetH} base=${meta.baseW}x${meta.baseH} contentRect=${meta.contentRect.left},${meta.contentRect.top},${meta.contentRect.right},${meta.contentRect.bottom}"
                    )
                }

                // Compress padded
                val bos = ByteArrayOutputStream()
                if (!paddedBitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)) {
                    if (paddedInputDataUrl == null) {
                        sourceBitmap?.recycle()
                    }
                    paddedBitmap.recycle()
                    return@withContext Result.failure(Exception("Failed to compress padded bitmap"))
                }
                val pngBytes = bos.toByteArray()
                bos.close()

                if (pngBytes.size >= 8) {
                    val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                    val headerOk = pngBytes.take(8).toByteArray().contentEquals(sig)
                    android.util.Log.d("OpenAIService", "Prepared PNG header valid: $headerOk sizeBytes=${pngBytes.size}")
                }

                val imageRequestBody = pngBytes.toRequestBody("image/png".toMediaType())
                val imagePart = MultipartBody.Part.createFormData("image", "image.png", imageRequestBody)

                // Full prompt assembly (same logic as legacy)
                val fullPrompt = if (isEditingEditedImage) {
                    if (prompt.startsWith(EDITED_IMAGE_PROMPT_PREFIX)) prompt else EDITED_IMAGE_PROMPT_PREFIX + prompt
                } else {
                    PromptsLoader.getBasePrompt(context) + prompt
                }

                val workingMeta = meta ?: return@withContext Result.failure(Exception("Internal error: meta not initialized"))
                val sizeBodyString = "${workingMeta.baseW}x${workingMeta.baseH}"

                android.util.Log.d(
                    "OpenAIService",
                    "Making API call (adv) size=$sizeBodyString inputFidelity=$effectiveInputFidelity quality=$effectiveQuality iterative=$isEditingEditedImage"
                )
                android.util.Log.d(
                    "ImagePipeline",
                    "phase=editImage_request sizeParam=$sizeBodyString fidelity=$effectiveInputFidelity quality=$effectiveQuality pngBytes=${pngBytes.size}"
                )

                val response = api.createImageEdit(
                    authorization = "Bearer ${BuildConfig.OPENAI_API_KEY}",
                    image = imagePart,
                    mask = null,
                    prompt = fullPrompt.toRequestBody("text/plain".toMediaType()),
                    model = "gpt-image-1".toRequestBody("text/plain".toMediaType()),
                    n = "1".toRequestBody("text/plain".toMediaType()),
                    size = sizeBodyString.toRequestBody("text/plain".toMediaType()),
                    user = null,
                    input_fidelity = effectiveInputFidelity.toRequestBody("text/plain".toMediaType()),
                    quality = effectiveQuality.toRequestBody("text/plain".toMediaType())
                )

                // Recycle intermediates
                if (paddedInputDataUrl == null) {
                    sourceBitmap?.recycle()
                }
                paddedBitmap.recycle()

                if (!response.isSuccessful) {
                    val err = response.errorBody()?.string()
                    android.util.Log.e("OpenAIService", "API Error: ${response.code()} ${response.message()} body=$err")
                    return@withContext Result.failure(
                        Exception("API call failed: ${response.code()} ${response.message()} error=$err")
                    )
                }

                val body = response.body()
                val imgData = body?.data?.firstOrNull()
                val b64 = imgData?.b64Json
                if (b64.isNullOrBlank()) {
                    return@withContext Result.failure(Exception("Empty base64 image data in response"))
                }

                android.util.Log.d("OpenAIService", "Received edited padded image (adv) b64Length=${b64.length}")
                android.util.Log.d("ImagePipeline", "phase=editImage_response b64Len=${b64.length}")

                val paddedDataUrl = "data:image/png;base64,$b64"

                if (returnPaddedOnly) {
                    return@withContext Result.success(EditResult(paddedDataUrl, paddedDataUrl, workingMeta))
                }

                // Decode edited padded
                val decodedBytes = try {
                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                } catch (e: Exception) {
                    android.util.Log.e("OpenAIService", "Base64 decode failed; returning padded only.", e)
                    return@withContext Result.success(EditResult(paddedDataUrl, paddedDataUrl, workingMeta))
                }

                val editedPadded = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    ?: return@withContext Result.success(EditResult(paddedDataUrl, paddedDataUrl, workingMeta))
                android.util.Log.d("ImagePipeline", "phase=editImage_response_decoded padded=${editedPadded.width}x${editedPadded.height}")

                // Use original meta for restoration (stable frame)
                var restoreMeta = previousMeta ?: workingMeta

                // NEW: When iterating, allow recovery of newly generated content that leaked into
                // the vertical padding (top/bottom) outside the original contentRect. We scan
                // padding rows for non‑transparent pixels and expand the contentRect accordingly.
                // This prevents apparent "top cropping" when the model paints into the padding,
                // which was then discarded by strict cropping.
                if (isEditingEditedImage) {
                    try {
                        val originalRect = restoreMeta.contentRect
                        val w = editedPadded.width
                        val h = editedPadded.height
                        val pixels = IntArray(w * h)
                        editedPadded.getPixels(pixels, 0, w, 0, 0, w, h)

                        // Heuristic tuning:
                        //  - Only consider extending into the ORIGINAL padding zones
                        //  - Require a minimum opaque coverage per row to treat it as intentional content
                        //  - Ignore sparse / noisy single rows (must have >= 2 qualifying rows to extend)
                        //  - Separate thresholds for top/bottom; currently same value
                        val coverageThreshold = 0.22f           // >=22% of pixels with alpha > 48
                        val alphaCutoff = 48                     // treat very low alpha as transparent
                        val requireContiguousRows = 2            // need this many qualifying rows to adopt new boundary
                        val topMaxExtend = originalRect.top      // cannot extend above original top padding region
                        val bottomMaxExtend = h - originalRect.bottom // cannot extend below original bottom padding region

                        fun rowCoverage(y: Int): Float {
                            var count = 0
                            val rowIndex = y * w
                            for (x in 0 until w) {
                                val p = pixels[rowIndex + x]
                                if ((p ushr 24) > alphaCutoff) count++
                            }
                            return count.toFloat() / w.toFloat()
                        }

                        var newTop = originalRect.top
                        var qualifyingTopRows = 0
                        // Scan upward only within allowed extension window
                        for (y in (originalRect.top - 1) downTo 0) {
                            val withinWindow = (originalRect.top - y) <= topMaxExtend
                            if (!withinWindow) break
                            val cov = rowCoverage(y)
                            if (cov >= coverageThreshold) {
                                qualifyingTopRows++
                                if (qualifyingTopRows >= requireContiguousRows) {
                                    newTop = y
                                }
                            } else {
                                // stop when coverage falls below threshold after starting
                                if (qualifyingTopRows > 0) break
                            }
                        }

                        var newBottom = originalRect.bottom
                        var qualifyingBottomRows = 0
                        for (y in originalRect.bottom until h) {
                            val withinWindow = (y - originalRect.bottom) <= bottomMaxExtend
                            if (!withinWindow) break
                            val cov = rowCoverage(y)
                            if (cov >= coverageThreshold) {
                                qualifyingBottomRows++
                                if (qualifyingBottomRows >= requireContiguousRows) {
                                    newBottom = y + 1 // bottom exclusive
                                }
                            } else {
                                if (qualifyingBottomRows > 0) break
                            }
                        }

                        // Avoid pathological full expansion if only a thin noisy band exists
                        val expandedHeight = newBottom - newTop
                        val originalHeight = originalRect.height()
                        val excessiveFullFill = expandedHeight >= h && (qualifyingTopRows < 3 || qualifyingBottomRows < 3)
                        if (excessiveFullFill) {
                            // Revert if looks like accidental capture of noise
                            newTop = originalRect.top
                            newBottom = originalRect.bottom
                            android.util.Log.d(
                                "ImagePipeline",
                                "phase=restore_expand abort_full noisyTopRows=$qualifyingTopRows noisyBottomRows=$qualifyingBottomRows"
                            )
                        }

                        if (newTop != originalRect.top || newBottom != originalRect.bottom) {
                            // Small safety: do not extend by only 1 row (often noise)
                            val topDelta = originalRect.top - newTop
                            val bottomDelta = newBottom - originalRect.bottom
                            val effectiveTop = if (topDelta in 1..1) originalRect.top else newTop
                            val effectiveBottom = if (bottomDelta in 1..1) originalRect.bottom else newBottom

                            if (effectiveTop != originalRect.top || effectiveBottom != originalRect.bottom) {
                                val expanded = android.graphics.Rect(
                                    originalRect.left,
                                    effectiveTop.coerceAtLeast(0),
                                    originalRect.right,
                                    effectiveBottom.coerceIn(effectiveTop + 1, h)
                                )
                                android.util.Log.d(
                                    "ImagePipeline",
                                    "phase=restore_expand iterativePaddingExpansion oldTop=${originalRect.top} newTop=${expanded.top} oldBottom=${originalRect.bottom} newBottom=${expanded.bottom} topRows=$qualifyingTopRows bottomRows=$qualifyingBottomRows"
                                )
                                restoreMeta = restoreMeta.copy(contentRect = expanded)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ImagePipeline", "phase=restore_expand skip reason=${e.message}")
                    }
                }

                // POLICY: Only scale back to full original resolution on FIRST edit.
                // Iterative edits keep drawn size to avoid re-up/down sampling & preserve any
                // newly expanded padding content; export pipeline can upscale later.
                val doScaleToOriginal = !isEditingEditedImage

                android.util.Log.d(
                    "ImagePipeline",
                    "phase=restore_policy iterative=$isEditingEditedImage scaleToOriginal=$doScaleToOriginal contentRectTop=${restoreMeta.contentRect.top}"
                )

                val restoredBitmap = try {
                    ImagePrep.restore(editedPadded, restoreMeta, scaleToOriginal = doScaleToOriginal)
                } catch (e: Exception) {
                    android.util.Log.e("OpenAIService", "Restore failed; returning padded only.", e)
                    editedPadded
                }

                // Dimension / drift diagnostics
                if (doScaleToOriginal) {
                    if (restoredBitmap.width != restoreMeta.originalW || restoredBitmap.height != restoreMeta.originalH) {
                        android.util.Log.w(
                            "ImagePipeline",
                            "phase=restore_dim_mismatch expected=${restoreMeta.originalW}x${restoreMeta.originalH} got=${restoredBitmap.width}x${restoredBitmap.height}"
                        )
                    }
                } else {
                    // Iterative path should generally yield drawn dimensions (unless no downscale was applied originally)
                    if (restoreMeta.downscale < 0.999f &&
                        (restoredBitmap.width != restoreMeta.drawnW || restoredBitmap.height != restoreMeta.drawnH)
                    ) {
                        android.util.Log.d(
                            "ImagePipeline",
                            "phase=restore_iter unexpected_dims got=${restoredBitmap.width}x${restoredBitmap.height} drawnRef=${restoreMeta.drawnW}x${restoreMeta.drawnH}"
                        )
                    }
                }

                if (restoredBitmap != editedPadded) {
                    editedPadded.recycle()
                }

                val restoredBos = ByteArrayOutputStream()
                if (!restoredBitmap.compress(Bitmap.CompressFormat.PNG, 100, restoredBos)) {
                    restoredBitmap.recycle()
                    return@withContext Result.failure(Exception("Failed to compress restored bitmap"))
                }
                val restoredBytes = restoredBos.toByteArray()
                restoredBos.close()
                restoredBitmap.recycle()

                val restoredB64 = android.util.Base64.encodeToString(restoredBytes, android.util.Base64.NO_WRAP)
                android.util.Log.d(
                    "OpenAIService",
                    "Restored image produced (adv) (orig=${restoreMeta.originalW}x${restoreMeta.originalH}) b64Len=${restoredB64.length}"
                )
                android.util.Log.d(
                    "ImagePipeline",
                    "phase=editImage_restore restored=${restoreMeta.originalW}x${restoreMeta.originalH} final=${restoreMeta.originalW}x${restoreMeta.originalH} restoredB64Len=${restoredB64.length}"
                )
                val restoredDataUrl = "data:image/png;base64,$restoredB64"

                Result.success(EditResult(paddedDataUrl, restoredDataUrl, restoreMeta))
            } catch (e: Exception) {
                android.util.Log.e("OpenAIService", "editImageAdvanced failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Legacy wrapper retained for backward compatibility.
     * Returns only the restored (cropped/upscaled) data URL.
     */
    suspend fun editImage(
        context: Context,
        uri: Uri,
        prompt: String,
        downsizeImage: Boolean = true,
        inputFidelity: String = "low",
        quality: String = "low",
        isEditingEditedImage: Boolean = false,
        returnPadded: Boolean = false
    ): Result<String> {
        val advanced = editImageAdvanced(
            context = context,
            uri = uri,
            prompt = prompt,
            inputFidelity = inputFidelity,
            quality = quality,
            isEditingEditedImage = isEditingEditedImage,
            paddedInputDataUrl = null,
            previousMeta = null,
            returnBoth = true,
            returnPaddedOnly = returnPadded
        )
        return advanced.map { if (returnPadded) it.paddedDataUrl else it.restoredDataUrl }
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

                // Load image and resize (with logging)
                android.util.Log.d("OpenAIService", "detectPersons: loading bitmap for uri=$uri")
                val originalBitmap = uriToBitmapWithCorrectOrientation(context, uri)
                    ?: return@withContext Result.failure(Exception("Unable to load image"))
                val preW = originalBitmap.width
                val preH = originalBitmap.height
                val resizedBitmap = resizeBitmapToHalf(originalBitmap)
                val postW = resizedBitmap.width
                val postH = resizedBitmap.height
                if (resizedBitmap != originalBitmap) {
                    originalBitmap.recycle()
                }
                android.util.Log.d("OpenAIService", "detectPersons: bitmap size ${preW}x${preH} -> ${postW}x${postH}")

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
                    android.util.Log.d("OpenAIService", "detectPersons: raw model response='${content}'")
                    val number = Regex("\\d+").find(content)?.value?.toIntOrNull()
                        ?: throw Exception("Failed to parse numeric person count from response: $content")
                    if (number <= 0 || number > 20) {
                        throw Exception("Unrealistic person count: $number (response='$content')")
                    }
                    android.util.Log.d("OpenAIService", "detectPersons: parsed personCount=$number")

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

package com.photoai.app.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.photoai.app.R
import com.photoai.app.data.PromptsData
import com.photoai.app.data.PromptCategory
import com.photoai.app.data.FlexiblePromptsData
import java.io.InputStreamReader

object PromptsLoader {
    private const val PREFS_NAME = "prompts_data"
    private const val KEY_CUSTOM_PROMPTS = "custom_prompts"
    private const val KEY_BASE_PROMPT = "base_prompt"
    private const val KEY_DOWNSIZE_IMAGES = "downsize_images"
    private const val KEY_INPUT_FIDELITY = "input_fidelity"
    private const val KEY_QUALITY = "quality"
    private const val KEY_MULTI_PERSON_PROMPTS = "multi_person_prompts"

    private const val DEFAULT_BASE_PROMPT = """Use the following prompt to edit the provided image.
The generated image should maintain the facial features. hair color and build of the person so they are easily recognizable.
You should keep any eye glasses the person is wearing, but do not add them if they are not already wearing them.
Maintain the color and lighting of the scene.
The generated image should be photorealistic.
Only apply the edits to the image in the given prompt. 
Do not make any other changes. 
Do not modify the brightness of the original image.
Prompt: 
"""
    
private const val KEY_VERSION_PREFIX = "v2_"
private val specialCharsRegex = Regex("[^a-zA-Z0-9\\s]")
private val multipleWhitespaceRegex = Regex("\\s+")
private var generationInProgress = false
private var totalPromptsToGenerate = 0
private var promptsGenerated = 0
private var generationCallback: ((Float) -> Unit)? = null
    
    private var cachedPromptsData: PromptsData? = null
    private var cachedFlexiblePrompts: Map<String, List<PromptsData.Prompt>>? = null
    private var cachedBasePrompt: String? = null
    private var cachedDownsizeImages: Boolean? = null
    private var cachedInputFidelity: String? = null
    private var cachedQuality: String? = null
    private var cachedMultiPersonPrompts: Map<String, Pair<String, Long>>? = null

    fun isGeneratingPrompts(): Boolean = generationInProgress

    fun getGenerationProgress(): Float {
        return if (totalPromptsToGenerate == 0) 1f else promptsGenerated.toFloat() / totalPromptsToGenerate
    }

    fun setGenerationCallback(callback: ((Float) -> Unit)?) {
        generationCallback = callback
        // Ensure any pending completion state is communicated to the new callback
        if (callback != null && !generationInProgress) {
            callback(1f)
        }
    }

    private fun updateProgress(progress: Float) {
        generationCallback?.let { callback ->
            val coercedProgress = progress.coerceIn(0f, 1f)
            callback(coercedProgress)
            if (coercedProgress >= 1f) {
                generationInProgress = false
            }
        }
    }

    fun getBasePrompt(context: Context): String {
        cachedBasePrompt?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val basePrompt = prefs.getString(KEY_BASE_PROMPT, DEFAULT_BASE_PROMPT) ?: DEFAULT_BASE_PROMPT
        cachedBasePrompt = basePrompt
        return basePrompt
    }

    fun saveBasePrompt(context: Context, basePrompt: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_BASE_PROMPT, basePrompt)
            .apply()
        cachedBasePrompt = null
    }

    fun getDownsizeImages(context: Context): Boolean {
        cachedDownsizeImages?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val downsizeImages = prefs.getBoolean(KEY_DOWNSIZE_IMAGES, true)
        cachedDownsizeImages = downsizeImages
        return downsizeImages
    }

    fun saveDownsizeImages(context: Context, downsizeImages: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_DOWNSIZE_IMAGES, downsizeImages)
            .apply()
        cachedDownsizeImages = null
    }

    fun getInputFidelity(context: Context): String {
        cachedInputFidelity?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val inputFidelity = prefs.getString(KEY_INPUT_FIDELITY, "low") ?: "low"
        cachedInputFidelity = inputFidelity
        return inputFidelity
    }

    fun saveInputFidelity(context: Context, inputFidelity: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_INPUT_FIDELITY, inputFidelity)
            .apply()
        cachedInputFidelity = null
    }

    fun getQuality(context: Context): String {
        cachedQuality?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val quality = prefs.getString(KEY_QUALITY, "low") ?: "low"
        cachedQuality = quality
        return quality
    }

    fun saveQuality(context: Context, quality: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_QUALITY, quality)
            .apply()
        cachedQuality = null
    }

    // Format category name for display purposes
    private fun formatCategoryForDisplay(category: String): String {
        return category.split(Regex("[_\\s/-]+"))
            .joinToString(" ") { word ->
                word.trim().replaceFirstChar { it.uppercase() }
            }
    }

    // Convert display name back to storage format (with underscores)
    private fun formatDisplayToStorage(displayName: String): String {
        return displayName.lowercase().replace(" ", "_")
    }

    private fun encodePart(raw: String): String =
        android.util.Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )

    private fun createCacheKeyV2(category: String, promptName: String): String {
        return KEY_VERSION_PREFIX + encodePart(category) + "::" + encodePart(promptName.trim())
    }

    private fun createLegacyKey(category: String, promptName: String): String {
        return "${category}_${promptName.trim()}"
    }

    fun getMultiPersonPrompt(displayCategory: String, promptName: String): String? {
        synchronized(this) {
            val rawCategory = getRawCategory(displayCategory)
            // Use raw category for both v2 and legacy keys
            val v2Key = createCacheKeyV2(rawCategory, promptName)
            android.util.Log.d("PromptsLoader", "Looking up v2 key: $v2Key")
            android.util.Log.d("PromptsLoader", "Available cache keys: ${cachedMultiPersonPrompts?.keys}")
            var prompt = cachedMultiPersonPrompts?.get(v2Key)?.first

            if (prompt == null) {
                // Try legacy key
                val legacyKey = createLegacyKey(rawCategory, promptName)
                android.util.Log.d("PromptsLoader", "Looking up legacy key: $legacyKey")
                prompt = cachedMultiPersonPrompts?.get(legacyKey)?.first

                // If found with legacy key, migrate to v2 key
                if (prompt != null) {
                    cachedMultiPersonPrompts?.get(legacyKey)?.let { (promptValue, timestamp) ->
                        val updatedMap = (cachedMultiPersonPrompts ?: emptyMap()).toMutableMap()
                        updatedMap[v2Key] = Pair(promptValue, timestamp)
                        cachedMultiPersonPrompts = updatedMap
                    }
                }
            }

            return prompt
        }
    }

    private suspend fun ensureMultiPersonPrompts(
        context: Context,
        prompts: Map<String, List<PromptsData.Prompt>>,
        forceRegenerate: Boolean = false,
        ignoreCacheExpiry: Boolean = false
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val openAIService = com.photoai.app.api.OpenAIService.getInstance()
        
        // Reset progress tracking
        totalPromptsToGenerate = 0
        promptsGenerated = 0
        generationInProgress = true
        updateProgress(0f)

        synchronized(this) {
            if (cachedMultiPersonPrompts == null) {
                val json = prefs.getString(KEY_MULTI_PERSON_PROMPTS, null)
                android.util.Log.d("PromptsLoader", "Loading cached prompts, found json: ${json != null}")
                if (json != null) {
                    try {
                        val gson = Gson()
                        val type = object : TypeToken<Map<String, Pair<String, Long>>>() {}.type
                        cachedMultiPersonPrompts = gson.fromJson(json, type)
                        android.util.Log.d("PromptsLoader", "Loaded ${cachedMultiPersonPrompts?.size} cached prompts")
                        android.util.Log.d("PromptsLoader", "Cache keys: ${cachedMultiPersonPrompts?.keys}")
                    } catch (e: Exception) {
                        android.util.Log.e("PromptsLoader", "Error loading cache: ${e.message}")
                        cachedMultiPersonPrompts = emptyMap()
                    }
                } else {
                    android.util.Log.d("PromptsLoader", "No cached prompts found")
                    cachedMultiPersonPrompts = emptyMap()
                }
            }
        }

        val now = System.currentTimeMillis()
        val CACHE_EXPIRY = 7 * 24 * 60 * 60 * 1000L // 7 days
        val multiPersonPrompts = mutableMapOf<String, Pair<String, Long>>()

        // Count prompts that need generation
        prompts.forEach { (category, promptList) ->
            promptList.forEach { prompt ->
                val v2Key = createCacheKeyV2(category, prompt.name)
                val cached = cachedMultiPersonPrompts?.get(v2Key)
                
                if (forceRegenerate || cached == null || (!ignoreCacheExpiry && (now - cached.second) > CACHE_EXPIRY)) {
                    totalPromptsToGenerate++
                } else {
                    multiPersonPrompts[v2Key] = cached
                }
            }
        }

        // If no prompts need generation, use cached data
        if (totalPromptsToGenerate == 0) {
            cachedMultiPersonPrompts?.let { multiPersonPrompts.putAll(it) }
            updateProgress(1f)
            return
        }

        // Generate new prompts as needed
        prompts.forEach { (category, promptList) ->
            promptList.forEach { prompt ->
                val v2Key = createCacheKeyV2(category, prompt.name)
                val cached = cachedMultiPersonPrompts?.get(v2Key)

                if (forceRegenerate || cached == null || (!ignoreCacheExpiry && (now - cached.second) > CACHE_EXPIRY)) {
                    try {
                        val result = openAIService.generateMultiPersonPrompt(context, prompt.prompt)
                        when {
                            result.isSuccess -> {
                                result.getOrNull()?.let { multiPersonPrompt ->
                                    multiPersonPrompts[v2Key] = Pair(multiPersonPrompt, now)
                                }
                            }
                            result.isFailure -> {
                                android.util.Log.e("PromptsLoader", "Error generating prompt for $v2Key: ${result.exceptionOrNull()?.message}")
                            }
                        }
                        promptsGenerated++
                        updateProgress(promptsGenerated.toFloat() / totalPromptsToGenerate)
                    } catch (e: Exception) {
                        android.util.Log.e("PromptsLoader", "Error generating prompt for $v2Key: ${e.message}")
                        promptsGenerated++ // Still update progress even on error
                        updateProgress(promptsGenerated.toFloat() / totalPromptsToGenerate)
                    }
                }
            }
        }

        // Save to cache
        synchronized(this) {
            if (multiPersonPrompts.isNotEmpty()) {
                val filteredPrompts = multiPersonPrompts.filterKeys { it.startsWith(KEY_VERSION_PREFIX) }
                android.util.Log.d("PromptsLoader", "Generated prompts before filtering: ${multiPersonPrompts.keys}")
                android.util.Log.d("PromptsLoader", "Saving filtered prompts: ${filteredPrompts.keys}")
                val gson = Gson()
                val json = gson.toJson(filteredPrompts)
                prefs.edit()
                    .putString(KEY_MULTI_PERSON_PROMPTS, json)
                    .apply()
                cachedMultiPersonPrompts = filteredPrompts
                android.util.Log.d("PromptsLoader", "Cache updated with ${filteredPrompts.size} prompts")
            }
        }

        // Optional cleanup of stale entries after successful regeneration
        val STALE_THRESHOLD = 30 * 24 * 60 * 60 * 1000L // 30 days
        val allowedKeys = mutableSetOf<String>()
        
        prompts.forEach { (category, promptList) ->
            promptList.forEach { prompt ->
                allowedKeys.add(createCacheKeyV2(category, prompt.name))
            }
        }

        synchronized(this) {
            cachedMultiPersonPrompts?.let { cached ->
                val staleEntries = cached.filterKeys { key ->
                    val timestamp = cached[key]?.second ?: 0L
                    key.startsWith(KEY_VERSION_PREFIX) &&
                    key !in allowedKeys &&
                    (now - timestamp) > STALE_THRESHOLD
                }

                if (staleEntries.isNotEmpty()) {
                    val updatedMap = cached.toMutableMap()
                    staleEntries.keys.forEach { key ->
                        updatedMap.remove(key)
                    }
                    val gson = Gson()
                    val json = gson.toJson(updatedMap)
                    prefs.edit()
                        .putString(KEY_MULTI_PERSON_PROMPTS, json)
                        .apply()
                    cachedMultiPersonPrompts = updatedMap
                }
            }
        }

        // Ensure final progress update
        updateProgress(1f)
    }

    suspend fun loadPrompts(
        context: Context,
        forceRegenerate: Boolean = false,
        ignoreCacheExpiry: Boolean = false
    ): FlexiblePromptsData {
        if (forceRegenerate) {
            clearDeviceCache(context)
        }
        
        val customPrompts = loadCustomPrompts(context)
        val prompts = if (customPrompts.isNotEmpty()) {
            customPrompts
        } else {
            val defaultPrompts = loadDefaultPrompts(context)
            defaultPrompts.toNormalizedFlexibleFormat()
        }
        
        android.util.Log.d("PromptsLoader", "Loading prompts with forceRegenerate=$forceRegenerate")
        ensureMultiPersonPrompts(context, prompts, forceRegenerate, ignoreCacheExpiry)
        return FlexiblePromptsData(prompts)
    }

    private fun loadCustomPrompts(context: Context): Map<String, List<PromptsData.Prompt>> {
        cachedFlexiblePrompts?.let { return it }
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CUSTOM_PROMPTS, null) ?: return emptyMap()
        
        return try {
            val gson = Gson()
            val type = object : TypeToken<Map<String, List<PromptsData.Prompt>>>() {}.type
            val loadedPrompts: Map<String, List<PromptsData.Prompt>> = gson.fromJson(json, type)
            cachedFlexiblePrompts = loadedPrompts
            loadedPrompts
        } catch (e: Exception) {
            android.util.Log.e("PromptsLoader", "Error loading custom prompts: ${e.message}")
            emptyMap()
        }
    }

    private fun loadDefaultPrompts(context: Context): PromptsData {
        cachedPromptsData?.let { return it }
        
        return try {
            val inputStream = context.resources.openRawResource(R.raw.prompts)
            val reader = InputStreamReader(inputStream)
            val gson = Gson()
            val promptsData = gson.fromJson(reader, PromptsData::class.java)
            reader.close()
            
            cachedPromptsData = promptsData
            promptsData
        } catch (e: Exception) {
            android.util.Log.e("PromptsLoader", "Error loading prompts: ${e.message}")
            PromptsData()
        }
    }

    private fun PromptsData.toNormalizedFlexibleFormat(): Map<String, List<PromptsData.Prompt>> {
        val flexibleFormat = this.toFlexibleFormat()
        // Keep original category names
        return flexibleFormat
    }

    fun savePrompts(context: Context, promptsData: FlexiblePromptsData) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val gson = Gson()
        val json = gson.toJson(promptsData.prompts)
        
        prefs.edit()
            .putString(KEY_CUSTOM_PROMPTS, json)
            .remove(KEY_MULTI_PERSON_PROMPTS)
            .apply()
        
        clearCache()
        cachedMultiPersonPrompts = null
    }

    // Store mapping of display names to raw category names
    private val displayToRawCategory = mutableMapOf<String, String>()

    suspend fun getCategoryNames(context: Context): List<String> {
        val flexiblePrompts = loadPrompts(context)
        displayToRawCategory.clear() // Reset mapping
        android.util.Log.d("PromptsLoader", "Original category names: ${flexiblePrompts.prompts.keys}")
        return flexiblePrompts.prompts.keys
            .map { category ->
                val displayName = formatCategoryForDisplay(category)
                android.util.Log.d("PromptsLoader", "Mapping '$category' to display name '$displayName'")
                displayToRawCategory[displayName] = category
                displayName
            }
            .sorted()
            .also { 
                android.util.Log.d("PromptsLoader", "Final category mapping: $displayToRawCategory")
            }
    }

    // Get raw category name from display name
    private fun getRawCategory(displayName: String): String {
        val fromMapping = displayToRawCategory[displayName]
        if (fromMapping == null) {
            android.util.Log.d("PromptsLoader", "No mapping found for display name '$displayName', using storage format")
            val storageFormat = formatDisplayToStorage(displayName)
            android.util.Log.d("PromptsLoader", "Converted '$displayName' to storage format '$storageFormat'")
            return storageFormat
        }
        android.util.Log.d("PromptsLoader", "Found mapping for '$displayName': '$fromMapping'")
        return fromMapping
    }

    suspend fun getPromptsForCategory(context: Context, displayCategoryName: String): List<PromptCategory> {
        val flexiblePrompts = loadPrompts(context)
        val rawCategoryName = getRawCategory(displayCategoryName)
        android.util.Log.d("PromptsLoader", "Looking up prompts for display category '$displayCategoryName', raw category '$rawCategoryName'")
        android.util.Log.d("PromptsLoader", "Available categories: ${flexiblePrompts.prompts.keys}")
        
        val prompts = flexiblePrompts.prompts[rawCategoryName] ?: run {
            // Try original name as fallback
            android.util.Log.d("PromptsLoader", "No prompts found for raw category, trying original name")
            flexiblePrompts.prompts[displayCategoryName] ?: emptyList()
        }
        return prompts.map { PromptCategory(it.name, it.prompt) }
    }

    fun clearMultiPersonPrompts(context: Context, forceRegenerate: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_MULTI_PERSON_PROMPTS)
            .apply()
        cachedMultiPersonPrompts = null
        generationInProgress = false
        updateProgress(1f)
        android.util.Log.d("PromptsLoader", "Multi-person prompts cache cleared (force regenerate: $forceRegenerate)")
    }

    fun clearCache() {
        cachedPromptsData = null
        cachedFlexiblePrompts = null
        cachedBasePrompt = null
        cachedDownsizeImages = null
        cachedInputFidelity = null
        cachedQuality = null
    }

    fun clearDeviceCache(context: Context) {
        // Clear SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        // Clear in-memory caches
        cachedPromptsData = null
        cachedFlexiblePrompts = null
        cachedBasePrompt = null
        cachedDownsizeImages = null
        cachedInputFidelity = null
        cachedQuality = null
        cachedMultiPersonPrompts = null

        // Reset generation state
        generationInProgress = false
        updateProgress(1f)

        // Clear app cache directory
        try {
            context.cacheDir.deleteRecursively()
        } catch (e: Exception) {
            android.util.Log.e("PromptsLoader", "Error clearing cache directory: ${e.message}")
        }

        android.util.Log.d("PromptsLoader", "Device cache cleared successfully")
    }

    fun resetToDefaults(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_CUSTOM_PROMPTS)
            .remove(KEY_BASE_PROMPT)
            .remove(KEY_DOWNSIZE_IMAGES)
            .remove(KEY_INPUT_FIDELITY)
            .remove(KEY_QUALITY)
            .remove(KEY_MULTI_PERSON_PROMPTS)
            .apply()
        
        clearCache()
        cachedMultiPersonPrompts = null
        generationInProgress = false
        updateProgress(1f)
    }
}

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
    
    private var cachedPromptsData: PromptsData? = null
    private var cachedFlexiblePrompts: Map<String, List<PromptsData.Prompt>>? = null
    private var cachedBasePrompt: String? = null
    private var cachedDownsizeImages: Boolean? = null
    private var cachedInputFidelity: String? = null
    private var cachedQuality: String? = null
    private var cachedMultiPersonPrompts: Map<String, Pair<String, Long>>? = null
    
    // Default base prompt
    private const val DEFAULT_BASE_PROMPT = """Use the following prompt to edit the provided image.
The generated image should maintain the facial features and build of the person so they are easily recognizable.
You should keep any eye glasses the person is wearing, but do not add them if they are not already wearing them.
Maintain the color and lighting of the scene.
The generated image should be photorealistic.
Prompt: 
"""
    
    private suspend fun ensureMultiPersonPrompts(context: Context, prompts: Map<String, List<PromptsData.Prompt>>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val openAIService = com.photoai.app.api.OpenAIService.getInstance()
        
        // Load cached multi-person prompts
        synchronized(this) {
            if (cachedMultiPersonPrompts == null) {
                val json = prefs.getString(KEY_MULTI_PERSON_PROMPTS, null)
                cachedMultiPersonPrompts = if (json != null) {
                    try {
                        val gson = Gson()
                        val type = object : TypeToken<Map<String, Pair<String, Long>>>() {}.type
                        gson.fromJson<Map<String, Pair<String, Long>>>(json, type)
                    } catch (e: Exception) {
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }
            }
        }
        
        val now = System.currentTimeMillis()
        val CACHE_EXPIRY = 24 * 60 * 60 * 1000L // 24 hours
        val multiPersonPrompts = mutableMapOf<String, Pair<String, Long>>()
        val results = mutableListOf<Pair<String, String?>>()
        
        // First, collect all the prompts that need generation
        prompts.forEach { (category, promptList) ->
            promptList.forEach { prompt ->
                val cacheKey = "${category}_${prompt.name}"
                val cached = cachedMultiPersonPrompts?.get(cacheKey)
                
                if (cached == null || (now - cached.second) > CACHE_EXPIRY) {
                    android.util.Log.d("PromptsLoader", "Will generate multi-person prompt for: $cacheKey")
                    results.add(cacheKey to null)
                } else {
                    android.util.Log.d("PromptsLoader", "Using cached prompt for: $cacheKey")
                    multiPersonPrompts[cacheKey] = cached
                }
            }
        }
        
        // Generate all needed prompts with proper coroutine handling
        results.forEach { (cacheKey, _) ->
            val prompt = prompts[cacheKey.substringBefore("_")]
                ?.find { "${cacheKey.substringBefore("_")}_${it.name}" == cacheKey }
                ?.prompt
                
            if (prompt != null) {
                try {
                    android.util.Log.d("PromptsLoader", "Starting generation for: $cacheKey")
                    val result = openAIService.generateMultiPersonPrompt(prompt)
                    result.fold(
                        onSuccess = { multiPersonPrompt ->
                            android.util.Log.d("PromptsLoader", "Generated multi-person prompt for: $cacheKey")
                            multiPersonPrompts[cacheKey] = Pair(multiPersonPrompt, now)
                        },
                        onFailure = { error ->
                            android.util.Log.e("PromptsLoader", "Failed to generate multi-person prompt for $cacheKey: ${error.message}")
                        }
                    )
                } catch (e: Exception) {
                    android.util.Log.e("PromptsLoader", "Error generating multi-person prompt for $cacheKey: ${e.message}")
                }
            }
        }
        
        android.util.Log.d("PromptsLoader", "Generated ${multiPersonPrompts.size} prompts")
        
        // Update cache atomically
        synchronized(this) {
            if (multiPersonPrompts.isNotEmpty()) {
                android.util.Log.d("PromptsLoader", "Updating cache with ${multiPersonPrompts.size} prompts")
                val gson = Gson()
                val json = gson.toJson(multiPersonPrompts)
                prefs.edit()
                    .putString(KEY_MULTI_PERSON_PROMPTS, json)
                    .apply()
                cachedMultiPersonPrompts = multiPersonPrompts
            }
        }
    }
    
    /**
     * Get multi-person prompt if available
     */
    private fun normalizeCategory(category: String): String {
        android.util.Log.d("PromptsLoader", "Normalizing category: $category")
        val normalized = when (category.lowercase().trim()) {
            "movie/tv", "movie_tv" -> "movie_tv"
            "face paint", "face_paint" -> "face_paint"
            "ghost/monster", "ghost_monster" -> "ghost_monster"
            else -> category.lowercase().trim()
        }
        android.util.Log.d("PromptsLoader", "Normalized category: $normalized")
        return normalized
    }

    fun getMultiPersonPrompt(category: String, promptName: String): String? {
        val normalizedCategory = normalizeCategory(category)
        android.util.Log.d("PromptsLoader", "Getting multi-person prompt. Category: $category, Normalized: $normalizedCategory, Prompt: $promptName")
        val cacheKey = "${normalizedCategory}_${promptName}"
        
        synchronized(this) {
            val prompt = cachedMultiPersonPrompts?.get(cacheKey)?.first
            if (prompt != null) {
                android.util.Log.d("PromptsLoader", "Found multi-person prompt for key: $cacheKey, prompt: ${prompt.take(50)}...")
            } else {
                android.util.Log.e("PromptsLoader", "No multi-person prompt found for key: $cacheKey")
            }
            return prompt
        }
    }
    
    /**
     * Load prompts in the new flexible format that supports editing
     */
    suspend fun loadPrompts(context: Context): FlexiblePromptsData {
        // Check if we have custom prompts in SharedPreferences
        val customPrompts = loadCustomPrompts(context)
        
        val prompts = if (customPrompts.isNotEmpty()) {
            // Return custom prompts if they exist
            customPrompts
        } else {
            // Load default prompts from resources and convert to flexible format
            val defaultPrompts = loadDefaultPrompts(context)
            defaultPrompts.toFlexibleFormat()
        }

        // Ensure multi-person prompts are generated and cached
        ensureMultiPersonPrompts(context, prompts)
        
        return FlexiblePromptsData(prompts)
    }
    
    /**
     * Save prompts to SharedPreferences
     */
    fun savePrompts(context: Context, promptsData: FlexiblePromptsData) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val gson = Gson()
        val json = gson.toJson(promptsData.prompts)
        
        prefs.edit()
            .putString(KEY_CUSTOM_PROMPTS, json)
            .remove(KEY_MULTI_PERSON_PROMPTS) // Clear multi-person prompts when prompts are updated
            .apply()
        
        // Clear caches so next load will use updated data
        cachedFlexiblePrompts = null
        cachedMultiPersonPrompts = null
    }
    
    /**
     * Load custom prompts from SharedPreferences
     */
    private fun loadCustomPrompts(context: Context): Map<String, List<PromptsData.Prompt>> {
        cachedFlexiblePrompts?.let { return it }
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CUSTOM_PROMPTS, null) ?: return emptyMap()
        
        return try {
            val gson = Gson()
            val type = object : TypeToken<Map<String, List<PromptsData.Prompt>>>() {}.type
            val prompts: Map<String, List<PromptsData.Prompt>> = gson.fromJson(json, type)
            cachedFlexiblePrompts = prompts
            prompts
        } catch (e: Exception) {
            android.util.Log.e("PromptsLoader", "Error loading custom prompts: ${e.message}")
            emptyMap()
        }
    }
    
    /**
     * Load default prompts from raw resource (legacy format)
     */
    private fun loadDefaultPrompts(context: Context): PromptsData {
        // Return cached data if available
        cachedPromptsData?.let { return it }
        
        return try {
            val inputStream = context.resources.openRawResource(R.raw.prompts)
            val reader = InputStreamReader(inputStream)
            val gson = Gson()
            val promptsData = gson.fromJson(reader, PromptsData::class.java)
            reader.close()
            
            // Cache the loaded data
            cachedPromptsData = promptsData
            promptsData
        } catch (e: Exception) {
            android.util.Log.e("PromptsLoader", "Error loading prompts: ${e.message}")
            // Return empty data if loading fails
            PromptsData()
        }
    }
    
    /**
     * Get all prompt names (backward compatibility)
     */
    suspend fun getAllPromptNames(context: Context): List<String> {
        val flexiblePrompts = loadPrompts(context)
        val allPrompts = mutableListOf<String>()
        
        flexiblePrompts.prompts.forEach { (category, prompts) ->
            prompts.forEach { prompt ->
                allPrompts.add("${category.replaceFirstChar { it.uppercase() }}: ${prompt.name}")
            }
        }
        
        return allPrompts
    }
    
    /**
     * Get all prompt texts (backward compatibility)
     */
    suspend fun getAllPromptTexts(context: Context): List<String> {
        val flexiblePrompts = loadPrompts(context)
        val allPrompts = mutableListOf<String>()
        
        flexiblePrompts.prompts.forEach { (_, prompts) ->
            prompts.forEach { prompt ->
                allPrompts.add(prompt.prompt)
            }
        }
        
        return allPrompts
    }
    
    /**
     * Get category names
     */
    suspend fun getCategoryNames(context: Context): List<String> {
        val flexiblePrompts = loadPrompts(context)
        android.util.Log.d("PromptsLoader", "Getting category names, available categories: ${flexiblePrompts.prompts.keys}")
        return flexiblePrompts.prompts.keys.map { category ->
            // Convert internal names to display names
            when (category) {
                "movie_tv" -> "Movie/TV"
                "face_paint" -> "Face Paint"
                "ghost_monster" -> "Ghost/Monster"
                else -> category.replaceFirstChar { it.uppercase() }
            }
        }.sorted().also {
            android.util.Log.d("PromptsLoader", "Returning category names: $it")
        }
    }
    
    /**
     * Get prompts for category (backward compatibility)
     */
    suspend fun getPromptsForCategory(context: Context, categoryName: String): List<PromptCategory> {
        val flexiblePrompts = loadPrompts(context)
        
        android.util.Log.d("PromptsLoader", "Getting prompts for category: $categoryName")
        val internalName = normalizeCategory(categoryName)
        android.util.Log.d("PromptsLoader", "Using internal name: $internalName")
        
        val prompts = flexiblePrompts.prompts[internalName] ?: emptyList()
        android.util.Log.d("PromptsLoader", "Found ${prompts.size} prompts for category $internalName")
        
        return prompts.map { PromptCategory(it.name, it.prompt) }
    }
    
    /**
     * Get prompts for category in new format
     */
    suspend fun getPromptsForCategoryNew(context: Context, categoryName: String): List<PromptsData.Prompt> {
        val flexiblePrompts = loadPrompts(context)
        
        android.util.Log.d("PromptsLoader", "Getting prompts for category (new): $categoryName")
        val internalName = normalizeCategory(categoryName)
        android.util.Log.d("PromptsLoader", "Using internal name (new): $internalName")
        
        val prompts = flexiblePrompts.prompts[internalName] ?: emptyList()
        android.util.Log.d("PromptsLoader", "Found ${prompts.size} prompts for category $internalName (new)")
        
        return prompts
    }
    
    /**
     * Get base prompt for OpenAI API calls
     */
    fun getBasePrompt(context: Context): String {
        cachedBasePrompt?.let { return it }
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val basePrompt = prefs.getString(KEY_BASE_PROMPT, DEFAULT_BASE_PROMPT) ?: DEFAULT_BASE_PROMPT
        
        cachedBasePrompt = basePrompt
        return basePrompt
    }
    
    /**
     * Save base prompt to SharedPreferences
     */
    fun saveBasePrompt(context: Context, basePrompt: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_BASE_PROMPT, basePrompt)
            .apply()
        
        // Clear cache so next load will use updated data
        cachedBasePrompt = null
    }
    
    /**
     * Get downsize images preference
     */
    fun getDownsizeImages(context: Context): Boolean {
        cachedDownsizeImages?.let { return it }
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val downsizeImages = prefs.getBoolean(KEY_DOWNSIZE_IMAGES, true) // Default to true for backward compatibility
        
        cachedDownsizeImages = downsizeImages
        return downsizeImages
    }
    
    /**
     * Save downsize images preference to SharedPreferences
     */
    fun saveDownsizeImages(context: Context, downsizeImages: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_DOWNSIZE_IMAGES, downsizeImages)
            .apply()
        
        // Clear cache so next load will use updated data
        cachedDownsizeImages = null
    }
    
    /**
     * Get input fidelity preference
     */
    fun getInputFidelity(context: Context): String {
        cachedInputFidelity?.let { return it }
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val inputFidelity = prefs.getString(KEY_INPUT_FIDELITY, "low") ?: "low" // Default to "low"
        
        cachedInputFidelity = inputFidelity
        return inputFidelity
    }
    
    /**
     * Save input fidelity preference to SharedPreferences
     */
    fun saveInputFidelity(context: Context, inputFidelity: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_INPUT_FIDELITY, inputFidelity)
            .apply()
        
        // Clear cache so next load will use updated data
        cachedInputFidelity = null
    }
    
    /**
     * Get quality preference
     */
    fun getQuality(context: Context): String {
        cachedQuality?.let { return it }
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val quality = prefs.getString(KEY_QUALITY, "low") ?: "low" // Default to "low"
        
        cachedQuality = quality
        return quality
    }
    
    /**
     * Save quality preference to SharedPreferences
     */
    fun saveQuality(context: Context, quality: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_QUALITY, quality)
            .apply()
        
        // Clear cache so next load will use updated data
        cachedQuality = null
    }
    
    /**
     * Reset to default prompts
     */
    fun resetToDefaults(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_CUSTOM_PROMPTS)
            .remove(KEY_BASE_PROMPT)
            .remove(KEY_DOWNSIZE_IMAGES)
            .remove(KEY_INPUT_FIDELITY)
            .remove(KEY_QUALITY)
            .remove(KEY_MULTI_PERSON_PROMPTS) // Also clear multi-person prompts
            .apply()
        
        // Clear caches
        cachedFlexiblePrompts = null
        cachedPromptsData = null
        cachedBasePrompt = null
        cachedDownsizeImages = null
        cachedInputFidelity = null
        cachedQuality = null
        cachedMultiPersonPrompts = null
    }
    
    /**
     * Clear only the multi-person prompts cache, forcing regeneration
     */
    fun clearMultiPersonPrompts(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_MULTI_PERSON_PROMPTS)
            .apply()
            
        // Clear only the multi-person prompts cache
        cachedMultiPersonPrompts = null
        
        android.util.Log.d("PromptsLoader", "Multi-person prompts cache cleared")
    }
    
    /**
     * Clear cache if needed (useful for testing or if data changes)
     */
    fun clearCache() {
        cachedPromptsData = null
        cachedFlexiblePrompts = null
        cachedBasePrompt = null
        cachedDownsizeImages = null
        cachedInputFidelity = null
        cachedQuality = null
    }
}

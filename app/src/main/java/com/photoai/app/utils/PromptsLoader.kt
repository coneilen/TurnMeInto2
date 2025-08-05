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
    
    private var cachedPromptsData: PromptsData? = null
    private var cachedFlexiblePrompts: Map<String, List<PromptsData.Prompt>>? = null
    
    /**
     * Load prompts in the new flexible format that supports editing
     */
    fun loadPrompts(context: Context): FlexiblePromptsData {
        // Check if we have custom prompts in SharedPreferences
        val customPrompts = loadCustomPrompts(context)
        
        return if (customPrompts.isNotEmpty()) {
            // Return custom prompts if they exist
            FlexiblePromptsData(customPrompts)
        } else {
            // Load default prompts from resources and convert to flexible format
            val defaultPrompts = loadDefaultPrompts(context)
            val flexiblePrompts = defaultPrompts.toFlexibleFormat()
            FlexiblePromptsData(flexiblePrompts)
        }
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
            .apply()
        
        // Clear cache so next load will use updated data
        cachedFlexiblePrompts = null
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
    fun getAllPromptNames(context: Context): List<String> {
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
    fun getAllPromptTexts(context: Context): List<String> {
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
    fun getCategoryNames(context: Context): List<String> {
        val flexiblePrompts = loadPrompts(context)
        return flexiblePrompts.prompts.keys.map { category ->
            // Convert internal names to display names
            when (category) {
                "movie_tv" -> "Movie/TV"
                "face_paint" -> "Face Paint"
                "ghost_monster" -> "Ghost/Monster"
                else -> category.replaceFirstChar { it.uppercase() }
            }
        }.sorted()
    }
    
    /**
     * Get prompts for category (backward compatibility)
     */
    fun getPromptsForCategory(context: Context, categoryName: String): List<PromptCategory> {
        val flexiblePrompts = loadPrompts(context)
        
        // Convert display name back to internal name
        val internalName = when (categoryName.lowercase()) {
            "movie/tv" -> "movie_tv"
            "face paint" -> "face_paint"
            "ghost/monster" -> "ghost_monster"
            else -> categoryName.lowercase()
        }
        
        val prompts = flexiblePrompts.prompts[internalName] ?: emptyList()
        return prompts.map { PromptCategory(it.name, it.prompt) }
    }
    
    /**
     * Get prompts for category in new format
     */
    fun getPromptsForCategoryNew(context: Context, categoryName: String): List<PromptsData.Prompt> {
        val flexiblePrompts = loadPrompts(context)
        
        // Convert display name back to internal name
        val internalName = when (categoryName.lowercase()) {
            "movie/tv" -> "movie_tv"
            "face paint" -> "face_paint"
            "ghost/monster" -> "ghost_monster"
            else -> categoryName.lowercase()
        }
        
        return flexiblePrompts.prompts[internalName] ?: emptyList()
    }
    
    /**
     * Reset to default prompts
     */
    fun resetToDefaults(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_CUSTOM_PROMPTS).apply()
        
        // Clear caches
        cachedFlexiblePrompts = null
        cachedPromptsData = null
    }
    
    /**
     * Clear cache if needed (useful for testing or if data changes)
     */
    fun clearCache() {
        cachedPromptsData = null
        cachedFlexiblePrompts = null
    }
}

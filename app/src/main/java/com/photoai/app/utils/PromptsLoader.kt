package com.photoai.app.utils

import android.content.Context
import com.google.gson.Gson
import com.photoai.app.R
import com.photoai.app.data.PromptsData
import com.photoai.app.data.PromptCategory
import java.io.InputStreamReader

object PromptsLoader {
    private var cachedPromptsData: PromptsData? = null
    
    fun loadPrompts(context: Context): PromptsData {
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
    
    fun getAllPromptNames(context: Context): List<String> {
        val promptsData = loadPrompts(context)
        return promptsData.getAllPrompts().map { "${it.first}: ${it.second.name}" }
    }
    
    fun getAllPromptTexts(context: Context): List<String> {
        val promptsData = loadPrompts(context)
        return promptsData.getAllPrompts().map { it.second.prompt }
    }
    
    fun getCategoryNames(): List<String> {
        return listOf(
            "Cartoon",
            "Movie/TV", 
            "Historic",
            "Fantasy",
            "Face Paint",
            "Animal",
            "Princess",
            "Ghost/Monster",
            "Sports",
            "Other",
            "Art",
            "Toy"
        )
    }
    
    fun getPromptsForCategory(context: Context, categoryName: String): List<PromptCategory> {
        val promptsData = loadPrompts(context)
        return promptsData.getCategoryPrompts(categoryName)
    }
    
    // Clear cache if needed (useful for testing or if data changes)
    fun clearCache() {
        cachedPromptsData = null
    }
}

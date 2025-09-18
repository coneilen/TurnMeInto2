package com.photoai.app.data

import com.google.gson.annotations.SerializedName

data class PromptCategory(
    @SerializedName("name") val name: String,
    @SerializedName("prompt") val prompt: String
)

data class PromptsData(
    @SerializedName("cartoon") val cartoon: List<PromptCategory> = emptyList(),
    @SerializedName("movie_tv") val movieTv: List<PromptCategory> = emptyList(),
    @SerializedName("historic") val historic: List<PromptCategory> = emptyList(),
    @SerializedName("fantasy") val fantasy: List<PromptCategory> = emptyList(),
    @SerializedName("face_paint") val facePaint: List<PromptCategory> = emptyList(),
    @SerializedName("animal") val animal: List<PromptCategory> = emptyList(),
    @SerializedName("princess") val princess: List<PromptCategory> = emptyList(),
    @SerializedName("ghost_monster") val ghostMonster: List<PromptCategory> = emptyList(),
    @SerializedName("sports") val sports: List<PromptCategory> = emptyList(),
    @SerializedName("other") val other: List<PromptCategory> = emptyList(),
    @SerializedName("art") val art: List<PromptCategory> = emptyList(),
    @SerializedName("toy") val toy: List<PromptCategory> = emptyList(),
    
    // For custom prompts stored as a flexible map
    val prompts: Map<String, List<Prompt>> = emptyMap()
) {
    // New simplified prompt data class for editing
    data class Prompt(
        val name: String,
        val prompt: String,
        val multiPersonPrompt: String? = null,
        val lastGeneratedTimestamp: Long? = null
    )
    
    fun getAllPrompts(): List<Pair<String, PromptCategory>> {
        val allPrompts = mutableListOf<Pair<String, PromptCategory>>()
        
        cartoon.forEach { allPrompts.add("Cartoon" to it) }
        movieTv.forEach { allPrompts.add("Movie/TV" to it) }
        historic.forEach { allPrompts.add("Historic" to it) }
        fantasy.forEach { allPrompts.add("Fantasy" to it) }
        facePaint.forEach { allPrompts.add("Face Paint" to it) }
        animal.forEach { allPrompts.add("Animal" to it) }
        princess.forEach { allPrompts.add("Princess" to it) }
        ghostMonster.forEach { allPrompts.add("Ghost/Monster" to it) }
        sports.forEach { allPrompts.add("Sports" to it) }
        other.forEach { allPrompts.add("Other" to it) }
        toy.forEach { allPrompts.add("Toy" to it) }
        art.forEach { allPrompts.add("Art" to it) }
        
        return allPrompts
    }
    
    fun getCategoryPrompts(categoryName: String): List<PromptCategory> {
        return when (categoryName.lowercase()) {
            "cartoon" -> cartoon
            "movie_tv", "movie/tv" -> movieTv
            "historic" -> historic
            "fantasy" -> fantasy
            "face_paint", "face paint" -> facePaint
            "animal" -> animal
            "princess" -> princess
            "ghost_monster", "ghost/monster" -> ghostMonster
            "sports" -> sports
            "other" -> other
            "art" -> art
            "toy" -> toy
            else -> emptyList()
        }
    }
    
    // Convert to the new flexible format
    fun toFlexibleFormat(): Map<String, List<Prompt>> {
        return mapOf(
            "cartoon" to cartoon.map { Prompt(it.name, it.prompt) },
            "movie_tv" to movieTv.map { Prompt(it.name, it.prompt) },
            "historic" to historic.map { Prompt(it.name, it.prompt) },
            "fantasy" to fantasy.map { Prompt(it.name, it.prompt) },
            "face_paint" to facePaint.map { Prompt(it.name, it.prompt) },
            "animal" to animal.map { Prompt(it.name, it.prompt) },
            "princess" to princess.map { Prompt(it.name, it.prompt) },
            "ghost_monster" to ghostMonster.map { Prompt(it.name, it.prompt) },
            "sports" to sports.map { Prompt(it.name, it.prompt) },
            "other" to other.map { Prompt(it.name, it.prompt) },
            "art" to art.map { Prompt(it.name, it.prompt) },
            "toy" to toy.map { Prompt(it.name, it.prompt) }
        ).plus(prompts)
    }
}

// New data class for the flexible format
data class FlexiblePromptsData(
    val prompts: Map<String, List<PromptsData.Prompt>>
)

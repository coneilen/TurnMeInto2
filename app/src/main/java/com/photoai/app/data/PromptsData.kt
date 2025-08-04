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
    @SerializedName("toy") val toy: List<PromptCategory> = emptyList()
) {
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
}

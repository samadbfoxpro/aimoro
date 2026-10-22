package com.moror.browser.model

import org.json.JSONObject

data class UserProfile(
    val id: String,
    var name: String,
    val slotIndex: Int,
    val createdAt: Long = System.currentTimeMillis(),
    var lastUsedUrl: String = "https://www.google.com"
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("name", name)
        json.put("slotIndex", slotIndex)
        json.put("createdAt", createdAt)
        json.put("lastUsedUrl", lastUsedUrl)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): UserProfile {
            return UserProfile(
                id = json.getString("id"),
                name = json.getString("name"),
                slotIndex = json.getInt("slotIndex"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                lastUsedUrl = json.optString("lastUsedUrl", "https://www.google.com")
            )
        }
    }
}

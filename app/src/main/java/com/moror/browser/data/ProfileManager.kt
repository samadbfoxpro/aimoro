package com.moror.browser.data

import android.content.Context
import com.moror.browser.model.UserProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object ProfileManager {
    private const val PROFILES_FILE_NAME = "moror_profiles.json"
    private const val OLD_PREFS_NAME = "moror_profiles_pref"
    private const val KEY_PROFILES = "profiles_list"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    const val MAX_SLOTS = 30

    private fun getStorageFile(context: Context): File {
        val dir = context.applicationContext.filesDir
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, PROFILES_FILE_NAME)
    }

    @Synchronized
    private fun readData(context: Context): Pair<String, MutableList<UserProfile>> {
        val file = getStorageFile(context)
        if (!file.exists()) {
            // Attempt migration from old SharedPreferences if it exists
            val prefs = context.applicationContext.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE)
            val oldProfilesJson = prefs.getString(KEY_PROFILES, null)
            val oldActiveId = prefs.getString(KEY_ACTIVE_PROFILE_ID, "p_1") ?: "p_1"

            val initialList = mutableListOf<UserProfile>()
            if (!oldProfilesJson.isNullOrEmpty()) {
                try {
                    val arr = JSONArray(oldProfilesJson)
                    for (i in 0 until arr.length()) {
                        initialList.add(UserProfile.fromJson(arr.getJSONObject(i)))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (initialList.isEmpty()) {
                val defaultProfile = UserProfile(
                    id = "p_1",
                    name = "اکانت اصلی",
                    slotIndex = 1
                )
                initialList.add(defaultProfile)
            }

            val result = Pair(oldActiveId, initialList)
            writeData(context, result.first, result.second)
            return result
        }

        return try {
            val content = file.readText(Charsets.UTF_8)
            if (content.isNotBlank()) {
                val root = JSONObject(content)
                val activeId = root.optString(KEY_ACTIVE_PROFILE_ID, "p_1")
                val arr = root.optJSONArray("profiles") ?: JSONArray()
                val list = mutableListOf<UserProfile>()
                for (i in 0 until arr.length()) {
                    list.add(UserProfile.fromJson(arr.getJSONObject(i)))
                }
                if (list.isEmpty()) {
                    val defaultProfile = UserProfile(
                        id = "p_1",
                        name = "اکانت اصلی",
                        slotIndex = 1
                    )
                    list.add(defaultProfile)
                }
                Pair(activeId, list)
            } else {
                Pair("p_1", mutableListOf(UserProfile(id = "p_1", name = "اکانت اصلی", slotIndex = 1)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Pair("p_1", mutableListOf(UserProfile(id = "p_1", name = "اکانت اصلی", slotIndex = 1)))
        }
    }

    @Synchronized
    private fun writeData(context: Context, activeProfileId: String, profiles: List<UserProfile>) {
        try {
            val root = JSONObject()
            root.put(KEY_ACTIVE_PROFILE_ID, activeProfileId)

            val arr = JSONArray()
            for (p in profiles) {
                arr.put(p.toJson())
            }
            root.put("profiles", arr)

            val file = getStorageFile(context)
            val temp = File(file.parentFile, "$PROFILES_FILE_NAME.tmp")
            FileOutputStream(temp).use { fos ->
                fos.write(root.toString(2).toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            if (temp.exists()) {
                temp.renameTo(file)
            }

            // Also sync to SharedPreferences with synchronous commit() for backwards compatibility
            try {
                val prefs = context.applicationContext.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_ACTIVE_PROFILE_ID, activeProfileId)
                    .putString(KEY_PROFILES, arr.toString())
                    .commit()
            } catch (e: Exception) {
                // Ignore fallback error
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun getProfiles(context: Context): MutableList<UserProfile> {
        val (_, profiles) = readData(context)
        return profiles
    }

    @Synchronized
    fun saveProfiles(context: Context, profiles: List<UserProfile>) {
        val (activeId, _) = readData(context)
        writeData(context, activeId, profiles)
    }

    @Synchronized
    fun getActiveProfileId(context: Context): String {
        val (activeId, _) = readData(context)
        return activeId
    }

    @Synchronized
    fun setActiveProfileId(context: Context, profileId: String) {
        val (_, profiles) = readData(context)
        writeData(context, profileId, profiles)
    }

    @Synchronized
    fun getActiveProfile(context: Context): UserProfile {
        val (activeId, profiles) = readData(context)
        return profiles.find { it.id == activeId } ?: profiles.first()
    }

    @Synchronized
    fun createProfile(context: Context, name: String): UserProfile? {
        val (activeId, profiles) = readData(context)
        val usedSlots = profiles.map { it.slotIndex }.toSet()

        var availableSlot = -1
        for (slot in 1..MAX_SLOTS) {
            if (!usedSlots.contains(slot)) {
                availableSlot = slot
                break
            }
        }

        if (availableSlot == -1) {
            return null // Maximum slots reached
        }

        val newProfile = UserProfile(
            id = "p_$availableSlot",
            name = if (name.isBlank()) "اکانت $availableSlot" else name.trim(),
            slotIndex = availableSlot
        )
        profiles.add(newProfile)
        writeData(context, activeId, profiles)
        return newProfile
    }

    @Synchronized
    fun renameProfile(context: Context, profileId: String, newName: String) {
        val (activeId, profiles) = readData(context)
        val profile = profiles.find { it.id == profileId }
        if (profile != null && newName.isNotBlank()) {
            profile.name = newName.trim()
            writeData(context, activeId, profiles)
        }
    }

    @Synchronized
    fun deleteProfile(context: Context, profileId: String): Boolean {
        val (activeId, profiles) = readData(context)
        if (profiles.size <= 1) {
            return false // Cannot delete the only remaining profile
        }

        val profile = profiles.find { it.id == profileId } ?: return false
        profiles.remove(profile)

        val newActiveId = if (activeId == profileId) profiles.first().id else activeId
        writeData(context, newActiveId, profiles)

        // Clear isolated storage for this slot
        clearProfileDirectory(context, profile.slotIndex)
        return true
    }

    @Synchronized
    fun updateLastUsedUrl(context: Context, profileId: String, url: String) {
        if (url.isBlank()) return
        val (activeId, profiles) = readData(context)
        val profile = profiles.find { it.id == profileId }
        if (profile != null) {
            profile.lastUsedUrl = url
            writeData(context, activeId, profiles)
        }
    }

    fun clearProfileDirectory(context: Context, slotIndex: Int) {
        val profileName = "profile_$slotIndex"
        // 1. Delete profile from Chromium ProfileStore with safe try-catch
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)) {
                try {
                    androidx.webkit.ProfileStore.getInstance().deleteProfile(profileName)
                } catch (e: Exception) {
                    // Chromium might throw IllegalStateException if internal ref is still releasing
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Physical directory cleanup for 100% data clearing guarantee
        try {
            val dataDir = File(context.applicationInfo.dataDir)

            // a) Legacy multi-process directory
            val legacyDir = File(dataDir, "app_webview_profile_$slotIndex")
            if (legacyDir.exists()) {
                legacyDir.deleteRecursively()
            }

            // b) Modern profile directories in app_webview
            val baseWebviewDir = File(dataDir, "app_webview")
            if (baseWebviewDir.exists() && baseWebviewDir.isDirectory) {
                val targetDir = File(baseWebviewDir, profileName)
                if (targetDir.exists()) {
                    targetDir.deleteRecursively()
                }
                val targetAltDir = File(File(baseWebviewDir, "Profiles"), profileName)
                if (targetAltDir.exists()) {
                    targetAltDir.deleteRecursively()
                }
                try {
                    baseWebviewDir.walkTopDown()
                        .maxDepth(3)
                        .filter { it.isDirectory && it.name == profileName }
                        .forEach { it.deleteRecursively() }
                } catch (e: Exception) {
                    // Ignore traversal errors
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

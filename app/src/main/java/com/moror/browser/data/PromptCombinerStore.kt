package com.moror.browser.data

import android.content.Context
import com.moror.browser.model.CombinerPlacementMode
import com.moror.browser.model.PromptCombinerData
import com.moror.browser.model.PromptCombinerFolder
import com.moror.browser.model.PromptCombinerItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object PromptCombinerStore {
    private const val FILE_NAME = "prompt_combiner_data.json"
    private const val PREFS_NAME = "moror_combiner_preferences"
    private const val KEY_CACHE_JSON = "combiner_json_cache"

    private var cachedData: PromptCombinerData? = null

    private fun getFile(context: Context): File {
        val dir = context.applicationContext.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    @Synchronized
    fun reload(context: Context): PromptCombinerData {
        cachedData = null
        return load(context)
    }

    @Synchronized
    fun load(context: Context): PromptCombinerData {
        if (cachedData != null) return cachedData!!

        try {
            val file = getFile(context)
            if (file.exists() && file.length() > 0) {
                val jsonStr = file.readText(Charsets.UTF_8).trim()
                if (jsonStr.isNotEmpty()) {
                    val data = fromJson(JSONObject(jsonStr))
                    // If the saved data has only 3 default dummy items, upgrade to the full bundle
                    if (data.folders.size <= 3 && hasAssetsBundle(context)) {
                        loadFromAssets(context)?.let { fullData ->
                            cachedData = fullData
                            save(context, fullData)
                            return fullData
                        }
                    }
                    ensureDefaults(data)
                    cachedData = data
                    return data
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_CACHE_JSON, null)
            if (!jsonStr.isNullOrBlank()) {
                val data = fromJson(JSONObject(jsonStr))
                if (data.folders.size <= 3 && hasAssetsBundle(context)) {
                    loadFromAssets(context)?.let { fullData ->
                        cachedData = fullData
                        save(context, fullData)
                        return fullData
                    }
                }
                ensureDefaults(data)
                cachedData = data
                return data
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Try loading initial bundle from assets first
        loadFromAssets(context)?.let { fullData ->
            cachedData = fullData
            save(context, fullData)
            return fullData
        }

        val defaultData = createDefaultData()
        cachedData = defaultData
        save(context, defaultData)
        return defaultData
    }

    private fun hasAssetsBundle(context: Context): Boolean {
        return try {
            context.assets.open("default_prompt_combiner_data.json").close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun loadFromAssets(context: Context): PromptCombinerData? {
        return try {
            context.assets.open("default_prompt_combiner_data.json").use { stream ->
                val jsonStr = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (jsonStr.isNotBlank()) {
                    val data = fromJson(JSONObject(jsonStr))
                    ensureDefaults(data)
                    data
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @Synchronized
    fun importFromJsonString(context: Context, jsonStr: String): Boolean {
        return try {
            val json = JSONObject(jsonStr)
            val data = fromJson(json)
            ensureDefaults(data)
            save(context, data)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @Synchronized
    fun save(context: Context, data: PromptCombinerData) {
        cachedData = data
        try {
            val json = toJson(data)
            val jsonStr = json.toString(2)

            val file = getFile(context)
            FileOutputStream(file).use { fos ->
                fos.write(jsonStr.toByteArray(Charsets.UTF_8))
                fos.flush()
                try { fos.fd.sync() } catch (_: Exception) {}
            }

            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_CACHE_JSON, jsonStr).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun ensureDefaults(data: PromptCombinerData) {
        if (data.folders.isEmpty()) {
            val def = createDefaultData()
            data.folders.addAll(def.folders)
            data.items.addAll(def.items)
        }
        if (data.activeFolderId.isEmpty() && data.folders.isNotEmpty()) {
            data.activeFolderId = data.folders[0].id
        }
    }

    private fun createDefaultData(): PromptCombinerData {
        val folderQuality = PromptCombinerFolder(id = UUID.randomUUID().toString(), name = "کیفیت 💎", order = 0)
        val folderStyle = PromptCombinerFolder(id = UUID.randomUUID().toString(), name = "سبک 🎨", order = 1)
        val folderLight = PromptCombinerFolder(id = UUID.randomUUID().toString(), name = "نورپردازی 💡", order = 2)

        val items = mutableListOf(
            PromptCombinerItem(folderId = folderQuality.id, title = "شاهکار 🌟", text = "masterpiece, best quality, ultra-detailed", order = 0),
            PromptCombinerItem(folderId = folderQuality.id, title = "8K عکاسی 📷", text = "8k resolution, photorealistic, sharp focus", order = 1),
            PromptCombinerItem(folderId = folderStyle.id, title = "سینمایی 🎬", text = "cinematic atmosphere, dramatic lighting, 35mm photograph", order = 0),
            PromptCombinerItem(folderId = folderStyle.id, title = "انیمه 🌸", text = "vibrant anime style, detailed lineart, studio ghibli inspired", order = 1),
            PromptCombinerItem(folderId = folderLight.id, title = "پرتو نور ☀️", text = "volumetric lighting, sunbeams, soft rim light", order = 0),
            PromptCombinerItem(folderId = folderLight.id, title = "نئون سایبرپانک 🌆", text = "neon reflections, cyberpunk night glow, vibrant contrast", order = 1)
        )

        return PromptCombinerData(
            isEnabled = false,
            activeFolderId = folderQuality.id,
            placementMode = CombinerPlacementMode.AFTER_COMMA,
            commaIndex = 1,
            separator = ", ",
            folders = mutableListOf(folderQuality, folderStyle, folderLight),
            items = items
        )
    }

    fun fromJson(json: JSONObject): PromptCombinerData {
        val data = PromptCombinerData()
        data.isEnabled = json.optBoolean("IsEnabled", false)
        data.isStandaloneGlobalEnabled = json.optBoolean("IsStandaloneGlobalEnabled", true)
        data.autoCaptureBasePrompt = json.optBoolean("AutoCaptureBasePrompt", false)
        data.activeFolderId = json.optString("ActiveFolderId", "")
        data.placementMode = CombinerPlacementMode.fromInt(json.optInt("PlacementMode", 0))
        data.commaIndex = json.optInt("CommaIndex", 1)
        data.separator = json.optString("Separator", ", ")

        val actItems = json.optJSONArray("ActiveItemIds")
        if (actItems != null) {
            for (i in 0 until actItems.length()) {
                data.activeItemIds.add(actItems.optString(i))
            }
        }

        val foldersArr = json.optJSONArray("Folders")
        if (foldersArr != null) {
            for (i in 0 until foldersArr.length()) {
                val fObj = foldersArr.optJSONObject(i) ?: continue
                val folder = PromptCombinerFolder(
                    id = fObj.optString("Id", UUID.randomUUID().toString()),
                    name = fObj.optString("Name", ""),
                    order = fObj.optInt("Order", i),
                    isCustomInput = fObj.optBoolean("IsCustomInput", false),
                    customInputText = fObj.optString("CustomInputText", ""),
                    customTitle = fObj.optString("CustomTitle", ""),
                    isCustomInputActive = fObj.optBoolean("IsCustomInputActive", false),
                    placementMode = CombinerPlacementMode.fromInt(fObj.optInt("PlacementMode", 0)),
                    commaIndex = fObj.optInt("CommaIndex", 1)
                )
                data.folders.add(folder)
            }
        }

        val itemsArr = json.optJSONArray("Items")
        if (itemsArr != null) {
            for (i in 0 until itemsArr.length()) {
                val iObj = itemsArr.optJSONObject(i) ?: continue
                val item = PromptCombinerItem(
                    id = iObj.optString("Id", UUID.randomUUID().toString()),
                    folderId = iObj.optString("FolderId", ""),
                    title = iObj.optString("Title", ""),
                    text = iObj.optString("Text", ""),
                    isEnabled = iObj.optBoolean("IsEnabled", false),
                    order = iObj.optInt("Order", i)
                )
                data.items.add(item)
            }
        }

        return data
    }

    fun toJson(data: PromptCombinerData): JSONObject {
        val json = JSONObject()
        json.put("IsEnabled", data.isEnabled)
        json.put("IsStandaloneGlobalEnabled", data.isStandaloneGlobalEnabled)
        json.put("AutoCaptureBasePrompt", data.autoCaptureBasePrompt)
        json.put("ActiveFolderId", data.activeFolderId)
        json.put("PlacementMode", data.placementMode.code)
        json.put("CommaIndex", data.commaIndex)
        json.put("Separator", data.separator)

        val actArr = JSONArray()
        data.activeItemIds.forEach { actArr.put(it) }
        json.put("ActiveItemIds", actArr)

        val foldersArr = JSONArray()
        data.folders.forEach { f ->
            val fObj = JSONObject()
            fObj.put("Id", f.id)
            fObj.put("Name", f.name)
            fObj.put("Order", f.order)
            fObj.put("IsCustomInput", f.isCustomInput)
            fObj.put("CustomInputText", f.customInputText)
            fObj.put("CustomTitle", f.customTitle)
            fObj.put("IsCustomInputActive", f.isCustomInputActive)
            fObj.put("PlacementMode", f.placementMode.code)
            fObj.put("CommaIndex", f.commaIndex)
            foldersArr.put(fObj)
        }
        json.put("Folders", foldersArr)

        val itemsArr = JSONArray()
        data.items.forEach { i ->
            val iObj = JSONObject()
            iObj.put("Id", i.id)
            iObj.put("FolderId", i.folderId)
            iObj.put("Title", i.title)
            iObj.put("Text", i.text)
            iObj.put("IsEnabled", i.isEnabled)
            iObj.put("Order", i.order)
            itemsArr.put(iObj)
        }
        json.put("Items", itemsArr)

        return json
    }
}

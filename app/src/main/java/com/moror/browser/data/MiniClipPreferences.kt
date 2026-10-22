package com.moror.browser.data

import android.content.Context
import android.os.Environment
import com.moror.browser.model.CapturedImage
import com.moror.browser.model.MiniClipDraft
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object MiniClipPreferences {
    private const val CONFIG_FILE_NAME = "miniclip_settings.json"
    private const val PREFS_BACKUP_NAME = "moror_global_preferences"
    private const val KEY_PREFS_JSON = "json_cache"

    private const val KEY_SAVE_DIR = "save_directory_path"
    private const val KEY_MIN_DIMENSION = "min_image_dimension"
    private const val KEY_NEGATIVE_LOCKED = "negative_prompt_locked"
    private const val KEY_LOCKED_NEGATIVE_TEXT = "locked_negative_prompt_text"
    private const val KEY_AUTO_IMPORT = "auto_import_images"
    private const val KEY_AUTO_CLIPBOARD = "auto_fill_clipboard"
    private const val KEY_BLOCK_VIDEOS = "block_videos_enabled"
    private const val KEY_SEARCH_ENGINE = "default_search_engine"
    private const val KEY_EXACT_MATCH = "dimension_exact_match"
    private const val KEY_TARGET_WIDTH = "target_image_width"
    private const val KEY_TARGET_HEIGHT = "target_image_height"
    private const val KEY_BURST_FLOOD_PROTECTION = "burst_flood_protection_enabled"
    private const val KEY_MANUAL_CLIPBOARD_ONLY = "manual_clipboard_only_enabled"
    const val SOUND_TYPE_CAMERA = "camera"
    const val SOUND_TYPE_TICK = "tick"
    const val SOUND_TYPE_WATER = "water"
    const val SOUND_TYPE_SENT = "sent"

    private const val KEY_IMPORT_SOUND = "miniclip_import_sound_enabled"
    private const val KEY_IMPORT_SOUND_TYPE = "miniclip_import_sound_type"
    private const val KEY_IMPORT_SOUND_VOLUME = "miniclip_import_sound_volume"
    private const val KEY_IMPORT_VIBRATION = "miniclip_import_vibration_enabled"
    private const val KEY_IMPORT_VIBRATION_INTENSITY = "miniclip_import_vibration_intensity"
    private const val KEY_FIXED_TITLE_PREFIX = "fixed_title_prefix"
    private const val KEY_AUTO_MIX_CLIPBOARD = "auto_mix_clipboard_enabled"
    private const val KEY_MIXER_EXTRA_TITLE = "mixer_extra_title"
    private const val KEY_MIXER_INJECTED_TEXT = "mixer_injected_text"
    private const val KEY_MIXER_POSITION_MODE = "mixer_position_mode"
    private const val KEY_MIXER_COMMA_INDEX = "mixer_comma_index"
    private const val KEY_SHOW_WEB_TABS_BAR = "show_web_tabs_bar_enabled"

    private fun getConfigFile(context: Context): File {
        val dir = context.applicationContext.filesDir
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, CONFIG_FILE_NAME)
    }

    @Synchronized
    private fun readConfig(context: Context): JSONObject {
        // 1. Try reading from disk file
        try {
            val file = getConfigFile(context)
            if (file.exists() && file.length() > 0) {
                val content = file.readText(Charsets.UTF_8).trim()
                if (content.isNotEmpty()) {
                    return JSONObject(content)
                }
            }
        } catch (e: Exception) {
            // Fallback to shared preferences
        }

        // 2. Fallback to SharedPreferences
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_BACKUP_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_PREFS_JSON, null)
            if (!jsonStr.isNullOrBlank()) {
                return JSONObject(jsonStr)
            }
        } catch (e: Exception) {
            // Ignore
        }

        return JSONObject()
    }

    @Synchronized
    private fun writeConfig(context: Context, json: JSONObject) {
        val jsonStr = json.toString(2)

        // 1. Write to atomic File with fsync
        try {
            val file = getConfigFile(context)
            FileOutputStream(file).use { fos ->
                fos.write(jsonStr.toByteArray(Charsets.UTF_8))
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (e: Exception) {
                    // Ignore sync if unsupported
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Write to SharedPreferences synchronously with commit() across all processes
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_BACKUP_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_PREFS_JSON, jsonStr).commit()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getSaveDirectory(context: Context): File {
        val json = readConfig(context)
        val customPath = json.optString(KEY_SAVE_DIR, "")
        if (customPath.isNotBlank()) {
            val dir = File(customPath)
            if (dir.exists() || dir.mkdirs()) {
                return dir
            }
        }

        // Default to standard Downloads folder: Download/SeaArt_Studio
        val defaultDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val seaArtDir = File(defaultDownloads, "SeaArt_Studio")
        if (!seaArtDir.exists()) {
            seaArtDir.mkdirs()
        }
        return seaArtDir
    }

    fun setSaveDirectory(context: Context, path: String) {
        val json = readConfig(context)
        json.put(KEY_SAVE_DIR, path)
        writeConfig(context, json)
    }

    fun getTargetWidth(context: Context): Int {
        val json = readConfig(context)
        return if (json.has(KEY_TARGET_WIDTH)) json.optInt(KEY_TARGET_WIDTH, 512) else json.optInt(KEY_MIN_DIMENSION, 512)
    }

    fun getTargetHeight(context: Context): Int {
        val json = readConfig(context)
        return if (json.has(KEY_TARGET_HEIGHT)) json.optInt(KEY_TARGET_HEIGHT, 512) else json.optInt(KEY_MIN_DIMENSION, 512)
    }

    fun isExactDimensionMatch(context: Context): Boolean {
        val json = readConfig(context)
        return json.optBoolean(KEY_EXACT_MATCH, false)
    }

    fun setDimensionSettings(context: Context, width: Int, height: Int, exactMatch: Boolean) {
        val json = readConfig(context)
        json.put(KEY_TARGET_WIDTH, width)
        json.put(KEY_TARGET_HEIGHT, height)
        json.put(KEY_MIN_DIMENSION, width)
        json.put(KEY_EXACT_MATCH, exactMatch)
        writeConfig(context, json)
    }

    fun getMinDimension(context: Context): Int = getTargetWidth(context)

    fun setMinDimension(context: Context, minDim: Int) {
        setDimensionSettings(context, minDim, minDim, false)
    }

    fun isNegativePromptLocked(context: Context, slotIndex: Int = 1): Boolean {
        val json = readConfig(context)
        val key = "${KEY_NEGATIVE_LOCKED}_slot_$slotIndex"
        return if (json.has(key)) json.optBoolean(key, false) else json.optBoolean(KEY_NEGATIVE_LOCKED, false)
    }

    fun setNegativePromptLocked(context: Context, locked: Boolean, slotIndex: Int = 1) {
        val json = readConfig(context)
        val key = "${KEY_NEGATIVE_LOCKED}_slot_$slotIndex"
        json.put(key, locked)
        if (slotIndex == 1) {
            json.put(KEY_NEGATIVE_LOCKED, locked)
        }
        writeConfig(context, json)
    }

    fun getLockedNegativePrompt(context: Context, slotIndex: Int = 1): String {
        val json = readConfig(context)
        val key = "${KEY_LOCKED_NEGATIVE_TEXT}_slot_$slotIndex"
        return if (json.has(key)) json.optString(key, "") else json.optString(KEY_LOCKED_NEGATIVE_TEXT, "")
    }

    fun setLockedNegativePrompt(context: Context, text: String, slotIndex: Int = 1) {
        val json = readConfig(context)
        val key = "${KEY_LOCKED_NEGATIVE_TEXT}_slot_$slotIndex"
        json.put(key, text)
        if (slotIndex == 1) {
            json.put(KEY_LOCKED_NEGATIVE_TEXT, text)
        }
        writeConfig(context, json)
    }

    fun saveSlotDraft(context: Context, slotIndex: Int, draft: MiniClipDraft) {
        try {
            val json = readConfig(context)
            val draftObj = JSONObject()
            draftObj.put("title", draft.title)
            draftObj.put("extraTitle", draft.extraTitle)
            draftObj.put("positivePrompt", draft.positivePrompt)
            draftObj.put("negativePrompt", draft.negativePrompt)
            draftObj.put("isPromptMixed", draft.isPromptMixed)

            val imagesArr = JSONArray()
            for (img in draft.images) {
                val imgObj = JSONObject()
                imgObj.put("id", img.id)
                imgObj.put("imageUrl", img.imageUrl ?: "")
                imgObj.put("width", img.width)
                imgObj.put("height", img.height)
                imgObj.put("timestamp", img.timestamp)
                imagesArr.put(imgObj)
            }
            draftObj.put("images", imagesArr)

            json.put("draft_slot_$slotIndex", draftObj)
            writeConfig(context, json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadSlotDraft(context: Context, slotIndex: Int): MiniClipDraft {
        try {
            val json = readConfig(context)
            val draftKey = "draft_slot_$slotIndex"
            if (!json.has(draftKey)) return MiniClipDraft()

            val draftObj = json.optJSONObject(draftKey) ?: return MiniClipDraft()
            val draft = MiniClipDraft(
                title = draftObj.optString("title", ""),
                extraTitle = draftObj.optString("extraTitle", ""),
                positivePrompt = draftObj.optString("positivePrompt", ""),
                negativePrompt = draftObj.optString("negativePrompt", ""),
                isPromptMixed = draftObj.optBoolean("isPromptMixed", false)
            )

            val imagesArr = draftObj.optJSONArray("images")
            if (imagesArr != null) {
                for (i in 0 until imagesArr.length()) {
                    val imgObj = imagesArr.optJSONObject(i) ?: continue
                    val imgUrl = imgObj.optString("imageUrl", "")
                    if (imgUrl.isNotBlank()) {
                        draft.images.add(
                            CapturedImage(
                                id = imgObj.optString("id", UUID.randomUUID().toString()),
                                imageUrl = imgUrl,
                                width = imgObj.optInt("width", 0),
                                height = imgObj.optInt("height", 0),
                                timestamp = imgObj.optLong("timestamp", System.currentTimeMillis())
                            )
                        )
                    }
                }
            }
            return draft
        } catch (e: Exception) {
            e.printStackTrace()
            return MiniClipDraft()
        }
    }

    fun clearSlotDraft(context: Context, slotIndex: Int) {
        try {
            val json = readConfig(context)
            json.remove("draft_slot_$slotIndex")
            writeConfig(context, json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getLastHandledClipboard(context: Context, slotIndex: Int): String {
        val json = readConfig(context)
        return json.optString("clipboard_handled_slot_$slotIndex", "")
    }

    fun setLastHandledClipboard(context: Context, slotIndex: Int, text: String) {
        val json = readConfig(context)
        json.put("clipboard_handled_slot_$slotIndex", text)
        writeConfig(context, json)
    }

    fun isAutoImportEnabled(context: Context): Boolean {
        val json = readConfig(context)
        return if (json.has(KEY_AUTO_IMPORT)) json.optBoolean(KEY_AUTO_IMPORT, true) else true
    }

    fun setAutoImportEnabled(context: Context, enabled: Boolean) {
        val json = readConfig(context)
        json.put(KEY_AUTO_IMPORT, enabled)
        writeConfig(context, json)
    }

    fun isManualClipboardOnly(context: Context): Boolean {
        val json = readConfig(context)
        return if (json.has(KEY_MANUAL_CLIPBOARD_ONLY)) {
            json.optBoolean(KEY_MANUAL_CLIPBOARD_ONLY, true)
        } else if (json.has(KEY_AUTO_CLIPBOARD)) {
            !json.optBoolean(KEY_AUTO_CLIPBOARD, false)
        } else {
            true // Default: active (manual only)
        }
    }

    fun setManualClipboardOnly(context: Context, enabled: Boolean) {
        val json = readConfig(context)
        json.put(KEY_MANUAL_CLIPBOARD_ONLY, enabled)
        json.put(KEY_AUTO_CLIPBOARD, !enabled)
        writeConfig(context, json)
    }

    fun isAutoClipboardEnabled(context: Context): Boolean {
        return !isManualClipboardOnly(context)
    }

    fun setAutoClipboardEnabled(context: Context, enabled: Boolean) {
        setManualClipboardOnly(context, !enabled)
    }

    fun isBlockVideosEnabled(context: Context): Boolean {
        val json = readConfig(context)
        return json.optBoolean(KEY_BLOCK_VIDEOS, false)
    }

    fun setBlockVideosEnabled(context: Context, enabled: Boolean) {
        val json = readConfig(context)
        json.put(KEY_BLOCK_VIDEOS, enabled)
        writeConfig(context, json)
    }

    fun getSearchEngine(context: Context): String {
        val json = readConfig(context)
        return json.optString(KEY_SEARCH_ENGINE, "Google (گوگل)")
    }

    fun setSearchEngine(context: Context, engine: String) {
        val json = readConfig(context)
        json.put(KEY_SEARCH_ENGINE, engine)
        writeConfig(context, json)
    }

    fun isBurstProtectionEnabled(context: Context): Boolean {
        val json = readConfig(context)
        return if (json.has(KEY_BURST_FLOOD_PROTECTION)) json.optBoolean(KEY_BURST_FLOOD_PROTECTION, true) else true
    }

    fun setBurstProtectionEnabled(context: Context, enabled: Boolean) {
        val json = readConfig(context)
        json.put(KEY_BURST_FLOOD_PROTECTION, enabled)
        writeConfig(context, json)
    }

    fun isImportSoundEnabled(context: Context): Boolean {
        val json = readConfig(context)
        return json.optBoolean(KEY_IMPORT_SOUND, true)
    }

    fun setImportSoundEnabled(context: Context, enabled: Boolean) {
        val json = readConfig(context)
        json.put(KEY_IMPORT_SOUND, enabled)
        writeConfig(context, json)
    }

    fun isImportVibrationEnabled(context: Context): Boolean {
        val json = readConfig(context)
        return json.optBoolean(KEY_IMPORT_VIBRATION, true)
    }

    fun setImportVibrationEnabled(context: Context, enabled: Boolean) {
        val json = readConfig(context)
        json.put(KEY_IMPORT_VIBRATION, enabled)
        writeConfig(context, json)
    }

    fun getImportSoundType(context: Context): String {
        val json = readConfig(context)
        return json.optString(KEY_IMPORT_SOUND_TYPE, SOUND_TYPE_CAMERA)
    }

    fun setImportSoundType(context: Context, type: String) {
        val json = readConfig(context)
        json.put(KEY_IMPORT_SOUND_TYPE, type)
        writeConfig(context, json)
    }

    fun getImportSoundVolume(context: Context): Int {
        val json = readConfig(context)
        return json.optInt(KEY_IMPORT_SOUND_VOLUME, 80)
    }

    fun setImportSoundVolume(context: Context, volume: Int) {
        val json = readConfig(context)
        json.put(KEY_IMPORT_SOUND_VOLUME, volume.coerceIn(10, 100))
        writeConfig(context, json)
    }

    fun getImportVibrationDuration(context: Context): Int {
        val json = readConfig(context)
        return json.optInt(KEY_IMPORT_VIBRATION_INTENSITY, 70)
    }

    fun setImportVibrationDuration(context: Context, durationMs: Int) {
        val json = readConfig(context)
        json.put(KEY_IMPORT_VIBRATION_INTENSITY, durationMs.coerceIn(20, 120))
        writeConfig(context, json)
    }

    fun getFixedTitlePrefix(context: Context, slotIndex: Int = 1): String {
        val json = readConfig(context)
        val key = "${KEY_FIXED_TITLE_PREFIX}_slot_$slotIndex"
        return if (json.has(key)) json.optString(key, "") else json.optString(KEY_FIXED_TITLE_PREFIX, "")
    }

    fun setFixedTitlePrefix(context: Context, prefix: String, slotIndex: Int = 1) {
        val json = readConfig(context)
        val key = "${KEY_FIXED_TITLE_PREFIX}_slot_$slotIndex"
        json.put(key, prefix.trim())
        writeConfig(context, json)
    }

    fun isAutoMixClipboardEnabled(context: Context): Boolean {
        val json = readConfig(context)
        return json.optBoolean(KEY_AUTO_MIX_CLIPBOARD, false)
    }

    fun setAutoMixClipboardEnabled(context: Context, enabled: Boolean) {
        val json = readConfig(context)
        json.put(KEY_AUTO_MIX_CLIPBOARD, enabled)
        writeConfig(context, json)
    }

    fun getMixerExtraTitle(context: Context): String {
        val json = readConfig(context)
        return json.optString(KEY_MIXER_EXTRA_TITLE, "")
    }

    fun setMixerExtraTitle(context: Context, title: String) {
        val json = readConfig(context)
        json.put(KEY_MIXER_EXTRA_TITLE, title.trim())
        writeConfig(context, json)
    }

    fun getMixerInjectedText(context: Context): String {
        val json = readConfig(context)
        return json.optString(KEY_MIXER_INJECTED_TEXT, "yellow striped fur")
    }

    fun setMixerInjectedText(context: Context, text: String) {
        val json = readConfig(context)
        json.put(KEY_MIXER_INJECTED_TEXT, text.trim())
        writeConfig(context, json)
    }

    fun getMixerPositionMode(context: Context): String {
        val json = readConfig(context)
        return json.optString(KEY_MIXER_POSITION_MODE, "AFTER_COMMA")
    }

    fun setMixerPositionMode(context: Context, mode: String) {
        val json = readConfig(context)
        json.put(KEY_MIXER_POSITION_MODE, mode)
        writeConfig(context, json)
    }

    fun getMixerCommaIndex(context: Context): Int {
        val json = readConfig(context)
        return json.optInt(KEY_MIXER_COMMA_INDEX, 4)
    }

    fun setMixerCommaIndex(context: Context, index: Int) {
        val json = readConfig(context)
        json.put(KEY_MIXER_COMMA_INDEX, if (index < 1) 1 else index)
        writeConfig(context, json)
    }

    fun isWebTabsBarEnabled(context: Context): Boolean {
        val json = readConfig(context)
        return json.optBoolean(KEY_SHOW_WEB_TABS_BAR, false) // Default: false (hidden)
    }

    fun setWebTabsBarEnabled(context: Context, enabled: Boolean) {
        val json = readConfig(context)
        json.put(KEY_SHOW_WEB_TABS_BAR, enabled)
        writeConfig(context, json)
    }
}

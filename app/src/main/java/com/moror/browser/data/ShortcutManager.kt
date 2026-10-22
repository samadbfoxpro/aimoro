package com.moror.browser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class ShortcutItem(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var url: String,
    var iconFileName: String? = null,
    var colorHex: String = "#8B5CF6"
) {
    fun getDisplayInitial(): String {
        return title.trim().firstOrNull()?.uppercase() ?: "W"
    }

    fun getCleanHost(): String {
        return try {
            val uri = Uri.parse(url)
            uri.host?.removePrefix("www.") ?: url
        } catch (e: Exception) {
            url
        }
    }
}

object ShortcutManager {
    private const val FILE_NAME = "shared_shortcuts.json"
    private const val ICON_DIR = "shortcut_favicons"

    private fun getFile(context: Context): File {
        return File(context.applicationContext.filesDir, FILE_NAME)
    }

    fun getIconDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, ICON_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getIconFile(context: Context, iconFileName: String?): File? {
        if (iconFileName.isNullOrBlank()) return null
        val file = File(getIconDir(context), iconFileName)
        return if (file.exists() && file.length() > 0) file else null
    }

    @Synchronized
    fun getShortcuts(context: Context): MutableList<ShortcutItem> {
        val file = getFile(context)
        if (!file.exists()) {
            val defaults = createDefaultShortcuts()
            saveShortcuts(context, defaults)
            return defaults
        }

        return try {
            val content = file.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(content)
            val list = mutableListOf<ShortcutItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    ShortcutItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", "سایت"),
                        url = obj.optString("url", "https://www.google.com"),
                        iconFileName = if (obj.has("iconFileName") && !obj.isNull("iconFileName")) obj.optString("iconFileName") else null,
                        colorHex = obj.optString("colorHex", "#8B5CF6")
                    )
                )
            }
            if (list.isEmpty()) {
                val defaults = createDefaultShortcuts()
                saveShortcuts(context, defaults)
                defaults
            } else {
                list
            }
        } catch (e: Exception) {
            val defaults = createDefaultShortcuts()
            saveShortcuts(context, defaults)
            defaults
        }
    }

    private fun createDefaultShortcuts(): MutableList<ShortcutItem> {
        return mutableListOf(
            ShortcutItem(
                id = "google",
                title = "گوگل",
                url = "https://www.google.com",
                colorHex = "#4285F4"
            ),
            ShortcutItem(
                id = "seaart",
                title = "SeaArt (ساخت تصویر)",
                url = "https://www.seaart.ai/create/image",
                colorHex = "#8B5CF6"
            )
        )
    }

    @Synchronized
    fun saveShortcuts(context: Context, list: List<ShortcutItem>) {
        try {
            val file = getFile(context)
            val jsonArray = JSONArray()
            for (item in list) {
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("title", item.title)
                obj.put("url", item.url)
                if (item.iconFileName != null) {
                    obj.put("iconFileName", item.iconFileName)
                }
                obj.put("colorHex", item.colorHex)
                jsonArray.put(obj)
            }
            val jsonStr = jsonArray.toString(2)
            FileOutputStream(file).use { fos ->
                fos.write(jsonStr.toByteArray(Charsets.UTF_8))
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addShortcut(context: Context, item: ShortcutItem) {
        val list = getShortcuts(context)
        list.add(item)
        saveShortcuts(context, list)
    }

    fun updateShortcut(context: Context, item: ShortcutItem) {
        val list = getShortcuts(context)
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            list[idx] = item
            saveShortcuts(context, list)
        }
    }

    fun deleteShortcut(context: Context, id: String) {
        val list = getShortcuts(context)
        val removed = list.removeAll { it.id == id }
        if (removed) {
            saveShortcuts(context, list)
        }
    }

    suspend fun fetchAndCacheFavicon(context: Context, item: ShortcutItem): String? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(item.url)
            val host = uri.host ?: return@withContext null
            val iconFileName = "favicon_${item.id}.png"
            val targetFile = File(getIconDir(context), iconFileName)

            // If already cached and valid, return existing
            if (targetFile.exists() && targetFile.length() > 0) {
                return@withContext iconFileName
            }

            val iconUrls = listOf(
                "https://www.google.com/s2/favicons?domain=$host&sz=128",
                "https://$host/favicon.ico",
                "https://$host/apple-touch-icon.png"
            )

            for (urlStr in iconUrls) {
                try {
                    val url = URL(urlStr)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 8000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.connect()

                    if (conn.responseCode in 200..299) {
                        val bmp = BitmapFactory.decodeStream(conn.inputStream)
                        if (bmp != null && bmp.width > 8 && bmp.height > 8) {
                            FileOutputStream(targetFile).use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            item.iconFileName = iconFileName
                            updateShortcut(context, item)
                            return@withContext iconFileName
                        }
                    }
                } catch (e: Exception) {
                    // Try next candidate
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}

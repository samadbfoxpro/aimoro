package com.moror.browser.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object ProfileMigrationHelper {
    private const val TAG = "ProfileMigrationHelper"
    private const val PREFS_NAME = "moror_migration_prefs"
    private const val KEY_MIGRATION_DONE = "migration_to_multi_profile_done_v1"

    fun migrateIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATION_DONE, false)) {
            return
        }

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            Log.w(TAG, "WebViewFeature.MULTI_PROFILE is not supported on this device/WebView engine.")
            return
        }

        try {
            val profileStore = ProfileStore.getInstance()
            val dataDir = File(context.applicationInfo.dataDir)
            val baseWebviewDir = File(dataDir, "app_webview")

            // Scan for legacy directories matching app_webview_profile_X (1..30)
            for (slot in 1..30) {
                val legacyDirName = "app_webview_profile_$slot"
                val legacyDir = File(dataDir, legacyDirName)
                if (!legacyDir.exists() || !legacyDir.isDirectory) continue

                Log.d(TAG, "Migrating legacy profile slot $slot from: ${legacyDir.absolutePath}")

                try {
                    // a) Ensure modern profile is created via ProfileStore
                    val profileName = "profile_$slot"
                    val modernProfile = profileStore.getOrCreateProfile(profileName)

                    // b) Locate destination directories dynamically across app_webview
                    val targetDirs = mutableSetOf<File>()
                    targetDirs.add(File(baseWebviewDir, profileName))
                    targetDirs.add(File(File(baseWebviewDir, "Profiles"), profileName))

                    if (baseWebviewDir.exists() && baseWebviewDir.isDirectory) {
                        try {
                            baseWebviewDir.walkTopDown()
                                .maxDepth(3)
                                .filter { it.isDirectory && it.name == profileName }
                                .forEach { targetDirs.add(it) }
                        } catch (e: Exception) {
                            // Ignore scan errors
                        }
                    }

                    for (dir in targetDirs) {
                        if (!dir.exists()) {
                            dir.mkdirs()
                        }
                    }

                    // c) Locate source directories (app_webview_profile_X/Default or app_webview_profile_X)
                    val srcDefaultDir = File(legacyDir, "Default")
                    val srcDir = if (srcDefaultDir.exists() && srcDefaultDir.isDirectory) srcDefaultDir else legacyDir

                    // d) Clear empty newly-generated lock or journal files before overwriting
                    for (tDir in targetDirs) {
                        try {
                            File(tDir, "Cookies-journal").delete()
                            File(tDir, "Cookies-wal").delete()
                            File(tDir, "Cookies-shm").delete()
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }

                    // e) File Copy: Vital session & login files/directories
                    val vitalItems = listOf(
                        "Cookies",
                        "Cookies-journal",
                        "Cookies-wal",
                        "Local Storage",
                        "IndexedDB",
                        "Web Data",
                        "Login Data"
                    )

                    for (item in vitalItems) {
                        val srcFile = File(srcDir, item)
                        if (srcFile.exists()) {
                            for (tDir in targetDirs) {
                                val destFile = File(tDir, item)

                                if (item == "Cookies") {
                                    if (destFile.exists()) destFile.delete()
                                    File(tDir, "Cookies-journal").delete()
                                    File(tDir, "Cookies-wal").delete()
                                    File(tDir, "Cookies-shm").delete()
                                } else if (destFile.exists()) {
                                    if (destFile.isDirectory) {
                                        destFile.deleteRecursively()
                                    } else {
                                        destFile.delete()
                                    }
                                }

                                copyRecursivelySafe(srcFile, destFile)
                            }
                        }
                    }

                    // f) SQLite Injection Fallback: Directly read cookies from legacy DB and inject to CookieManager
                    val legacyCookieFile = File(srcDir, "Cookies")
                    injectCookiesFromLegacyDb(legacyCookieFile, modernProfile)

                    // g) Clean up heavy caches in legacy dir to free space
                    val cacheDirNames = listOf("Cache", "Code Cache", "GPUCache", "Service Worker")
                    for (cName in cacheDirNames) {
                        val cDir = File(legacyDir, cName)
                        if (cDir.exists()) {
                            cDir.deleteRecursively()
                        }
                        val cDefaultDir = File(srcDefaultDir, cName)
                        if (cDefaultDir.exists()) {
                            cDefaultDir.deleteRecursively()
                        }
                    }

                    Log.d(TAG, "Successfully migrated session for slot $slot")
                } catch (slotEx: Exception) {
                    Log.e(TAG, "Error migrating slot $slot", slotEx)
                }
            }

            // Mark migration as completed
            prefs.edit().putBoolean(KEY_MIGRATION_DONE, true).commit()
            Log.i(TAG, "Multi-profile migration completed successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run ProfileMigrationHelper", e)
        }
    }

    private fun injectCookiesFromLegacyDb(legacyCookieFile: File, newProfile: Profile) {
        if (!legacyCookieFile.exists() || legacyCookieFile.length() == 0L) return

        var db: SQLiteDatabase? = null
        var cursor: Cursor? = null
        try {
            db = SQLiteDatabase.openDatabase(
                legacyCookieFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )

            val cookieManager = newProfile.cookieManager

            cursor = db.rawQuery(
                "SELECT host_key, name, value, path, expires_utc, is_secure, is_httponly, samesite FROM cookies",
                null
            )

            val hostKeyIdx = cursor.getColumnIndex("host_key")
            val nameIdx = cursor.getColumnIndex("name")
            val valueIdx = cursor.getColumnIndex("value")
            val pathIdx = cursor.getColumnIndex("path")
            val secureIdx = cursor.getColumnIndex("is_secure")
            val httpOnlyIdx = cursor.getColumnIndex("is_httponly")
            val sameSiteIdx = cursor.getColumnIndex("samesite")

            var count = 0
            while (cursor.moveToNext()) {
                val hostKey = if (hostKeyIdx >= 0) cursor.getString(hostKeyIdx) else ""
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else ""
                val value = if (valueIdx >= 0) cursor.getString(valueIdx) else ""
                val path = if (pathIdx >= 0) cursor.getString(pathIdx) ?: "/" else "/"
                val isSecure = if (secureIdx >= 0) cursor.getInt(secureIdx) else 0
                val isHttpOnly = if (httpOnlyIdx >= 0) cursor.getInt(httpOnlyIdx) else 0
                val sameSite = if (sameSiteIdx >= 0) cursor.getInt(sameSiteIdx) else -1

                if (hostKey.isNotBlank() && name.isNotBlank() && value.isNotBlank()) {
                    val domain = hostKey.removePrefix(".")
                    val scheme = if (isSecure == 1) "https" else "http"
                    val cookieUrl = "$scheme://$domain$path"

                    val cookieValue = StringBuilder("$name=$value; path=$path; domain=$hostKey")
                    if (isSecure == 1) {
                        cookieValue.append("; Secure")
                    }
                    if (isHttpOnly == 1) {
                        cookieValue.append("; HttpOnly")
                    }
                    if (sameSite == 1) {
                        cookieValue.append("; SameSite=Lax")
                    } else if (sameSite == 2) {
                        cookieValue.append("; SameSite=Strict")
                    }

                    cookieManager.setCookie(cookieUrl, cookieValue.toString())
                    count++
                }
            }

            cookieManager.flush()
            Log.d(TAG, "Injected $count legacy cookies into modern CookieManager for profile ${newProfile.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Direct SQLite cookie injection skipped/failed: ${e.message}")
        } finally {
            try { cursor?.close() } catch (e: Exception) {}
            try { db?.close() } catch (e: Exception) {}
        }
    }

    private fun copyRecursivelySafe(src: File, dest: File) {
        try {
            if (src.isDirectory) {
                if (!dest.exists()) {
                    dest.mkdirs()
                }
                src.listFiles()?.forEach { child ->
                    copyRecursivelySafe(child, File(dest, child.name))
                }
            } else {
                dest.parentFile?.let {
                    if (!it.exists()) it.mkdirs()
                }
                FileInputStream(src).use { inStream ->
                    FileOutputStream(dest).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying ${src.absolutePath} to ${dest.absolutePath}", e)
        }
    }
}

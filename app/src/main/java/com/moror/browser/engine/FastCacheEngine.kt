package com.moror.browser.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class CacheCategory(
    val id: String,
    val titleFa: String,
    val extensionsDesc: String,
    val colorHex: String
) {
    IMAGE(
        id = "image",
        titleFa = "تصاویر و گرافیک‌ها",
        extensionsDesc = "WebP, PNG, JPG, JPEG, GIF, SVG, AVIF, ICO",
        colorHex = "#10B981" // Emerald Green
    ),
    VIDEO(
        id = "video",
        titleFa = "ویدیوها و صوت",
        extensionsDesc = "MP4, WebM, MP3, M4A, Media Streams",
        colorHex = "#EF4444" // Crimson Red
    ),
    FONT(
        id = "font",
        titleFa = "فونت‌های وب‌سایت",
        extensionsDesc = "WOFF2, WOFF, TTF, OTF, EOT",
        colorHex = "#F59E0B" // Amber / Orange
    ),
    STYLE_CODE(
        id = "style_code",
        titleFa = "استایل‌ها و کدهای شتاب‌دهنده",
        extensionsDesc = "CSS, WebAssembly (WASM)",
        colorHex = "#3B82F6" // Royal Blue
    ),
    OTHER(
        id = "other",
        titleFa = "سایر منابع و فایل‌های موقت وب",
        extensionsDesc = "کش صفحات و بخش‌های موقت وب",
        colorHex = "#8B5CF6" // Purple
    );

    companion object {
        fun detectCategory(file: File): CacheCategory {
            val name = file.name.lowercase()
            return when {
                name.endsWith(".webp") || name.endsWith(".png") || name.endsWith(".jpg") ||
                name.endsWith(".jpeg") || name.endsWith(".gif") || name.endsWith(".svg") ||
                name.endsWith(".avif") || name.endsWith(".ico") || name.endsWith(".bmp") -> IMAGE

                name.endsWith(".mp4") || name.endsWith(".webm") || name.endsWith(".mp3") ||
                name.endsWith(".m4a") || name.endsWith(".wav") || name.endsWith(".ogg") ||
                name.endsWith(".ts") || name.endsWith(".flv") -> VIDEO

                name.endsWith(".woff2") || name.endsWith(".woff") || name.endsWith(".ttf") ||
                name.endsWith(".otf") || name.endsWith(".eot") -> FONT

                name.endsWith(".css") || name.endsWith(".wasm") -> STYLE_CODE

                else -> detectFromHeaderBytes(file)
            }
        }

        private fun detectFromHeaderBytes(file: File): CacheCategory {
            return try {
                if (!file.exists() || file.length() < 4) return OTHER
                val header = ByteArray(16)
                val readBytes = file.inputStream().use { it.read(header) }
                if (readBytes >= 4) {
                    val hex = header.take(readBytes).joinToString("") { "%02X".format(it) }
                    val ascii = String(header, 0, minOf(readBytes, 16), Charsets.ISO_8859_1)

                    if (hex.startsWith("FFD8FF") || hex.startsWith("89504E47") ||
                        ascii.startsWith("GIF") || ascii.startsWith("RIFF") ||
                        ascii.contains("WEBP") || ascii.contains("<svg")
                    ) {
                        return IMAGE
                    }

                    if (ascii.startsWith("wOF2") || ascii.startsWith("wOFF") ||
                        hex.startsWith("00010000") || hex.startsWith("4F54544F")
                    ) {
                        return FONT
                    }

                    if (ascii.contains("ftyp") || hex.startsWith("1A45DFA3") ||
                        ascii.startsWith("ID3") || hex.startsWith("FFF")
                    ) {
                        return VIDEO
                    }

                    if (hex.startsWith("0061736D")) { // wasm
                        return STYLE_CODE
                    }
                }
                OTHER
            } catch (e: Exception) {
                OTHER
            }
        }
    }
}

data class CategoryStat(
    val category: CacheCategory,
    var sizeBytes: Long = 0L,
    var fileCount: Int = 0,
    var percentOfTotal: Float = 0f
)

data class HostCacheStat(
    val host: String,
    val sizeBytes: Long,
    val fileCount: Int
)

data class CacheAnalysis(
    val totalSizeBytes: Long,
    val totalFiles: Int,
    val categoryStats: List<CategoryStat>,
    val topHosts: List<HostCacheStat>
)

object FastCacheEngine {

    private const val CACHE_DIR_NAME = "permanent_media_cache"

    fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Checks if a request is for video or media stream.
     */
    fun isVideoRequest(request: WebResourceRequest): Boolean {
        val uri = request.url ?: return false
        val urlStr = uri.toString().lowercase()
        val acceptHeader = request.requestHeaders?.get("Accept")?.lowercase() ?: ""
        return isVideoUrl(urlStr) || acceptHeader.contains("video/")
    }

    fun isVideoUrl(urlStr: String): Boolean {
        val lower = urlStr.lowercase()
        return lower.contains(".mp4") || lower.contains(".webm") ||
               lower.contains(".m3u8") || lower.contains(".ts") ||
               lower.contains(".mov") || lower.contains(".mkv") ||
               lower.contains(".avi") || lower.contains(".flv") ||
               lower.contains(".3gp") || lower.contains(".mpd") ||
               lower.contains("format=mp4") || lower.contains("format=webm") ||
               lower.contains("/video/") || lower.contains("type=video") ||
               lower.contains("mime=video") || lower.contains("content-type=video") ||
               lower.contains("video_url")
    }

    /**
     * Returns an immediate 204 No Content response to instantly cut off video network transfer.
     */
    fun createEmptyVideoResponse(): WebResourceResponse {
        return WebResourceResponse(
            "video/mp4",
            "UTF-8",
            204,
            "No Content",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-cache, no-store, must-revalidate"
            ),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    /**
     * Identifies if a request is for media, fonts, or stylesheets (to be aggressively cached),
     * while excluding live APIs and authentication so code/actions stay live.
     */
    fun isCacheableResource(request: WebResourceRequest, isVideoBlocked: Boolean = false): Boolean {
        if (request.method != "GET") return false

        val uri = request.url ?: return false
        val urlStr = uri.toString().lowercase()

        // Exclude live APIs, authentication, and dynamic sockets
        if (urlStr.contains("/api/") ||
            urlStr.contains("/auth") ||
            urlStr.contains("/oauth") ||
            urlStr.contains("/signin") ||
            urlStr.contains("/login") ||
            urlStr.contains("/token") ||
            urlStr.contains("/ws") ||
            urlStr.contains("/socket")
        ) {
            return false
        }

        // If videos are blocked, do not cache or download videos
        if (isVideoBlocked && isVideoUrl(urlStr)) {
            return false
        }

        // Exclude main HTML navigation documents
        if (request.isForMainFrame) {
            return false
        }

        // Cache Media & Fonts & Stylesheets (Scripts load live from server)
        return isMediaOrFontOrStyle(urlStr)
    }

    private fun isMediaOrFontOrStyle(urlStr: String): Boolean {
        // Fonts
        if (urlStr.contains(".woff2") || urlStr.contains(".woff") ||
            urlStr.contains(".ttf") || urlStr.contains(".otf") || urlStr.contains(".eot")
        ) return true

        // Stylesheets
        if (urlStr.contains(".css")) return true

        // Images & Graphics (extensions + CDN query parameters like format=webp, format,webp)
        if (urlStr.contains(".webp") || urlStr.contains(".png") ||
            urlStr.contains(".jpg") || urlStr.contains(".jpeg") ||
            urlStr.contains(".gif") || urlStr.contains(".svg") ||
            urlStr.contains(".ico") || urlStr.contains(".avif") ||
            urlStr.contains("format,webp") || urlStr.contains("format=webp") ||
            urlStr.contains("format/webp") || urlStr.contains("format=avif") ||
            urlStr.contains("format,avif")
        ) return true

        // Audio, Video & WebAssembly
        if (urlStr.contains(".mp4") || urlStr.contains(".webm") ||
            urlStr.contains(".mp3") || urlStr.contains(".wasm")
        ) return true

        return false
    }

    fun getMimeType(urlStr: String): String {
        val lower = urlStr.lowercase()
        return when {
            lower.contains(".woff2") -> "font/woff2"
            lower.contains(".woff") -> "font/woff"
            lower.contains(".ttf") -> "font/ttf"
            lower.contains(".otf") -> "font/otf"
            lower.contains(".css") -> "text/css"
            lower.contains(".webp") || lower.contains("webp") -> "image/webp"
            lower.contains(".png") -> "image/png"
            lower.contains(".jpg") || lower.contains(".jpeg") -> "image/jpeg"
            lower.contains(".svg") -> "image/svg+xml"
            lower.contains(".gif") -> "image/gif"
            lower.contains(".ico") -> "image/x-icon"
            lower.contains(".avif") || lower.contains("avif") -> "image/avif"
            lower.contains(".mp4") -> "video/mp4"
            lower.contains(".webm") -> "video/webm"
            lower.contains(".mp3") -> "audio/mpeg"
            lower.contains(".wasm") -> "application/wasm"
            else -> "application/octet-stream"
        }
    }

    private fun md5(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }

    private val memoryCache: LruCache<String, Bitmap> by lazy {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8
        object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    fun getCacheFile(context: Context, uri: Uri): File {
        val host = uri.host?.replace(Regex("[^a-zA-Z0-9.-]"), "_") ?: "general"
        val hostDir = File(getCacheDir(context), host)
        if (!hostDir.exists()) {
            hostDir.mkdirs()
        }
        val hash = md5(uri.toString())
        val urlStr = uri.toString().lowercase()
        val ext = when {
            urlStr.contains(".webp") -> ".webp"
            urlStr.contains(".png") -> ".png"
            urlStr.contains(".jpg") || urlStr.contains(".jpeg") -> ".jpg"
            urlStr.contains(".gif") -> ".gif"
            urlStr.contains(".svg") -> ".svg"
            urlStr.contains(".avif") -> ".avif"
            urlStr.contains(".ico") -> ".ico"
            urlStr.contains(".woff2") -> ".woff2"
            urlStr.contains(".woff") -> ".woff"
            urlStr.contains(".ttf") -> ".ttf"
            urlStr.contains(".otf") -> ".otf"
            urlStr.contains(".css") -> ".css"
            urlStr.contains(".mp4") -> ".mp4"
            urlStr.contains(".webm") -> ".webm"
            urlStr.contains(".mp3") -> ".mp3"
            urlStr.contains(".wasm") -> ".wasm"
            else -> ".cache"
        }
        return File(hostDir, "$hash$ext")
    }

    fun getCachedFile(context: Context, urlStr: String): File? {
        return try {
            val uri = Uri.parse(urlStr)
            val file = getCacheFile(context, uri)
            if (file.exists() && file.length() > 0) file else null
        } catch (e: Exception) {
            null
        }
    }

    fun loadThumbnailBitmap(context: Context, urlStr: String, reqWidth: Int = 300, reqHeight: Int = 300): Bitmap? {
        // 1. Instant RAM cache
        val mem = memoryCache.get(urlStr)
        if (mem != null && !mem.isRecycled) return mem

        // 2. High-speed disk cache
        val cachedFile = getCachedFile(context, urlStr)
        if (cachedFile != null) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(cachedFile.absolutePath, opts)
                var inSample = 1
                if (opts.outHeight > reqHeight || opts.outWidth > reqWidth) {
                    val halfHeight = opts.outHeight / 2
                    val halfWidth = opts.outWidth / 2
                    while (halfHeight / inSample >= reqHeight && halfWidth / inSample >= reqWidth) {
                        inSample *= 2
                    }
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = inSample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bmp = BitmapFactory.decodeFile(cachedFile.absolutePath, decodeOpts)
                if (bmp != null) {
                    memoryCache.put(urlStr, bmp)
                    return bmp
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return null
    }

    fun getImageDimensions(context: Context, urlStr: String): Pair<Int, Int>? {
        val cachedFile = getCachedFile(context, urlStr) ?: return null
        if (!cachedFile.exists() || cachedFile.length() <= 0) return null
        return try {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(cachedFile.absolutePath, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                Pair(opts.outWidth, opts.outHeight)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun cacheBitmapToMemory(urlStr: String, bmp: Bitmap) {
        memoryCache.put(urlStr, bmp)
    }

    fun saveStreamToCache(context: Context, urlStr: String, inputStream: InputStream): File? {
        return try {
            val uri = Uri.parse(urlStr)
            val cacheFile = getCacheFile(context, uri)
            val tempFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp_${System.currentTimeMillis()}")
            tempFile.outputStream().use { out ->
                inputStream.copyTo(out)
            }
            if (tempFile.exists() && tempFile.length() > 0) {
                if (cacheFile.exists()) cacheFile.delete()
                tempFile.renameTo(cacheFile)
                cacheFile
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Intercepts the request: if cached on disk, returns it immediately (0ms network delay).
     * If not cached, downloads and caches in the permanent directory for subsequent ultra-fast loads.
     */
    fun interceptAndCache(context: Context, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        val urlStr = uri.toString()
        val cacheFile = getCacheFile(context, uri)
        val mimeType = getMimeType(urlStr)

        val headers = mutableMapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "public, max-age=31536000, immutable"
        )

        // 1. Serve from disk cache if exists and not empty
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return try {
                val stream: InputStream = FileInputStream(cacheFile)
                WebResourceResponse(mimeType, "UTF-8", 200, "OK", headers, stream)
            } catch (e: Exception) {
                null
            }
        }

        // 2. Fetch from network and write atomically to permanent disk cache
        return try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", WebViewHelper.getCleanMobileUserAgent(context))

            // Forward relevant browser request headers (like Referer, Accept) so CDNs and SeaArt authorize media
            request.requestHeaders?.forEach { (headerName, headerVal) ->
                val lowerKey = headerName.lowercase()
                if (lowerKey != "host" && lowerKey != "user-agent" && lowerKey != "accept-encoding") {
                    connection.setRequestProperty(headerName, headerVal)
                }
            }

            connection.connect()

            if (connection.responseCode in 200..299) {
                // Write to temporary file first, then atomically rename to prevent incomplete cache files
                val tempFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp_${System.currentTimeMillis()}")
                var writeSuccess = false
                try {
                    connection.inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    writeSuccess = tempFile.exists() && tempFile.length() > 0
                    if (writeSuccess) {
                        if (cacheFile.exists()) {
                            cacheFile.delete()
                        }
                        tempFile.renameTo(cacheFile)
                    }
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }

                if (cacheFile.exists() && cacheFile.length() > 0) {
                    val stream: InputStream = FileInputStream(cacheFile)
                    WebResourceResponse(mimeType, "UTF-8", 200, "OK", headers, stream)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            // In case of network glitch or timeout, let WebView handle directly
            null
        }
    }

    fun getCacheSizeBytes(context: Context): Long {
        return try {
            getCacheDir(context).walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Analyzes cache contents categorized by media type and by website host.
     * Strictly touches ONLY media/accelerator cache, NEVER login cookies or session data!
     */
    fun analyzeCache(context: Context): CacheAnalysis {
        val cacheDir = getCacheDir(context)
        val categoryMap = CacheCategory.values().associateWith { CategoryStat(it) }.toMutableMap()
        val hostMap = mutableMapOf<String, Pair<Long, Int>>() // host -> (size, count)

        var totalSize = 0L
        var totalCount = 0

        if (cacheDir.exists()) {
            val files = cacheDir.walkTopDown().filter { it.isFile }.toList()
            for (file in files) {
                val len = file.length()
                val cat = CacheCategory.detectCategory(file)
                val stat = categoryMap[cat] ?: CategoryStat(cat).also { categoryMap[cat] = it }
                stat.sizeBytes += len
                stat.fileCount += 1

                totalSize += len
                totalCount += 1

                // Extract host from parent directory name
                val hostName = file.parentFile?.name ?: "general"
                val currentHost = hostMap[hostName] ?: Pair(0L, 0)
                hostMap[hostName] = Pair(currentHost.first + len, currentHost.second + 1)
            }
        }

        // Also inspect generic WebView cache directory (e.g. context.cacheDir/WebView) while strictly ignoring cookies
        try {
            val generalWebCacheDir = File(context.cacheDir, "WebView")
            if (generalWebCacheDir.exists()) {
                val webFiles = generalWebCacheDir.walkTopDown().filter { it.isFile }.toList()
                for (file in webFiles) {
                    val len = file.length()
                    val cat = CacheCategory.detectCategory(file)
                    val stat = categoryMap[cat] ?: CategoryStat(cat).also { categoryMap[cat] = it }
                    stat.sizeBytes += len
                    stat.fileCount += 1
                    totalSize += len
                    totalCount += 1
                }
            }
        } catch (e: Exception) {
            // Ignore WebView general cache access errors
        }

        // Calculate percentages
        val categoryList = categoryMap.values.toList().map { stat ->
            val pct = if (totalSize > 0) (stat.sizeBytes.toFloat() / totalSize.toFloat()) * 100f else 0f
            stat.copy(percentOfTotal = pct)
        }

        // Top hosts sorted by size
        val topHosts = hostMap.entries
            .sortedByDescending { it.value.first }
            .take(6)
            .map { HostCacheStat(it.key, it.value.first, it.value.second) }

        return CacheAnalysis(
            totalSizeBytes = totalSize,
            totalFiles = totalCount,
            categoryStats = categoryList,
            topHosts = topHosts
        )
    }

    /**
     * Clears cache files belonging ONLY to a specific category.
     * Preserves all other categories, cookies, and login sessions.
     */
    fun clearCategory(context: Context, category: CacheCategory): Long {
        var freedBytes = 0L
        try {
            val cacheDir = getCacheDir(context)
            if (cacheDir.exists()) {
                val files = cacheDir.walkTopDown().filter { it.isFile }.toList()
                for (file in files) {
                    if (CacheCategory.detectCategory(file) == category) {
                        val len = file.length()
                        if (file.delete()) {
                            freedBytes += len
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return freedBytes
    }

    /**
     * Clears cached media files for a specific website host only.
     */
    fun clearHost(context: Context, host: String): Long {
        var freedBytes = 0L
        try {
            val hostDir = File(getCacheDir(context), host)
            if (hostDir.exists()) {
                val files = hostDir.walkTopDown().filter { it.isFile }.toList()
                for (f in files) {
                    val len = f.length()
                    if (f.delete()) {
                        freedBytes += len
                    }
                }
                hostDir.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return freedBytes
    }

    /**
     * Clears all media/accelerator cache while STRICTLY preserving cookies, logins, and profile slots.
     */
    fun clearAllPermanentCache(context: Context): Long {
        val total = getCacheSizeBytes(context)
        try {
            getCacheDir(context).deleteRecursively()
            getCacheDir(context).mkdirs()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return total
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024L -> "$bytes بایت"
            bytes < 1024L * 1024L -> "%.1f کیلوبایت".format(bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> "%.2f مگابایت".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f گیگابایت".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}

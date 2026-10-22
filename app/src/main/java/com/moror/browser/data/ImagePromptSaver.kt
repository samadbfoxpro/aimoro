package com.moror.browser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import com.moror.browser.engine.FastCacheEngine
import com.moror.browser.model.CapturedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object ImagePromptSaver {

    private fun compressBitmapToWebp(bitmap: Bitmap, out: OutputStream) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
        } else {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, 100, out)
        }
    }

    fun sanitizeFileName(input: String): String {
        var clean = input.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim()
        clean = clean.replace(Regex("\\s+"), " ")
        if (clean.length > 80) {
            clean = clean.substring(0, 80).trim()
        }
        return if (clean.isEmpty()) "SeaArt_Image" else clean
    }

    suspend fun savePair(
        context: Context,
        bitmap: Bitmap? = null,
        imageUrl: String? = null,
        title: String,
        positivePrompt: String,
        negativePrompt: String
    ): Result<Pair<File, File>> = withContext(Dispatchers.IO) {
        try {
            val saveDir = MiniClipPreferences.getSaveDirectory(context)
            if (!saveDir.exists()) {
                saveDir.mkdirs()
            }

            val safeTitle = sanitizeFileName(if (title.isBlank()) "SeaArt_${System.currentTimeMillis()}" else title)

            var imgFile = File(saveDir, "$safeTitle.webp")
            var txtFile = File(saveDir, "$safeTitle.txt")
            var counter = 1

            while (imgFile.exists() || txtFile.exists()) {
                imgFile = File(saveDir, "$safeTitle ($counter).webp")
                txtFile = File(saveDir, "$safeTitle ($counter).txt")
                counter++
            }

            val imageSaved = if (!imageUrl.isNullOrBlank()) {
                downloadAndSaveImage(context, imageUrl, imgFile)
            } else if (bitmap != null) {
                FileOutputStream(imgFile).use { out ->
                    compressBitmapToWebp(bitmap, out)
                }
                true
            } else {
                false
            }

            if (!imageSaved || !imgFile.exists()) {
                return@withContext Result.failure(Exception("ذخیره فایل تصویر با خطا مواجه شد"))
            }

            val txtContent = buildString {
                append("Positive Prompt:\n")
                append(positivePrompt.trim())
                append("\n\n")
                append("Negative Prompt:\n")
                append(negativePrompt.trim())
                append("\n")
            }
            txtFile.writeText(txtContent, Charsets.UTF_8)

            try {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(imgFile.absolutePath, txtFile.absolutePath),
                    null,
                    null
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }

            Result.success(Pair(imgFile, txtFile))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveMultipleImagesAndPrompts(
        context: Context,
        images: List<CapturedImage>,
        title: String,
        positivePrompt: String,
        negativePrompt: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (images.isEmpty()) {
                return@withContext Result.failure(Exception("هیچ تصویری برای ذخیره در مینی‌کلیپ وجود ندارد"))
            }

            val saveDir = MiniClipPreferences.getSaveDirectory(context)
            if (!saveDir.exists()) {
                saveDir.mkdirs()
            }

            val safeTitle = sanitizeFileName(if (title.isBlank()) "SeaArt_${System.currentTimeMillis()}" else title)
            val scannedFiles = mutableListOf<String>()
            var savedCount = 0

            val txtContent = buildString {
                append("Positive Prompt:\n")
                append(positivePrompt.trim())
                append("\n\n")
                append("Negative Prompt:\n")
                append(negativePrompt.trim())
                append("\n")
            }

            for (i in images.indices) {
                val item = images[i]
                val currentBaseTitle = if (images.size > 1) "$safeTitle (${i + 1})" else safeTitle

                var imgFile = File(saveDir, "$currentBaseTitle.webp")
                var txtFile = File(saveDir, "$currentBaseTitle.txt")
                var counter = 1

                while (imgFile.exists() || txtFile.exists()) {
                    imgFile = File(saveDir, "$currentBaseTitle ($counter).webp")
                    txtFile = File(saveDir, "$currentBaseTitle ($counter).txt")
                    counter++
                }

                // Save Image
                val imageSaved = if (!item.imageUrl.isNullOrBlank()) {
                    downloadAndSaveImage(context, item.imageUrl!!, imgFile)
                } else if (item.bitmap != null) {
                    FileOutputStream(imgFile).use { out ->
                        compressBitmapToWebp(item.bitmap!!, out)
                    }
                    true
                } else {
                    false
                }

                if (imageSaved && imgFile.exists() && imgFile.length() > 0L) {
                    txtFile.writeText(txtContent, Charsets.UTF_8)
                    scannedFiles.add(imgFile.absolutePath)
                    scannedFiles.add(txtFile.absolutePath)
                    savedCount++
                }
            }

            if (scannedFiles.isNotEmpty()) {
                try {
                    MediaScannerConnection.scanFile(
                        context,
                        scannedFiles.toTypedArray(),
                        null,
                        null
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (savedCount > 0) {
                Result.success(savedCount)
            } else {
                Result.failure(Exception("خطا در ذخیره‌سازی تصاویر"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun downloadAndSaveImage(context: Context?, urlStr: String, destination: File): Boolean {
        // 1. Instant copy from local cache if present
        if (context != null) {
            val cachedFile = FastCacheEngine.getCachedFile(context, urlStr)
            if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
                try {
                    cachedFile.copyTo(destination, overwrite = true)
                    return true
                } catch (e: Exception) {
                    // Fallback to network
                }
            }
        }

        // 2. Otherwise download and store to disk + cache
        return try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = true
            connection.connect()

            if (connection.responseCode in 200..299) {
                connection.inputStream.use { input ->
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output)
                    }
                }
                if (context != null && destination.exists() && destination.length() > 0) {
                    try {
                        destination.inputStream().use { input ->
                            FastCacheEngine.saveStreamToCache(context, urlStr, input)
                        }
                    } catch (e: Exception) {}
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    data class BitmapWithDimensions(
        val bitmap: Bitmap,
        val originalWidth: Int,
        val originalHeight: Int
    )

    suspend fun fetchBitmapWithDimensions(context: Context? = null, urlStr: String): BitmapWithDimensions? = withContext(Dispatchers.IO) {
        if (urlStr.isBlank()) return@withContext null

        // 1. Try instantaneous RAM and local disk cache
        if (context != null) {
            val cachedDim = FastCacheEngine.getImageDimensions(context, urlStr)
            val cachedBmp = FastCacheEngine.loadThumbnailBitmap(context, urlStr)
            if (cachedBmp != null && cachedDim != null && cachedDim.first > 0 && cachedDim.second > 0) {
                return@withContext BitmapWithDimensions(cachedBmp, cachedDim.first, cachedDim.second)
            }
        }

        // 2. Fetch from network, cache on disk, and decode safely with inSampleSize
        try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.instanceFollowRedirects = true
            connection.connect()
            if (connection.responseCode in 200..299) {
                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.isNotEmpty()) {
                    if (context != null) {
                        try {
                            FastCacheEngine.saveStreamToCache(context, urlStr, bytes.inputStream())
                        } catch (e: Exception) {}
                    }
                    val opts = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    val origW = opts.outWidth
                    val origH = opts.outHeight

                    var inSample = 1
                    val reqW = 320
                    val reqH = 320
                    if (opts.outHeight > reqH || opts.outWidth > reqW) {
                        val halfH = opts.outHeight / 2
                        val halfW = opts.outWidth / 2
                        while (halfH / inSample >= reqH && halfW / inSample >= reqW) {
                            inSample *= 2
                        }
                    }
                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = inSample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                    if (bmp != null) {
                        FastCacheEngine.cacheBitmapToMemory(urlStr, bmp)
                        return@withContext BitmapWithDimensions(
                            bitmap = bmp,
                            originalWidth = if (origW > 0) origW else bmp.width,
                            originalHeight = if (origH > 0) origH else bmp.height
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    suspend fun fetchBitmapFromUrl(context: Context? = null, urlStr: String): Bitmap? {
        return fetchBitmapWithDimensions(context, urlStr)?.bitmap
    }
}

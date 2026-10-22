package com.moror.browser.model

import android.graphics.Bitmap
import java.util.UUID

data class CapturedImage(
    val id: String = UUID.randomUUID().toString(),
    var imageUrl: String? = null,
    var bitmap: Bitmap? = null,
    var width: Int = 0,
    var height: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

data class MiniClipDraft(
    val images: MutableList<CapturedImage> = mutableListOf(),
    var title: String = "",
    var extraTitle: String = "",
    var positivePrompt: String = "",
    var negativePrompt: String = "",
    var isPromptMixed: Boolean = false
)

typealias MiniClipItem = CapturedImage

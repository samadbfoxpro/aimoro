package com.moror.browser.ui.dialogs

import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.moror.browser.R
import com.moror.browser.ui.BaseBrowserActivity
import com.moror.browser.data.ImagePromptSaver
import com.moror.browser.data.MiniClipPreferences
import com.moror.browser.model.MiniClipItem
import kotlinx.coroutines.launch

class MiniClipDialog(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val initialItem: MiniClipItem? = null,
    private val onSaveSuccess: () -> Unit = {}
) {

    private var currentBitmap: Bitmap? = initialItem?.bitmap
    private var currentImageUrl: String? = initialItem?.imageUrl
    private var isNegativeLocked = MiniClipPreferences.isNegativePromptLocked(context)

    fun show() {
        val d = Dialog(context)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(R.layout.dialog_mini_clip)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val ivThumb = d.findViewById<ImageView>(R.id.ivThumbPreview)
        val tvDimensions = d.findViewById<TextView>(R.id.tvDimensionsBadge)
        val etTitle = d.findViewById<EditText>(R.id.etMiniClipTitle)
        val etPositive = d.findViewById<EditText>(R.id.etPositivePrompt)
        val etNegative = d.findViewById<EditText>(R.id.etNegativePrompt)
        val btnPastePos = d.findViewById<Button>(R.id.btnPastePositive)
        val btnClearPos = d.findViewById<Button>(R.id.btnClearPositive)
        val btnToggleLock = d.findViewById<Button>(R.id.btnToggleLockNegative)
        val btnSave = d.findViewById<Button>(R.id.btnSaveMiniClip)
        val btnClose = d.findViewById<ImageButton>(R.id.btnCloseMiniClip)
        val tvSavePath = d.findViewById<TextView>(R.id.tvSaveLocationInfo)

        // 1. Setup Image & Dimensions
        if (currentBitmap != null) {
            ivThumb.setImageBitmap(currentBitmap)
            val w = if (initialItem != null && initialItem.width > 0) initialItem.width else currentBitmap!!.width
            val h = if (initialItem != null && initialItem.height > 0) initialItem.height else currentBitmap!!.height
            tvDimensions.text = "${w}×${h}"
        } else if (!currentImageUrl.isNullOrBlank()) {
            tvDimensions.text = "دریافت..."
            lifecycleScope.launch {
                val res = ImagePromptSaver.fetchBitmapWithDimensions(context, currentImageUrl!!)
                if (res != null) {
                    currentBitmap = res.bitmap
                    ivThumb.setImageBitmap(res.bitmap)
                    val w = if (initialItem != null && initialItem.width > 0) initialItem.width else res.originalWidth
                    val h = if (initialItem != null && initialItem.height > 0) initialItem.height else res.originalHeight
                    tvDimensions.text = "${w}×${h}"
                }
            }
        }

        // 2. Setup Save path note
        val saveDir = MiniClipPreferences.getSaveDirectory(context)
        tvSavePath.text = "مسیر ذخیره: ${saveDir.name}/[Title].webp + .txt"

        // 3. Setup Negative Prompt & Lock State
        fun updateNegativeLockUI() {
            if (isNegativeLocked) {
                btnToggleLock.text = "🔒 قفل است"
                btnToggleLock.setTextColor(context.getColor(R.color.accent_green))
                etNegative.setBackgroundResource(R.drawable.bg_locked_box)
                etNegative.isEnabled = false
                val lockedText = MiniClipPreferences.getLockedNegativePrompt(context)
                if (lockedText.isNotEmpty()) {
                    etNegative.setText(lockedText)
                }
            } else {
                btnToggleLock.text = "🔓 باز (تغییرپذیر)"
                btnToggleLock.setTextColor(context.getColor(R.color.text_secondary))
                etNegative.setBackgroundResource(R.drawable.bg_edit_text)
                etNegative.isEnabled = true
            }
        }
        updateNegativeLockUI()

        btnToggleLock.setOnClickListener {
            isNegativeLocked = !isNegativeLocked
            MiniClipPreferences.setNegativePromptLocked(context, isNegativeLocked)
            if (isNegativeLocked) {
                val currentNegText = etNegative.text.toString().trim()
                MiniClipPreferences.setLockedNegativePrompt(context, currentNegText)
            }
            updateNegativeLockUI()
        }

        // 4. Setup Positive Prompt & Clipboard Actions
        fun showNotify(msg: String) {
            if (context is BaseBrowserActivity) {
                context.showTopPillNotification(msg)
            } else {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }

        fun pasteFromClipboard() {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString()?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        etPositive.setText(text)
                        etPositive.setSelection(text.length)
                        showNotify("پرامپت از کلیپ‌بورد درج شد")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Auto paste if configured and positive prompt is empty
        if (MiniClipPreferences.isAutoClipboardEnabled(context) && etPositive.text.isEmpty()) {
            pasteFromClipboard()
        }

        btnPastePos.setOnClickListener {
            pasteFromClipboard()
        }

        btnClearPos.setOnClickListener {
            etPositive.setText("")
            showNotify("پرامپت مثبت پاک شد")
        }

        // 5. Default Title
        if (etTitle.text.isEmpty()) {
            etTitle.setText("SeaArt_${System.currentTimeMillis() % 100000}")
        }

        // 6. Save Action
        btnSave.setOnClickListener {
            val title = etTitle.text.toString().trim()
            val posPrompt = etPositive.text.toString().trim()
            val negPrompt = if (isNegativeLocked) {
                MiniClipPreferences.getLockedNegativePrompt(context)
            } else {
                etNegative.text.toString().trim()
            }

            btnSave.isEnabled = false
            btnSave.text = "در حال ذخیره دوقلو..."

            lifecycleScope.launch {
                val result = ImagePromptSaver.savePair(
                    context = context,
                    bitmap = currentBitmap,
                    imageUrl = currentImageUrl,
                    title = title,
                    positivePrompt = posPrompt,
                    negativePrompt = negPrompt
                )

                if (result.isSuccess) {
                    val (savedImg, savedTxt) = result.getOrThrow()
                    showNotify("ذخیره شد: ${savedImg.name} و ${savedTxt.name}")
                    d.dismiss()
                    onSaveSuccess()
                } else {
                    btnSave.isEnabled = true
                    btnSave.text = "💾 ذخیره دوقلو (عکس + پرامپت)"
                    val err = result.exceptionOrNull()?.message ?: "خطا در ذخیره‌سازی"
                    showNotify(err)
                }
            }
        }

        btnClose.setOnClickListener {
            d.dismiss()
        }

        d.show()
        d.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}

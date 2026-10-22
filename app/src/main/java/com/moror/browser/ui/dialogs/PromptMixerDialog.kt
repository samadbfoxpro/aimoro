package com.moror.browser.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.moror.browser.R
import com.moror.browser.data.MiniClipPreferences
import com.moror.browser.ui.BaseBrowserActivity

class PromptMixerDialog(
    private val context: Context,
    private val onApplyToMiniClip: ((extraTitle: String, injectedText: String, mode: MergeMode, commaIndex: Int) -> Unit)? = null
) {

    enum class MergeMode {
        START,
        AFTER_COMMA,
        END
    }

    private var currentMode = MergeMode.AFTER_COMMA
    private var currentCommaIndex = 4

    companion object {
        fun parseMode(str: String): MergeMode {
            return when (str.uppercase()) {
                "START" -> MergeMode.START
                "END" -> MergeMode.END
                else -> MergeMode.AFTER_COMMA
            }
        }

        fun mergeText(
            base: String,
            inject: String,
            mode: MergeMode,
            commaIdx: Int
        ): String {
            // فقط ابتدای کل متن و انتهای کل متن را تریم کن؛ خطوط شکسته (\n) داخل متن باید ۱۰۰٪ حفظ شوند
            val cleanBase = base.trim()
            val cleanInject = inject.trim().trim(',', '،').trim()

            if (cleanInject.isEmpty()) return cleanBase
            if (cleanBase.isEmpty()) return cleanInject

            return when (mode) {
                MergeMode.START -> {
                    "$cleanInject, $cleanBase"
                }
                MergeMode.END -> {
                    if (cleanBase.endsWith(",") || cleanBase.endsWith("،")) {
                        "$cleanBase $cleanInject"
                    } else {
                        "$cleanBase, $cleanInject"
                    }
                }
                MergeMode.AFTER_COMMA -> {
                    val commaPositions = mutableListOf<Int>()
                    for (i in cleanBase.indices) {
                        if (cleanBase[i] == ',' || cleanBase[i] == '،') {
                            commaPositions.add(i)
                        }
                    }
                    if (commaPositions.isEmpty()) {
                        "$cleanBase, $cleanInject"
                    } else {
                        val targetPosIndex = (commaIdx - 1).coerceIn(0, commaPositions.size - 1)
                        val splitPos = commaPositions[targetPosIndex]

                        // بسیار مهم: فقط Space و Tab در نقطه برش حذف شوند، نه کاراکترهای خط جدید (\n و \r)!
                        val partBefore = cleanBase.substring(0, splitPos + 1).trimEnd(' ', '\t')
                        val partAfter = cleanBase.substring(splitPos + 1).trimStart(' ', '\t')

                        if (partAfter.isEmpty()) {
                            "$partBefore $cleanInject"
                        } else {
                            // اگر بلافاصله بعد از این کاما، خط جدید (\n) شروع شده بود، ابتدا متن میکسر و کاما را بگذار و سپس همان خط جدید (\n) را حفظ کن
                            if (partAfter.startsWith("\n") || partAfter.startsWith("\r")) {
                                "$partBefore $cleanInject,$partAfter"
                            } else {
                                "$partBefore $cleanInject, $partAfter"
                            }
                        }
                    }
                }
            }
        }
    }

    fun show() {
        val d = Dialog(context)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(R.layout.dialog_prompt_mixer)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnOpenManager = d.findViewById<TextView>(R.id.btnOpenCombinerManager)
        val btnClose = d.findViewById<ImageButton>(R.id.btnMixerClose)
        val btnCancel = d.findViewById<Button>(R.id.btnMixerCancel)
        val etExtraTitle = d.findViewById<EditText>(R.id.etMixerExtraTitle)
        val etInjected = d.findViewById<EditText>(R.id.etMixerInjectedText)

        val btnClearSelected = d.findViewById<TextView>(R.id.tvClearSelectedCombinerItems)
        val layoutFoldersContainer = d.findViewById<LinearLayout>(R.id.layoutMixerFoldersContainer)
        val layoutItemsList = d.findViewById<LinearLayout>(R.id.layoutMixerItemsList)
        val tvFolderHint = d.findViewById<TextView>(R.id.tvMixerFolderHint)

        val btnModeStart = d.findViewById<TextView>(R.id.btnModeStart)
        val btnModeAfterComma = d.findViewById<TextView>(R.id.btnModeAfterComma)
        val btnModeEnd = d.findViewById<TextView>(R.id.btnModeEnd)
        val layoutStepper = d.findViewById<LinearLayout>(R.id.layoutCommaStepper)
        val btnMinus = d.findViewById<TextView>(R.id.btnStepperMinus)
        val btnPlus = d.findViewById<TextView>(R.id.btnStepperPlus)
        val tvCommaDisplay = d.findViewById<TextView>(R.id.tvCommaIndexDisplay)

        val btnSaveAndApply = d.findViewById<Button>(R.id.btnMixerSaveAndApply)

        // 1. Load preferences
        etExtraTitle.setText(MiniClipPreferences.getMixerExtraTitle(context))
        etInjected.setText(MiniClipPreferences.getMixerInjectedText(context))
        currentMode = parseMode(MiniClipPreferences.getMixerPositionMode(context))
        currentCommaIndex = MiniClipPreferences.getMixerCommaIndex(context)
        tvCommaDisplay.text = currentCommaIndex.toString()

        // 2. Open Manager Screen
        btnOpenManager.setOnClickListener {
            val intent = android.content.Intent(context, com.moror.browser.ui.CombinerManagerActivity::class.java)
            context.startActivity(intent)
        }

        // 3. Combiner Folders & Items Selection Logic (Up to 3 items)
        val combinerData = com.moror.browser.data.PromptCombinerStore.reload(context)
        var currentFolderId = combinerData.activeFolderId.ifEmpty { combinerData.folders.firstOrNull()?.id ?: "" }
        val selectedItems = mutableListOf<com.moror.browser.model.PromptCombinerItem>()

        fun updateInjectedAndTitleFromSelection() {
            if (selectedItems.isEmpty()) return

            // 1. Extra Title derived from item titles combined by underscore
            val combinedTitle = selectedItems.joinToString("_") { it.title.trim().replace(" ", "_") }
            etExtraTitle.setText(combinedTitle)

            // 2. Injected text combined with comma and deduplicated
            val combinedText = selectedItems.joinToString(", ") { it.text.trim() }
            etInjected.setText(combinedText)
        }

        fun renderMixerItems() {
            layoutItemsList.removeAllViews()
            val folder = combinerData.folders.firstOrNull { it.id == currentFolderId }
            if (folder == null) {
                tvFolderHint.text = "دسته‌ای یافت نشد."
                return
            }

            val itemsInFolder = combinerData.items.filter { it.folderId == folder.id }
            if (itemsInFolder.isEmpty()) {
                tvFolderHint.text = "هیچ گزینه‌ای در دسته '${folder.name}' وجود ندارد."
                return
            }

            tvFolderHint.text = "دسته: ${folder.name} (انتخاب شده: ${selectedItems.size} از ۳)"

            for (item in itemsInFolder) {
                val itemRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 4, 0, 4) }
                    setPadding(10, 8, 10, 8)
                }

                val isSelected = selectedItems.any { it.id == item.id }

                if (isSelected) {
                    itemRow.setBackgroundResource(R.drawable.bg_btn_primary)
                } else {
                    itemRow.setBackgroundResource(R.drawable.bg_badge_profile)
                }

                val tvItemTitle = TextView(context).apply {
                    text = item.title
                    textSize = 11.5f
                    setTextColor(if (isSelected) Color.WHITE else context.getColor(R.color.accent_blue))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvItemCheck = TextView(context).apply {
                    text = if (isSelected) "✔ انتخاب شد" else "＋ انتخاب"
                    textSize = 10.5f
                    setTextColor(if (isSelected) Color.WHITE else context.getColor(R.color.text_secondary))
                }

                itemRow.addView(tvItemTitle)
                itemRow.addView(tvItemCheck)

                itemRow.setOnClickListener {
                    val alreadyIndex = selectedItems.indexOfFirst { it.id == item.id }
                    if (alreadyIndex >= 0) {
                        selectedItems.removeAt(alreadyIndex)
                    } else {
                        if (selectedItems.size >= 3) {
                            Toast.makeText(context, "حداکثر ۳ گزینه می‌توانید انتخاب کنید", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        selectedItems.add(item)
                    }
                    updateInjectedAndTitleFromSelection()
                    renderMixerItems()
                }

                layoutItemsList.addView(itemRow)
            }
        }

        fun showNotify(msg: String) {
            if (context is BaseBrowserActivity) {
                context.showTopPillNotification(msg)
            } else {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }

        fun renderMixerFolders() {
            val container = d.findViewById<LinearLayout>(R.id.layoutMixerFoldersContainer)
            container?.removeAllViews()
            for (f in combinerData.folders) {
                val fChip = TextView(context).apply {
                    text = f.name
                    textSize = 11f
                    setPadding(20, 8, 20, 8)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 12, 0) }
                }

                if (f.id == currentFolderId) {
                    fChip.setBackgroundResource(R.drawable.bg_btn_primary)
                    fChip.setTextColor(Color.WHITE)
                    fChip.setTypeface(fChip.typeface, android.graphics.Typeface.BOLD)
                } else {
                    fChip.setBackgroundResource(R.drawable.bg_badge_profile)
                    fChip.setTextColor(context.getColor(R.color.text_secondary))
                }

                fChip.setOnClickListener {
                    currentFolderId = f.id
                    renderMixerFolders()
                    renderMixerItems()
                }

                container?.addView(fChip)
            }
        }

        btnClearSelected.setOnClickListener {
            selectedItems.clear()
            renderMixerItems()
            showNotify("انتخاب‌ها لغو شدند")
        }


        renderMixerFolders()
        renderMixerItems()

        // 4. Mode Selection UI
        fun updateModeUI() {
            when (currentMode) {
                MergeMode.START -> {
                    btnModeStart.setBackgroundResource(R.drawable.bg_btn_primary)
                    btnModeStart.setTextColor(Color.WHITE)
                    btnModeAfterComma.setBackgroundResource(R.drawable.bg_btn_secondary)
                    btnModeAfterComma.setTextColor(context.getColor(R.color.text_secondary))
                    btnModeEnd.setBackgroundResource(R.drawable.bg_btn_secondary)
                    btnModeEnd.setTextColor(context.getColor(R.color.text_secondary))
                    layoutStepper.visibility = View.GONE
                }
                MergeMode.AFTER_COMMA -> {
                    btnModeStart.setBackgroundResource(R.drawable.bg_btn_secondary)
                    btnModeStart.setTextColor(context.getColor(R.color.text_secondary))
                    btnModeAfterComma.setBackgroundResource(R.drawable.bg_btn_primary)
                    btnModeAfterComma.setTextColor(Color.WHITE)
                    btnModeEnd.setBackgroundResource(R.drawable.bg_btn_secondary)
                    btnModeEnd.setTextColor(context.getColor(R.color.text_secondary))
                    layoutStepper.visibility = View.VISIBLE
                }
                MergeMode.END -> {
                    btnModeStart.setBackgroundResource(R.drawable.bg_btn_secondary)
                    btnModeStart.setTextColor(context.getColor(R.color.text_secondary))
                    btnModeAfterComma.setBackgroundResource(R.drawable.bg_btn_secondary)
                    btnModeAfterComma.setTextColor(context.getColor(R.color.text_secondary))
                    btnModeEnd.setBackgroundResource(R.drawable.bg_btn_primary)
                    btnModeEnd.setTextColor(Color.WHITE)
                    layoutStepper.visibility = View.GONE
                }
            }
        }

        btnModeStart.setOnClickListener {
            currentMode = MergeMode.START
            updateModeUI()
        }

        btnModeAfterComma.setOnClickListener {
            currentMode = MergeMode.AFTER_COMMA
            updateModeUI()
        }

        btnModeEnd.setOnClickListener {
            currentMode = MergeMode.END
            updateModeUI()
        }

        // 5. Comma Stepper
        btnMinus.setOnClickListener {
            if (currentCommaIndex > 1) {
                currentCommaIndex--
                tvCommaDisplay.text = currentCommaIndex.toString()
            }
        }

        btnPlus.setOnClickListener {
            currentCommaIndex++
            tvCommaDisplay.text = currentCommaIndex.toString()
        }


        // 6. Save and Apply Action
        btnSaveAndApply.setOnClickListener {
            val extraTitle = etExtraTitle.text?.toString()?.trim() ?: ""
            val injectedText = etInjected.text?.toString()?.trim() ?: ""

            MiniClipPreferences.setMixerExtraTitle(context, extraTitle)
            MiniClipPreferences.setMixerInjectedText(context, injectedText)
            MiniClipPreferences.setMixerPositionMode(context, currentMode.name)
            MiniClipPreferences.setMixerCommaIndex(context, currentCommaIndex)

            onApplyToMiniClip?.invoke(extraTitle, injectedText, currentMode, currentCommaIndex)
            showNotify("تنظیمات میکسر ذخیره و اعمال شد")
            d.dismiss()
        }

        btnCancel.setOnClickListener {
            d.dismiss()
        }

        btnClose.setOnClickListener {
            d.dismiss()
        }


        updateModeUI()
        d.show()

        d.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.94).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}

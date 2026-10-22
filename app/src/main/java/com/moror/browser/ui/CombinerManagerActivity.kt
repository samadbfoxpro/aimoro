package com.moror.browser.ui

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moror.browser.R
import com.moror.browser.data.PromptCombinerStore
import com.moror.browser.model.PromptCombinerData
import com.moror.browser.model.PromptCombinerFolder
import com.moror.browser.model.PromptCombinerItem
import java.util.UUID

class CombinerManagerActivity : AppCompatActivity() {

    private lateinit var combinerData: PromptCombinerData
    private var selectedFolderId: String = ""

    private lateinit var layoutFoldersContainer: LinearLayout
    private lateinit var tvCurrentFolderTitle: TextView
    private lateinit var rvCombinerItems: RecyclerView
    private lateinit var tvEmptyItemsPlaceholder: TextView
    private lateinit var itemsAdapter: CombinerItemsAdapter

    private val jsonFilePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            importJsonFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_combiner_manager)

        combinerData = PromptCombinerStore.load(this)
        if (combinerData.folders.isNotEmpty()) {
            selectedFolderId = combinerData.activeFolderId.ifEmpty { combinerData.folders[0].id }
        }

        initViews()
        renderFolders()
        renderItems()
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        findViewById<TextView>(R.id.btnImportJson).setOnClickListener {
            try {
                jsonFilePickerLauncher.launch("application/json")
            } catch (e: Exception) {
                // Fallback to all files if mime application/json isn't handled by some picker
                try {
                    jsonFilePickerLauncher.launch("*/*")
                } catch (e2: Exception) {
                    Toast.makeText(this, "امکان باز کردن فایل منیجر وجود ندارد", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<TextView>(R.id.btnResetDefaultBundle).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("بازنشانی به پیش‌فرض")
                .setMessage("آیا می‌خواهید تمام دسته‌ها و گزینه‌ها به لیست پیش‌فرض اولیه (شامل تمام دسته‌بندی‌های کامل) بازگردانده شوند؟")
                .setPositiveButton("بله، بازنشانی کن") { _, _ ->
                    resetToDefaultAssets()
                }
                .setNegativeButton("انصراف", null)
                .show()
        }

        layoutFoldersContainer = findViewById(R.id.layoutFoldersContainer)
        tvCurrentFolderTitle = findViewById(R.id.tvCurrentFolderTitle)
        rvCombinerItems = findViewById(R.id.rvCombinerItems)
        tvEmptyItemsPlaceholder = findViewById(R.id.tvEmptyItemsPlaceholder)

        rvCombinerItems.layoutManager = LinearLayoutManager(this)
        itemsAdapter = CombinerItemsAdapter(
            onEdit = { item -> showEditItemDialog(item) },
            onDelete = { item -> deleteItem(item) }
        )
        rvCombinerItems.adapter = itemsAdapter

        findViewById<TextView>(R.id.btnAddFolder).setOnClickListener {
            showAddFolderDialog()
        }

        findViewById<TextView>(R.id.btnDeleteFolder).setOnClickListener {
            deleteCurrentFolder()
        }

        findViewById<Button>(R.id.btnAddItem).setOnClickListener {
            showEditItemDialog(null)
        }
    }

    private fun importJsonFromUri(uri: android.net.Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val jsonStr = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (jsonStr.isNotBlank()) {
                    val success = PromptCombinerStore.importFromJsonString(this, jsonStr)
                    if (success) {
                        combinerData = PromptCombinerStore.load(this)
                        selectedFolderId = combinerData.folders.firstOrNull()?.id ?: ""
                        renderFolders()
                        renderItems()
                        Toast.makeText(this, "فایل JSON با موفقیت بارگذاری و اعمال شد (${combinerData.folders.size} دسته، ${combinerData.items.size} گزینه)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "فرمت فایل JSON نامعتبر است", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "خطا در خواندن فایل: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetToDefaultAssets() {
        try {
            assets.open("default_prompt_combiner_data.json").use { stream ->
                val jsonStr = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (jsonStr.isNotBlank()) {
                    PromptCombinerStore.importFromJsonString(this, jsonStr)
                    combinerData = PromptCombinerStore.load(this)
                    selectedFolderId = combinerData.folders.firstOrNull()?.id ?: ""
                    renderFolders()
                    renderItems()
                    Toast.makeText(this, "داده‌ها به حالت پیش‌فرض بازگردانی شدند (${combinerData.folders.size} دسته)", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "خطا در بازنشانی: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }


    private fun getCurrentFolder(): PromptCombinerFolder? {
        return combinerData.folders.firstOrNull { it.id == selectedFolderId }
    }

    private fun renderFolders() {
        layoutFoldersContainer.removeAllViews()

        for (folder in combinerData.folders) {
            val chipView = LayoutInflater.from(this).inflate(R.layout.item_combiner_folder_chip, layoutFoldersContainer, false)
            val tvName = chipView.findViewById<TextView>(R.id.tvFolderName)
            val tvCount = chipView.findViewById<TextView>(R.id.tvFolderItemCount)

            tvName.text = folder.name
            val count = combinerData.items.count { it.folderId == folder.id }
            tvCount.text = "($count)"

            val isSelected = (folder.id == selectedFolderId)
            if (isSelected) {
                chipView.setBackgroundResource(R.drawable.bg_btn_primary)
                tvName.setTextColor(Color.WHITE)
                tvCount.setTextColor(Color.WHITE)
            } else {
                chipView.setBackgroundResource(R.drawable.bg_badge_profile)
                tvName.setTextColor(getColor(R.color.text_primary))
                tvCount.setTextColor(getColor(R.color.accent_blue))
            }

            chipView.setOnClickListener {
                selectedFolderId = folder.id
                combinerData.activeFolderId = folder.id
                saveData()
                renderFolders()
                renderItems()
            }

            layoutFoldersContainer.addView(chipView)
        }
    }

    private fun renderItems() {
        val folder = getCurrentFolder()
        if (folder == null) {
            tvCurrentFolderTitle.text = "هیچ دسته‌ای انتخاب نشده است"
            itemsAdapter.submitList(emptyList())
            tvEmptyItemsPlaceholder.visibility = View.VISIBLE
            return
        }

        tvCurrentFolderTitle.text = "گزینه‌های دسته: ${folder.name}"
        val folderItems = combinerData.items.filter { it.folderId == folder.id }
        itemsAdapter.submitList(folderItems)

        tvEmptyItemsPlaceholder.visibility = if (folderItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddFolderDialog() {
        val et = EditText(this)
        et.hint = "نام دسته (مثلاً: استایل کاراکتر یا بک‌گراند)"
        et.setTextColor(getColor(R.color.text_primary))
        et.setHintTextColor(getColor(R.color.text_hint))

        AlertDialog.Builder(this)
            .setTitle("افزودن دسته جدید")
            .setView(et)
            .setPositiveButton("افزودن") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) {
                    val newFolder = PromptCombinerFolder(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        order = combinerData.folders.size
                    )
                    combinerData.folders.add(newFolder)
                    selectedFolderId = newFolder.id
                    combinerData.activeFolderId = newFolder.id
                    saveData()
                    renderFolders()
                    renderItems()
                    Toast.makeText(this, "دسته اضافه شد", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun deleteCurrentFolder() {
        val folder = getCurrentFolder() ?: return
        if (combinerData.folders.size <= 1) {
            Toast.makeText(this, "حداقل یک دسته باید وجود داشته باشد", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("حذف دسته")
            .setMessage("آیا از حذف دسته '${folder.name}' و همه گزینه‌های آن مطمئن هستید؟")
            .setPositiveButton("بله، حذف کن") { _, _ ->
                combinerData.folders.removeAll { it.id == folder.id }
                combinerData.items.removeAll { it.folderId == folder.id }
                selectedFolderId = combinerData.folders[0].id
                combinerData.activeFolderId = selectedFolderId
                saveData()
                renderFolders()
                renderItems()
                Toast.makeText(this, "دسته حذف شد", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun showEditItemDialog(item: PromptCombinerItem?) {
        val folder = getCurrentFolder() ?: return

        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(R.layout.dialog_edit_combiner_item)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = d.findViewById<TextView>(R.id.tvDialogTitle)
        val etTitle = d.findViewById<EditText>(R.id.etItemTitle)
        val etText = d.findViewById<EditText>(R.id.etItemText)
        val btnCancel = d.findViewById<Button>(R.id.btnCancelEdit)
        val btnSave = d.findViewById<Button>(R.id.btnSaveEdit)

        if (item != null) {
            tvTitle.text = "✏️ ویرایش گزینه"
            etTitle.setText(item.title)
            etText.setText(item.text)
        } else {
            tvTitle.text = "＋ افزودن گزینه جدید به ${folder.name}"
        }

        btnCancel.setOnClickListener { d.dismiss() }

        btnSave.setOnClickListener {
            val title = etTitle.text.toString().trim()
            val text = etText.text.toString().trim()

            if (title.isEmpty() || text.isEmpty()) {
                Toast.makeText(this, "عنوان و متن پرامپت نمی‌توانند خالی باشند", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (item != null) {
                item.title = title
                item.text = text
            } else {
                val newItem = PromptCombinerItem(
                    id = UUID.randomUUID().toString(),
                    folderId = folder.id,
                    title = title,
                    text = text,
                    order = combinerData.items.count { it.folderId == folder.id }
                )
                combinerData.items.add(newItem)
            }

            saveData()
            renderFolders()
            renderItems()
            d.dismiss()
        }

        d.show()
        d.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun deleteItem(item: PromptCombinerItem) {
        AlertDialog.Builder(this)
            .setTitle("حذف گزینه")
            .setMessage("آیا از حذف '${item.title}' مطمئن هستید؟")
            .setPositiveButton("حذف") { _, _ ->
                combinerData.items.removeAll { it.id == item.id }
                combinerData.activeItemIds.remove(item.id)
                saveData()
                renderFolders()
                renderItems()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun saveData() {
        PromptCombinerStore.save(this, combinerData)
    }

    // RecyclerView Adapter
    class CombinerItemsAdapter(
        private val onEdit: (PromptCombinerItem) -> Unit,
        private val onDelete: (PromptCombinerItem) -> Unit
    ) : RecyclerView.Adapter<CombinerItemsAdapter.ItemViewHolder>() {

        private var items = listOf<PromptCombinerItem>()

        fun submitList(newItems: List<PromptCombinerItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_combiner_manager_entry, parent, false)
            return ItemViewHolder(v)
        }

        override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.tvText.text = item.text

            holder.btnEdit.setOnClickListener { onEdit(item) }
            holder.btnDelete.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount(): Int = items.size

        class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvTitle: TextView = itemView.findViewById(R.id.tvEntryTitle)
            val tvText: TextView = itemView.findViewById(R.id.tvEntryText)
            val btnEdit: ImageButton = itemView.findViewById(R.id.btnEditEntry)
            val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteEntry)
        }
    }
}

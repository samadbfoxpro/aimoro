package com.moror.browser.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moror.browser.R
import com.moror.browser.data.ProfileManager
import com.moror.browser.model.UserProfile
import com.moror.browser.ui.BaseBrowserActivity

class ProfileManagerDialog(
    private val context: Context,
    private val activeProfileId: String,
    private val loadedSlotsInRam: Set<Int> = emptySet(),
    private val onProfileSelected: (UserProfile) -> Unit,
    private val onProfileCleared: (UserProfile) -> Unit,
    private val onCloseFromRam: (UserProfile) -> Unit = {}
) {

    private var dialog: Dialog? = null

    private fun showNotify(msg: String) {
        if (context is BaseBrowserActivity) {
            context.showTopPillNotification(msg)
        } else {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun show() {
        val d = Dialog(context)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(R.layout.dialog_profile_manager)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val rvProfiles = d.findViewById<RecyclerView>(R.id.rvProfiles)
        val btnAdd = d.findViewById<View>(R.id.btnAddProfile)
        val btnClose = d.findViewById<View>(R.id.btnCloseDialog)

        rvProfiles.layoutManager = LinearLayoutManager(context)

        fun reloadList() {
            val list = ProfileManager.getProfiles(context)
            rvProfiles.adapter = ProfileAdapter(
                items = list,
                activeProfileId = activeProfileId,
                loadedSlotsInRam = loadedSlotsInRam,
                onSelect = { profile ->
                    d.dismiss()
                    onProfileSelected(profile)
                },
                onClean = { profile ->
                    showConfirmClear(profile) {
                        (context as? BaseBrowserActivity)?.destroyWebViewForSlot(profile.slotIndex)
                        onProfileCleared(profile)
                        ProfileManager.clearProfileDirectory(context, profile.slotIndex)
                        showNotify(context.getString(R.string.data_cleared_success))
                        reloadList()
                    }
                },
                onRename = { profile ->
                    showRenameDialog(profile) {
                        reloadList()
                        (context as? BaseBrowserActivity)?.runOnUiThread {
                            (context as? BaseBrowserActivity)?.updateProfileBadge()
                            (context as? BaseBrowserActivity)?.updateProfileTabsBar()
                        }
                    }
                },
                onDelete = { profile ->
                    showConfirmDelete(profile) {
                        (context as? BaseBrowserActivity)?.destroyWebViewForSlot(profile.slotIndex)
                        val deleted = ProfileManager.deleteProfile(context, profile.id)
                        if (deleted) {
                            reloadList()
                            (context as? BaseBrowserActivity)?.runOnUiThread {
                                (context as? BaseBrowserActivity)?.updateProfileBadge()
                                (context as? BaseBrowserActivity)?.updateProfileTabsBar()
                            }
                        }
                    }
                },
                onCloseFromRam = { profile ->
                    d.dismiss()
                    onCloseFromRam(profile)
                }
            )

            val maxListHeight = (context.resources.displayMetrics.heightPixels * 0.55).toInt()
            rvProfiles.post {
                if (rvProfiles.measuredHeight > maxListHeight || rvProfiles.height > maxListHeight) {
                    val p = rvProfiles.layoutParams
                    p.height = maxListHeight
                    rvProfiles.layoutParams = p
                } else if (list.size <= 4) {
                    val p = rvProfiles.layoutParams
                    p.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    rvProfiles.layoutParams = p
                }
            }
        }

        reloadList()

        btnAdd.setOnClickListener {
            showCreateProfileDialog {
                reloadList()
                (context as? BaseBrowserActivity)?.runOnUiThread {
                    (context as? BaseBrowserActivity)?.updateProfileBadge()
                    (context as? BaseBrowserActivity)?.updateProfileTabsBar()
                }
            }
        }

        btnClose.setOnClickListener {
            d.dismiss()
        }

        d.setOnDismissListener {
            (context as? BaseBrowserActivity)?.runOnUiThread {
                (context as? BaseBrowserActivity)?.updateProfileBadge()
                (context as? BaseBrowserActivity)?.updateProfileTabsBar()
            }
        }

        dialog = d
        d.show()
    }

    private fun showCreateProfileDialog(onSuccess: () -> Unit) {
        val d = Dialog(context)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(R.layout.dialog_edit_profile)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = d.findViewById<TextView>(R.id.tvEditTitle)
        val etName = d.findViewById<EditText>(R.id.etProfileName)
        val btnCancel = d.findViewById<View>(R.id.btnCancelEdit)
        val btnSave = d.findViewById<View>(R.id.btnSaveEdit)

        tvTitle.setText(R.string.dialog_create_profile)
        etName.setHint(R.string.dialog_profile_name_hint)

        btnCancel.setOnClickListener { d.dismiss() }
        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            val newProfile = ProfileManager.createProfile(context, name)
            if (newProfile != null) {
                d.dismiss()
                onSuccess()
            } else {
                showNotify(context.getString(R.string.max_profiles_reached))
            }
        }
        d.show()
        d.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.88).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showRenameDialog(profile: UserProfile, onSuccess: () -> Unit) {
        val d = Dialog(context)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(R.layout.dialog_edit_profile)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = d.findViewById<TextView>(R.id.tvEditTitle)
        val etName = d.findViewById<EditText>(R.id.etProfileName)
        val btnCancel = d.findViewById<View>(R.id.btnCancelEdit)
        val btnSave = d.findViewById<View>(R.id.btnSaveEdit)

        tvTitle.setText(R.string.btn_rename)
        etName.setText(profile.name)
        etName.setSelection(profile.name.length)

        btnCancel.setOnClickListener { d.dismiss() }
        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isNotEmpty()) {
                ProfileManager.renameProfile(context, profile.id, name)
                d.dismiss()
                onSuccess()
            }
        }
        d.show()
        d.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.88).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showConfirmClear(profile: UserProfile, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.clear_confirm_title)
            .setMessage(R.string.clear_confirm_msg)
            .setPositiveButton(R.string.btn_confirm) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showConfirmDelete(profile: UserProfile, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(R.string.delete_confirm_msg)
            .setPositiveButton(R.string.btn_confirm) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private class ProfileAdapter(
        private val items: List<UserProfile>,
        private val activeProfileId: String,
        private val loadedSlotsInRam: Set<Int>,
        private val onSelect: (UserProfile) -> Unit,
        private val onClean: (UserProfile) -> Unit,
        private val onRename: (UserProfile) -> Unit,
        private val onDelete: (UserProfile) -> Unit,
        private val onCloseFromRam: (UserProfile) -> Unit
    ) : RecyclerView.Adapter<ProfileAdapter.ViewHolder>() {

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvProfileName)
            val tvSlot: TextView = v.findViewById(R.id.tvProfileSlot)
            val indicator: View = v.findViewById(R.id.viewActiveIndicator)
            val btnSwitch: View = v.findViewById(R.id.btnSwitchProfile)
            val btnClean: ImageButton = v.findViewById(R.id.btnCleanProfile)
            val btnDelete: ImageButton = v.findViewById(R.id.btnDeleteProfile)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name

            val isLoadedInRam = loadedSlotsInRam.contains(item.slotIndex)
            if (isLoadedInRam) {
                holder.tvSlot.text = "محیط ایزوله ${item.slotIndex} • 🟢 باز در رم"
                holder.tvSlot.setTextColor(holder.itemView.context.getColor(R.color.accent_green))
                holder.tvSlot.setOnClickListener {
                    AlertDialog.Builder(holder.itemView.context)
                        .setTitle("بستن از حافظه رم")
                        .setMessage("آیا می‌خواهید تب این پروفایل بدون خروج از حساب کاربری از رم بسته شود؟")
                        .setPositiveButton("بستن از رم") { _, _ ->
                            onCloseFromRam(item)
                        }
                        .setNegativeButton("انصراف", null)
                        .show()
                }
            } else {
                holder.tvSlot.text = "محیط ایزوله ${item.slotIndex}"
                holder.tvSlot.setTextColor(holder.itemView.context.getColor(R.color.text_secondary))
                holder.tvSlot.setOnClickListener(null)
            }

            val isActive = item.id == activeProfileId
            holder.indicator.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
            holder.btnSwitch.visibility = if (isActive) View.GONE else View.VISIBLE

            holder.btnDelete.visibility = if (items.size > 1) View.VISIBLE else View.GONE

            holder.tvName.setOnClickListener { onRename(item) }
            holder.btnSwitch.setOnClickListener { onSelect(item) }
            holder.btnClean.setOnClickListener { onClean(item) }
            holder.btnDelete.setOnClickListener { onDelete(item) }
        }
    }
}

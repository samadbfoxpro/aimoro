package com.moror.browser.ui.adapter

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moror.browser.R
import com.moror.browser.data.ShortcutItem
import com.moror.browser.data.ShortcutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

class HomeShortcutAdapter(
    private var items: MutableList<ShortcutItem>,
    private val scope: CoroutineScope,
    private val onItemClick: (ShortcutItem) -> Unit,
    private val onItemEdit: (ShortcutItem) -> Unit,
    private val onItemDelete: (ShortcutItem) -> Unit,
    private val onItemsReordered: (List<ShortcutItem>) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<HomeShortcutAdapter.ShortcutViewHolder>() {

    fun updateList(newItems: List<ShortcutItem>) {
        items = newItems.toMutableList()
        notifyDataSetChanged()
    }

    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(items, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(items, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        onItemsReordered(items)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortcutViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_shortcut, parent, false)
        return ShortcutViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ShortcutViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    inner class ShortcutViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvShortcutTitle)
        private val tvHost: TextView = itemView.findViewById(R.id.tvShortcutHost)
        private val tvInitial: TextView = itemView.findViewById(R.id.tvShortcutInitial)
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivShortcutIcon)
        private val iconContainer: FrameLayout = itemView.findViewById(R.id.iconContainer)
        private val btnOptions: ImageView = itemView.findViewById(R.id.btnShortcutOptions)
        private val ivDragHandle: ImageView = itemView.findViewById(R.id.ivDragHandle)

        fun bind(item: ShortcutItem) {
            tvTitle.text = item.title
            tvHost.text = item.getCleanHost()
            tvInitial.text = item.getDisplayInitial()

            // Dynamic premium gradient on icon container
            try {
                val baseColor = try { Color.parseColor(item.colorHex) } catch (e: Exception) { Color.parseColor("#6366F1") }
                val startAlpha = Color.argb(220, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                val endAlpha = Color.argb(90, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                val strokeColor = Color.argb(160, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                val gd = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(startAlpha, endAlpha)
                )
                gd.cornerRadius = 36f
                gd.setStroke(2, strokeColor)
                iconContainer.background = gd
            } catch (e: Exception) {
                // Keep default
            }

            // Check cached icon on disk
            val context = itemView.context
            val cachedFile = ShortcutManager.getIconFile(context, item.iconFileName)
            if (cachedFile != null) {
                try {
                    val bmp = BitmapFactory.decodeFile(cachedFile.absolutePath)
                    if (bmp != null) {
                        ivIcon.setImageBitmap(bmp)
                        ivIcon.visibility = View.VISIBLE
                        tvInitial.visibility = View.GONE
                    } else {
                        ivIcon.visibility = View.GONE
                        tvInitial.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    ivIcon.visibility = View.GONE
                    tvInitial.visibility = View.VISIBLE
                }
            } else {
                ivIcon.visibility = View.GONE
                tvInitial.visibility = View.VISIBLE

                // Fetch favicon in background and cache it permanently
                scope.launch {
                    val iconName = ShortcutManager.fetchAndCacheFavicon(context, item)
                    if (iconName != null && adapterPosition != RecyclerView.NO_POSITION) {
                        val file = File(ShortcutManager.getIconDir(context), iconName)
                        if (file.exists()) {
                            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
                            if (bmp != null) {
                                ivIcon.setImageBitmap(bmp)
                                ivIcon.visibility = View.VISIBLE
                                tvInitial.visibility = View.GONE
                            }
                        }
                    }
                }
            }

            // Click -> Navigate to website
            itemView.setOnClickListener {
                onItemClick(item)
            }

            // Long Click or Drag Handle -> Reorder
            itemView.setOnLongClickListener {
                onStartDrag(this)
                true
            }

            ivDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(this)
                }
                false
            }

            // Three-dots menu -> Edit or Delete or Move
            btnOptions.setOnClickListener { v ->
                showPopupMenu(v, item)
            }
        }

        private fun showPopupMenu(anchor: View, item: ShortcutItem) {
            val popup = PopupMenu(anchor.context, anchor)
            popup.menu.add(0, 1, 0, "✏️ ویرایش میانبر")
            popup.menu.add(0, 2, 1, "🗑️ حذف میانبر")
            if (adapterPosition > 0) {
                popup.menu.add(0, 3, 2, "⬅️ انتقال به موقعیت قبل")
            }
            if (adapterPosition < items.size - 1) {
                popup.menu.add(0, 4, 3, "➡️ انتقال به موقعیت بعد")
            }

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> onItemEdit(item)
                    2 -> onItemDelete(item)
                    3 -> {
                        val pos = adapterPosition
                        if (pos > 0) {
                            onItemMove(pos, pos - 1)
                        }
                    }
                    4 -> {
                        val pos = adapterPosition
                        if (pos < items.size - 1) {
                            onItemMove(pos, pos + 1)
                        }
                    }
                }
                true
            }
            popup.show()
        }
    }
}

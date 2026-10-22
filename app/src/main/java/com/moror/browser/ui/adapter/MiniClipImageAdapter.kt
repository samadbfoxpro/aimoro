package com.moror.browser.ui.adapter

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moror.browser.R
import com.moror.browser.data.ImagePromptSaver
import com.moror.browser.model.CapturedImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MiniClipImageAdapter(
    private val images: MutableList<CapturedImage>,
    private val coroutineScope: CoroutineScope,
    private val onRemoveClick: (Int, CapturedImage) -> Unit
) : RecyclerView.Adapter<MiniClipImageAdapter.ImageViewHolder>() {

    class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivThumb: ImageView = itemView.findViewById(R.id.ivCapturedThumb)
        val tvDimension: TextView = itemView.findViewById(R.id.tvCapturedDimension)
        val btnRemove: ImageButton = itemView.findViewById(R.id.btnRemoveImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_miniclip_image, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val item = images[position]

        // Reset image placeholder
        holder.ivThumb.setImageResource(R.drawable.ic_image)

        // Set dimensions label
        if (item.width > 0 && item.height > 0) {
            holder.tvDimension.text = "${item.width}×${item.height}"
        } else {
            holder.tvDimension.text = "دریافت..."
        }

        // Load bitmap
        if (item.bitmap != null) {
            holder.ivThumb.setImageBitmap(item.bitmap)
            val w = if (item.width > 0) item.width else item.bitmap!!.width
            val h = if (item.height > 0) item.height else item.bitmap!!.height
            holder.tvDimension.text = "${w}×${h}"
        } else if (!item.imageUrl.isNullOrBlank()) {
            val url = item.imageUrl!!
            val ctx = holder.itemView.context.applicationContext
            coroutineScope.launch {
                val res = ImagePromptSaver.fetchBitmapWithDimensions(ctx, url)
                if (res != null) {
                    item.bitmap = res.bitmap
                    if (item.width <= 0 || item.height <= 0) {
                        item.width = res.originalWidth
                        item.height = res.originalHeight
                    }
                    withContext(Dispatchers.Main) {
                        val currentPos = holder.adapterPosition
                        if (currentPos == position) {
                            holder.ivThumb.setImageBitmap(res.bitmap)
                            val w = if (item.width > 0) item.width else res.originalWidth
                            val h = if (item.height > 0) item.height else res.originalHeight
                            holder.tvDimension.text = "${w}×${h}"
                        }
                    }
                }
            }
        }

        holder.btnRemove.setOnClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos != RecyclerView.NO_POSITION && currentPos < images.size) {
                onRemoveClick(currentPos, images[currentPos])
            }
        }
    }

    override fun getItemCount(): Int = images.size

    fun removeAt(position: Int) {
        if (position in images.indices) {
            images.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, images.size - position)
        }
    }

    fun addImage(image: CapturedImage) {
        // Prevent duplicate image URLs if already present
        if (!image.imageUrl.isNullOrBlank() && images.any { it.imageUrl == image.imageUrl }) {
            return
        }
        images.add(image)
        notifyItemInserted(images.size - 1)
    }

    fun clearAll() {
        val size = images.size
        images.clear()
        notifyItemRangeRemoved(0, size)
    }
}

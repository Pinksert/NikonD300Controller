package com.example.nikond300controller

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 相册列表中的一张照片 */
data class GalleryPhoto(
    val handle: Int,
    val fileName: String,
    val byteSize: Long,
    var thumb: Bitmap? = null
)

class GalleryAdapter(
    private val onItemClick: (GalleryPhoto, Int) -> Unit,
    private val onItemLongClick: (GalleryPhoto, Int) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.PhotoViewHolder>() {

    private val items = mutableListOf<GalleryPhoto>()

    fun getItems(): List<GalleryPhoto> = items

    fun addPhotos(photos: List<GalleryPhoto>) {
        if (photos.isEmpty()) return
        val startIndex = items.size
        items.addAll(photos)
        notifyItemRangeInserted(startIndex, photos.size)
    }

    /** 缩略图是串行异步拉取的，到位后逐项刷新，不阻塞 UI */
    fun setThumbAt(position: Int, bitmap: Bitmap?) {
        if (position < 0 || position >= items.size) return
        items[position].thumb = bitmap
        notifyItemChanged(position)
    }

    fun removeAt(position: Int): GalleryPhoto? {
        if (position < 0 || position >= items.size) return null
        val removed = items.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, items.size - position)
        return removed
    }

    fun indexOfHandle(handle: Int): Int = items.indexOfFirst { it.handle == handle }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_photo, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbView: ImageView = itemView.findViewById(R.id.photoThumb)
        private val nameView: TextView = itemView.findViewById(R.id.photoName)

        fun bind(photo: GalleryPhoto) {
            val bitmap = photo.thumb
            if (bitmap != null) {
                thumbView.scaleType = ImageView.ScaleType.CENTER_CROP
                thumbView.setPadding(0, 0, 0, 0)
                thumbView.setImageBitmap(bitmap)
            } else {
                thumbView.scaleType = ImageView.ScaleType.CENTER
                thumbView.setPadding(24, 24, 24, 24)
                thumbView.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            nameView.text = photo.fileName

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onItemClick(items[pos], pos)
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onItemLongClick(items[pos], pos)
                true
            }
        }
    }
}

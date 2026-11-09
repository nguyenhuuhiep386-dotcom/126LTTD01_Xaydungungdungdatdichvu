package vn.servio.customer.ui.request

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vn.servio.customer.R
import vn.servio.customer.databinding.ItemAddImageBinding
import vn.servio.customer.databinding.ItemDraftImageBinding

/** CS-08 image grid: the picked images, then the "Thêm ảnh" tile while there is a free slot. */
class DraftImageAdapter(
    private val thumbnails: ImageThumbnails,
    private val onAdd: () -> Unit,
    private val onRetry: (DraftImage) -> Unit,
    private val onRemove: (DraftImage) -> Unit,
) : ListAdapter<DraftImageAdapter.Tile, RecyclerView.ViewHolder>(Diff) {

    sealed interface Tile {
        data class Image(val image: DraftImage) : Tile
        data object Add : Tile
    }

    class ImageViewHolder(val binding: ItemDraftImageBinding) : RecyclerView.ViewHolder(binding.root)

    class AddViewHolder(val binding: ItemAddImageBinding) : RecyclerView.ViewHolder(binding.root)

    fun submitImages(images: List<DraftImage>) {
        val tiles = images.map(Tile::Image)
        submitList(if (images.size < RequestDraft.MAX_IMAGES) tiles + Tile.Add else tiles)
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is Tile.Image -> TYPE_IMAGE
        Tile.Add -> TYPE_ADD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_IMAGE) {
            ImageViewHolder(ItemDraftImageBinding.inflate(inflater, parent, false))
        } else {
            AddViewHolder(ItemAddImageBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AddViewHolder -> holder.binding.card.setOnClickListener { onAdd() }
            is ImageViewHolder -> bindImage(holder.binding, (getItem(position) as Tile.Image).image)
        }
    }

    private fun bindImage(binding: ItemDraftImageBinding, image: DraftImage) = with(binding) {
        thumbnails.load(image.uri, thumbnail)
        val uploading = image.status == DraftImage.Status.UPLOADING
        val failed = image.status == DraftImage.Status.FAILED
        scrim.isVisible = uploading || failed
        progress.isVisible = uploading
        failedGroup.isVisible = failed
        card.contentDescription = root.context.getString(
            when (image.status) {
                DraftImage.Status.UPLOADING -> R.string.create_request_image_uploading_desc
                DraftImage.Status.FAILED -> R.string.create_request_image_failed_desc
                DraftImage.Status.UPLOADED -> R.string.create_request_image_desc
            },
        )
        card.isClickable = failed
        card.isFocusable = failed
        if (failed) card.setOnClickListener { onRetry(image) } else card.setOnClickListener(null)
        removeButton.setOnClickListener { onRemove(image) }
    }

    private object Diff : DiffUtil.ItemCallback<Tile>() {
        override fun areItemsTheSame(old: Tile, new: Tile) = when {
            old is Tile.Image && new is Tile.Image -> old.image.localId == new.image.localId
            else -> old == new
        }

        override fun areContentsTheSame(old: Tile, new: Tile) = old == new
    }

    private companion object {
        const val TYPE_IMAGE = 0
        const val TYPE_ADD = 1
    }
}

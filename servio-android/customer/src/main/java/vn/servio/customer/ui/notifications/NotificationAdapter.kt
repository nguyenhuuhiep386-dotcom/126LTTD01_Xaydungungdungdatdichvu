package vn.servio.customer.ui.notifications

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vn.servio.core.R as CoreR
import vn.servio.customer.R
import vn.servio.customer.data.model.AppNotification
import vn.servio.customer.databinding.ItemNotificationBinding
import vn.servio.customer.databinding.ItemNotificationHeaderBinding
import vn.servio.customer.ui.notifications.NotificationTexts.Day
import vn.servio.customer.ui.notifications.NotificationTexts.Icon
import vn.servio.customer.ui.notifications.NotificationTexts.Line

/** CS-31 rows: a day header, then notifications. Unread: tinted background, dot, bold title. */
class NotificationAdapter(private val onOpen: (AppNotification) -> Unit) :
    ListAdapter<Line, RecyclerView.ViewHolder>(Diff) {

    private class HeaderHolder(val binding: ItemNotificationHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    private class RowHolder(val binding: ItemNotificationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Line.Header -> TYPE_HEADER
        is Line.Row -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) HeaderHolder(ItemNotificationHeaderBinding.inflate(inflater, parent, false))
        else RowHolder(ItemNotificationBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val line = getItem(position)) {
            is Line.Header -> (holder as HeaderHolder).binding.root.text = when (val day = line.day) {
                Day.Today -> holder.binding.root.context.getString(R.string.notifications_today)
                Day.Yesterday -> holder.binding.root.context.getString(R.string.notifications_yesterday)
                is Day.Date -> day.text
            }
            is Line.Row -> with((holder as RowHolder).binding) {
                val item = line.notification
                icon.setImageResource(iconOf(item.type))
                title.text = item.title
                body.text = item.body
                time.text = line.time
                time.isVisible = line.time.isNotEmpty()
                TextViewCompat.setTextAppearance(
                    title,
                    if (item.isRead) CoreR.style.TextAppearance_Servio_Body else CoreR.style.TextAppearance_Servio_BodyStrong,
                )
                unreadDot.isVisible = !item.isRead
                root.isActivated = !item.isRead
                root.contentDescription = root.context.getString(
                    if (item.isRead) R.string.notifications_row_read else R.string.notifications_row_unread,
                    item.title.trimEnd('.', ' '), item.body.trimEnd('.', ' '), line.time,
                )
                root.setOnClickListener { onOpen(item) }
            }
        }
    }

    private fun iconOf(type: String): Int = when (NotificationTexts.icon(type)) {
        Icon.QUOTE -> R.drawable.ic_quote_accent
        Icon.ORDER -> R.drawable.ic_receipt
        Icon.REVIEW -> R.drawable.ic_star
        Icon.PAYMENT -> R.drawable.ic_profile_wallet
        Icon.OTHER -> R.drawable.ic_profile_notification
    }

    private object Diff : DiffUtil.ItemCallback<Line>() {
        override fun areItemsTheSame(old: Line, new: Line): Boolean = when {
            old is Line.Header && new is Line.Header -> old.day == new.day
            old is Line.Row && new is Line.Row -> old.notification.id == new.notification.id
            else -> false
        }

        override fun areContentsTheSame(old: Line, new: Line): Boolean = old == new
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ROW = 1
    }
}

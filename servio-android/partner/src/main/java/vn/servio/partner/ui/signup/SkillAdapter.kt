package vn.servio.partner.ui.signup

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vn.servio.partner.R
import vn.servio.partner.data.model.ApprovalStatus
import vn.servio.partner.databinding.ItemSkillGroupBinding
import vn.servio.partner.databinding.ItemSkillServiceBinding

/** PS-03 / PS-24 list: level-1 groups that expand into checkable level-2 services. */
class SkillAdapter(
    private val onGroupClick: (String) -> Unit,
    private val onServiceClick: (String) -> Unit,
) : ListAdapter<SkillRow, RecyclerView.ViewHolder>(Diff) {

    class GroupHolder(val binding: ItemSkillGroupBinding) : RecyclerView.ViewHolder(binding.root)
    class ServiceHolder(val binding: ItemSkillServiceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = if (getItem(position) is SkillRow.Group) TYPE_GROUP else TYPE_SERVICE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_GROUP) {
            GroupHolder(ItemSkillGroupBinding.inflate(inflater, parent, false))
        } else {
            ServiceHolder(ItemSkillServiceBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is SkillRow.Group -> with((holder as GroupHolder).binding) {
                val context = root.context
                name.text = row.category.name
                count.isVisible = row.selectedCount > 0
                count.text = context.getString(R.string.skills_group_count, row.selectedCount)
                chevron.rotation = if (row.expanded) 180f else 0f
                root.setOnClickListener { onGroupClick(row.category.id) }
            }
            is SkillRow.Service -> with((holder as ServiceHolder).binding) {
                val context = root.context
                name.text = row.category.name
                check.isChecked = row.checked
                val statusText = when (row.skill?.status) {
                    ApprovalStatus.APPROVED -> context.getString(R.string.status_approved)
                    ApprovalStatus.PENDING -> context.getString(R.string.status_pending)
                    ApprovalStatus.REJECTED -> context.getString(R.string.status_rejected)
                    null -> if (row.category.requiresCertificate) context.getString(R.string.skills_needs_certificate) else null
                }
                status.isVisible = statusText != null
                status.text = statusText
                root.setOnClickListener { onServiceClick(row.category.id) }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<SkillRow>() {
        override fun areItemsTheSame(old: SkillRow, new: SkillRow) = when {
            old is SkillRow.Group && new is SkillRow.Group -> old.category.id == new.category.id
            old is SkillRow.Service && new is SkillRow.Service -> old.category.id == new.category.id
            else -> false
        }

        override fun areContentsTheSame(old: SkillRow, new: SkillRow) = old == new
    }

    private companion object {
        const val TYPE_GROUP = 0
        const val TYPE_SERVICE = 1
    }
}

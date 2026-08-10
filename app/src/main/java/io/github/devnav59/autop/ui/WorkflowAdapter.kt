package io.github.devnav59.autop.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.devnav59.autop.R
import io.github.devnav59.autop.data.Workflow
import io.github.devnav59.autop.databinding.ItemWorkflowBinding

class WorkflowAdapter(
    private val onClick: (Workflow) -> Unit,
) : RecyclerView.Adapter<WorkflowAdapter.Holder>() {
    private var items: List<Workflow> = emptyList()

    fun submitList(newItems: List<Workflow>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemWorkflowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemWorkflowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Workflow) = with(binding) {
            workflowName.text = item.name
            appName.text = item.targetAppLabel
            appBadge.text = item.targetAppLabel.trim().firstOrNull()?.toString() ?: "A"
            stepCount.text = root.context.getString(R.string.steps_count, item.steps.size)
            root.setOnClickListener { onClick(item) }
        }
    }
}

package io.github.devnav59.autop.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.devnav59.autop.R
import io.github.devnav59.autop.data.ActionType
import io.github.devnav59.autop.data.AutomationStep
import io.github.devnav59.autop.databinding.ItemStepBinding

class StepAdapter(
    private val onDelete: (AutomationStep) -> Unit,
) : RecyclerView.Adapter<StepAdapter.Holder>() {
    private var items: List<AutomationStep> = emptyList()

    fun submitList(newItems: List<AutomationStep>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemStepBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position], position)

    inner class Holder(private val binding: ItemStepBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(step: AutomationStep, position: Int) = with(binding) {
            val context = root.context
            stepNumber.text = (position + 1).toString()
            actionName.text = context.getString(
                when (step.type) {
                    ActionType.CLICK -> R.string.action_click
                    ActionType.LONG_CLICK -> R.string.action_long_click
                    ActionType.SELECT -> R.string.action_select
                    ActionType.SET_TEXT -> R.string.action_set_text
                    ActionType.SET_PROGRESS -> R.string.action_set_progress
                    ActionType.SCROLL_UP -> R.string.action_scroll_up
                    ActionType.SCROLL_DOWN -> R.string.action_scroll_down
                    ActionType.SCROLL_LEFT -> R.string.action_scroll_left
                    ActionType.SCROLL_RIGHT -> R.string.action_scroll_right
                    ActionType.BACK -> R.string.action_back
                },
            )
            val selectorName = step.selector?.displayName().orEmpty()
                .ifBlank { context.getString(R.string.unknown_element) }
            elementName.text = when (step.type) {
                ActionType.SET_TEXT ->
                    "$selectorName — ${context.getString(R.string.text_value_hidden, step.value.orEmpty().take(80))}"
                ActionType.SET_PROGRESS -> "$selectorName — مقدار: ${step.value.orEmpty()}"
                else -> selectorName
            }
            warning.visibility = if (step.selector != null && step.selector.semanticStrength == 0) {
                View.VISIBLE
            } else {
                View.GONE
            }
            deleteStep.setOnClickListener { onDelete(step) }
        }
    }
}

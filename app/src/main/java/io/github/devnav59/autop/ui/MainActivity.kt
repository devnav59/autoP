package io.github.devnav59.autop.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.devnav59.autop.R
import io.github.devnav59.autop.accessibility.AutomationAccessibilityService
import io.github.devnav59.autop.data.WorkflowRepository
import io.github.devnav59.autop.databinding.ActivityMainBinding

class MainActivity : BaseActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: WorkflowRepository
    private lateinit var adapter: WorkflowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = WorkflowRepository(this)

        adapter = WorkflowAdapter { workflow ->
            startActivity(
                Intent(this, WorkflowActivity::class.java)
                    .putExtra(WorkflowActivity.EXTRA_WORKFLOW_ID, workflow.id),
            )
        }
        binding.workflowList.layoutManager = LinearLayoutManager(this)
        binding.workflowList.adapter = adapter
        binding.addWorkflow.setOnClickListener {
            startActivity(Intent(this, WorkflowActivity::class.java))
        }
        binding.openAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val workflows = repository.getAll()
        adapter.submitList(workflows)
        binding.emptyState.visibility = if (workflows.isEmpty()) View.VISIBLE else View.GONE

        val enabled = isServiceEnabled()
        binding.serviceStatus.setText(
            if (enabled && AutomationAccessibilityService.isConnected()) {
                R.string.service_enabled
            } else {
                R.string.service_disabled
            },
        )
        binding.serviceIndicator.setBackgroundResource(
            if (enabled && AutomationAccessibilityService.isConnected()) {
                R.drawable.bg_status_on
            } else {
                R.drawable.bg_status_off
            },
        )
        binding.openAccessibility.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun isServiceEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val service = it.resolveInfo?.serviceInfo
            service?.packageName == packageName &&
                service.name == AutomationAccessibilityService::class.java.name
        }
    }
}

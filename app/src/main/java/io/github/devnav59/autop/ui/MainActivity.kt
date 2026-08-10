package io.github.devnav59.autop.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.devnav59.autop.BuildConfig
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
        binding.toolbar.subtitle = "نسخه ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

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

        val enabled = AutomationAccessibilityService.isEnabledInSettings(this)
        val connected = AutomationAccessibilityService.isConnected()
        binding.serviceStatus.setText(
            when {
                connected -> R.string.service_enabled
                enabled -> R.string.service_connecting
                else -> R.string.service_disabled
            },
        )
        binding.serviceIndicator.setBackgroundResource(
            if (connected) R.drawable.bg_status_on else R.drawable.bg_status_off,
        )
        // Keep settings reachable while enabled-but-not-bound so the user can toggle the service
        // after an APK update on OEMs that do not reconnect it automatically.
        binding.openAccessibility.visibility = if (connected) View.GONE else View.VISIBLE
    }
}

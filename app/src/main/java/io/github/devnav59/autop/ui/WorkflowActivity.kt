package io.github.devnav59.autop.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.devnav59.autop.R
import io.github.devnav59.autop.accessibility.AutomationAccessibilityService
import io.github.devnav59.autop.data.Workflow
import io.github.devnav59.autop.data.WorkflowRepository
import io.github.devnav59.autop.databinding.ActivityWorkflowBinding

class WorkflowActivity : BaseActivity() {
    private lateinit var binding: ActivityWorkflowBinding
    private lateinit var repository: WorkflowRepository
    private lateinit var stepAdapter: StepAdapter
    private var workflowId: String? = null
    private var targetPackageName: String? = null
    private var targetLabel: String? = null

    private val appPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val newPackage = result.data?.getStringExtra(AppPickerActivity.EXTRA_PACKAGE) ?: return@registerForActivityResult
        val newLabel = result.data?.getStringExtra(AppPickerActivity.EXTRA_LABEL) ?: newPackage
        val existing = workflowId?.let(repository::get)
        if (existing != null && existing.steps.isNotEmpty() && existing.targetPackage != newPackage) {
            MaterialAlertDialogBuilder(this)
                .setTitle("برنامهٔ هدف تغییر کند؟")
                .setMessage("شناسه‌های مراحل فعلی متعلق به برنامهٔ قبلی هستند و پاک خواهند شد.")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear) { _, _ ->
                    repository.clearSteps(existing.id)
                    applyTarget(newPackage, newLabel)
                    persist(showMessage = false)
                    refreshSteps()
                }
                .show()
        } else {
            applyTarget(newPackage, newLabel)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkflowBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = WorkflowRepository(this)
        workflowId = intent.getStringExtra(EXTRA_WORKFLOW_ID)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setTitle(if (workflowId == null) R.string.workflow_new_title else R.string.workflow_edit_title)
        binding.chooseAppCard.setOnClickListener {
            appPicker.launch(Intent(this, AppPickerActivity::class.java))
        }
        binding.saveButton.setOnClickListener { persist(showMessage = true) }
        binding.recordButton.setOnClickListener { startRecording() }
        binding.runButton.setOnClickListener { startReplay() }
        binding.clearButton.setOnClickListener { confirmClear() }
        binding.deleteButton.setOnClickListener { confirmDelete() }

        stepAdapter = StepAdapter { step ->
            workflowId?.let { repository.removeStep(it, step.id) }
            refreshSteps()
        }
        binding.stepList.layoutManager = LinearLayoutManager(this)
        binding.stepList.adapter = stepAdapter

        loadWorkflow()
    }

    override fun onResume() {
        super.onResume()
        refreshSteps()
    }

    private fun loadWorkflow() {
        val workflow = workflowId?.let(repository::get)
        if (workflow != null) {
            binding.workflowName.setText(workflow.name)
            applyTarget(workflow.targetPackage, workflow.targetAppLabel)
        }
        binding.deleteButton.visibility = if (workflow == null) View.GONE else View.VISIBLE
        refreshSteps()
    }

    private fun applyTarget(packageName: String, label: String) {
        targetPackageName = packageName
        targetLabel = label
        binding.targetName.text = label
        binding.targetPackage.text = packageName
        binding.targetPackage.visibility = View.VISIBLE
        val icon = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
        binding.targetIcon.setImageDrawable(icon ?: getDrawable(R.drawable.ic_app))
    }

    private fun persist(showMessage: Boolean): Workflow? {
        val name = binding.workflowName.text?.toString()?.trim().orEmpty()
        val packageName = targetPackageName
        if (name.isBlank()) {
            binding.nameLayout.error = getString(R.string.field_required)
            return null
        }
        binding.nameLayout.error = null
        if (packageName.isNullOrBlank()) {
            Snackbar.make(binding.root, R.string.app_required, Snackbar.LENGTH_SHORT).show()
            return null
        }

        val existing = workflowId?.let(repository::get)
        val workflow = Workflow(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name,
            targetPackage = packageName,
            targetAppLabel = targetLabel ?: packageName,
            steps = existing?.steps.orEmpty(),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = existing?.updatedAt ?: System.currentTimeMillis(),
        )
        val saved = repository.save(workflow)
        workflowId = saved.id
        binding.toolbar.setTitle(R.string.workflow_edit_title)
        binding.deleteButton.visibility = View.VISIBLE
        if (showMessage) Snackbar.make(binding.root, R.string.saved, Snackbar.LENGTH_SHORT).show()
        return saved
    }

    private fun startRecording() {
        val workflow = persist(showMessage = false) ?: return
        handleCommandResult(
            AutomationAccessibilityService.requestRecording(this, workflow.id),
        )
        // The service launches the selected app. Keeping this task in history makes the overlay's
        // “finish” button return to this editor without creating duplicate screens.
    }

    private fun startReplay() {
        val workflow = persist(showMessage = false) ?: return
        if (workflow.steps.isEmpty()) {
            Snackbar.make(binding.root, R.string.no_steps, Snackbar.LENGTH_SHORT).show()
            return
        }
        handleCommandResult(
            AutomationAccessibilityService.requestReplay(this, workflow.id),
        )
    }

    private fun handleCommandResult(
        result: AutomationAccessibilityService.CommandRequestResult,
    ) {
        when (result) {
            AutomationAccessibilityService.CommandRequestResult.STARTED -> Unit
            AutomationAccessibilityService.CommandRequestResult.QUEUED_UNTIL_CONNECTED -> {
                Snackbar.make(binding.root, R.string.service_connecting, Snackbar.LENGTH_LONG)
                    .setAction(R.string.open_accessibility) {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .show()
            }
            AutomationAccessibilityService.CommandRequestResult.DISABLED -> showServiceError()
            AutomationAccessibilityService.CommandRequestResult.FAILED -> {
                Snackbar.make(binding.root, R.string.service_command_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showServiceError() {
        Snackbar.make(binding.root, R.string.service_not_ready, Snackbar.LENGTH_LONG)
            .setAction(R.string.open_accessibility) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .show()
    }

    private fun refreshSteps() {
        val workflow = workflowId?.let(repository::get)
        val steps = workflow?.steps.orEmpty()
        stepAdapter.submitList(steps)
        binding.emptySteps.visibility = if (steps.isEmpty()) View.VISIBLE else View.GONE
        binding.clearButton.visibility = if (steps.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmClear() {
        val id = workflowId ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_clear_title)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear) { _, _ ->
                repository.clearSteps(id)
                refreshSteps()
            }
            .show()
    }

    private fun confirmDelete() {
        val id = workflowId ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.confirm_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                repository.delete(id)
                finish()
            }
            .show()
    }

    companion object {
        const val EXTRA_WORKFLOW_ID = "workflow_id"
    }
}

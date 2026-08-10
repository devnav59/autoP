package io.github.devnav59.autop.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import io.github.devnav59.autop.R
import io.github.devnav59.autop.data.ActionType
import io.github.devnav59.autop.data.AutomationStep
import io.github.devnav59.autop.data.ElementSelector
import io.github.devnav59.autop.data.Workflow
import io.github.devnav59.autop.data.WorkflowRepository
import io.github.devnav59.autop.databinding.OverlayControllerBinding
import io.github.devnav59.autop.ui.WorkflowActivity
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class AutomationAccessibilityService : AccessibilityService() {
    private lateinit var repository: WorkflowRepository
    private val handler = Handler(Looper.getMainLooper())
    private var state: SessionState = SessionState.Idle
    private var overlayBinding: OverlayControllerBinding? = null
    private var overlayAdded = false
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    private val commitTextRunnable = Runnable { flushPendingText() }
    private val replayRunnable = Runnable { runCurrentStep() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = WorkflowRepository(this)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (val current = state) {
            is SessionState.Recording -> recordEvent(current, event)
            is SessionState.Replaying -> {
                if (event.packageName?.toString() == current.workflow.targetPackage) {
                    scheduleReplay(120)
                }
            }
            else -> Unit
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val current = state
        if (current is SessionState.Recording &&
            event.action == KeyEvent.ACTION_UP &&
            event.keyCode == KeyEvent.KEYCODE_BACK &&
            activePackageName() == current.targetPackage &&
            !isInputMethodVisible()
        ) {
            flushPendingText()
            appendRecordedStep(current, AutomationStep(type = ActionType.BACK))
        }
        // Observing must never consume the user's key.
        return false
    }

    override fun onInterrupt() {
        stopSession(openEditor = false)
    }

    override fun onDestroy() {
        stopSession(openEditor = false)
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun startRecording(workflowId: String): Boolean {
        val workflow = repository.get(workflowId) ?: return false
        val launchIntent = packageManager.getLaunchIntentForPackage(workflow.targetPackage) ?: run {
            Toast.makeText(this, R.string.target_cannot_launch, Toast.LENGTH_LONG).show()
            return false
        }
        stopSession(openEditor = false)
        state = SessionState.Recording(
            workflowId = workflow.id,
            targetPackage = workflow.targetPackage,
            recordedCount = workflow.steps.size,
        )
        showOverlay()
        updateOverlay()
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(launchIntent)
        return true
    }

    fun startReplay(workflowId: String): Boolean {
        val workflow = repository.get(workflowId) ?: return false
        if (workflow.steps.isEmpty()) return false
        val launchIntent = packageManager.getLaunchIntentForPackage(workflow.targetPackage) ?: run {
            Toast.makeText(this, R.string.target_cannot_launch, Toast.LENGTH_LONG).show()
            return false
        }
        stopSession(openEditor = false)
        state = SessionState.Replaying(workflow = workflow)
        showOverlay()
        updateOverlay()
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(launchIntent)
        scheduleReplay(1_000)
        return true
    }

    private fun recordEvent(session: SessionState.Recording, event: AccessibilityEvent) {
        if (event.packageName?.toString() != session.targetPackage) return
        val source = event.source ?: return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    flushPendingText()
                    val selector = SelectorFactory.fromNode(source, session.targetPackage)
                    appendIfNotDuplicate(session, ActionType.CLICK, selector, duplicateWindowMs = 280)
                }
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                    flushPendingText()
                    val selector = SelectorFactory.fromNode(source, session.targetPackage)
                    appendIfNotDuplicate(session, ActionType.LONG_CLICK, selector, duplicateWindowMs = 350)
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> recordText(session, event, source)
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> recordScroll(session, event, source)
            }
        } finally {
            @Suppress("DEPRECATION")
            source.recycle()
        }
    }

    private fun recordText(
        session: SessionState.Recording,
        event: AccessibilityEvent,
        source: AccessibilityNodeInfo,
    ) {
        if (event.isPassword || source.isPassword) {
            handler.removeCallbacks(commitTextRunnable)
            session.pendingText = null
            Toast.makeText(this, R.string.record_password_skipped, Toast.LENGTH_SHORT).show()
            return
        }
        val selector = SelectorFactory.fromNode(source, session.targetPackage)
        val value = source.text?.toString() ?: event.text.joinToString(separator = "")
        val oldPending = session.pendingText
        if (oldPending != null && oldPending.selector.stableKey() != selector.stableKey()) {
            flushPendingText()
        }
        session.pendingText = PendingText(selector, value)
        handler.removeCallbacks(commitTextRunnable)
        handler.postDelayed(commitTextRunnable, TEXT_DEBOUNCE_MS)
    }

    private fun recordScroll(
        session: SessionState.Recording,
        event: AccessibilityEvent,
        source: AccessibilityNodeInfo,
    ) {
        val scrollNode = SelectorFactory.nearestScrollable(source)
        try {
            val selector = SelectorFactory.fromNode(scrollNode, session.targetPackage)
            val direction = scrollDirection(session, event, selector) ?: return
            appendIfNotDuplicate(session, direction, selector, duplicateWindowMs = 500)
        } finally {
            @Suppress("DEPRECATION")
            scrollNode.recycle()
        }
    }

    private fun scrollDirection(
        session: SessionState.Recording,
        event: AccessibilityEvent,
        selector: ElementSelector,
    ): ActionType? {
        if (Build.VERSION.SDK_INT >= 28) {
            if (abs(event.scrollDeltaY) >= abs(event.scrollDeltaX) && event.scrollDeltaY != 0) {
                return if (event.scrollDeltaY > 0) ActionType.SCROLL_DOWN else ActionType.SCROLL_UP
            }
            if (event.scrollDeltaX != 0) {
                return if (event.scrollDeltaX > 0) ActionType.SCROLL_RIGHT else ActionType.SCROLL_LEFT
            }
        }

        val key = selector.stableKey()
        val x = event.scrollX.takeIf { it >= 0 }
        val y = event.scrollY.takeIf { it >= 0 } ?: event.toIndex.takeIf { it >= 0 }
        val previous = session.scrollPositions.put(key, ScrollPosition(x, y))
        if (previous != null) {
            if (y != null && previous.y != null && y != previous.y) {
                return if (y > previous.y) ActionType.SCROLL_DOWN else ActionType.SCROLL_UP
            }
            if (x != null && previous.x != null && x != previous.x) {
                return if (x > previous.x) ActionType.SCROLL_RIGHT else ActionType.SCROLL_LEFT
            }
        }
        // On old Android releases the first event has no delta. A positive index reliably means
        // movement toward the end, otherwise waiting for the next event is safer than guessing.
        return if ((y ?: 0) > 0 || event.fromIndex > 0) ActionType.SCROLL_DOWN else null
    }

    private fun appendIfNotDuplicate(
        session: SessionState.Recording,
        type: ActionType,
        selector: ElementSelector,
        duplicateWindowMs: Long,
    ) {
        val now = System.currentTimeMillis()
        val key = "${type.name}:${selector.stableKey()}"
        if (key == session.lastActionKey && now - session.lastActionAt < duplicateWindowMs) return
        session.lastActionKey = key
        session.lastActionAt = now
        appendRecordedStep(session, AutomationStep(type = type, selector = selector))
    }

    private fun appendRecordedStep(session: SessionState.Recording, step: AutomationStep) {
        repository.appendStep(session.workflowId, step)
        session.recordedCount = repository.get(session.workflowId)?.steps?.size ?: session.recordedCount + 1
        updateOverlay()
    }

    private fun flushPendingText() {
        handler.removeCallbacks(commitTextRunnable)
        val session = state as? SessionState.Recording ?: return
        val pending = session.pendingText ?: return
        session.pendingText = null
        appendRecordedStep(
            session,
            AutomationStep(
                type = ActionType.SET_TEXT,
                selector = pending.selector,
                value = pending.value,
            ),
        )
    }

    private fun runCurrentStep() {
        val session = state as? SessionState.Replaying ?: return
        if (session.index >= session.workflow.steps.size) {
            finishReplay(
                session = session,
                success = true,
                message = getString(R.string.workflow_complete),
            )
            return
        }

        val step = session.workflow.steps[session.index]
        val result = if (step.type == ActionType.BACK) {
            if (performGlobalAction(GLOBAL_ACTION_BACK)) StepResult.Success else StepResult.Retry("بازگشت انجام نشد")
        } else {
            findAndPerform(session, step)
        }

        when (result) {
            StepResult.Success -> {
                session.index += 1
                session.stepStartedAt = System.currentTimeMillis()
                session.lastIssue = null
                updateOverlay()
                scheduleReplay(ACTION_SETTLE_MS)
            }
            is StepResult.Retry -> {
                session.lastIssue = result.reason
                updateOverlay()
                if (System.currentTimeMillis() - session.stepStartedAt >= STEP_TIMEOUT_MS) {
                    val message = "مرحلهٔ ${session.index + 1}: ${result.reason}"
                    finishReplay(session = session, success = false, message = message)
                } else {
                    scheduleReplay(RETRY_MS)
                }
            }
        }
    }

    private fun findAndPerform(session: SessionState.Replaying, step: AutomationStep): StepResult {
        val selector = step.selector ?: return StepResult.Retry("انتخاب‌گر المان وجود ندارد")
        if (selector.packageName != session.workflow.targetPackage) {
            return StepResult.Retry("المان متعلق به برنامهٔ هدف نیست")
        }
        val roots = targetRoots(session.workflow.targetPackage)
        if (roots.isEmpty()) return StepResult.Retry("صفحهٔ برنامهٔ هدف آماده نیست")

        val match = try {
            NodeMatcher.findUnique(selector, roots)
        } finally {
            roots.forEach {
                @Suppress("DEPRECATION")
                it.recycle()
            }
        }
        return when (match) {
            is NodeMatcher.Result.NotFound -> StepResult.Retry("المان پیدا نشد")
            is NodeMatcher.Result.Ambiguous -> StepResult.Retry("چند المان مشابه پیدا شد؛ اجرا برای ایمنی متوقف می‌شود")
            is NodeMatcher.Result.Found -> {
                val performed = try {
                    performOnNode(match.node, step)
                } finally {
                    @Suppress("DEPRECATION")
                    match.node.recycle()
                }
                if (performed) StepResult.Success else StepResult.Retry("المان پیدا شد اما عمل را نپذیرفت")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun targetRoots(targetPackage: String): List<AccessibilityNodeInfo> {
        val roots = runCatching {
            val matching = mutableListOf<AccessibilityNodeInfo>()
            windows.mapNotNull { it.root }.forEach { root ->
                if (root.packageName?.toString() == targetPackage) {
                    matching += root
                } else {
                    root.recycle()
                }
            }
            matching
        }.getOrDefault(emptyList())
        if (roots.isNotEmpty()) return roots
        val active = rootInActiveWindow ?: return emptyList()
        return if (active.packageName?.toString() == targetPackage) {
            listOf(active)
        } else {
            active.recycle()
            emptyList()
        }
    }

    private fun performOnNode(node: AccessibilityNodeInfo, step: AutomationStep): Boolean = when (step.type) {
        ActionType.CLICK -> performClickable(node, AccessibilityNodeInfo.ACTION_CLICK, requireLongClickable = false)
        ActionType.LONG_CLICK -> performClickable(node, AccessibilityNodeInfo.ACTION_LONG_CLICK, requireLongClickable = true)
        ActionType.SET_TEXT -> {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step.value.orEmpty())
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) ||
                (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) &&
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        }
        ActionType.SCROLL_UP -> performScroll(
            node,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
        )
        ActionType.SCROLL_DOWN -> performScroll(
            node,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
        )
        ActionType.SCROLL_LEFT -> performScroll(
            node,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
        )
        ActionType.SCROLL_RIGHT -> performScroll(
            node,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
        )
        ActionType.BACK -> false
    }

    @Suppress("DEPRECATION")
    private fun performClickable(node: AccessibilityNodeInfo, action: Int, requireLongClickable: Boolean): Boolean {
        var current = AccessibilityNodeInfo.obtain(node)
        repeat(6) {
            val acceptsAction = if (requireLongClickable) current.isLongClickable else current.isClickable
            if (acceptsAction && current.performAction(action)) {
                current.recycle()
                return true
            }
            val parent = current.parent
            current.recycle()
            current = parent ?: return false
        }
        current.recycle()
        return false
    }

    @Suppress("DEPRECATION")
    private fun performScroll(node: AccessibilityNodeInfo, directionalAction: Int, fallbackAction: Int): Boolean {
        val scrollable = SelectorFactory.nearestScrollable(node)
        return try {
            scrollable.performAction(directionalAction) || scrollable.performAction(fallbackAction)
        } finally {
            scrollable.recycle()
        }
    }

    private fun scheduleReplay(delayMs: Long) {
        handler.removeCallbacks(replayRunnable)
        handler.postDelayed(replayRunnable, delayMs)
    }

    private fun finishReplay(session: SessionState.Replaying, success: Boolean, message: String) {
        handler.removeCallbacks(replayRunnable)
        state = SessionState.Outcome(session.workflow.id, success, message)
        updateOverlay()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun stopSession(openEditor: Boolean) {
        val workflowId = when (val current = state) {
            is SessionState.Recording -> {
                flushPendingText()
                current.workflowId
            }
            is SessionState.Replaying -> current.workflow.id
            is SessionState.Outcome -> current.workflowId
            SessionState.Idle -> null
        }
        handler.removeCallbacks(commitTextRunnable)
        handler.removeCallbacks(replayRunnable)
        state = SessionState.Idle
        hideOverlay()
        if (openEditor && workflowId != null) openWorkflowEditor(workflowId)
    }

    private fun openWorkflowEditor(workflowId: String) {
        startActivity(
            Intent(this, WorkflowActivity::class.java)
                .putExtra(WorkflowActivity.EXTRA_WORKFLOW_ID, workflowId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private fun showOverlay() {
        if (overlayAdded) return
        val binding = OverlayControllerBinding.inflate(LayoutInflater.from(this))
        binding.undoButton.setOnClickListener {
            val recording = state as? SessionState.Recording ?: return@setOnClickListener
            flushPendingText()
            repository.removeLastStep(recording.workflowId)
            recording.recordedCount = repository.get(recording.workflowId)?.steps?.size ?: 0
            updateOverlay()
        }
        binding.stopButton.setOnClickListener { stopSession(openEditor = true) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 88
        }
        runCatching {
            windowManager.addView(binding.root, params)
            overlayBinding = binding
            overlayAdded = true
        }
    }

    private fun hideOverlay() {
        val binding = overlayBinding ?: return
        if (overlayAdded) runCatching { windowManager.removeView(binding.root) }
        overlayAdded = false
        overlayBinding = null
    }

    private fun updateOverlay() {
        val binding = overlayBinding ?: return
        when (val current = state) {
            is SessionState.Recording -> {
                binding.modeIndicator.setBackgroundResource(R.drawable.bg_status_off)
                binding.modeTitle.setText(R.string.overlay_recording)
                binding.modeDetail.text = getString(R.string.recorded_count, current.recordedCount)
                binding.undoButton.visibility = View.VISIBLE
                binding.undoButton.isEnabled = current.recordedCount > 0
                binding.stopButton.setText(R.string.stop)
            }
            is SessionState.Replaying -> {
                binding.modeIndicator.setBackgroundResource(R.drawable.bg_status_on)
                binding.modeTitle.setText(R.string.overlay_running)
                val progress = getString(
                    R.string.step_progress,
                    (current.index + 1).coerceAtMost(current.workflow.steps.size),
                    current.workflow.steps.size,
                )
                binding.modeDetail.text = current.lastIssue?.let { "$progress\n$it" } ?: progress
                binding.undoButton.visibility = View.GONE
                binding.stopButton.setText(R.string.stop)
            }
            is SessionState.Outcome -> {
                binding.modeIndicator.setBackgroundResource(
                    if (current.success) R.drawable.bg_status_on else R.drawable.bg_status_off,
                )
                binding.modeTitle.setText(
                    if (current.success) R.string.overlay_done else R.string.overlay_failed,
                )
                binding.modeDetail.text = current.message
                binding.undoButton.visibility = View.GONE
                binding.stopButton.setText(R.string.close)
            }
            SessionState.Idle -> Unit
        }
    }

    private fun activePackageName(): String? = rootInActiveWindow?.let { root ->
        val name = root.packageName?.toString()
        @Suppress("DEPRECATION")
        root.recycle()
        name
    }

    private fun isInputMethodVisible(): Boolean = runCatching {
        windows.any { window ->
            window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && window.isActive
        }
    }.getOrDefault(false)

    private sealed interface StepResult {
        data object Success : StepResult
        data class Retry(val reason: String) : StepResult
    }

    private sealed interface SessionState {
        data object Idle : SessionState

        data class Recording(
            val workflowId: String,
            val targetPackage: String,
            var recordedCount: Int = 0,
            var pendingText: PendingText? = null,
            var lastActionKey: String? = null,
            var lastActionAt: Long = 0,
            val scrollPositions: MutableMap<String, ScrollPosition> = ConcurrentHashMap(),
        ) : SessionState

        data class Replaying(
            val workflow: Workflow,
            var index: Int = 0,
            var stepStartedAt: Long = System.currentTimeMillis(),
            var lastIssue: String? = null,
        ) : SessionState

        data class Outcome(
            val workflowId: String,
            val success: Boolean,
            val message: String,
        ) : SessionState
    }

    private data class PendingText(val selector: ElementSelector, val value: String)
    private data class ScrollPosition(val x: Int?, val y: Int?)

    companion object {
        private const val TEXT_DEBOUNCE_MS = 650L
        private const val ACTION_SETTLE_MS = 650L
        private const val RETRY_MS = 350L
        private const val STEP_TIMEOUT_MS = 15_000L

        @Volatile
        var instance: AutomationAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }
}

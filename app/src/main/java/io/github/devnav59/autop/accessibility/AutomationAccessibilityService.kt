package io.github.devnav59.autop.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
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
import kotlin.math.abs

class AutomationAccessibilityService : AccessibilityService() {
    private lateinit var repository: WorkflowRepository
    private val handler = Handler(Looper.getMainLooper())
    private var state: SessionState = SessionState.Idle
    private var overlayBinding: OverlayControllerBinding? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayAdded = false
    private var overlayX = 12
    private var overlayY = 96
    private var lastErrorToastAt = 0L
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    private val commitTextRunnable = safeRunnable("ذخیره متن") { flushPendingText() }
    private val commitScrollRunnable = safeRunnable("ذخیره اسکرول") { flushPendingScroll() }
    private val commitProgressRunnable = safeRunnable("ذخیره مقدار") { flushPendingProgress() }
    private val replayRunnable = Runnable {
        try {
            runCurrentStep()
        } catch (error: Exception) {
            reportServiceError("اجرای مرحله", error)
            (state as? SessionState.Replaying)?.let { session ->
                finishReplay(session, success = false, message = "اجرای مرحله به خطای داخلی برخورد کرد")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = WorkflowRepository(this)
        instance = this
        // The Activity may be restored a little earlier than Android reconnects the accessibility
        // service after an install/update. Execute the user's queued tap as soon as binding ends.
        handler.post { runPendingCommand() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        try {
            when (val current = state) {
                is SessionState.Recording -> recordEvent(current, event)
                is SessionState.Replaying -> {
                    if (event.packageName?.toString() == current.workflow.targetPackage) scheduleReplay(120)
                }
                else -> Unit
            }
        } catch (error: Exception) {
            // Accessibility nodes can become stale while a target app is redrawing. A malformed
            // node must skip one event, not terminate the service or crash the whole app.
            reportServiceError("پردازش رویداد دسترس‌پذیری", error)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val current = state
        if (current is SessionState.Recording &&
            event.action == KeyEvent.ACTION_UP &&
            event.keyCode == KeyEvent.KEYCODE_BACK &&
            System.currentTimeMillis() >= current.ignoreBackUntil &&
            activePackageName() == current.targetPackage &&
            !isInputMethodVisible()
        ) {
            flushAllPending()
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

    private fun runPendingCommand() {
        val command = synchronized(commandLock) {
            pendingCommand.also { pendingCommand = null }
        } ?: return
        if (System.currentTimeMillis() - command.requestedAt > COMMAND_TIMEOUT_MS) return
        val started = when (command.type) {
            PendingCommandType.RECORD -> startRecording(command.workflowId)
            PendingCommandType.REPLAY -> startReplay(command.workflowId)
        }
        if (!started) {
            Toast.makeText(this, R.string.service_command_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun startRecording(workflowId: String): Boolean {
        return try {
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
            true
        } catch (error: Exception) {
            reportServiceError("شروع ضبط", error)
            stopSession(openEditor = false)
            false
        }
    }

    fun startReplay(workflowId: String): Boolean {
        return try {
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
            true
        } catch (error: Exception) {
            reportServiceError("شروع اجرا", error)
            stopSession(openEditor = false)
            false
        }
    }

    private fun recordEvent(session: SessionState.Recording, event: AccessibilityEvent) {
        if (event.packageName?.toString() != session.targetPackage) return
        val source = event.source ?: return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> {
                    flushAllPending()
                    session.lastClickAt = System.currentTimeMillis()
                    recordNodeAction(session, source, ActionType.CLICK, AccessibilityNodeInfo.ACTION_CLICK, 280)
                }
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                    flushAllPending()
                    recordNodeAction(
                        session,
                        source,
                        ActionType.LONG_CLICK,
                        AccessibilityNodeInfo.ACTION_LONG_CLICK,
                        350,
                    )
                }
                AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                    if (!recordRangeChange(session, source) &&
                        System.currentTimeMillis() - session.lastClickAt > SELECT_AFTER_CLICK_WINDOW_MS
                    ) {
                        flushAllPending()
                        recordNodeAction(
                            session,
                            source,
                            ActionType.SELECT,
                            AccessibilityNodeInfo.ACTION_SELECT,
                            450,
                        )
                    }
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> recordText(session, event, source)
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    if (!recordRangeChange(session, source)) recordScroll(session, event, source)
                }
            }
        } finally {
            @Suppress("DEPRECATION")
            source.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun recordNodeAction(
        session: SessionState.Recording,
        source: AccessibilityNodeInfo,
        type: ActionType,
        accessibilityAction: Int,
        duplicateWindowMs: Long,
    ) {
        val actionable = SelectorFactory.nearestActionable(source, accessibilityAction)
        try {
            val selector = SelectorFactory.fromNode(actionable, session.targetPackage)
            appendIfNotDuplicate(session, type, selector, duplicateWindowMs)
        } finally {
            actionable.recycle()
        }
    }

    private fun recordText(
        session: SessionState.Recording,
        event: AccessibilityEvent,
        source: AccessibilityNodeInfo,
    ) {
        flushPendingScroll()
        flushPendingProgress()
        if (event.isPassword || source.isPassword) {
            handler.removeCallbacks(commitTextRunnable)
            session.pendingText = null
            Toast.makeText(this, R.string.record_password_skipped, Toast.LENGTH_SHORT).show()
            return
        }
        val selector = SelectorFactory.fromNode(source, session.targetPackage)
        val value = source.text?.toString() ?: event.text.joinToString(separator = "")
        val oldPending = session.pendingText
        if (oldPending != null && oldPending.selector.stableKey() != selector.stableKey()) flushPendingText()
        session.pendingText = PendingText(selector, value)
        handler.removeCallbacks(commitTextRunnable)
        handler.postDelayed(commitTextRunnable, TEXT_DEBOUNCE_MS)
    }

    @Suppress("DEPRECATION")
    private fun recordRangeChange(
        session: SessionState.Recording,
        source: AccessibilityNodeInfo,
    ): Boolean {
        val rangeNode = SelectorFactory.nearestRange(source) ?: return false
        try {
            val currentValue = rangeNode.rangeInfo?.current ?: return false
            if (!currentValue.isFinite()) return false
            flushPendingText()
            flushPendingScroll()
            val selector = SelectorFactory.fromNode(rangeNode, session.targetPackage)
            val previous = session.pendingProgress
            if (previous != null && previous.selector.stableKey() != selector.stableKey()) flushPendingProgress()
            session.pendingProgress = PendingProgress(selector, currentValue)
            handler.removeCallbacks(commitProgressRunnable)
            handler.postDelayed(commitProgressRunnable, VALUE_DEBOUNCE_MS)
            return true
        } finally {
            rangeNode.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun recordScroll(
        session: SessionState.Recording,
        event: AccessibilityEvent,
        source: AccessibilityNodeInfo,
    ) {
        flushPendingText()
        flushPendingProgress()
        val scrollNode = SelectorFactory.nearestScrollable(source)
        try {
            val selector = SelectorFactory.fromNode(scrollNode, session.targetPackage)
            val direction = scrollDirection(session, event, selector) ?: return
            val targetPosition = event.fromIndex.takeIf { it >= 0 }
            val oldPending = session.pendingScroll
            if (oldPending != null &&
                (oldPending.type != direction || oldPending.selector.stableKey() != selector.stableKey())
            ) {
                flushPendingScroll()
            }
            session.pendingScroll = PendingScroll(direction, selector, targetPosition)
            handler.removeCallbacks(commitScrollRunnable)
            handler.postDelayed(commitScrollRunnable, VALUE_DEBOUNCE_MS)
        } finally {
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

    private fun flushAllPending() {
        flushPendingText()
        flushPendingProgress()
        flushPendingScroll()
    }

    private fun flushPendingText() {
        handler.removeCallbacks(commitTextRunnable)
        val session = state as? SessionState.Recording ?: return
        val pending = session.pendingText ?: return
        session.pendingText = null
        appendRecordedStep(
            session,
            AutomationStep(type = ActionType.SET_TEXT, selector = pending.selector, value = pending.value),
        )
    }

    private fun flushPendingProgress() {
        handler.removeCallbacks(commitProgressRunnable)
        val session = state as? SessionState.Recording ?: return
        val pending = session.pendingProgress ?: return
        session.pendingProgress = null
        appendRecordedStep(
            session,
            AutomationStep(
                type = ActionType.SET_PROGRESS,
                selector = pending.selector,
                value = pending.value.toString(),
            ),
        )
    }

    private fun flushPendingScroll() {
        handler.removeCallbacks(commitScrollRunnable)
        val session = state as? SessionState.Recording ?: return
        val pending = session.pendingScroll ?: return
        session.pendingScroll = null
        appendRecordedStep(
            session,
            AutomationStep(
                type = pending.type,
                selector = pending.selector,
                targetPosition = pending.targetPosition,
            ),
        )
    }

    private fun runCurrentStep() {
        val session = state as? SessionState.Replaying ?: return
        if (session.index >= session.workflow.steps.size) {
            finishReplay(session, success = true, message = getString(R.string.workflow_complete))
            return
        }

        val step = session.workflow.steps[session.index]
        val result = if (step.type == ActionType.BACK) {
            if (performGlobalAction(GLOBAL_ACTION_BACK)) StepResult.Success
            else StepResult.Retry("بازگشت انجام نشد")
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
                    finishReplay(
                        session = session,
                        success = false,
                        message = "مرحلهٔ ${session.index + 1}: ${result.reason}",
                    )
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
            is NodeMatcher.Result.Ambiguous ->
                StepResult.Retry("چند المان مشابه پیدا شد؛ اجرا برای ایمنی متوقف می‌شود")
            is NodeMatcher.Result.Found -> {
                val performed = try {
                    performOnNode(match.node, step)
                } finally {
                    @Suppress("DEPRECATION")
                    match.node.recycle()
                }
                if (performed) StepResult.Success
                else StepResult.Retry("المان پیدا شد اما عمل را نپذیرفت")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun targetRoots(targetPackage: String): List<AccessibilityNodeInfo> {
        val roots = runCatching {
            val matching = mutableListOf<AccessibilityNodeInfo>()
            windows.mapNotNull { it.root }.forEach { root ->
                if (root.packageName?.toString() == targetPackage) matching += root else root.recycle()
            }
            matching
        }.getOrDefault(emptyList())
        if (roots.isNotEmpty()) return roots
        val active = rootInActiveWindow ?: return emptyList()
        return if (active.packageName?.toString() == targetPackage) listOf(active) else {
            active.recycle()
            emptyList()
        }
    }

    private fun performOnNode(node: AccessibilityNodeInfo, step: AutomationStep): Boolean = when (step.type) {
        ActionType.CLICK -> performClickable(node, AccessibilityNodeInfo.ACTION_CLICK, false)
        ActionType.LONG_CLICK -> performClickable(node, AccessibilityNodeInfo.ACTION_LONG_CLICK, true)
        ActionType.SELECT -> performSelect(node)
        ActionType.SET_TEXT -> {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step.value.orEmpty())
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) ||
                (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) &&
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        }
        ActionType.SET_PROGRESS -> step.value?.toFloatOrNull()?.let { value ->
            val arguments = Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
            }
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments)
        } ?: false
        ActionType.SCROLL_UP -> performScroll(
            node,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            step.targetPosition,
            vertical = true,
        )
        ActionType.SCROLL_DOWN -> performScroll(
            node,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            step.targetPosition,
            vertical = true,
        )
        ActionType.SCROLL_LEFT -> performScroll(
            node,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            step.targetPosition,
            vertical = false,
        )
        ActionType.SCROLL_RIGHT -> performScroll(
            node,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            step.targetPosition,
            vertical = false,
        )
        ActionType.BACK -> false
    }

    @Suppress("DEPRECATION")
    private fun performClickable(
        node: AccessibilityNodeInfo,
        action: Int,
        requireLongClickable: Boolean,
    ): Boolean {
        var current = AccessibilityNodeInfo.obtain(node)
        repeat(7) {
            val acceptsAction = if (requireLongClickable) current.isLongClickable else current.isClickable
            if ((acceptsAction || supportsAction(current, action)) && current.performAction(action)) {
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
    private fun performSelect(node: AccessibilityNodeInfo): Boolean {
        var current = AccessibilityNodeInfo.obtain(node)
        repeat(7) {
            if (supportsAction(current, AccessibilityNodeInfo.ACTION_SELECT) &&
                current.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            ) {
                current.recycle()
                return true
            }
            if ((current.isClickable || supportsAction(current, AccessibilityNodeInfo.ACTION_CLICK)) &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
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
    private fun performScroll(
        node: AccessibilityNodeInfo,
        directionalAction: Int,
        fallbackAction: Int,
        targetPosition: Int?,
        vertical: Boolean,
    ): Boolean {
        val scrollable = SelectorFactory.nearestScrollable(node)
        return try {
            val exactPositionPerformed = targetPosition?.let { position ->
                val arguments = Bundle().apply {
                    putInt(
                        if (vertical) AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT
                        else AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT,
                        position,
                    )
                }
                scrollable.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id,
                    arguments,
                )
            } ?: false
            exactPositionPerformed || scrollable.performAction(directionalAction) ||
                scrollable.performAction(fallbackAction)
        } finally {
            scrollable.recycle()
        }
    }

    private fun supportsAction(node: AccessibilityNodeInfo, actionId: Int): Boolean =
        node.actionList.any { it.id == actionId }

    private fun safeRunnable(stage: String, block: () -> Unit): Runnable = Runnable {
        try {
            block()
        } catch (error: Exception) {
            reportServiceError(stage, error)
        }
    }

    private fun reportServiceError(stage: String, error: Exception) {
        Log.e(TAG, "$stage failed", error)
        runCatching {
            val report = buildString {
                appendLine("زمان: ${System.currentTimeMillis()}")
                appendLine("بخش: $stage")
                appendLine("اندروید: ${Build.VERSION.SDK_INT}")
                appendLine(error.stackTraceToString())
            }
            filesDir.resolve("last-service-error.txt").writeText(report)
        }
        (state as? SessionState.Recording)?.lastWarning = "یک رویداد ناسازگار رد شد؛ ضبط ادامه دارد"
        runCatching { updateOverlay() }
        val now = System.currentTimeMillis()
        if (now - lastErrorToastAt > ERROR_TOAST_INTERVAL_MS) {
            lastErrorToastAt = now
            Toast.makeText(this, "یک رویداد قابل خواندن نبود؛ ضبط متوقف نشد", Toast.LENGTH_LONG).show()
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
                flushAllPending()
                current.workflowId
            }
            is SessionState.Replaying -> current.workflow.id
            is SessionState.Outcome -> current.workflowId
            SessionState.Idle -> null
        }
        handler.removeCallbacks(commitTextRunnable)
        handler.removeCallbacks(commitProgressRunnable)
        handler.removeCallbacks(commitScrollRunnable)
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
            flushAllPending()
            repository.removeLastStep(recording.workflowId)
            recording.recordedCount = repository.get(recording.workflowId)?.steps?.size ?: 0
            updateOverlay()
        }
        binding.backButton.setOnClickListener {
            val recording = state as? SessionState.Recording ?: return@setOnClickListener
            flushAllPending()
            recording.ignoreBackUntil = System.currentTimeMillis() + 1_000
            appendRecordedStep(recording, AutomationStep(type = ActionType.BACK))
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        binding.stopButton.setOnClickListener { stopSession(openEditor = true) }

        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = overlayX
            y = overlayY
        }
        installOverlayDrag(binding, params)
        runCatching {
            windowManager.addView(binding.root, params)
            overlayBinding = binding
            overlayParams = params
            overlayAdded = true
        }
    }

    private fun installOverlayDrag(
        binding: OverlayControllerBinding,
        params: WindowManager.LayoutParams,
    ) {
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        binding.dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = (resources.displayMetrics.widthPixels - binding.root.width).coerceAtLeast(0)
                    val maxY = (resources.displayMetrics.heightPixels - binding.root.height).coerceAtLeast(0)
                    params.x = (startX + (event.rawX - downRawX).toInt()).coerceIn(0, maxX)
                    params.y = (startY + (event.rawY - downRawY).toInt()).coerceIn(0, maxY)
                    overlayX = params.x
                    overlayY = params.y
                    if (overlayAdded) runCatching { windowManager.updateViewLayout(binding.root, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun hideOverlay() {
        val binding = overlayBinding ?: return
        if (overlayAdded) runCatching { windowManager.removeView(binding.root) }
        overlayAdded = false
        overlayBinding = null
        overlayParams = null
    }

    private fun updateOverlay() {
        val binding = overlayBinding ?: return
        when (val current = state) {
            is SessionState.Recording -> {
                binding.modeIndicator.setBackgroundResource(R.drawable.bg_status_off)
                binding.modeTitle.setText(R.string.overlay_recording)
                val count = getString(R.string.recorded_count, current.recordedCount)
                binding.modeDetail.text = current.lastWarning?.let { "$count\n$it" } ?: count
                binding.recordingTools.visibility = View.VISIBLE
                binding.undoButton.isEnabled = current.recordedCount > 0
                binding.stopButton.setText(R.string.stop_recording)
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
                binding.recordingTools.visibility = View.GONE
                binding.stopButton.setText(R.string.stop_running)
            }
            is SessionState.Outcome -> {
                binding.modeIndicator.setBackgroundResource(
                    if (current.success) R.drawable.bg_status_on else R.drawable.bg_status_off,
                )
                binding.modeTitle.setText(
                    if (current.success) R.string.overlay_done else R.string.overlay_failed,
                )
                binding.modeDetail.text = current.message
                binding.recordingTools.visibility = View.GONE
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
            var pendingProgress: PendingProgress? = null,
            var pendingScroll: PendingScroll? = null,
            var lastActionKey: String? = null,
            var lastActionAt: Long = 0,
            var lastClickAt: Long = 0,
            var ignoreBackUntil: Long = 0,
            var lastWarning: String? = null,
            val scrollPositions: MutableMap<String, ScrollPosition> = mutableMapOf(),
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
    private data class PendingProgress(val selector: ElementSelector, val value: Float)
    private data class PendingScroll(
        val type: ActionType,
        val selector: ElementSelector,
        val targetPosition: Int?,
    )
    private data class ScrollPosition(val x: Int?, val y: Int?)

    enum class CommandRequestResult {
        STARTED,
        QUEUED_UNTIL_CONNECTED,
        DISABLED,
        FAILED,
    }

    private enum class PendingCommandType { RECORD, REPLAY }

    private data class PendingCommand(
        val type: PendingCommandType,
        val workflowId: String,
        val requestedAt: Long = System.currentTimeMillis(),
    )

    companion object {
        private const val TEXT_DEBOUNCE_MS = 650L
        private const val VALUE_DEBOUNCE_MS = 550L
        private const val SELECT_AFTER_CLICK_WINDOW_MS = 500L
        private const val ACTION_SETTLE_MS = 650L
        private const val RETRY_MS = 350L
        private const val STEP_TIMEOUT_MS = 15_000L
        private const val ERROR_TOAST_INTERVAL_MS = 5_000L
        private const val COMMAND_TIMEOUT_MS = 30_000L
        private const val TAG = "AutoPAccessibility"
        private val commandLock = Any()

        @Volatile
        private var pendingCommand: PendingCommand? = null

        @Volatile
        var instance: AutomationAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null

        fun requestRecording(context: Context, workflowId: String): CommandRequestResult =
            requestCommand(context, PendingCommandType.RECORD, workflowId)

        fun requestReplay(context: Context, workflowId: String): CommandRequestResult =
            requestCommand(context, PendingCommandType.REPLAY, workflowId)

        private fun requestCommand(
            context: Context,
            type: PendingCommandType,
            workflowId: String,
        ): CommandRequestResult {
            instance?.let { service ->
                val started = when (type) {
                    PendingCommandType.RECORD -> service.startRecording(workflowId)
                    PendingCommandType.REPLAY -> service.startReplay(workflowId)
                }
                return if (started) CommandRequestResult.STARTED else CommandRequestResult.FAILED
            }
            if (!isEnabledInSettings(context)) return CommandRequestResult.DISABLED
            synchronized(commandLock) {
                pendingCommand = PendingCommand(type, workflowId)
            }
            return CommandRequestResult.QUEUED_UNTIL_CONNECTED
        }

        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, AutomationAccessibilityService::class.java)
            val secureSettingMatch = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
                    .split(':')
                    .mapNotNull(ComponentName::unflattenFromString)
                    .any { component ->
                        component.packageName == expected.packageName &&
                            component.className == expected.className
                    }
            }.getOrDefault(false)
            if (secureSettingMatch) return true

            return runCatching {
                val manager = context.getSystemService(AccessibilityManager::class.java)
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                    val info = it.resolveInfo?.serviceInfo ?: return@any false
                    val className = if (info.name.startsWith('.')) info.packageName + info.name else info.name
                    info.packageName == expected.packageName && className == expected.className
                }
            }.getOrDefault(false)
        }
    }
}

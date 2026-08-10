package io.github.devnav59.autop.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import io.github.devnav59.autop.data.ElementSelector
import io.github.devnav59.autop.data.NodeHint

object SelectorFactory {
    private const val MAX_DEPTH = 8
    private const val MAX_ANCESTORS = 5
    private const val MAX_TEXT_LENGTH = 160
    private const val MAX_CONTEXT_LABELS = 6

    fun fromNode(node: AccessibilityNodeInfo, targetPackage: String): ElementSelector {
        val editable = node.isEditable
        val ancestry = readAncestry(node)
        return ElementSelector(
            packageName = node.packageName?.toString().orEmpty().ifBlank { targetPackage },
            viewId = clean(node.viewIdResourceName),
            className = clean(node.className?.toString()),
            // Text inside an input changes after recording, so it must never identify that input.
            text = if (editable) null else clean(node.text?.toString()),
            contentDescription = clean(node.contentDescription?.toString()),
            hintText = clean(node.hintText?.toString()),
            descendantLabels = labelsFromDescendants(node),
            siblingLabels = siblingLabels(node),
            editable = editable,
            clickable = node.isClickable || supportsAction(node, AccessibilityNodeInfo.ACTION_CLICK),
            scrollable = node.isScrollable,
            range = node.rangeInfo != null,
            path = ancestry.path,
            ancestors = ancestry.hints,
        )
    }

    /**
     * Event sources are sometimes a TextView/Icon child while its parent owns ACTION_CLICK.
     * Capturing the actionable parent plus its descendant label is stable for bottom navigation.
     * The returned node is owned by the caller.
     */
    @Suppress("DEPRECATION")
    fun nearestActionable(node: AccessibilityNodeInfo, action: Int): AccessibilityNodeInfo {
        var current = AccessibilityNodeInfo.obtain(node)
        repeat(MAX_DEPTH) {
            val accepts = when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> current.isClickable || supportsAction(current, action)
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> current.isLongClickable || supportsAction(current, action)
                AccessibilityNodeInfo.ACTION_SELECT -> current.isSelected || supportsAction(current, action)
                else -> supportsAction(current, action)
            }
            if (accepts) return current
            val parent = current.parent ?: return current
            current.recycle()
            current = parent
        }
        return current
    }

    /** Returns an owned copy that the caller must recycle. */
    @Suppress("DEPRECATION")
    fun nearestScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = AccessibilityNodeInfo.obtain(node)
        repeat(MAX_DEPTH) {
            if (current.isScrollable ||
                supportsAction(current, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ||
                supportsAction(current, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            ) {
                return current
            }
            val parent = current.parent ?: return current
            current.recycle()
            current = parent
        }
        return current
    }

    /** Returns null when neither this node nor an ancestor exposes RangeInfo. */
    @Suppress("DEPRECATION")
    fun nearestRange(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = AccessibilityNodeInfo.obtain(node)
        repeat(MAX_DEPTH) {
            if (current.rangeInfo != null) return current
            val parent = current.parent
            current.recycle()
            current = parent ?: return null
        }
        current.recycle()
        return null
    }

    private data class Ancestry(val path: List<Int>, val hints: List<NodeHint>)

    @Suppress("DEPRECATION")
    private fun readAncestry(node: AccessibilityNodeInfo): Ancestry {
        val leafToRootPath = mutableListOf<Int>()
        val hints = mutableListOf<NodeHint>()
        var current = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        while (depth++ < MAX_DEPTH) {
            val parent = current.parent ?: break
            leafToRootPath += childIndex(parent, current)
            if (hints.size < MAX_ANCESTORS) {
                hints += NodeHint(
                    viewId = clean(parent.viewIdResourceName),
                    className = clean(parent.className?.toString()),
                    text = clean(parent.text?.toString()),
                    contentDescription = clean(parent.contentDescription?.toString()),
                )
            }
            current.recycle()
            current = parent
        }
        current.recycle()
        return Ancestry(leafToRootPath.asReversed().filter { it >= 0 }, hints)
    }

    @Suppress("DEPRECATION")
    private fun labelsFromDescendants(node: AccessibilityNodeInfo): List<String> {
        val labels = linkedSetOf<String>()
        fun visit(parent: AccessibilityNodeInfo, depth: Int) {
            if (depth > 2 || labels.size >= MAX_CONTEXT_LABELS) return
            for (index in 0 until parent.childCount) {
                if (labels.size >= MAX_CONTEXT_LABELS) break
                val child = parent.getChild(index) ?: continue
                clean(child.contentDescription?.toString())?.let(labels::add)
                clean(child.text?.toString())?.let(labels::add)
                clean(child.hintText?.toString())?.let(labels::add)
                visit(child, depth + 1)
                child.recycle()
            }
        }
        visit(node, 1)
        return labels.toList()
    }

    @Suppress("DEPRECATION")
    private fun siblingLabels(node: AccessibilityNodeInfo): List<String> {
        val parent = node.parent ?: return emptyList()
        val labels = linkedSetOf<String>()
        for (index in 0 until parent.childCount) {
            if (labels.size >= MAX_CONTEXT_LABELS) break
            val sibling = parent.getChild(index) ?: continue
            if (sibling != node) {
                clean(sibling.contentDescription?.toString())?.let(labels::add)
                clean(sibling.text?.toString())?.let(labels::add)
                if (labels.size < MAX_CONTEXT_LABELS) {
                    labelsFromDescendants(sibling).take(2).forEach(labels::add)
                }
            }
            sibling.recycle()
        }
        parent.recycle()
        return labels.take(MAX_CONTEXT_LABELS)
    }

    @Suppress("DEPRECATION")
    private fun childIndex(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo): Int {
        for (index in 0 until parent.childCount) {
            val candidate = parent.getChild(index) ?: continue
            val isSame = candidate == child
            candidate.recycle()
            if (isSame) return index
        }
        return -1
    }

    private fun supportsAction(node: AccessibilityNodeInfo, actionId: Int): Boolean =
        node.actionList.any { it.id == actionId }

    private fun clean(value: String?): String? = value
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(MAX_TEXT_LENGTH)
        ?.takeIf { it.isNotBlank() }
}

package io.github.devnav59.autop.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import io.github.devnav59.autop.data.ElementSelector
import io.github.devnav59.autop.data.NodeHint

object SelectorFactory {
    private const val MAX_DEPTH = 7
    private const val MAX_ANCESTORS = 4
    private const val MAX_TEXT_LENGTH = 160

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
            editable = editable,
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            path = ancestry.path,
            ancestors = ancestry.hints,
        )
    }

    /** Returns an owned copy that the caller must recycle. */
    @Suppress("DEPRECATION")
    fun nearestScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = AccessibilityNodeInfo.obtain(node)
        repeat(MAX_DEPTH) {
            if (current.isScrollable) return current
            val parent = current.parent ?: return current
            current.recycle()
            current = parent
        }
        return current
    }

    private data class Ancestry(val path: List<Int>, val hints: List<NodeHint>)

    @Suppress("DEPRECATION")
    private fun readAncestry(node: AccessibilityNodeInfo): Ancestry {
        val leafToRootPath = mutableListOf<Int>()
        val hints = mutableListOf<NodeHint>()
        var current = AccessibilityNodeInfo.obtain(node)
        repeat(MAX_DEPTH) {
            val parent = current.parent ?: return@repeat
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
    private fun childIndex(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo): Int {
        for (index in 0 until parent.childCount) {
            val candidate = parent.getChild(index) ?: continue
            val isSame = candidate == child
            candidate.recycle()
            if (isSame) return index
        }
        return -1
    }

    private fun clean(value: String?): String? = value
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(MAX_TEXT_LENGTH)
        ?.takeIf { it.isNotBlank() }
}

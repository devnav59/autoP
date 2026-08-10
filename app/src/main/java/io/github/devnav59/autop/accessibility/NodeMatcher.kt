package io.github.devnav59.autop.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import io.github.devnav59.autop.data.ElementSelector
import io.github.devnav59.autop.data.NodeHint
import java.text.Normalizer

/**
 * Finds a unique accessibility node using semantic properties only. Screen bounds are never read.
 * Position in the hierarchy has a deliberately tiny weight and cannot override semantic identity.
 */
object NodeMatcher {
    private const val MAX_NODES = 5_000
    private const val MAX_DEPTH = 64
    private const val UNIQUE_MARGIN = 8
    private const val MAX_CONTEXT_LABELS = 8

    sealed class Result {
        data class Found(val node: AccessibilityNodeInfo, val score: Int) : Result()
        data class NotFound(val bestScore: Int) : Result()
        data class Ambiguous(val bestScore: Int, val secondScore: Int) : Result()
    }

    @Suppress("DEPRECATION")
    fun findUnique(selector: ElementSelector, roots: List<AccessibilityNodeInfo>): Result {
        var visited = 0
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        var secondScore = Int.MIN_VALUE

        fun visit(node: AccessibilityNodeInfo, path: MutableList<Int>, depth: Int) {
            if (visited++ >= MAX_NODES || depth > MAX_DEPTH) return
            val score = score(selector, node, path)
            if (score > bestScore) {
                bestNode?.recycle()
                secondScore = bestScore
                bestScore = score
                bestNode = AccessibilityNodeInfo.obtain(node)
            } else if (score > secondScore) {
                secondScore = score
            }

            for (index in 0 until node.childCount) {
                if (visited >= MAX_NODES) break
                val child = node.getChild(index) ?: continue
                path += index
                visit(child, path, depth + 1)
                path.removeAt(path.lastIndex)
                child.recycle()
            }
        }

        roots.forEach { visit(it, mutableListOf(), 0) }
        val minimum = minimumScore(selector)
        val winner = bestNode
        if (winner == null || bestScore < minimum) {
            winner?.recycle()
            return Result.NotFound(bestScore.coerceAtLeast(0))
        }
        if (secondScore >= minimum && bestScore - secondScore < UNIQUE_MARGIN) {
            winner.recycle()
            return Result.Ambiguous(bestScore, secondScore)
        }
        return Result.Found(winner, bestScore)
    }

    private fun score(
        selector: ElementSelector,
        node: AccessibilityNodeInfo,
        candidatePath: List<Int>,
    ): Int {
        val packageName = node.packageName?.toString()
        if (!packageName.isNullOrBlank() && packageName != selector.packageName) return Int.MIN_VALUE

        var score = 0
        val nodeId = node.viewIdResourceName
        if (!selector.viewId.isNullOrBlank()) {
            if (selector.viewId != nodeId) return Int.MIN_VALUE
            score += 100
        }

        val selectorClass = normalized(selector.className)
        val nodeClass = normalized(node.className?.toString())
        if (selectorClass != null && selectorClass == nodeClass) score += 18

        val selectorDescription = normalized(selector.contentDescription)
        val nodeDescription = normalized(node.contentDescription?.toString())
        val descriptionMatched = selectorDescription != null && selectorDescription == nodeDescription
        if (descriptionMatched) score += 65

        val selectorText = normalized(selector.text)
        val nodeText = normalized(node.text?.toString())
        val textMatched = selectorText != null && selectorText == nodeText
        if (textMatched) score += 55

        val selectorHint = normalized(selector.hintText)
        val nodeHint = normalized(node.hintText?.toString())
        val hintMatched = selectorHint != null && selectorHint == nodeHint
        if (hintMatched) score += 55

        val expectedDescendants = selector.descendantLabels.mapNotNull(::normalized).toSet()
        val candidateDescendants = if (expectedDescendants.isEmpty()) {
            emptySet()
        } else {
            descendantLabels(node)
        }
        val descendantMatches = expectedDescendants.intersect(candidateDescendants).size
        if (descendantMatches > 0) score += 52 + (descendantMatches - 1).coerceAtMost(2) * 12

        val expectedSiblings = selector.siblingLabels.mapNotNull(::normalized).toSet()
        if (expectedSiblings.isNotEmpty()) {
            val siblingMatches = expectedSiblings.intersect(siblingLabels(node)).size
            score += siblingMatches.coerceAtMost(4) * 5
        }

        if (selector.editable == node.isEditable) score += 8
        if (selector.clickable == (node.isClickable || supportsAction(node, AccessibilityNodeInfo.ACTION_CLICK))) score += 5
        if (selector.scrollable == node.isScrollable) score += 5
        if (selector.range == (node.rangeInfo != null)) score += 7

        if (selector.ancestors.isNotEmpty()) score += ancestorScore(selector.ancestors, node)
        if (
            selector.path.isNotEmpty() &&
            candidatePath.size >= selector.path.size &&
            selector.path == candidatePath.takeLast(selector.path.size)
        ) {
            score += 4
        }

        // Without a resource ID, at least one available direct semantic label must match.
        if (selector.viewId.isNullOrBlank()) {
            val hasDirectSemantic = selectorDescription != null || selectorText != null ||
                selectorHint != null || expectedDescendants.isNotEmpty()
            val directMatched = descriptionMatched || textMatched || hintMatched || descendantMatches > 0
            if (hasDirectSemantic && !directMatched) return Int.MIN_VALUE
        }
        return score
    }

    @Suppress("DEPRECATION")
    private fun ancestorScore(expected: List<NodeHint>, node: AccessibilityNodeInfo): Int {
        var score = 0
        var current = node.parent
        for (hint in expected.take(5)) {
            val candidate = current ?: break
            if (!hint.viewId.isNullOrBlank() && hint.viewId == candidate.viewIdResourceName) score += 20
            if (sameNonBlank(hint.contentDescription, candidate.contentDescription?.toString())) score += 14
            if (sameNonBlank(hint.text, candidate.text?.toString())) score += 12
            if (sameNonBlank(hint.className, candidate.className?.toString())) score += 3
            val next = candidate.parent
            candidate.recycle()
            current = next
        }
        current?.recycle()
        return score
    }

    @Suppress("DEPRECATION")
    private fun descendantLabels(node: AccessibilityNodeInfo): Set<String> {
        val labels = linkedSetOf<String>()
        fun visit(parent: AccessibilityNodeInfo, depth: Int) {
            if (depth > 2 || labels.size >= MAX_CONTEXT_LABELS) return
            for (index in 0 until parent.childCount) {
                if (labels.size >= MAX_CONTEXT_LABELS) break
                val child = parent.getChild(index) ?: continue
                normalized(child.contentDescription?.toString())?.let(labels::add)
                normalized(child.text?.toString())?.let(labels::add)
                normalized(child.hintText?.toString())?.let(labels::add)
                visit(child, depth + 1)
                child.recycle()
            }
        }
        visit(node, 1)
        return labels
    }

    @Suppress("DEPRECATION")
    private fun siblingLabels(node: AccessibilityNodeInfo): Set<String> {
        val parent = node.parent ?: return emptySet()
        val labels = linkedSetOf<String>()
        for (index in 0 until parent.childCount) {
            if (labels.size >= MAX_CONTEXT_LABELS) break
            val sibling = parent.getChild(index) ?: continue
            if (sibling != node) {
                normalized(sibling.contentDescription?.toString())?.let(labels::add)
                normalized(sibling.text?.toString())?.let(labels::add)
                if (labels.size < MAX_CONTEXT_LABELS) descendantLabels(sibling).take(2).forEach(labels::add)
            }
            sibling.recycle()
        }
        parent.recycle()
        return labels
    }

    private fun minimumScore(selector: ElementSelector): Int = when {
        !selector.viewId.isNullOrBlank() -> 95
        !selector.contentDescription.isNullOrBlank() -> 62
        !selector.text.isNullOrBlank() || !selector.hintText.isNullOrBlank() -> 52
        selector.descendantLabels.isNotEmpty() -> 50
        selector.ancestors.any {
            !it.viewId.isNullOrBlank() || !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank()
        } -> 42
        selector.clickable || selector.editable || selector.scrollable || selector.range -> 28
        else -> Int.MAX_VALUE
    }

    private fun supportsAction(node: AccessibilityNodeInfo, actionId: Int): Boolean =
        node.actionList.any { it.id == actionId }

    private fun sameNonBlank(first: String?, second: String?): Boolean {
        val a = normalized(first) ?: return false
        return a == normalized(second)
    }

    private fun normalized(value: String?): String? = value
        ?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
        ?.replace('\u200c', ' ')
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
}

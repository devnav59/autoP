package io.github.devnav59.autop.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import io.github.devnav59.autop.data.ElementSelector
import io.github.devnav59.autop.data.NodeHint
import java.text.Normalizer

/**
 * Finds a unique accessibility node using semantic properties only. Screen bounds are never read.
 * The hierarchy path has a deliberately tiny weight and cannot make an otherwise unsafe match pass.
 */
object NodeMatcher {
    private const val MAX_NODES = 5_000
    private const val MAX_DEPTH = 64
    private const val UNIQUE_MARGIN = 8

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
        if (selectorDescription != null && selectorDescription == nodeDescription) score += 65

        val selectorText = normalized(selector.text)
        val nodeText = normalized(node.text?.toString())
        if (selectorText != null && selectorText == nodeText) score += 55

        val selectorHint = normalized(selector.hintText)
        val nodeHint = normalized(node.hintText?.toString())
        if (selectorHint != null && selectorHint == nodeHint) score += 55

        if (selector.editable == node.isEditable) score += 8
        if (selector.clickable == node.isClickable) score += 4
        if (selector.scrollable == node.isScrollable) score += 5

        if (selector.ancestors.isNotEmpty()) {
            score += ancestorScore(selector.ancestors, node)
        }
        if (
            selector.path.isNotEmpty() &&
            candidatePath.size >= selector.path.size &&
            selector.path == candidatePath.takeLast(selector.path.size)
        ) {
            score += 6
        }

        // A semantic field without an ID must actually match; class/path alone are not enough.
        if (selector.viewId.isNullOrBlank()) {
            val directSemanticMatched =
                (selectorDescription != null && selectorDescription == nodeDescription) ||
                    (selectorText != null && selectorText == nodeText) ||
                    (selectorHint != null && selectorHint == nodeHint)
            val hasDirectSemantic = selectorDescription != null || selectorText != null || selectorHint != null
            if (hasDirectSemantic && !directSemanticMatched) return Int.MIN_VALUE
        }
        return score
    }

    @Suppress("DEPRECATION")
    private fun ancestorScore(expected: List<NodeHint>, node: AccessibilityNodeInfo): Int {
        var score = 0
        var current = node.parent
        for (hint in expected.take(4)) {
            val candidate = current ?: break
            if (!hint.viewId.isNullOrBlank() && hint.viewId == candidate.viewIdResourceName) {
                score += 20
            }
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

    private fun minimumScore(selector: ElementSelector): Int = when {
        !selector.viewId.isNullOrBlank() -> 95
        !selector.contentDescription.isNullOrBlank() -> 62
        !selector.text.isNullOrBlank() || !selector.hintText.isNullOrBlank() -> 52
        selector.ancestors.any {
            !it.viewId.isNullOrBlank() || !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank()
        } -> 48
        else -> Int.MAX_VALUE
    }

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

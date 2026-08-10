package io.github.devnav59.autop.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ActionType {
    CLICK,
    LONG_CLICK,
    SET_TEXT,
    SCROLL_UP,
    SCROLL_DOWN,
    SCROLL_LEFT,
    SCROLL_RIGHT,
    BACK,
}

data class NodeHint(
    val viewId: String? = null,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        putNullable("viewId", viewId)
        putNullable("className", className)
        putNullable("text", text)
        putNullable("contentDescription", contentDescription)
    }

    companion object {
        fun fromJson(json: JSONObject) = NodeHint(
            viewId = json.nullableString("viewId"),
            className = json.nullableString("className"),
            text = json.nullableString("text"),
            contentDescription = json.nullableString("contentDescription"),
        )
    }
}

data class ElementSelector(
    val packageName: String,
    val viewId: String? = null,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hintText: String? = null,
    val editable: Boolean = false,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    /** Root-to-leaf accessibility-tree indexes. This is only a low-weight tie breaker, never a coordinate. */
    val path: List<Int> = emptyList(),
    /** Nearest parent first. */
    val ancestors: List<NodeHint> = emptyList(),
) {
    val semanticStrength: Int
        get() = when {
            !viewId.isNullOrBlank() -> 4
            !contentDescription.isNullOrBlank() -> 3
            !text.isNullOrBlank() || !hintText.isNullOrBlank() -> 2
            ancestors.any { !it.viewId.isNullOrBlank() || !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() } -> 1
            else -> 0
        }

    fun stableKey(): String = listOf(
        packageName,
        viewId.orEmpty(),
        className.orEmpty(),
        contentDescription.orEmpty(),
        text.orEmpty(),
        hintText.orEmpty(),
        if (editable) "editable" else "",
        ancestors.joinToString("/") { "${it.viewId}|${it.className}|${it.text}|${it.contentDescription}" },
    ).joinToString("::")

    fun displayName(): String {
        val idTail = viewId?.substringAfterLast('/')
        return contentDescription?.takeIf { it.isNotBlank() }
            ?: text?.takeIf { it.isNotBlank() }
            ?: hintText?.takeIf { it.isNotBlank() }
            ?: idTail?.takeIf { it.isNotBlank() }
            ?: className?.substringAfterLast('.')
            ?: ""
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        putNullable("viewId", viewId)
        putNullable("className", className)
        putNullable("text", text)
        putNullable("contentDescription", contentDescription)
        putNullable("hintText", hintText)
        put("editable", editable)
        put("clickable", clickable)
        put("scrollable", scrollable)
        put("path", JSONArray().apply { path.forEach { put(it) } })
        put("ancestors", JSONArray().apply { ancestors.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): ElementSelector {
            val pathJson = json.optJSONArray("path") ?: JSONArray()
            val ancestorsJson = json.optJSONArray("ancestors") ?: JSONArray()
            return ElementSelector(
                packageName = json.optString("packageName"),
                viewId = json.nullableString("viewId"),
                className = json.nullableString("className"),
                text = json.nullableString("text"),
                contentDescription = json.nullableString("contentDescription"),
                hintText = json.nullableString("hintText"),
                editable = json.optBoolean("editable"),
                clickable = json.optBoolean("clickable"),
                scrollable = json.optBoolean("scrollable"),
                path = buildList { for (index in 0 until pathJson.length()) add(pathJson.optInt(index, -1)) }
                    .filter { it >= 0 },
                ancestors = buildList {
                    for (index in 0 until ancestorsJson.length()) {
                        ancestorsJson.optJSONObject(index)?.let { add(NodeHint.fromJson(it)) }
                    }
                },
            )
        }
    }
}

data class AutomationStep(
    val id: String = UUID.randomUUID().toString(),
    val type: ActionType,
    val selector: ElementSelector? = null,
    val value: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        putNullable("selector", selector?.toJson())
        putNullable("value", value)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): AutomationStep? {
            val type = runCatching { ActionType.valueOf(json.optString("type")) }.getOrNull() ?: return null
            return AutomationStep(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                type = type,
                selector = json.optJSONObject("selector")?.let(ElementSelector::fromJson),
                value = json.nullableString("value"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            )
        }
    }
}

data class Workflow(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val targetPackage: String,
    val targetAppLabel: String,
    val steps: List<AutomationStep> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("targetPackage", targetPackage)
        put("targetAppLabel", targetAppLabel)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): Workflow? {
            val name = json.optString("name")
            val targetPackage = json.optString("targetPackage")
            if (name.isBlank() || targetPackage.isBlank()) return null
            val stepsJson = json.optJSONArray("steps") ?: JSONArray()
            return Workflow(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = name,
                targetPackage = targetPackage,
                targetAppLabel = json.optString("targetAppLabel", targetPackage),
                steps = buildList {
                    for (index in 0 until stepsJson.length()) {
                        stepsJson.optJSONObject(index)?.let { AutomationStep.fromJson(it) }?.let(::add)
                    }
                },
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            )
        }
    }
}

internal fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}

internal fun JSONObject.nullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() }
}

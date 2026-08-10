package io.github.devnav59.autop.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Tiny, explicit JSON store. Workflows never leave app-private storage.
 * Every mutation reloads the file so the Activity and AccessibilityService stay consistent.
 */
class WorkflowRepository(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun getAll(): List<Workflow> = synchronized(lock) {
        readUnsafe().sortedByDescending { it.updatedAt }
    }

    fun get(id: String): Workflow? = synchronized(lock) {
        readUnsafe().firstOrNull { it.id == id }
    }

    fun save(workflow: Workflow): Workflow = synchronized(lock) {
        val all = readUnsafe().toMutableList()
        val index = all.indexOfFirst { it.id == workflow.id }
        val updated = workflow.copy(updatedAt = System.currentTimeMillis())
        if (index >= 0) all[index] = updated else all.add(updated)
        writeUnsafe(all)
        updated
    }

    fun appendStep(workflowId: String, step: AutomationStep): Workflow? = synchronized(lock) {
        mutateUnsafe(workflowId) { workflow -> workflow.copy(steps = workflow.steps + step) }
    }

    fun removeLastStep(workflowId: String): Workflow? = synchronized(lock) {
        mutateUnsafe(workflowId) { workflow ->
            workflow.copy(steps = if (workflow.steps.isEmpty()) emptyList() else workflow.steps.dropLast(1))
        }
    }

    fun removeStep(workflowId: String, stepId: String): Workflow? = synchronized(lock) {
        mutateUnsafe(workflowId) { it.copy(steps = it.steps.filterNot { step -> step.id == stepId }) }
    }

    fun clearSteps(workflowId: String): Workflow? = synchronized(lock) {
        mutateUnsafe(workflowId) { it.copy(steps = emptyList()) }
    }

    fun delete(workflowId: String) = synchronized(lock) {
        val all = readUnsafe().filterNot { it.id == workflowId }
        writeUnsafe(all)
    }

    fun export(workflowId: String): String? = synchronized(lock) {
        readUnsafe().firstOrNull { it.id == workflowId }?.toJson()?.toString(2)
    }

    private fun mutateUnsafe(workflowId: String, block: (Workflow) -> Workflow): Workflow? {
        val all = readUnsafe().toMutableList()
        val index = all.indexOfFirst { it.id == workflowId }
        if (index < 0) return null
        val changed = block(all[index]).copy(updatedAt = System.currentTimeMillis())
        all[index] = changed
        writeUnsafe(all)
        return changed
    }

    private fun readUnsafe(): List<Workflow> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val schema = root.optInt("schema", 1)
            if (schema > SCHEMA_VERSION) return@runCatching emptyList()
            val array = root.optJSONArray("workflows") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { Workflow.fromJson(it) }?.let(::add)
                }
            }
        }.getOrElse {
            // Preserve malformed data for manual recovery instead of silently overwriting it.
            runCatching {
                val backup = File(file.parentFile, "workflows-corrupt-${System.currentTimeMillis()}.json")
                file.copyTo(backup, overwrite = false)
            }
            emptyList()
        }
    }

    private fun writeUnsafe(workflows: List<Workflow>) {
        val root = JSONObject().apply {
            put("schema", SCHEMA_VERSION)
            put("workflows", JSONArray().apply { workflows.forEach { put(it.toJson()) } })
        }
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(root.toString(2).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    companion object {
        private const val FILE_NAME = "workflows.json"
        private const val SCHEMA_VERSION = 1
        private val lock = Any()
    }
}

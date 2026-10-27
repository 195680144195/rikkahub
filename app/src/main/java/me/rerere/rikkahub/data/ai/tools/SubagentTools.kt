package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SubagentManager
import kotlin.uuid.Uuid

/**
 * 子智能体工具：spawn 立即返回，子任务后台执行；
 * 完成/失败/停止后由 SubagentManager 把「交付」写回主会话。
 */
fun createSubagentTools(
    manager: SubagentManager,
    parentId: Uuid,
): List<Tool> = listOf(
    Tool(
        name = "subagent_spawn",
        description = """
            Delegate a task to a sub-agent. Returns immediately; the sub-agent runs in the background
            and its delivery summary will be posted back into this conversation when it finishes.
            Title is REQUIRED and should be a short task name. Use subagent_list to check progress.
            Do not pass the whole conversation history in context; only essential background.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "子任务标题，必填，简短，如“调研 Rust 异步生态”")
                    })
                    put("task", buildJsonObject {
                        put("type", "string")
                        put("description", "交给子代理的完整任务说明")
                    })
                    put("context", buildJsonObject {
                        put("type", "string")
                        put("description", "可选的必要背景，不要贴整段对话")
                    })
                },
                required = listOf("title", "task"),
            )
        },
        needsApproval = { true },
        execute = { raw ->
            val p = raw as JsonObject
            val title = p.str("title")?.trim().orEmpty()
            val task = p.str("task")?.trim().orEmpty()
            require(title.isNotEmpty()) { "title 必填" }
            require(task.isNotEmpty()) { "task 必填" }
            val run = manager.spawn(parentId = parentId, title = title, task = task, context = p.str("context"))
            listOf(UIMessagePart.Text(buildJsonObject {
                put("id", run.id.toString())
                put("title", run.title)
                put("status", "running")
            }.toString()))
        },
    ),
    Tool(
        name = "subagent_list",
        description = "List sub-agents spawned from this conversation with their status.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { }, required = emptyList())
        },
        execute = {
            val runs = manager.runs.value.filter { it.parentId == parentId }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("count", runs.size)
                put("agents", buildJsonArray {
                    runs.forEach { r ->
                        add(buildJsonObject {
                            put("id", r.id.toString())
                            put("title", r.title)
                            put("status", r.status)
                        })
                    }
                })
            }.toString()))
        },
    ),
)

private fun JsonObject.str(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

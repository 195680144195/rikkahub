package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceManager

private const val MW_MAX_OUTPUT = 32 * 1024
private const val MW_TIMEOUT_MAX_SECONDS = 600L

/**
 * 多工作区工具：让一次对话可以同时操作所有已配置的工作区，
 * 不需要在助手设置里二选一。
 *
 * - workspace_list  列出所有工作区
 * - workspace_exec  在指定工作区执行命令
 * - workspace_read  读指定工作区的文件
 * - workspace_write 写指定工作区的文件
 *
 * 工作区参数支持「名称」或「id」，不区分大小写。
 */
suspend fun createMultiWorkspaceTools(
    workspaceRepository: WorkspaceRepository,
): List<Tool> {
    suspend fun all(): List<WorkspaceEntity> = workspaceRepository.listFlow().first()

    suspend fun resolve(key: String): WorkspaceEntity {
        val list = all()
        if (list.isEmpty()) error("当前没有任何工作区，请先在 扩展 → 工作区 中创建")
        val k = key.trim()
        list.firstOrNull { it.id == k }?.let { return it }
        val matches = list.filter { it.name.equals(k, ignoreCase = true) }
        when {
            matches.size == 1 -> return matches[0]
            matches.isEmpty() -> error(
                "找不到工作区 \"$k\"。可用：" + list.joinToString { "${it.name}(${it.id})" }
            )
            else -> error(
                "\"$k\" 匹配到多个工作区，请改用 id：" + matches.joinToString { "${it.name}(${it.id})" }
            )
        }
    }

    return listOf(
        Tool(
            name = "workspace_list",
            description = """
                List all configured workspaces (local proot and SSH) with their id, name, type and root.
                Use this first when you need to work in more than one workspace.
            """.trimIndent().replace("\n", " "),
            parameters = { InputSchema.Obj(properties = buildJsonObject { }, required = emptyList()) },
            execute = {
                val list = all()
                val arr = buildJsonArray {
                    list.forEach { w ->
                        add(buildJsonObject {
                            put("id", w.id)
                            put("name", w.name)
                            put("type", w.type)
                            put("root", w.root)
                            put("shell_status", w.shellStatus)
                        })
                    }
                }
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("count", list.size)
                    put("workspaces", arr)
                }.toString()))
            },
        ),

        Tool(
            name = "workspace_exec",
            description = """
                Run a shell command in a chosen workspace (not only the assistant's bound one).
                Pass workspace as name or id. cwd is relative to the workspace root. Default timeout 30s, max 600s.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("workspace", buildJsonObject {
                            put("type", "string")
                            put("description", "工作区名称或 id（可用 workspace_list 查询）")
                        })
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "要执行的命令")
                        })
                        put("cwd", buildJsonObject {
                            put("type", "string")
                            put("description", "工作区内的相对目录，默认根目录")
                        })
                        put("timeout", buildJsonObject {
                            put("type", "integer")
                            put("description", "超时秒数，默认 30，最大 600")
                        })
                    },
                    required = listOf("workspace", "command"),
                )
            },
            needsApproval = { true },
            execute = { raw ->
                val p = raw.jsonObject()
                val ws = resolve(p.str("workspace") ?: error("workspace is required"))
                val command = p.str("command") ?: error("command is required")
                val cwd = p.str("cwd").orEmpty()
                val timeout = (p.str("timeout")?.toLongOrNull() ?: 30L)
                    .coerceIn(1L, MW_TIMEOUT_MAX_SECONDS) * 1000L
                val r = workspaceRepository.executeCommand(ws.id, command, cwd, timeout)
                val out = buildString {
                    append(r.stdout)
                    if (r.stderr.isNotBlank()) append("\n[stderr]\n").append(r.stderr)
                    if (r.timedOut) append("\n[timed out]")
                }.trim().ifEmpty { "(no output, exit ${r.exitCode})" }
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("workspace", ws.name)
                    put("exitCode", r.exitCode)
                    put("output", out.take(MW_MAX_OUTPUT))
                }.toString()))
            },
        ),

        Tool(
            name = "workspace_read",
            description = """
                Read a text file from a chosen workspace. Path is relative to the workspace root
                (for local rootfs you can also use an absolute path inside that workspace).
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("workspace", buildJsonObject {
                            put("type", "string")
                            put("description", "工作区名称或 id")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "文件路径")
                        })
                    },
                    required = listOf("workspace", "path"),
                )
            },
            execute = { raw ->
                val p = raw.jsonObject()
                val ws = resolve(p.str("workspace") ?: error("workspace is required"))
                val path = p.str("path") ?: error("path is required")
                val text = workspaceRepository.readText(ws.id, path)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("workspace", ws.name)
                    put("path", path)
                    put("text", text.take(MW_MAX_OUTPUT))
                }.toString()))
            },
        ),

        Tool(
            name = "workspace_write",
            description = """
                Write a text file into a chosen workspace. Path is relative to the workspace root.
                Set overwrite=false to avoid clobbering existing files.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("workspace", buildJsonObject {
                            put("type", "string")
                            put("description", "工作区名称或 id")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "文件路径")
                        })
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "文件内容")
                        })
                        put("overwrite", buildJsonObject {
                            put("type", "boolean")
                            put("description", "是否覆盖已存在文件，默认 true")
                        })
                    },
                    required = listOf("workspace", "path", "text"),
                )
            },
            needsApproval = { true },
            execute = { raw ->
                val p = raw.jsonObject()
                val ws = resolve(p.str("workspace") ?: error("workspace is required"))
                val path = p.str("path") ?: error("path is required")
                val text = p.str("text") ?: error("text is required")
                val overwrite = p.str("overwrite")?.toBooleanStrictOrNull() ?: true
                val entry = workspaceRepository.writeText(ws.id, path, text, overwrite)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("workspace", ws.name)
                    put("path", path)
                    put("size", entry.sizeBytes)
                }.toString()))
            },
        ),
    )
}

private fun kotlinx.serialization.json.JsonElement.jsonObject(): JsonObject = this as JsonObject

private fun JsonObject.str(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

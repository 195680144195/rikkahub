package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.ChatRequestFactory
import me.rerere.rikkahub.data.ai.backgroundTextGenerationParams
import me.rerere.rikkahub.data.ai.retryPolicy
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.ai.provider.ProviderManager
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 子智能体：把一次任务跑成一个独立的子会话，完成后把「交付」写回主会话。
 *
 * 注意：这是第一版（后台执行 + 交付回写）。UI 任务条、自动续跑在后续阶段接入。
 */
class SubagentManager(
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val providerManager: ProviderManager,
    private val appScope: AppScope,
) {
    data class Run(
        val id: Uuid,
        val parentId: Uuid,
        val childId: Uuid?,
        val title: String,
        val status: String,
        val startedAt: Long,
    )

    private val _runs = MutableStateFlow<List<Run>>(emptyList())
    val runs: StateFlow<List<Run>> = _runs.asStateFlow()

    private val jobs = ConcurrentHashMap<Uuid, Job>()

    fun runningOf(parentId: Uuid): List<Run> =
        _runs.value.filter { it.parentId == parentId && it.status == STATUS_RUNNING }

    fun spawn(parentId: Uuid, title: String, task: String, context: String?): Run {
        require(title.isNotBlank()) { "title 不能为空" }
        require(task.isNotBlank()) { "task 不能为空" }
        val run = Run(
            id = Uuid.random(),
            parentId = parentId,
            childId = null,
            title = title.trim(),
            status = STATUS_RUNNING,
            startedAt = System.currentTimeMillis(),
        )
        _runs.update { it + run }
        val job = appScope.launch(Dispatchers.IO) { execute(run, task, context) }
        jobs[run.id] = job
        return run
    }

    fun stop(id: Uuid) {
        jobs.remove(id)?.cancel()
        _runs.update { list -> list.map { if (it.id == id) it.copy(status = STATUS_CANCELLED) else it } }
        _runs.update { list -> list.filterNot { it.id == id } }
    }

    private suspend fun execute(run: Run, task: String, context: String?) {
        var childId: Uuid? = null
        try {
            val settings = settingsStore.settingsFlow.value
            val parent = conversationRepository.getConversationById(run.parentId)
                ?: error("主会话不存在")

            val child = Conversation(
                assistantId = parent.assistantId,
                title = run.title,
                messageNodes = listOf(MessageNode.of(UIMessage.user(task))),
                config = (parent.config ?: ConversationConfig()).copy(
                    kind = "SUBAGENT",
                    parentId = parent.id,
                    subagentTitle = run.title,
                    subagentStatus = STATUS_RUNNING,
                    workspaceId = parent.config?.workspaceId,
                ),
                workspaceCwd = parent.workspaceCwd,
            )
            conversationRepository.insertConversation(child)
            childId = child.id
            _runs.update { list -> list.map { if (it.id == run.id) it.copy(childId = child.id) else it } }

            val requestFactory: ChatRequestFactory = org.koin.java.KoinJavaComponent.getKoin().get()
            val ctx = requestFactory.createContext(settings, child)
            // 子代理不能再派生子代理
            val tools = ctx.tools.filterNot { it.name.startsWith(SUBAGENT_TOOL_PREFIX) }

            val system = buildString {
                if (ctx.assistant.systemPrompt.isNotBlank()) {
                    append(ctx.assistant.systemPrompt).append("\n\n")
                }
                append("[子智能体规范]\n")
                append(settings.subagentPrompt)
                if (!context.isNullOrBlank()) {
                    append("\n\n[主会话提供的背景]\n").append(context)
                }
            }
            val params = backgroundTextGenerationParams(ctx.model, child.id)
            val hooks = object : AgentHooks {
                override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                    return StepRequest(
                        messages = listOf(UIMessage.system(system)) + messages,
                        params = params,
                    )
                }
            }

            val finished = runAgent(
                request = AgentRequest(
                    provider = providerManager.getProviderByType(ctx.providerSetting),
                    providerSetting = ctx.providerSetting,
                    messages = listOf(UIMessage.user(task)),
                    tools = tools,
                    stream = false,
                    maxSteps = settings.subagentMaxSteps.coerceIn(1, 32),
                    retry = settings.retryPolicy(),
                ),
                hooks = hooks,
            ).filterIsInstance<AgentEvent.Finished>().last()

            val text = finished.message.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .ifBlank { "（子代理没有输出文本）" }

            val doneChild = conversationRepository.getConversationById(child.id) ?: child
            conversationRepository.updateConversation(
                doneChild.copy(
                    messageNodes = doneChild.messageNodes + MessageNode.of(finished.message),
                    updateAt = Instant.now(),
                    config = doneChild.config?.copy(subagentStatus = STATUS_DONE),
                )
            )
            deliver(run, text, STATUS_DONE)
        } catch (e: CancellationException) {
            deliver(run, "（子代理已停止）", STATUS_CANCELLED)
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "subagent ${run.id} failed", t)
            deliver(run, "（子代理失败：${t.message ?: t.javaClass.simpleName}）", STATUS_FAILED)
        } finally {
            jobs.remove(run.id)
            _runs.update { list -> list.filterNot { it.id == run.id } }
        }
    }

    private suspend fun deliver(run: Run, text: String, status: String) {
        runCatching {
            val parent = conversationRepository.getConversationById(run.parentId) ?: return
            val header = when (status) {
                STATUS_DONE -> "【子代理交付】${run.title}"
                STATUS_FAILED -> "【子代理失败】${run.title}"
                else -> "【子代理已停止】${run.title}"
            }
            val content = "$header\n\n$text"
            conversationRepository.updateConversation(
                parent.copy(
                    messageNodes = parent.messageNodes + MessageNode.of(UIMessage.user(content)),
                    updateAt = Instant.now(),
                )
            )
        }.onFailure { Log.e(TAG, "deliver failed", it) }
    }

    companion object {
        private const val TAG = "SubagentManager"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val SUBAGENT_TOOL_PREFIX = "subagent"
    }
}

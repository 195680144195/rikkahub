package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
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

    data class PendingApproval(
        val runId: Uuid,
        val toolName: String,
        val argsPreview: String,
        val deferred: kotlinx.coroutines.CompletableDeferred<Boolean>,
    )

    private val _runs = MutableStateFlow<List<Run>>(emptyList())
    val runs: StateFlow<List<Run>> = _runs.asStateFlow()

    private val _pendingApprovals = MutableStateFlow<Map<Uuid, PendingApproval>>(emptyMap())
    val pendingApprovals: StateFlow<Map<Uuid, PendingApproval>> = _pendingApprovals.asStateFlow()

    fun approve(runId: Uuid, allow: Boolean) {
        _pendingApprovals.value[runId]?.deferred?.complete(allow)
    }

    init {
        appScope.launch {
            runCatching { conversationRepository.markInterruptedSubagents() }
                .onFailure { Log.e(TAG, "mark interrupted failed", it) }
        }
    }

    private val jobs = ConcurrentHashMap<Uuid, Job>()

    // 自动续跑：500ms 防抖 + 每会话最多 5 次无用户输入的续跑
    private data class Delivery(val text: String, val meta: kotlinx.serialization.json.JsonObject)

    private val deliveryBuffer = ConcurrentHashMap<Uuid, MutableList<Delivery>>()
    private val flushJobs = ConcurrentHashMap<Uuid, Job>()
    private val autoContinueCount = ConcurrentHashMap<Uuid, Int>()

    fun runningOf(parentId: Uuid): List<Run> =
        _runs.value.filter { it.parentId == parentId && it.status == STATUS_RUNNING }

    fun spawn(parentId: Uuid, title: String, task: String, context: String?): Run {
        require(title.isNotBlank()) { "title 不能为空" }
        require(task.isNotBlank()) { "task 不能为空" }
        val max = settingsStore.settingsFlow.value.subagentMaxConcurrent.coerceAtLeast(1)
        val running = _runs.value.count { it.parentId == parentId && it.status == STATUS_RUNNING }
        require(running < max) { "并发已达上限 $max，请等待其他子代理完成" }
        val run = Run(
            id = Uuid.random(),
            parentId = parentId,
            childId = null,
            title = title.trim(),
            status = STATUS_RUNNING,
            startedAt = System.currentTimeMillis(),
        )
        _runs.update { it + run }
        autoContinueCount[parentId] = 0
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
            // 第一版：子代理在后台跑，没有人能点审批，所以子工具一律自动放行；
            // 审批 UI 在下一阶段接到任务条上。
            val tools = ctx.tools
                .filterNot { it.name.startsWith(SUBAGENT_TOOL_PREFIX) }
                .map { original ->
                    original.copy(
                        needsApproval = { false },
                        execute = { args ->
                            val needs = runCatching { original.needsApproval(args) }.getOrDefault(false)
                            if (needs) {
                                val allowed = awaitApproval(run, original.name, args.toString().take(200))
                                if (!allowed) {
                                    error("用户拒绝了子代理的工具调用：${original.name}")
                                }
                            }
                            original.execute(args)
                        },
                    )
                }

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
            // 关键：必须把工具声明放进 params，否则模型看不到工具，会退化成输出 <tool_call> 文本
            val params = backgroundTextGenerationParams(ctx.model, child.id).copy(tools = tools)
            val hooks = object : AgentHooks {
                override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                    return StepRequest(
                        messages = listOf(UIMessage.system(system)) + messages,
                        params = params,
                    )
                }
            }

            // 收集每一步的助手消息（同一 id 覆盖），完整保留工具调用过程
            val stepMessages = LinkedHashMap<String, UIMessage>()
            var finishedEvent: AgentEvent.Finished? = null
            runAgent(
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
            ).collect { event ->
                when (event) {
                    is AgentEvent.MessageUpdated -> stepMessages[event.message.id.toString()] = event.message
                    is AgentEvent.Finished -> finishedEvent = event
                    else -> Unit
                }
            }
            val finished = finishedEvent ?: run {
                deliver(run, "（子代理未完成：可能被中断或等待审批）", STATUS_FAILED, childId)
                return
            }

            val text = finished.message.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .ifBlank { "（子代理没有输出文本）" }

            val doneChild = conversationRepository.getConversationById(child.id) ?: child
            val allNodes = doneChild.messageNodes +
                stepMessages.values.map { MessageNode.of(it) }
            conversationRepository.updateConversation(
                doneChild.copy(
                    messageNodes = allNodes,
                    updateAt = Instant.now(),
                    config = doneChild.config?.copy(subagentStatus = STATUS_DONE),
                )
            )
            deliver(run, text, STATUS_DONE, childId)
        } catch (e: CancellationException) {
            deliver(run, "（子代理已停止）", STATUS_CANCELLED, childId)
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "subagent ${run.id} failed", t)
            deliver(run, "（子代理失败：${t.message ?: t.javaClass.simpleName}）", STATUS_FAILED, childId)
        } finally {
            jobs.remove(run.id)
            _runs.update { list -> list.filterNot { it.id == run.id } }
        }
    }

    private suspend fun awaitApproval(run: Run, toolName: String, argsPreview: String): Boolean {
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        _pendingApprovals.update {
            it + (run.id to PendingApproval(run.id, toolName, argsPreview, deferred))
        }
        return try {
            deferred.await()
        } finally {
            _pendingApprovals.update { it - run.id }
        }
    }

    private suspend fun deliver(run: Run, text: String, status: String, childId: Uuid?) {
        runCatching {
            val header = when (status) {
                STATUS_DONE -> "【子代理交付】${run.title}"
                STATUS_FAILED -> "【子代理失败】${run.title}"
                else -> "【子代理已停止】${run.title}"
            }
            val content = "$header\n\n$text"
            val meta = kotlinx.serialization.json.buildJsonObject {
                put("subagentId", childId?.toString().orEmpty())
                put("subagentTitle", run.title)
                put("subagentStatus", status)
            }
            val delivery = Delivery(content, meta)
            val settings = settingsStore.settingsFlow.value
            if (settings.subagentAutoContinue) {
                scheduleAutoContinue(run.parentId, delivery)
            } else {
                appendDirect(run.parentId, delivery)
            }
        }.onFailure { Log.e(TAG, "deliver failed", it) }
    }

    /** 交付先入缓冲，500ms 内的多条合并成一次续跑 */
    private fun scheduleAutoContinue(parentId: Uuid, delivery: Delivery) {
        deliveryBuffer.getOrPut(parentId) { mutableListOf() }.add(delivery)
        flushJobs.remove(parentId)?.cancel()
        flushJobs[parentId] = appScope.launch {
            kotlinx.coroutines.delay(500)
            flushDeliveries(parentId)
        }
    }

    private suspend fun flushDeliveries(parentId: Uuid) {
        val items = deliveryBuffer.remove(parentId) ?: return
        if (items.isEmpty()) return
        val count = autoContinueCount.getOrDefault(parentId, 0)
        if (count >= 5) {
            // 达到自动续跑上限，只落库不再触发模型
            items.forEach { appendDirect(parentId, it) }
            return
        }
        autoContinueCount[parentId] = count + 1
        runCatching {
            val chatService: me.rerere.rikkahub.service.ChatService =
                org.koin.java.KoinJavaComponent.getKoin().get()
            chatService.sendMessage(
                parentId,
                items.map { UIMessagePart.Text(it.text, it.meta) },
                answer = true,
            )
        }.onFailure { Log.e(TAG, "auto continue failed", it) }
    }

    private suspend fun appendDirect(parentId: Uuid, delivery: Delivery) {
        val parent = conversationRepository.getConversationById(parentId) ?: return
        conversationRepository.updateConversation(
            parent.copy(
                messageNodes = parent.messageNodes + MessageNode.of(
                    UIMessage(
                        role = me.rerere.ai.core.MessageRole.USER,
                        parts = listOf(UIMessagePart.Text(delivery.text, delivery.meta)),
                    )
                ),
                updateAt = Instant.now(),
            )
        )
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

package me.rerere.rikkahub.ui.pages.extensions.subagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 子代理会话（只读）：
 * 直接复用主会话的 [ChatMessage] 渲染，保证工具卡片、思考、正文风格完全一致。
 */
@Composable
fun SubagentConversationPage(id: String) {
    val repository: ConversationRepository = koinInject()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var conversation by remember { mutableStateOf<Conversation?>(null) }

    LaunchedEffect(id) {
        runCatching { Uuid.parse(id) }.getOrNull()?.let { uuid ->
            conversation = repository.getConversationById(uuid)
        }
    }

    val nodes = conversation?.messageNodes.orEmpty()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        text = conversation?.title?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.subagent_readonly_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = {
                        val all = nodes.joinToString("\n\n") { node ->
                            node.currentMessage.parts.filterIsInstance<UIMessagePart.Text>()
                                .joinToString("\n") { it.text }
                        }
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("subagent", all))
                    }) { Text(stringResource(R.string.subagent_copy_all)) }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(nodes) { node ->
                ChatMessage(
                    node = node,
                    loading = false,
                    onFork = {},
                    onRegenerate = {},
                    onEdit = {},
                    onShare = {},
                    onDelete = {},
                    onUpdate = {},
                    readOnly = true,
                )
            }
        }
    }
}

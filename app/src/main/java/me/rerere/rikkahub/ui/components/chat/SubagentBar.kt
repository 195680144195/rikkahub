package me.rerere.rikkahub.ui.components.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.ai.subagent.SubagentManager
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 子代理任务条（第一版：单条 / 合并计数 + 全部停止）。
 * 展开动画、逐条审批、历史卡片在后续阶段补。
 */
@Composable
fun SubagentBar(
    conversationId: Uuid,
    modifier: Modifier = Modifier,
) {
    val manager: SubagentManager = koinInject()
    val runs by manager.runs.collectAsStateWithLifecycle()
    val active = runs.filter { it.parentId == conversationId }
    if (active.isEmpty()) return

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (active.size == 1) active.first().title else "${active.size} 个子代理",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { active.forEach { manager.stop(it.id) } }) {
                Text("停止")
            }
        }
    }
}

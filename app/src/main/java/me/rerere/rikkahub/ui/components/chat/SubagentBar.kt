package me.rerere.rikkahub.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.subagent.SubagentManager
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 子代理任务条：
 * - 1 个：直接显示条目（点击进入只读子会话，右侧停止）
 * - ≥2 个：显示「N 个子代理」，点击展开；展开时从底向上生长
 */
@Composable
fun SubagentBar(
    conversationId: Uuid,
    modifier: Modifier = Modifier,
) {
    val manager: SubagentManager = koinInject()
    val navController = LocalNavController.current
    val runs by manager.runs.collectAsStateWithLifecycle()
    val pending by manager.pendingApprovals.collectAsStateWithLifecycle()
    val active = runs.filter { it.parentId == conversationId }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(active.size) {
        if (active.size <= 1) expanded = false
    }

    if (active.isEmpty()) return

    fun open(run: SubagentManager.Run) {
        run.childId?.let { navController.navigate(Screen.SubagentConversation(it.toString())) }
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Column {
            // 头部：单条显示标题，多条显示数量
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = active.size > 1) { expanded = !expanded }
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                if (active.size == 1) {
                    val only = active.first()
                    Text(
                        text = only.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable { open(only) },
                    )
                    val approval = pending[only.id]
                    if (approval != null) {
                        Text(
                            text = "待审批: ${approval.toolName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { manager.approve(only.id, true) }) { Text("✓") }
                        TextButton(onClick = { manager.approve(only.id, false) }) { Text("✗") }
                    }
                    TextButton(onClick = { manager.stop(only.id) }) {
                        Text("停止")
                    }
                } else {
                    Text(
                        text = "${active.size} 个子代理",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { active.forEach { manager.stop(it.id) } }) {
                        Text("全部停止")
                    }
                    Text(
                        text = if (expanded) "⌄" else "⌃",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            // 展开列表：从底向上生长
            AnimatedVisibility(
                visible = expanded && active.size > 1,
                enter = expandVertically(expandFrom = Alignment.Bottom),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
            ) {
                Column {
                    active.forEach { run ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { open(run) }
                                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = run.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            val approval = pending[run.id]
                            if (approval != null) {
                                TextButton(onClick = { manager.approve(run.id, true) }) { Text("✓") }
                                TextButton(onClick = { manager.approve(run.id, false) }) { Text("✗") }
                            }
                            TextButton(onClick = { manager.stop(run.id) }) {
                                Text("停止")
                            }
                        }
                    }
                }
            }
        }
    }
}

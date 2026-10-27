package me.rerere.rikkahub.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
 * 子代理任务条：与输入框同一套视觉（surface + 细边框 + 圆角）。
 * 1 个直接显示；≥2 个合并计数，点击向上展开。
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
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = active.size > 1) { expanded = !expanded }
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(8.dp))

                if (active.size == 1) {
                    val only = active.first()
                    Text(
                        text = only.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable { open(only) },
                    )
                    ApprovalOrStop(
                        approvalTool = pending[only.id]?.toolName,
                        onApprove = { manager.approve(only.id, true) },
                        onReject = { manager.approve(only.id, false) },
                        onStop = { manager.stop(only.id) },
                    )
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${active.size} 个子代理",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "点击展开",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { active.forEach { manager.stop(it.id) } }) {
                        Text("全部停止", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        text = if (expanded) "⌄" else "⌃",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded && active.size > 1,
                enter = expandVertically(expandFrom = Alignment.Bottom),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                ) {
                    active.forEach { run ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { open(run) }
                                .padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = run.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            ApprovalOrStop(
                                approvalTool = pending[run.id]?.toolName,
                                onApprove = { manager.approve(run.id, true) },
                                onReject = { manager.approve(run.id, false) },
                                onStop = { manager.stop(run.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalOrStop(
    approvalTool: String?,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onStop: () -> Unit,
) {
    if (approvalTool != null) {
        Text(
            text = approvalTool,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
        )
        TextButton(onClick = onApprove) { Text("✓", style = MaterialTheme.typography.labelLarge) }
        TextButton(onClick = onReject) { Text("✗", style = MaterialTheme.typography.labelLarge) }
    }
    TextButton(onClick = onStop) { Text("停止", style = MaterialTheme.typography.labelMedium) }
}

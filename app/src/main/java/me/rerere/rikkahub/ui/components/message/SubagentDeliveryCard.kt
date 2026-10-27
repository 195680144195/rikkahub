package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController

/** 子代理交付卡片：标题 / 状态 / 完整内容，点击进入只读子会话 */
@Composable
fun SubagentDeliveryCard(
    part: UIMessagePart.Text,
    modifier: Modifier = Modifier,
) {
    val navController = LocalNavController.current
    val meta = part.metadata
    val id = meta?.get("subagentId")?.jsonPrimitive?.contentOrNull.orEmpty()
    val title = meta?.get("subagentTitle")?.jsonPrimitive?.contentOrNull.orEmpty()
    val status = meta?.get("subagentStatus")?.jsonPrimitive?.contentOrNull.orEmpty()
    val statusText = when (status) {
        "DONE" -> "已完成"
        "FAILED" -> "失败"
        "CANCELLED" -> "已停止"
        "INTERRUPTED" -> "已中断"
        else -> status
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .clickable(enabled = id.isNotBlank()) {
                    navController.navigate(Screen.SubagentConversation(id))
                }
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "子代理 · $title",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            val body = part.text.substringAfter("\n\n", part.text)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            if (id.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "查看 ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

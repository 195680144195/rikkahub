package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * 多工作区工具的子页面：
 * - 总开关
 * - 每个工具单独的「执行前审批」开关
 */
@Composable
fun MultiWorkspaceSettingsPage() {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun update(transform: (Settings) -> Settings) {
        scope.launch { settingsStore.update(transform) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.multi_ws_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CardGroup {
                    switchItem(
                        checked = settings.enableMultiWorkspaceTools,
                        onCheckedChange = { enabled ->
                            update { it.copy(enableMultiWorkspaceTools = enabled) }
                        },
                        headlineContent = { Text(stringResource(R.string.multi_ws_master_title)) },
                        supportingContent = { Text(stringResource(R.string.multi_ws_master_desc)) },
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.multi_ws_tools_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Text(
                    text = stringResource(R.string.multi_ws_tools_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            item {
                CardGroup {
                    approvalItem(
                        name = "workspace_list",
                        titleRes = R.string.multi_ws_tool_list_title,
                        default = false,
                        settings = settings,
                        onToggle = { value ->
                            update { it.copy(multiWorkspaceToolApprovals = it.multiWorkspaceToolApprovals + ("workspace_list" to value)) }
                        },
                    )
                    approvalItem(
                        name = "workspace_exec",
                        titleRes = R.string.multi_ws_tool_exec_title,
                        default = true,
                        settings = settings,
                        onToggle = { value ->
                            update { it.copy(multiWorkspaceToolApprovals = it.multiWorkspaceToolApprovals + ("workspace_exec" to value)) }
                        },
                    )
                    approvalItem(
                        name = "workspace_read",
                        titleRes = R.string.multi_ws_tool_read_title,
                        default = false,
                        settings = settings,
                        onToggle = { value ->
                            update { it.copy(multiWorkspaceToolApprovals = it.multiWorkspaceToolApprovals + ("workspace_read" to value)) }
                        },
                    )
                    approvalItem(
                        name = "workspace_write",
                        titleRes = R.string.multi_ws_tool_write_title,
                        default = true,
                        settings = settings,
                        onToggle = { value ->
                            update { it.copy(multiWorkspaceToolApprovals = it.multiWorkspaceToolApprovals + ("workspace_write" to value)) }
                        },
                    )
                }
            }
        }
    }
}

private fun CardGroupScope.approvalItem(
    name: String,
    titleRes: Int,
    default: Boolean,
    settings: Settings,
    onToggle: (Boolean) -> Unit,
) {
    val checked = settings.multiWorkspaceToolApprovals[name] ?: default
    switchItem(
        checked = checked,
        onCheckedChange = onToggle,
        headlineContent = { Text(stringResource(titleRes)) },
        supportingContent = { Text(stringResource(R.string.multi_ws_needs_approval_desc)) },
    )
}

package me.rerere.rikkahub.ui.pages.extensions.subagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

@Composable
fun SubagentSettingsPage() {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var promptDraft by remember { mutableStateOf(settings.subagentPrompt) }
    var savedPrompt by remember { mutableStateOf(settings.subagentPrompt) }
    if (savedPrompt != settings.subagentPrompt && promptDraft == savedPrompt) {
        // 设置被外部改过时同步一次
        savedPrompt = settings.subagentPrompt
        promptDraft = settings.subagentPrompt
    }

    fun update(transform: (me.rerere.rikkahub.data.datastore.Settings) -> me.rerere.rikkahub.data.datastore.Settings) {
        scope.launch { settingsStore.update(transform) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.subagent_settings_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = {
                        update { it.copy(subagentPrompt = promptDraft) }
                        savedPrompt = promptDraft
                    }) { Text(stringResource(R.string.subagent_save)) }
                },
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
                        checked = settings.enableSubagents,
                        onCheckedChange = { v -> update { it.copy(enableSubagents = v) } },
                        headlineContent = { Text(stringResource(R.string.subagent_enable_title)) },
                        supportingContent = { Text(stringResource(R.string.subagent_enable_desc)) },
                    )
                    switchItem(
                        checked = settings.subagentAutoContinue,
                        onCheckedChange = { v -> update { it.copy(subagentAutoContinue = v) } },
                        headlineContent = { Text(stringResource(R.string.subagent_auto_continue_title)) },
                        supportingContent = { Text(stringResource(R.string.subagent_auto_continue_desc)) },
                    )
                }
            }

            item {
                CardGroup {
                    item(
                        headlineContent = { Text(stringResource(R.string.subagent_concurrency_title)) },
                        supportingContent = { Text(stringResource(R.string.subagent_concurrency_desc)) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    update { it.copy(subagentMaxConcurrent = (it.subagentMaxConcurrent - 1).coerceAtLeast(1)) }
                                }) { Text("-") }
                                Text(settings.subagentMaxConcurrent.toString())
                                TextButton(onClick = {
                                    update { it.copy(subagentMaxConcurrent = (it.subagentMaxConcurrent + 1).coerceAtMost(8)) }
                                }) { Text("+") }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.subagent_max_steps_title)) },
                        supportingContent = { Text(stringResource(R.string.subagent_max_steps_desc)) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    update { it.copy(subagentMaxSteps = (it.subagentMaxSteps - 1).coerceAtLeast(1)) }
                                }) { Text("-") }
                                Text(settings.subagentMaxSteps.toString())
                                TextButton(onClick = {
                                    update { it.copy(subagentMaxSteps = (it.subagentMaxSteps + 1).coerceAtMost(32)) }
                                }) { Text("+") }
                            }
                        },
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = promptDraft,
                    onValueChange = { promptDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.subagent_prompt_title)) },
                    supportingText = { Text(stringResource(R.string.subagent_prompt_desc)) },
                    minLines = 6,
                    maxLines = 14,
                )
            }
        }
    }
}

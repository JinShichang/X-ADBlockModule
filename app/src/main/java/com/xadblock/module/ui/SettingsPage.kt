package com.xadblock.module.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xadblock.module.BuildConfig
import com.xadblock.module.data.Contract

private data class ToggleSetting(val title: String, val icon: ImageVector)

@Composable
internal fun SettingsPage(viewModel: MainViewModel, onExportLogs: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(16.dp)),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        DisplayModeSection(viewModel)
        MatchOptionsSection(viewModel)
        BrowseHistorySection(viewModel)
        WhitelistSection(viewModel)
        LoggingSection(viewModel)
        AboutSection(viewModel, onExportLogs)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayModeSection(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    SettingsSection("过滤行为") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = settings.displayMode == Contract.DISPLAY_MODE_REMOVE,
                onClick = { viewModel.updateSettings(settings.copy(displayMode = Contract.DISPLAY_MODE_REMOVE)) },
                shape = SegmentedButtonDefaults.itemShape(0, 2)
            ) { Text("直接移除") }
            SegmentedButton(
                selected = settings.displayMode == Contract.DISPLAY_MODE_MARK,
                onClick = { viewModel.updateSettings(settings.copy(displayMode = Contract.DISPLAY_MODE_MARK)) },
                shape = SegmentedButtonDefaults.itemShape(1, 2)
            ) { Text("保留占位") }
        }
    }
}

@Composable
private fun MatchOptionsSection(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val items = listOf(
        ToggleSetting("检查用户名", Icons.Filled.PersonSearch) to settings.optUsername,
        ToggleSetting("Emoji 内容", Icons.Filled.EmojiEmotions) to settings.optEmoji,
        ToggleSetting("异常符号", Icons.Filled.DataObject) to settings.optSpecialChars,
        ToggleSetting("Grok 回复", Icons.Filled.AutoAwesome) to settings.optGrok,
        ToggleSetting("不过滤已认证账号", Icons.Filled.VerifiedUser) to settings.skipVerified
    )
    SettingsSection("匹配范围") {
        items.forEachIndexed { index, item ->
            SettingToggle(item.first, item.second) { checked ->
                val updated = when (index) {
                    0 -> settings.copy(optUsername = checked)
                    1 -> settings.copy(optEmoji = checked)
                    2 -> settings.copy(optSpecialChars = checked)
                    3 -> settings.copy(optGrok = checked)
                    else -> settings.copy(skipVerified = checked)
                }
                viewModel.updateSettings(updated)
            }
            if (index != items.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp))
        }
    }
}

@Composable
private fun BrowseHistorySection(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    SettingsSection("浏览历史") {
        SettingToggle(
            ToggleSetting("记录帖子浏览历史", Icons.Filled.Visibility),
            settings.recordViews
        ) { checked ->
            viewModel.updateSettings(settings.copy(recordViews = checked))
        }
        Text(
            "记录点开的帖子链接与正文摘要，保留最近 7 天；可在首页「浏览历史」中查看、搜索或清空",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WhitelistSection(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    SettingsSection("用户白名单") {
        ElevatedCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "命中白名单用户时跳过过滤",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { showDialog = true }) { Text("添加") }
                }
                settings.whitelistUsers.sorted().forEach { user ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            user,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = { viewModel.removeWhitelistUser(user) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "移除$user")
                        }
                    }
                }
                if (settings.whitelistUsers.isEmpty()) {
                    Text(
                        "暂未添加用户，可从过滤历史直接加入",
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    if (showDialog) {
        AddWhitelistDialog(
            onDismiss = { showDialog = false },
            onConfirm = {
                viewModel.addWhitelistUser(it)
                showDialog = false
            }
        )
    }
}

@Composable
private fun LoggingSection(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    SettingsSection("诊断日志") {
        SettingToggle(
            ToggleSetting("启用诊断日志", Icons.Filled.DataObject),
            settings.loggingEnabled
        ) { checked ->
            viewModel.updateSettings(settings.copy(loggingEnabled = checked))
        }
        Text(
            if (settings.loggingEnabled) "记录模块与 hook 的运行日志"
            else "已关闭，不会产生新的模块或 hook 日志",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private const val PROJECT_URL = "https://github.com/JinShichang/X-ADBlockModule"

@Composable
private fun AboutSection(viewModel: MainViewModel, onExportLogs: () -> Unit) {
    val context = LocalContext.current
    SettingsSection("关于") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure { viewModel.reportFailure("无法打开项目主页", it) }
                    }
            ) {
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Link, contentDescription = null) },
                    headlineContent = { Text("GitHub 主页") },
                    supportingContent = { Text(PROJECT_URL, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                    headlineContent = { Text("版本号") },
                    supportingContent = {
                        Text("X-ADBlock ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    }
                )
            }
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExportLogs)
            ) {
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Share, contentDescription = null) },
                    headlineContent = { Text("导出日志") },
                    supportingContent = { Text("将模块与 hook 日志保存到你选择的位置") }
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Column(modifier = Modifier.fillMaxWidth(), content = { content() })
    }
}

@Composable
private fun SettingToggle(setting: ToggleSetting, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange),
        leadingContent = { Icon(setting.icon, contentDescription = null) },
        headlineContent = { Text(setting.title) },
        trailingContent = { Switch(checked, null) }
    )
}

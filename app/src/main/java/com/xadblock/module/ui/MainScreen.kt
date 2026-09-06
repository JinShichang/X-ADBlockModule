package com.xadblock.module.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xadblock.module.data.SubscriptionEntity

internal enum class MainTab(val title: String, val icon: ImageVector) {
    HOME("首页", Icons.Filled.Home),
    RULES("规则", Icons.AutoMirrored.Filled.LibraryBooks),
    SETTINGS("设置", Icons.Filled.Settings)
}

private data class MainShellState(
    val tab: MainTab,
    val historyVisible: Boolean,
    val browseVisible: Boolean,
    val serviceState: com.xadblock.module.XposedServiceState,
    val snackbarHost: SnackbarHostState
)

private data class MainShellActions(
    val onTabSelect: (MainTab) -> Unit,
    val onOpenHistory: () -> Unit,
    val onCloseHistory: () -> Unit,
    val onOpenBrowse: () -> Unit,
    val onCloseBrowse: () -> Unit,
    val onAddWhitelist: (String) -> Unit,
    val onAddSubscription: () -> Unit,
    val onEditSubscription: (SubscriptionEntity) -> Unit,
    val onAddLocal: () -> Unit,
    val onImportLocal: () -> Unit,
    val onExportLogs: () -> Unit
)

private data class SubscriptionEditorState(
    val visible: Boolean,
    val subscription: SubscriptionEntity?
)

private data class MainDialogState(
    val subscriptionEditor: SubscriptionEditorState,
    val showLocalEditor: Boolean
)

private data class MainDialogActions(
    val onDismissSubscription: () -> Unit,
    val onDismissLocal: () -> Unit,
    val onAddLocal: (LocalRuleDraft) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XADBlockApp(viewModel: MainViewModel) {
    val serviceState by viewModel.serviceState.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var tabName by rememberSaveable { mutableStateOf(MainTab.HOME.name) }
    var historyVisible by rememberSaveable { mutableStateOf(false) }
    var browseVisible by rememberSaveable { mutableStateOf(false) }
    var showSubscriptionEditor by remember { mutableStateOf(false) }
    var editingSubscription by remember { mutableStateOf<SubscriptionEntity?>(null) }
    var showLocalEditor by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val tab = MainTab.valueOf(tabName)

    BackHandler(enabled = historyVisible) { historyVisible = false }
    BackHandler(enabled = browseVisible) { browseVisible = false }
    MessageEffect(message, snackbarHost, viewModel::dismissMessage)
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("无法读取所选文件")
        }.onSuccess(viewModel::importLocalText)
            .onFailure { viewModel.reportFailure("导入失败", it) }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) viewModel.exportLogs(uri)
    }

    val shellState = MainShellState(tab, historyVisible, browseVisible, serviceState, snackbarHost)
    val shellActions = MainShellActions(
        onTabSelect = { tabName = it.name },
        onOpenHistory = {
            browseVisible = false
            historyVisible = true
        },
        onCloseHistory = { historyVisible = false },
        onOpenBrowse = {
            historyVisible = false
            browseVisible = true
        },
        onCloseBrowse = { browseVisible = false },
        onAddWhitelist = viewModel::addWhitelistUser,
        onAddSubscription = {
            editingSubscription = null
            showSubscriptionEditor = true
        },
        onEditSubscription = {
            editingSubscription = it
            showSubscriptionEditor = true
        },
        onAddLocal = { showLocalEditor = true },
        onImportLocal = { importLauncher.launch("text/plain") },
        onExportLogs = { exportLauncher.launch("xadblock-logs.txt") }
    )
    MainPageHost(viewModel, shellState, shellActions)
    MainDialogHost(
        MainDialogState(
            SubscriptionEditorState(showSubscriptionEditor, editingSubscription),
            showLocalEditor
        ),
        viewModel,
        MainDialogActions(
            onDismissSubscription = { showSubscriptionEditor = false },
            onDismissLocal = { showLocalEditor = false },
            onAddLocal = {
                viewModel.addLocalRule(it.kind, it.value)
                showLocalEditor = false
            }
        )
    )
}

@Composable
private fun MessageEffect(
    message: MainViewModel.UiMessage?,
    snackbarHost: SnackbarHostState,
    onDismiss: () -> Unit
) {
    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        snackbarHost.showSnackbar(current.text)
        onDismiss()
    }
}

@Composable
private fun MainDialogHost(
    state: MainDialogState,
    viewModel: MainViewModel,
    actions: MainDialogActions
) {
    SubscriptionEditorHost(state.subscriptionEditor, viewModel, actions.onDismissSubscription)
    if (!state.showLocalEditor) return
    AddLocalRuleDialog(actions.onDismissLocal, actions.onAddLocal)
}

@Composable
private fun MainPageHost(
    viewModel: MainViewModel,
    state: MainShellState,
    actions: MainShellActions
) {
    var confirmClearBrowse by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            MainTopBar(
                MainTopBarState(state.tab, state.historyVisible, state.browseVisible),
                MainTopBarActions(
                    onBack = if (state.browseVisible) actions.onCloseBrowse else actions.onCloseHistory,
                    onClearHistory = viewModel::clearHistory,
                    onClearBrowse = { confirmClearBrowse = true }
                )
            )
        },
        bottomBar = {
            MainNavigation(state.tab) {
                actions.onCloseHistory()
                actions.onCloseBrowse()
                actions.onTabSelect(it)
            }
        },
        snackbarHost = { SnackbarHost(state.snackbarHost) }
    ) { padding ->
        if (confirmClearBrowse) {
            ClearBrowseHistoryDialog(
                onDismiss = { confirmClearBrowse = false },
                onConfirm = {
                    confirmClearBrowse = false
                    viewModel.clearBrowseHistory()
                }
            )
        }
        if (state.browseVisible) {
            BrowseHistoryPage(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else if (state.historyVisible) {
            HistoryPage(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize().padding(padding),
                onAddWhitelist = actions.onAddWhitelist
            )
        } else {
            AnimatedContent(
                targetState = state.tab,
                modifier = Modifier.fillMaxSize().padding(padding),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "main-tabs"
            ) { target ->
                when (target) {
                    MainTab.HOME -> DashboardPage(
                        viewModel,
                        state.serviceState,
                        actions.onOpenHistory,
                        actions.onOpenBrowse
                    )
                    MainTab.RULES -> RulesPage(
                        viewModel,
                        RulesPageActions(
                            actions.onAddSubscription,
                            actions.onEditSubscription,
                            actions.onAddLocal,
                            actions.onImportLocal
                        )
                    )
                    MainTab.SETTINGS -> SettingsPage(viewModel, actions.onExportLogs)
                }
            }
        }
    }
}

private data class MainTopBarState(
    val tab: MainTab,
    val historyVisible: Boolean,
    val browseVisible: Boolean
) {
    val overlayVisible: Boolean get() = historyVisible || browseVisible
}

private data class MainTopBarActions(
    val onBack: () -> Unit,
    val onClearHistory: () -> Unit,
    val onClearBrowse: () -> Unit
)

@Composable
private fun ClearBrowseHistoryDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清空浏览历史") },
        text = { Text("将删除全部帖子浏览记录，操作无法撤销。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("清空") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(state: MainTopBarState, actions: MainTopBarActions) {
    TopAppBar(
        title = {
            Text(
                when {
                    state.browseVisible -> "浏览历史"
                    state.historyVisible -> "过滤历史"
                    state.tab == MainTab.HOME -> "X-ADBlock"
                    else -> state.tab.title
                }
            )
        },
        navigationIcon = {
            if (state.overlayVisible) {
                IconButton(onClick = actions.onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回首页")
                }
            }
        },
        actions = {
            if (state.browseVisible) {
                IconButton(onClick = actions.onClearBrowse) {
                    Icon(Icons.Filled.Delete, contentDescription = "清空浏览历史")
                }
            } else if (state.historyVisible) {
                IconButton(onClick = actions.onClearHistory) {
                    Icon(Icons.Filled.Delete, contentDescription = "清空过滤历史")
                }
            }
        }
    )
}

@Composable
private fun MainNavigation(selected: MainTab, onSelect: (MainTab) -> Unit) {
    NavigationBar {
        MainTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.title) }
            )
        }
    }
}

@Composable
private fun SubscriptionEditorHost(
    state: SubscriptionEditorState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    if (!state.visible) return
    SubscriptionDialog(state.subscription, onDismiss) { draft ->
        val subscription = state.subscription
        if (subscription == null) {
            viewModel.addSubscription(draft.name, draft.url, draft.secondaryUrl)
        } else {
            val changedUrl = draft.url != subscription.url
            viewModel.updateSubscription(
                subscription.copy(
                    name = draft.name.ifBlank { draft.url },
                    url = draft.url,
                    secondaryUrl = draft.secondaryUrl.ifBlank { null },
                    etag = if (changedUrl) "" else subscription.etag
                )
            )
        }
        onDismiss()
    }
}

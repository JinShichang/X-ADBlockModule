package com.xadblock.module.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.xadblock.module.XAdApplication
import com.xadblock.module.XposedServiceState
import com.xadblock.module.data.AppDatabase
import com.xadblock.module.data.BLOCK_EVENT_HISTORY_LIMIT
import com.xadblock.module.data.BlockEventEntity
import com.xadblock.module.data.HeartbeatEntity
import com.xadblock.module.data.ModuleLogger
import com.xadblock.module.data.POST_VIEW_HISTORY_LIMIT
import com.xadblock.module.data.POST_VIEW_RETENTION_MS
import com.xadblock.module.data.PostViewEntity
import com.xadblock.module.data.PostViewEvents
import com.xadblock.module.data.RuleRepository
import com.xadblock.module.data.RuleEntity
import com.xadblock.module.data.RuleSnapshotStore
import com.xadblock.module.data.SettingsStore
import com.xadblock.module.data.SubscriptionEntity
import com.xadblock.module.data.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val repository = RuleRepository(application)

    val subscriptions: StateFlow<List<SubscriptionEntity>> = db.subscriptionDao().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rules: StateFlow<List<RuleEntity>> = db.ruleDao().allFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val heartbeat: StateFlow<HeartbeatEntity?> = db.heartbeatDao().latest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allHistory: StateFlow<List<BlockEventEntity>> = db.blockEventDao()
        .all(BLOCK_EVENT_HISTORY_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Posts the user opened in X; newest first, capped by age (7 days) and row count. */
    val browseHistory: StateFlow<List<PostViewEntity>> = db.postViewDao()
        .recent(POST_VIEW_HISTORY_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Row count only, so the home screen does not have to hold every record. */
    val browseCount: StateFlow<Int> = db.postViewDao().countFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _browseQuery = MutableStateFlow("")
    val browseQuery: StateFlow<String> = _browseQuery

    val browseResults: StateFlow<List<PostViewEntity>> =
        combine(browseHistory, _browseQuery) { entries, query ->
            val keyword = query.trim()
            if (keyword.isEmpty()) entries else entries.filter { PostViewEvents.matches(it, keyword) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _localCount = MutableStateFlow(0)
    val localCount: StateFlow<Int> = _localCount

    private val _blockTotal = MutableStateFlow(0L)
    val blockTotal: StateFlow<Long> = _blockTotal

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    private val _settings = MutableStateFlow(SettingsStore.load(application))
    val settings: StateFlow<SettingsStore.Settings> = _settings

    val serviceState: StateFlow<XposedServiceState> = XAdApplication.serviceState

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message

    data class UiMessage(val text: String, val isError: Boolean)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            db.blockEventDao().trim(BLOCK_EVENT_HISTORY_LIMIT)
            db.postViewDao().deleteOlderThan(System.currentTimeMillis() - POST_VIEW_RETENTION_MS)
            db.postViewDao().trim(POST_VIEW_HISTORY_LIMIT)
        }
        viewModelScope.launch {
            db.ruleDao().allEnabledFlow().collect { rules ->
                _localCount.value = rules.count { it.sourceId == "local" }
            }
        }
        viewModelScope.launch {
            while (true) {
                val app = getApplication<Application>()
                val dbCount = withContext(Dispatchers.IO) {
                    db.blockEventDao().countAll().toLong()
                }
                _blockTotal.value = XAdApplication.hookStats(app) + dbCount
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    fun addSubscription(name: String, url: String, secondaryUrl: String) {
        val normalizedUrl = url.trim()
        if (!normalizedUrl.startsWith("https://")) {
            _message.value = UiMessage("订阅地址必须使用 HTTPS", true)
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.subscriptionDao().upsert(
                        SubscriptionEntity(
                            name = name.ifBlank { normalizedUrl },
                            url = normalizedUrl,
                            secondaryUrl = secondaryUrl.trim().ifBlank { null }
                        )
                    )
                }
                _message.value = UiMessage("云端规则已添加", false)
            } catch (failure: Throwable) {
                reportFailure("添加订阅失败", failure)
            }
        }
    }

    fun updateSubscription(updated: SubscriptionEntity) {
        if (!updated.url.startsWith("https://")) {
            _message.value = UiMessage("订阅地址必须使用 HTTPS", true)
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val current = db.subscriptionDao().allOnce().first { it.id == updated.id }
                    val changed = current.url != updated.url || current.secondaryUrl != updated.secondaryUrl
                    val saved = if (changed) {
                        updated.copy(ruleCount = 0, lastSyncStatus = "", etag = "")
                    } else {
                        updated
                    }
                    db.withTransaction {
                        db.subscriptionDao().upsert(saved)
                        if (changed) db.ruleDao().deleteBySource("sub:${updated.id}")
                    }
                    if (changed) RuleSnapshotStore.rebuild(getApplication())
                    changed
                }
                _message.value = UiMessage("订阅已更新", false)
            } catch (failure: Throwable) {
                reportFailure("更新订阅失败", failure)
            }
        }
    }

    fun deleteSubscription(subscription: SubscriptionEntity) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.subscriptionDao().delete(subscription)
                    db.ruleDao().deleteBySource("sub:${subscription.id}")
                    RuleSnapshotStore.rebuild(getApplication())
                }
                _message.value = UiMessage("订阅已删除", false)
            } catch (failure: Throwable) {
                reportFailure("删除订阅失败", failure)
            }
        }
    }

    fun setSubscriptionEnabled(subscription: SubscriptionEntity, enabled: Boolean) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.subscriptionDao().upsert(subscription.copy(enabled = enabled))
                    RuleSnapshotStore.rebuild(getApplication())
                }
                _message.value = UiMessage(if (enabled) "订阅已启用" else "订阅已停用", false)
            } catch (failure: Throwable) {
                reportFailure("订阅状态保存失败", failure)
            }
        }
    }

    fun syncNow() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                val results = repository.syncAll(force = true)
                val failed = results.count { it.second.status == "error" }
                _message.value = if (failed == 0) {
                    UiMessage("词库同步完成", false)
                } else {
                    UiMessage("有 $failed 个订阅同步失败", true)
                }
            } catch (failure: Throwable) {
                reportFailure("同步失败", failure)
            } finally {
                _syncing.value = false
            }
        }
    }

    fun syncOne(subscription: SubscriptionEntity) {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                val result = repository.syncOne(subscription, force = true)
                _message.value = if (result.status == "error") {
                    UiMessage("${subscription.name}：${result.error}", true)
                } else {
                    UiMessage("${subscription.name} 已同步", false)
                }
            } catch (failure: Throwable) {
                reportFailure("订阅同步失败", failure)
            } finally {
                _syncing.value = false
            }
        }
    }

    fun exportLogs(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ModuleLogger.exportTo(getApplication(), uri)
                }
                _message.value = UiMessage("日志已导出", false)
            } catch (failure: Throwable) {
                reportFailure("日志导出失败", failure)
            }
        }
    }

    fun importLocalText(text: String) {
        viewModelScope.launch {
            try {
                val result = repository.importLocalText(text)
                result.fold(
                    onSuccess = { count -> _message.value = UiMessage("已导入 $count 条本地规则", false) },
                    onFailure = { failure -> reportFailure("导入失败", failure) }
                )
            } catch (failure: Throwable) {
                reportFailure("导入失败", failure)
            }
        }
    }

    fun addLocalRule(kind: String, text: String) {
        viewModelScope.launch {
            repository.addLocalRule(kind, text).fold(
                onSuccess = { _message.value = UiMessage("本地规则已添加", false) },
                onFailure = { reportFailure("添加规则失败", it) }
            )
        }
    }

    fun deleteLocalRule(rule: RuleEntity) {
        viewModelScope.launch {
            try {
                repository.deleteLocalRule(rule)
                _message.value = UiMessage("本地规则已删除", false)
            } catch (failure: Throwable) {
                reportFailure("删除规则失败", failure)
            }
        }
    }

    fun removeLocalRules() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.ruleDao().deleteBySource("local")
                    RuleSnapshotStore.rebuild(getApplication())
                }
                _message.value = UiMessage("本地规则已清空", false)
            } catch (failure: Throwable) {
                reportFailure("清空后发布规则失败", failure)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { db.blockEventDao().clear() }
                _message.value = UiMessage("过滤历史已清空", false)
            } catch (failure: Throwable) {
                reportFailure("清空过滤历史失败", failure)
            }
        }
    }

    fun setBrowseQuery(value: String) {
        _browseQuery.value = value
    }

    fun clearBrowseHistory() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { db.postViewDao().clear() }
                _browseQuery.value = ""
                _message.value = UiMessage("浏览历史已清空", false)
            } catch (failure: Throwable) {
                reportFailure("清空浏览历史失败", failure)
            }
        }
    }

    fun deleteBrowseEntry(entry: PostViewEntity) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { db.postViewDao().delete(entry.postId) }
                _message.value = UiMessage("记录已删除", false)
            } catch (failure: Throwable) {
                reportFailure("删除记录失败", failure)
            }
        }
    }

    fun notifyInfo(text: String) {
        _message.value = UiMessage(text, false)
    }

    fun addWhitelistUser(user: String) {
        val value = user.trim()
        if (value.isBlank()) {
            _message.value = UiMessage("用户名不能为空", true)
            return
        }
        val handles = value.split(Regex("\\s+"))
            .filter { it.startsWith("@") }
            .toSet()
        val entries = if (handles.isEmpty()) setOf(value) else handles
        val current = _settings.value.whitelistUsers
        val merged = current + entries
        if (merged == current) {
            _message.value = UiMessage("用户已在白名单中", false)
            return
        }
        persistSettings(
            _settings.value.copy(whitelistUsers = merged),
            "用户白名单已更新"
        )
    }

    fun removeWhitelistUser(user: String) {
        val remaining = _settings.value.whitelistUsers - user
        if (remaining == _settings.value.whitelistUsers) return
        persistSettings(
            _settings.value.copy(whitelistUsers = remaining),
            "用户白名单已更新"
        )
    }

    fun setEnqueueBackgroundSync(intervalHours: Long) {
        SyncWorker.schedulePeriodic(getApplication(), intervalHours)
    }

    fun updateSettings(newSettings: SettingsStore.Settings) {
        persistSettings(newSettings, "过滤设置已更新")
    }

    private fun persistSettings(
        newSettings: SettingsStore.Settings,
        successMessage: String
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SettingsStore.save(getApplication(), newSettings)
                }
                _settings.value = newSettings
                _message.value = UiMessage(successMessage, false)
            } catch (failure: Throwable) {
                reportFailure("设置保存失败", failure)
            }
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun reportFailure(prefix: String, failure: Throwable) {
        val detail = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
        _message.value = UiMessage("$prefix：$detail", true)
    }
}

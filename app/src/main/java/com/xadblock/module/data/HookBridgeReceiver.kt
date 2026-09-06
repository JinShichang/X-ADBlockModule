package com.xadblock.module.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xadblock.module.XAdApplication

/**
 * Receives block events, heartbeats, and hook log batches broadcast by the injected X process.
 * The channel is a package-scoped exported broadcast (the ContentProvider route
 * is blocked on Android 11+ by package visibility).
 */
class HookBridgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == null) return
        // DB access must never run on the main thread; offload with goAsync.
        val pending = goAsync()
        EXECUTOR.execute {
            try {
                when (intent.action) {
                    Contract.ACTION_BLOCK_EVENTS -> handleBlockEvents(context, intent)
                    Contract.ACTION_VIEW_EVENTS -> handleViewEvents(context, intent)
                    Contract.ACTION_HEARTBEAT -> handleHeartbeat(context, intent)
                    Contract.ACTION_HOOK_LOGS -> handleHookLogs(intent)
                }
            } catch (failure: Throwable) {
                ModuleLogger.log("receiver error: $failure")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "xadblock-receiver").apply { isDaemon = true }
        }
    }

    private fun handleBlockEvents(context: Context, intent: Intent) {
        val payload = intent.getStringExtra(Contract.EXTRA_ITEMS) ?: return
        if (payload.isEmpty()) return
        val dao = AppDatabase.get(context).blockEventDao()
        dao.removeDuplicatePosts()
        var inserted = 0L
        payload.lineSequence().mapNotNull(::parseBlockEvent).forEach { event ->
            if (event.postId != null && dao.containsPost(event.postId)) return@forEach
            dao.insert(event)
            inserted++
        }
        dao.trim(BLOCK_EVENT_HISTORY_LIMIT)
        if (inserted == 0L) return
        XAdApplication.recordBlockCount(context, inserted)
        ModuleLogger.log("block events received: $inserted")
    }

    private fun parseBlockEvent(line: String): BlockEventEntity? {
        val parts = line.split('\t')
        if (parts.size < 2) return null
        return BlockEventEntity(
            sourceId = parts[0],
            preview = parts[1].take(200),
            postId = parts.getOrNull(2)?.takeIf(String::isNotBlank),
            author = parts.getOrNull(3)?.takeIf(String::isNotBlank)?.take(200),
            matchedRule = parts.getOrNull(4)?.takeIf(String::isNotBlank)?.take(200)
        )
    }

    private fun handleViewEvents(context: Context, intent: Intent) {
        val payload = intent.getStringExtra(Contract.EXTRA_ITEMS) ?: return
        if (payload.isEmpty()) return
        val dao = AppDatabase.get(context).postViewDao()
        var stored = 0
        payload.lineSequence().mapNotNull { PostViewEvents.parse(it) }.forEach { view ->
            dao.upsert(view)
            stored++
        }
        if (stored == 0) return
        dao.deleteOlderThan(System.currentTimeMillis() - POST_VIEW_RETENTION_MS)
        dao.trim(POST_VIEW_HISTORY_LIMIT)
        ModuleLogger.log("post views received: $stored")
    }

    private fun handleHeartbeat(context: Context, intent: Intent) {
        val db = AppDatabase.get(context)
        val heartbeat = HeartbeatEntity(
            process = intent.getStringExtra(Contract.EXTRA_PROCESS) ?: "?",
            status = intent.getStringExtra(Contract.EXTRA_STATUS) ?: "?",
            snapshotVersion = intent.getLongExtra(Contract.EXTRA_SNAPSHOT_VERSION, 0L),
            targetVersion = intent.getStringExtra(Contract.EXTRA_TARGET_VERSION) ?: ""
        )
        db.heartbeatDao().deleteAll()
        db.heartbeatDao().insert(heartbeat)
        db.heartbeatDao().trim(20)
        ModuleLogger.log("heartbeat: ${heartbeat.status} proc=${heartbeat.process} " +
                "snap=${heartbeat.snapshotVersion} x=${heartbeat.targetVersion}")
        intent.getStringExtra(Contract.EXTRA_SELFCHECK)?.let { summary ->
            ModuleLogger.log("hook selfcheck: $summary")
        }
        intent.getStringExtra(Contract.EXTRA_LOG)?.let(::handleHookLogBatch)
    }

    private fun handleHookLogs(intent: Intent) {
        intent.getStringExtra(Contract.EXTRA_LOG)?.let(::handleHookLogBatch)
    }

    private fun handleHookLogBatch(lines: String) {
        ModuleLogger.logHookBatch(lines)
    }
}

package com.xadblock.module.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Module-side log writer to the module-private filesDir/logs directory. Hook
 * log batches are routed here by HookBridgeReceiver and share the same file.
 */
object ModuleLogger {
    private const val LOG_DIRECTORY = "logs"
    private const val FILE_NAME = "xadblock_module.log"
    private const val MAX_BYTES = 512 * 1024
    private const val RETAIN_RATIO = 0.6
    private const val QUEUE_CAPACITY = 4096
    private const val FLUSH_WAIT_MILLIS = 2000L
    private const val TRUNCATION_MARKER = "\n... [log truncated] ...\n"
    private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val fileLock = Any()
    private val enabled = AtomicBoolean(true)
    private var started = false
    private var contextRef: Context? = null

    @Synchronized
    fun init(context: Context) {
        if (!started) {
            val appContext = context.applicationContext
            started = true
            enabled.set(SettingsStore.load(appContext).loggingEnabled)
            contextRef = appContext
            Thread { flushLoop() }.apply {
                name = "xadblock-module-log"
                isDaemon = true
                start()
            }
        }
    }

    fun log(message: String) {
        if (!enabled.get()) return
        enqueue("${timestamp()} $message")
    }

    fun logHookBatch(text: String) {
        if (!enabled.get()) return
        text.lineSequence()
            .filter { it.isNotEmpty() }
            .forEach(::enqueue)
    }

    fun exportTo(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        flushPending(appContext)
        val content = synchronized(fileLock) {
            val file = logFile(appContext)
            if (file.exists()) file.readBytes() else ByteArray(0)
        }
        val output = appContext.contentResolver.openOutputStream(uri)
            ?: throw IOException("无法打开导出目标")
        output.use { it.write(content) }
    }

    fun setEnabled(value: Boolean) {
        synchronized(fileLock) {
            enabled.set(value)
            if (!value) queue.clear()
        }
    }

    private fun flushLoop() {
        while (true) {
            try {
                val lines = ArrayList<String>()
                val deadline = System.currentTimeMillis() + FLUSH_WAIT_MILLIS
                while (true) {
                    val line = queue.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                        ?: break
                    lines.add(line)
                    if (System.currentTimeMillis() >= deadline) break
                }
                if (lines.isNotEmpty()) {
                    append(lines.joinToString("\n") + "\n")
                }
            } catch (ignored: InterruptedException) {
                return
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun append(text: String) {
        if (!enabled.get()) return
        val context = contextRef ?: return
        try {
            synchronized(fileLock) {
                if (enabled.get()) appendLocked(context, text)
            }
        } catch (failure: Throwable) {
            if (enabled.get()) {
                android.util.Log.e("X-ADBlock", "module log append failed", failure)
            }
        }
    }

    private fun flushPending(context: Context) {
        synchronized(fileLock) {
            if (!enabled.get()) {
                queue.clear()
                return
            }
            val lines = ArrayList<String>()
            queue.drainTo(lines)
            if (lines.isNotEmpty()) {
                appendLocked(context, lines.joinToString("\n") + "\n")
            }
        }
    }

    private fun appendLocked(context: Context, text: String) {
        val file = logFile(context)
        val directory = file.parentFile ?: throw IOException("日志目录不存在")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("无法创建日志目录: ${directory.absolutePath}")
        }
        FileOutputStream(file, true).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
        }
        trimToLimit(file)
    }

    private fun trimToLimit(file: File) {
        if (file.length() <= MAX_BYTES) return
        val bytes = file.readBytes()
        val keepBytes = (MAX_BYTES * RETAIN_RATIO).toInt()
        val from = (bytes.size - keepBytes).coerceAtLeast(0)
        val retained = bytes.copyOfRange(from, bytes.size)
        val marker = TRUNCATION_MARKER.toByteArray(Charsets.UTF_8)
        val next = marker + retained
        val limited = if (next.size <= MAX_BYTES) next
        else next.copyOfRange(next.size - MAX_BYTES, next.size)
        file.writeBytes(limited)
    }

    private fun logFile(context: Context): File =
        File(File(context.filesDir, LOG_DIRECTORY), FILE_NAME)

    private fun enqueue(line: String) {
        synchronized(fileLock) {
            if (enabled.get()) queue.offer(line)
        }
    }

    private fun timestamp(): String = synchronized(time) {
        time.format(Date())
    }
}

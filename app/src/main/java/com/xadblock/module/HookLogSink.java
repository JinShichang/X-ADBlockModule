package com.xadblock.module;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.xadblock.module.data.Contract;

/**
 * Forwards injected-process log batches to the module app. The module app owns
 * the private log file, so the injected process never writes to shared storage.
 * If the broadcast fails, the lines are retained for the next heartbeat.
 */
public final class HookLogSink {
    private static final String TAG = "[X-ADBlock]";
    private static final int QUEUE_CAPACITY = 4096;
    private static final int MAX_FALLBACK_CHARS = 16 * 1024;
    private static final long FLUSH_WAIT_MILLIS = 2000L;
    private static final BlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile Context context;
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private HookLogSink() {}

    public static void init(Context appContext) {
        context = appContext;
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread flusher = new Thread(HookLogSink::flushLoop, "xadblock-hook-log");
        flusher.setDaemon(true);
        flusher.start();
    }

    public static void log(String message) {
        if (!HookEntry.isLoggingEnabled()) {
            return;
        }
        QUEUE.offer(timestamp() + " hook|" + message);
    }

    public static void clear() {
        QUEUE.clear();
        synchronized (HookLogSink.class) {
            FALLBACK.setLength(0);
        }
    }

    private static void flushLoop() {
        while (true) {
            try {
                ArrayList<String> lines = new ArrayList<>();
                long deadline = System.currentTimeMillis() + FLUSH_WAIT_MILLIS;
                while (true) {
                    String line = QUEUE.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                    if (line == null) {
                        break;
                    }
                    lines.add(line);
                    if (System.currentTimeMillis() >= deadline) {
                        break;
                    }
                }
                if (!lines.isEmpty()) {
                    append(String.join("\n", lines) + "\n");
                }
            } catch (InterruptedException ignored) {
                return;
            } catch (Throwable ignored) {
                // logging must never break the injected process
            }
        }
    }

    private static final StringBuilder FALLBACK = new StringBuilder(16 * 1024);

    /** Returns and clears pending fallback lines (delivered via heartbeat extras). */
    public static synchronized String drainFallback() {
        if (FALLBACK.length() == 0) {
            return null;
        }
        String value = FALLBACK.toString();
        FALLBACK.setLength(0);
        return value;
    }

    private static synchronized void pushFallback(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        FALLBACK.append(text);
        if (FALLBACK.length() > MAX_FALLBACK_CHARS) {
            FALLBACK.delete(0, FALLBACK.length() - MAX_FALLBACK_CHARS);
        }
    }

    private static void append(String text) {
        if (!HookEntry.isLoggingEnabled()) {
            return;
        }
        Context appContext = context;
        if (appContext == null) {
            pushFallback(text);
            return;
        }
        try {
            Intent intent = new Intent(Contract.ACTION_HOOK_LOGS)
                    .setPackage(Contract.MODULE_PACKAGE)
                    .putExtra(Contract.EXTRA_LOG, text);
            appContext.sendBroadcast(intent);
        } catch (Throwable failure) {
            if (HookEntry.isLoggingEnabled()) {
                Log.e(TAG, "hook log broadcast failed", failure);
                pushFallback(text);
            }
        }
    }

    private static String timestamp() {
        synchronized (TIME) {
            return TIME.format(new Date());
        }
    }
}

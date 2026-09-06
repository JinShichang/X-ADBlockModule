package com.xadblock.module;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

import com.xadblock.module.data.Contract;

/**
 * X-ADBlock entry point. Hooks the official X Android app (com.twitter.android),
 * removes posts whose text/url matches the cloud/local keyword rulesets.
 *
 * Reverse-engineering notes (X 12.22.0, see orchestra/reverse-findings-x-12.22.0.md):
 *  - UrtTimelinePost real dex name: com.x.models.timelines.items.l1
 *  - Main-feed compose entry: com.x.urt.ui.o#c(...) arg[0] = kotlinx.collections.immutable.b
 *  - Fallback closure/remember classes: com.x.urt.ui.h / com.x.urt.ui.j (ctor arg[0] same)
 */
public final class HookEntry extends XposedModule {
    private static final String TAG = "[X-ADBlock]";
    private static volatile HookEntry activeInstance;
    private static volatile boolean loggingEnabled = true;
    private static volatile boolean loggingPolicyLoaded;

    private final List<HookHandle> hookHandles = new CopyOnWriteArrayList<>();

    public HookEntry() {
        activeInstance = this;
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!"com.twitter.android".equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        log("loading into " + param.getPackageName());

        try {
            hookApplicationAttach();
            TimelineFilter.install(param.getClassLoader());
        } catch (Throwable failure) {
            logError("failed to install target hooks", failure);
        }
        // Browsing history is independent from filtering: keep it alive even if the
        // feed filter failed to install, and never let it break the filter either.
        PostViewTracker.install(param.getClassLoader());
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        TimelineFilter.clearState();
        PostViewTracker.clearState();
        RuleBridge.clearState();
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        // Prefer the target app's class loader: the Application.attach hook is declared on a
        // framework class, and its (boot) loader cannot resolve any of the X classes.
        ClassLoader bootLoader = Application.class.getClassLoader();
        ClassLoader classLoader = null;
        for (HookHandle handle : param.getOldHookHandles()) {
            ClassLoader candidate = handle.getExecutable().getDeclaringClass().getClassLoader();
            if (candidate != null && candidate != bootLoader) {
                classLoader = candidate;
                break;
            }
        }
        for (HookHandle handle : param.getOldHookHandles()) {
            handle.unhook();
        }
        hookHandles.clear();

        // Application.attach already ran in this process and never fires again, so the
        // bridge (rules, event channel, heartbeat) has to be revived explicitly - without
        // this the module stays loaded but filters nothing until X is restarted.
        Context context = currentApplication();
        if (context != null) {
            HookLogSink.init(context);
            RuleBridge.initialize(context);
        } else {
            logError("hot reload: no current Application; bridge stays idle");
        }
        if (classLoader == null) {
            logError("hot reload: target class loader not resolved");
            return;
        }
        try {
            hookApplicationAttach();
            TimelineFilter.install(classLoader);
        } catch (Throwable failure) {
            logError("failed to reinstall hooks after hot reload", failure);
        }
        PostViewTracker.install(classLoader);
        log("hot reload complete");
    }

    /** The running app instance; the only way back to a Context after a hot reload. */
    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application = activityThread.getMethod("currentApplication").invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void hookApplicationAttach() throws Throwable {
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        registerHook(attach, "application_attach", chain -> {
            Object result = chain.proceed();
            Object context = chain.getArg(0);
            if (context instanceof Context) {
                HookLogSink.init((Context) context);
                RuleBridge.initialize((Context) context);
            }
            return result;
        });
        log("Application.attach hook installed");
    }

    static HookHandle registerHook(
            Executable executable, String id, XposedInterface.Hooker hooker) {
        HookEntry instance = activeInstance;
        if (instance == null) {
            throw new IllegalStateException("X-ADBlock module entry is not active");
        }
        return instance.register(executable, id, hooker);
    }

    /** Module APK path, straight from the framework: no package visibility needed. */
    static String moduleApkPath() {
        HookEntry instance = activeInstance;
        if (instance == null) {
            return null;
        }
        try {
            android.content.pm.ApplicationInfo info = instance.getModuleApplicationInfo();
            return info == null ? null : info.sourceDir;
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * Reads one of the module's remote files. Unlike Remote Preferences (delivered as a
     * per-process snapshot) this is fetched from the framework on every call, so it also
     * works when the module app was reinstalled while the target process kept running.
     */
    static String readRemoteFile(String name) {
        HookEntry instance = activeInstance;
        if (instance == null) {
            return null;
        }
        try (android.os.ParcelFileDescriptor descriptor = instance.openRemoteFile(name)) {
            if (descriptor == null) {
                return null;
            }
            try (java.io.InputStream in =
                         new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                return new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Throwable failure) {
            return null;
        }
    }

    static SharedPreferences remotePreferences(String group) {
        HookEntry instance = activeInstance;
        if (instance == null) {
            throw new IllegalStateException("X-ADBlock module entry is not active");
        }
        return instance.getRemotePreferences(group);
    }

    static boolean isLoggingEnabled() {
        if (!loggingPolicyLoaded && activeInstance != null) {
            refreshLoggingPolicy();
        }
        return loggingEnabled;
    }

    static void refreshLoggingPolicy() {
        HookEntry instance = activeInstance;
        if (instance == null) {
            return;
        }
        Boolean resolved = readRemoteLoggingPolicy();
        setLoggingEnabled(resolved == null || resolved);
    }

    static void setLoggingEnabled(boolean enabled) {
        loggingEnabled = enabled;
        loggingPolicyLoaded = true;
        if (!enabled) {
            HookLogSink.clear();
        }
    }

    static void resetLoggingPolicy() {
        loggingPolicyLoaded = false;
    }

    private static Boolean readRemoteLoggingPolicy() {
        String content = readRemoteFile(Contract.SNAPSHOT_FILE);
        if (content != null) {
            for (String line : content.split("\\r?\\n")) {
                if (line.startsWith("#loggingEnabled=")) {
                    return Boolean.parseBoolean(line.substring("#loggingEnabled=".length()).trim());
                }
            }
        }
        try {
            HookEntry instance = activeInstance;
            if (instance == null) {
                return null;
            }
            SharedPreferences prefs = instance.getRemotePreferences(Contract.PREF_SNAPSHOT);
            if (prefs.contains(Contract.KEY_LOGGING_ENABLED)) {
                return prefs.getBoolean(Contract.KEY_LOGGING_ENABLED, true);
            }
        } catch (Throwable ignored) {
            // Use the enabled default when the framework has no policy yet.
        }
        return null;
    }

    private HookHandle register(
            Executable executable, String id, XposedInterface.Hooker hooker) {
        HookHandle handle = hook(executable)
                .setId(id)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(hooker);
        hookHandles.add(handle);
        return handle;
    }

    static void log(String message) {
        if (!isLoggingEnabled()) {
            return;
        }
        HookEntry instance = activeInstance;
        if (instance != null) {
            instance.log(Log.INFO, TAG, message);
        } else {
            Log.i(TAG, message);
        }
        HookLogSink.log(message);
    }

    private static void logError(String message) {
        if (!isLoggingEnabled()) {
            return;
        }
        HookEntry instance = activeInstance;
        if (instance != null) {
            instance.log(Log.ERROR, TAG, message);
        } else {
            Log.e(TAG, message);
        }
        HookLogSink.log(message);
    }

    private static void logError(String message, Throwable throwable) {
        if (!isLoggingEnabled()) {
            return;
        }
        HookEntry instance = activeInstance;
        if (instance != null) {
            instance.log(Log.ERROR, TAG, message, throwable);
        } else {
            Log.e(TAG, message, throwable);
        }
        HookLogSink.log(message);
        logThrowable(throwable);
    }

    static void logThrowable(Throwable throwable) {
        if (!isLoggingEnabled()) {
            return;
        }
        java.io.StringWriter writer = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(writer));
        for (String line : writer.toString().split("\\r?\\n")) {
            HookLogSink.log(line);
        }
    }
}

package com.xadblock.module;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.SharedPreferences;

import com.xadblock.module.data.Contract;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Runs inside the X process. Loads rule snapshots written by the module app
 * through LibXposed Remote Preferences, matches post text/url against them and
 * reports blocks back via exported broadcasts. The packaged asset bootstrap
 * stays as the explicit no-configuration startup path.
 */
public final class RuleBridge {
    private static final String TAG = "[X-ADBlock]";
    private static final String BUILTIN_ASSET = "builtin_keywords.txt";
    private static final long HEARTBEAT_INTERVAL_MS = 120_000L;
    private static final long EVENT_FLUSH_MS = 4_000L;
    private static final int MAX_EVENT_BATCH = 20;
    /** Re-opening the same post within this window is treated as the same visit. */
    private static final long VIEW_REPEAT_WINDOW_MS = 60_000L;
    private static final int MAX_RECORDED_ENTRY_IDS = 10_000;

    private static final AtomicReference<Snapshot> ACTIVE = new AtomicReference<>();
    private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean(false);
    private static final AtomicBoolean BOOTSTRAP_RUNNING = new AtomicBoolean(false);
    private static final AtomicLong BOOTSTRAP_LAST_TRY = new AtomicLong();
    private static final AtomicLong DIAG_LAST_NO_RULES = new AtomicLong();
    private static final AtomicLong DIAG_LAST_PREFS = new AtomicLong();
    private static final AtomicLong DIAG_LAST_FILE = new AtomicLong();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    private static volatile Context targetContext;
    private static volatile SharedPreferences snapshotPrefs;
    private static volatile SharedPreferences.OnSharedPreferenceChangeListener snapshotListener;
    private static volatile long lastSnapshotVersion = Long.MIN_VALUE;
    private static volatile String lastHeartbeatStatus = "STARTING";

    private static volatile int displayMode = Contract.DISPLAY_MODE_MARK;
    private static volatile boolean optUsername = true;
    private static volatile boolean optEmoji = true;
    private static volatile boolean optSpecialChars = true;
    private static volatile boolean optGrok = false;
    private static volatile boolean skipVerified = false;
    private static volatile java.util.Set<String> whitelistUsers = Collections.emptySet();
    private static volatile String markText = "[已拦截]";
    private static volatile boolean recordViews = true;

    static int getDisplayMode() {
        return displayMode;
    }

    static String getMarkText() {
        return markText;
    }

    static boolean isRecordingViews() {
        return recordViews;
    }

    private static final AtomicLong STAT_TOTAL = new AtomicLong();
    private static volatile android.os.Handler MAIN_HANDLER;
    private static final Runnable FLUSH_RUNNABLE = RuleBridge::flushEvents;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xadblock-bridge");
        thread.setDaemon(true);
        return thread;
    });

    /** entryId -> Match for the active snapshot; cleared on reload. */
    private static final java.util.Map<String, Match> EVAL_CACHE =
            java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, Match>(4096, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Match> eldest) {
                    return size() > 8192;
                }
            });
    private static final List<PendingBlock> EVENT_QUEUE = Collections.synchronizedList(new ArrayList<>());
    /** Opened posts waiting to be shipped to the module app (browsing history). */
    private static final List<PendingView> VIEW_QUEUE = Collections.synchronizedList(new ArrayList<>());
    /** postId -> last time the open was queued; keeps recompositions from spamming the channel. */
    private static final Map<String, Long> RECENT_VIEWS =
            Collections.synchronizedMap(new LinkedHashMap<String, Long>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > 512;
                }
            });
    /** Entry IDs already counted and queued during this X process lifetime. */
    private static final Map<String, Boolean> RECORDED_ENTRY_IDS =
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(4096, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_RECORDED_ENTRY_IDS;
                }
            });
    private static volatile long lastEventFlushAt;

    private RuleBridge() {}

    static void initialize(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("null base context during Application.attach");
        }
        targetContext = context;
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        try {
            snapshotPrefs = HookEntry.remotePreferences(Contract.PREF_SNAPSHOT);
            snapshotListener = (preferences, key) -> {
                if (key == null || isSnapshotSettingKey(key)) {
                    IO.execute(() -> reloadIfChanged(true));
                }
            };
            snapshotPrefs.registerOnSharedPreferenceChangeListener(snapshotListener);
        } catch (Throwable throwable) {
            snapshotPrefs = null;
            HookEntry.log("Remote Preferences unavailable; using packaged bootstrap: " + throwable);
        }
        HookEntry.refreshLoggingPolicy();
        reloadIfChanged(true);
        lastHeartbeatStatus = "ACTIVE";
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        MAIN_HANDLER = mainHandler;
        mainHandler.postDelayed(RuleBridge::sendPeriodicHeartbeat, HEARTBEAT_INTERVAL_MS);
        mainHandler.postDelayed(FLUSH_RUNNABLE, EVENT_FLUSH_MS);
        sendHeartbeat("ACTIVE");
        HookEntry.log("bridge initialized: snapshotVersion=" + lastSnapshotVersion);
    }

    static void clearState() {
        SharedPreferences prefs = snapshotPrefs;
        SharedPreferences.OnSharedPreferenceChangeListener listener = snapshotListener;
        if (prefs != null && listener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(listener);
        }
        snapshotPrefs = null;
        snapshotListener = null;
        targetContext = null;
        ACTIVE.set(null);
        EVAL_CACHE.clear();
        RECORDED_ENTRY_IDS.clear();
        EVENT_QUEUE.clear();
        VIEW_QUEUE.clear();
        RECENT_VIEWS.clear();
        android.os.Handler handler = MAIN_HANDLER;
        MAIN_HANDLER = null;
        if (handler != null) {
            handler.removeCallbacks(FLUSH_RUNNABLE);
        }
        STAT_TOTAL.set(0);
        BOOTSTRAPPED.set(false);
        BOOTSTRAP_LAST_TRY.set(0L);
        INITIALIZED.set(false);
        lastSnapshotVersion = Long.MIN_VALUE;
        lastHeartbeatStatus = "STARTING";
        HookEntry.resetLoggingPolicy();
    }

    /**
     * Refreshes the active ruleset. Remote Preferences are tried first (cheapest), then the
     * remote snapshot file, then the ruleset packaged in the module APK. The file layer
     * matters after a module reinstall: the target process keeps a stale, empty copy of the
     * preference group until it restarts, which used to leave the filter with no rules.
     */
    private static void reloadIfChanged(boolean force) {
        ensureBootstrap();
        if (loadFromPreferences(force)) {
            return;
        }
        loadFromRemoteFile(force);
    }

    /** @return true when Remote Preferences carried a usable snapshot. */
    private static boolean loadFromPreferences(boolean force) {
        SharedPreferences prefs = snapshotPrefs;
        if (prefs == null) {
            return false;
        }
        try {
            long version = prefs.getLong(Contract.KEY_SNAPSHOT_VERSION, 0L);
            String data = prefs.getString(Contract.KEY_SNAPSHOT_DATA, "");
            if (version <= 0 || data == null || data.isEmpty()) {
                if (diagAllowed(DIAG_LAST_PREFS, 60_000L)) {
                    HookEntry.log("remote prefs snapshot empty (version=" + version + " bytes="
                            + (data == null ? -1 : data.length()) + "); trying remote file");
                }
                return false;
            }
            if (!force && version == lastSnapshotVersion) {
                return true;
            }
            loadOptions(prefs);
            return activate(version, data, "module-snapshot");
        } catch (Throwable throwable) {
            HookEntry.log("remote prefs reload failed; keeping active: " + throwable);
            return false;
        }
    }

    /** Reads the snapshot the module app wrote through XposedService.openRemoteFile(). */
    private static void loadFromRemoteFile(boolean force) {
        try {
            String content = HookEntry.readRemoteFile(Contract.SNAPSHOT_FILE);
            if (content == null || content.isEmpty()) {
                if (diagAllowed(DIAG_LAST_FILE, 60_000L)) {
                    HookEntry.log("remote snapshot file unavailable: " + Contract.SNAPSHOT_FILE);
                }
                return;
            }
            long version = 0L;
            StringBuilder rules = new StringBuilder(content.length());
            java.util.Map<String, String> settings = new LinkedHashMap<>();
            for (String line : content.split("\\r?\\n")) {
                if (line.isEmpty()) continue;
                if (line.charAt(0) == '#') {
                    int equals = line.indexOf('=');
                    if (equals > 1) {
                        settings.put(line.substring(1, equals), line.substring(equals + 1));
                    }
                    continue;
                }
                rules.append(line).append('\n');
            }
            String versionText = settings.get("v");
            if (versionText != null) {
                try {
                    version = Long.parseLong(versionText.trim());
                } catch (NumberFormatException ignored) {
                    version = 0L;
                }
            }
            if (version <= 0 || rules.length() == 0) {
                return;
            }
            if (!force && version == lastSnapshotVersion) {
                return;
            }
            applyFileSettings(settings);
            activate(version, rules.toString(), "remote-file");
        } catch (Throwable throwable) {
            HookEntry.log("remote snapshot file reload failed: " + throwable);
        }
    }

    private static boolean activate(long version, String data, String origin) {
        List<Rule> rules = compileSnapshot(data);
        TimelineFilter.invalidateMatches();
        EVAL_CACHE.clear();
        if (rules.isEmpty()) {
            return false;
        }
        ACTIVE.set(new Snapshot(version, Collections.unmodifiableList(rules), origin));
        lastSnapshotVersion = version;
        HookEntry.log("activated " + origin + "=" + version + " rules=" + rules.size()
                + " mode=" + displayMode + " opt(U/E/S/G)=" + optUsername + "/" + optEmoji
                + "/" + optSpecialChars + "/" + optGrok);
        return true;
    }

    private static void applyFileSettings(java.util.Map<String, String> settings) {
        try {
            displayMode = intSetting(settings, "mode", Contract.DISPLAY_MODE_MARK);
            optUsername = boolSetting(settings, "optUsername", true);
            optEmoji = boolSetting(settings, "optEmoji", true);
            optSpecialChars = boolSetting(settings, "optSpecialChars", true);
            optGrok = boolSetting(settings, "optGrok", false);
            skipVerified = boolSetting(settings, "skipVerified", false);
            recordViews = boolSetting(settings, "recordViews", true);
            HookEntry.setLoggingEnabled(boolSetting(settings, "loggingEnabled", true));
            String text = settings.get("mark");
            markText = (text == null || text.isEmpty() || "已屏蔽".equals(text))
                    ? "[已拦截]" : text;
            String users = settings.get("whitelist");
            java.util.HashSet<String> normalizedUsers = new java.util.HashSet<>();
            if (users != null && !users.isEmpty()) {
                for (String user : users.split("\t")) {
                    if (user == null || user.trim().isEmpty()) continue;
                    normalizedUsers.add(normalizeForMatch(user));
                }
            }
            whitelistUsers = Collections.unmodifiableSet(normalizedUsers);
        } catch (Throwable ignored) {
            // keep previous values on parse hiccup
        }
    }

    private static int intSetting(java.util.Map<String, String> settings, String key, int fallback) {
        String value = settings.get(key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean boolSetting(java.util.Map<String, String> settings, String key,
                                      boolean fallback) {
        String value = settings.get(key);
        if (value == null) return fallback;
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim());
    }

    private static void loadOptions(SharedPreferences prefs) {
        try {
            displayMode = prefs.getInt(Contract.KEY_DISPLAY_MODE, Contract.DISPLAY_MODE_MARK);
            optUsername = prefs.getBoolean(Contract.KEY_OPT_USERNAME, true);
            optEmoji = prefs.getBoolean(Contract.KEY_OPT_EMOJI, true);
            optSpecialChars = prefs.getBoolean(Contract.KEY_OPT_SPECIAL_CHARS, true);
            optGrok = prefs.getBoolean(Contract.KEY_OPT_GROK, false);
            skipVerified = prefs.getBoolean(Contract.KEY_SKIP_VERIFIED, false);
            recordViews = prefs.getBoolean(Contract.KEY_RECORD_VIEWS, true);
            HookEntry.setLoggingEnabled(
                    prefs.getBoolean(Contract.KEY_LOGGING_ENABLED, true));
            java.util.Set<String> configuredUsers = prefs.getStringSet(
                    Contract.KEY_WHITELIST_USERS, Collections.emptySet());
            java.util.HashSet<String> normalizedUsers = new java.util.HashSet<>();
            if (configuredUsers != null) {
                for (String user : configuredUsers) {
                    if (user == null || user.trim().isEmpty()) continue;
                    normalizedUsers.add(normalizeForMatch(user));
                }
            }
            whitelistUsers = Collections.unmodifiableSet(normalizedUsers);
            String text = prefs.getString(Contract.KEY_MARK_TEXT, "[已拦截]");
            markText = (text == null || text.isEmpty() || "已屏蔽".equals(text)) ? "[已拦截]" : text;
        } catch (Throwable ignored) {
            // keep previous values on parse hiccup
        }
    }

    private static boolean isSnapshotSettingKey(String key) {
        return Contract.KEY_SNAPSHOT_VERSION.equals(key)
                || Contract.KEY_SNAPSHOT_DATA.equals(key)
                || Contract.KEY_DISPLAY_MODE.equals(key)
                || Contract.KEY_OPT_USERNAME.equals(key)
                || Contract.KEY_OPT_EMOJI.equals(key)
                || Contract.KEY_OPT_SPECIAL_CHARS.equals(key)
                || Contract.KEY_OPT_GROK.equals(key)
                || Contract.KEY_SKIP_VERIFIED.equals(key)
                || Contract.KEY_RECORD_VIEWS.equals(key)
                || Contract.KEY_LOGGING_ENABLED.equals(key)
                || Contract.KEY_WHITELIST_USERS.equals(key)
                || Contract.KEY_MARK_TEXT.equals(key);
    }

    private static List<Rule> compileSnapshot(String data) {
        List<Rule> rules = new ArrayList<>();
        long id = 0;
        for (String line : data.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int slash = trimmed.indexOf('/');
            if (slash <= 0) continue;
            String kind = trimmed.substring(0, slash);
            String pattern = trimmed.substring(slash + 1);
            switch (kind) {
                case Contract.KIND_LITERAL:
                case Contract.KIND_REGEX:
                case Contract.KIND_ALL_OF:
                    try {
                        rules.add(Rule.compile(id++, kind.equals(Contract.KIND_REGEX) ? "regex"
                                : kind.toLowerCase(Locale.ROOT), kind, pattern, 80));
                    } catch (Throwable ignored) {
                        // skip unparsable rule
                    }
                    break;
                default:
                    break;
            }
        }
        return rules;
    }

    private static void ensureBootstrap() {
        if (BOOTSTRAPPED.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        long previous = BOOTSTRAP_LAST_TRY.get();
        if (previous != 0L && now - previous < 10_000L) {
            return;
        }
        if (!BOOTSTRAP_LAST_TRY.compareAndSet(previous, now)
                || !BOOTSTRAP_RUNNING.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Rule> rules = loadBuiltinRules();
            if (rules.isEmpty()) {
                HookEntry.log("packaged bootstrap unavailable (will retry)");
                return;
            }
            Snapshot snapshot = new Snapshot(
                    rules.size(), Collections.unmodifiableList(rules), "packaged-bootstrap");
            if (ACTIVE.compareAndSet(null, snapshot) || ACTIVE.get().origin.startsWith("packaged")) {
                ACTIVE.set(snapshot);
            }
            BOOTSTRAPPED.set(true);
            HookEntry.log("packaged bootstrap ready rules=" + rules.size());
        } catch (Throwable throwable) {
            HookEntry.log("packaged bootstrap failed (will retry): " + throwable);
        } finally {
            BOOTSTRAP_RUNNING.set(false);
        }
    }

    /**
     * Loads the ruleset packaged with the module. Reading the module APK directly is the
     * reliable path (works without a Context and without package visibility); the
     * createPackageContext route stays as a fallback.
     */
    private static List<Rule> loadBuiltinRules() {
        String apkPath = HookEntry.moduleApkPath();
        if (apkPath != null) {
            try (java.util.zip.ZipFile apk = new java.util.zip.ZipFile(apkPath)) {
                java.util.zip.ZipEntry entry = apk.getEntry("assets/" + BUILTIN_ASSET);
                if (entry != null) {
                    try (InputStream in = apk.getInputStream(entry)) {
                        return parseBuiltinRules(in);
                    }
                }
            } catch (Throwable throwable) {
                HookEntry.log("builtin read from module apk failed: " + throwable);
            }
        }
        Context context = targetContext;
        if (context != null) {
            try {
                AssetManager assets = context.createPackageContext(
                        Contract.MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY).getAssets();
                try (InputStream in = assets.open(BUILTIN_ASSET)) {
                    return parseBuiltinRules(in);
                }
            } catch (Throwable throwable) {
                HookEntry.log("builtin read from module assets failed: " + throwable);
            }
        }
        return Collections.emptyList();
    }

    private static List<Rule> parseBuiltinRules(InputStream in) throws Throwable {
        List<Rule> rules = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            int builtInId = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                List<String[]> parsed = RuleParserLine.parse(line);
                if (parsed == null) continue;
                for (String[] kindPattern : parsed) {
                    rules.add(Rule.compile(builtInId--, "builtin",
                            kindPattern[0], kindPattern[1], 50));
                }
            }
        }
        return rules;
    }

    private static boolean diagAllowed(AtomicLong marker, long intervalMs) {
        long now = System.currentTimeMillis();
        long previous = marker.get();
        return now - previous >= intervalMs && marker.compareAndSet(previous, now);
    }

    /** One-line health summary shipped with every heartbeat, mirrored into the module log. */
    private static String selfCheck() {
        Snapshot snapshot = ACTIVE.get();
        return "rules=" + (snapshot == null ? -1 : snapshot.rules.size())
                + " origin=" + (snapshot == null ? "none" : snapshot.origin)
                + " mode=" + displayMode
                + " opt(U/E/S/G)=" + optUsername + "/" + optEmoji + "/" + optSpecialChars
                + "/" + optGrok
                + " skipVerified=" + skipVerified
                + " whitelist=" + whitelistUsers.size()
                + " views=" + recordViews
                + " prefs=" + (snapshotPrefs != null)
                + " filter[" + TimelineFilter.installSummary() + "]";
    }

    /** Render-time text-level check: matches a single text against the active ruleset. */
    static String matchText(String text) {
        ensureBootstrap();
        if (text == null || text.isEmpty()) {
            return null;
        }
        Snapshot snapshot = ACTIVE.get();
        if (snapshot == null) {
            return null;
        }
        if ((optEmoji && isEmojiOnly(text))
                || (optSpecialChars && hasSuspiciousSpecialChars(text))) {
            return markText;
        }
        String normalized = normalizeForMatch(text);
        for (Rule rule : snapshot.rules) {
            String matched = rule.match(text, normalized);
            if (matched != null) {
                return markText;
            }
        }
        return null;
    }

    static Match firstMatch(String postText, String postUrl, String authorText, String entryId,
                            boolean isGrok, boolean isVerified) {
        ensureBootstrap();
        if ((isVerified && skipVerified) || isWhitelisted(authorText)) {
            return null;
        }
        if (entryId != null && !entryId.isEmpty()) {
            Match cached = EVAL_CACHE.get(entryId);
            if (cached != null) {
                return cached;
            }
        }
        Snapshot snapshot = ACTIVE.get();
        if (snapshot == null) {
            ensureBootstrap();
            snapshot = ACTIVE.get();
        }
        if (snapshot == null) {
            if (diagAllowed(DIAG_LAST_NO_RULES, 30_000L)) {
                HookEntry.log("no active ruleset: nothing will be filtered (" + selfCheck() + ")");
            }
            return null;
        }
        String combined = combine(postText, postUrl, optUsername ? authorText : null);
        Match result = automaticMatch(postText, snapshot.version, entryId, isGrok);
        if (result == null) {
            result = ruleMatch(snapshot, combined, snapshot.version, entryId);
        }
        if (entryId != null && !entryId.isEmpty()) {
            if (result == null) {
                EVAL_CACHE.remove(entryId);
            } else {
                EVAL_CACHE.put(entryId, result);
            }
        }
        return result;
    }

    static Match firstMatch(String postText, String postUrl, String authorText, String entryId,
                            boolean isGrok) {
        return firstMatch(postText, postUrl, authorText, entryId, isGrok, false);
    }

    private static boolean isWhitelisted(String authorText) {
        if (authorText == null || authorText.trim().isEmpty() || whitelistUsers.isEmpty()) {
            return false;
        }
        if (containsWhitelistValue(authorText)) {
            return true;
        }
        for (String token : authorText.split("\\s+")) {
            if (containsWhitelistValue(token)) return true;
        }
        return false;
    }

    private static boolean containsWhitelistValue(String value) {
        String normalized = normalizeForMatch(value);
        if (normalized.isEmpty()) return false;
        if (whitelistUsers.contains(normalized)) return true;
        String withoutAt = normalized.startsWith("@") ? normalized.substring(1) : normalized;
        String withAt = normalized.startsWith("@") ? normalized : "@" + normalized;
        return whitelistUsers.contains(withoutAt) || whitelistUsers.contains(withAt);
    }

    private static String combine(String postText, String postUrl, String authorText) {
        StringBuilder builder = new StringBuilder();
        appendPart(builder, postText);
        appendPart(builder, postUrl);
        appendPart(builder, authorText);
        return builder.toString();
    }

    private static void appendPart(StringBuilder builder, String value) {
        if (value == null || value.isEmpty()) return;
        if (builder.length() > 0) builder.append('\n');
        builder.append(value);
    }

    private static Match automaticMatch(String postText, long version, String entryId,
                                        boolean isGrok) {
        if (optGrok && isGrok) {
            return new Match(null, "grok", version, entryId);
        }
        if (postText == null || postText.isEmpty()) return null;
        if (optEmoji && isEmojiOnly(postText)) {
            return new Match(null, "emoji", version, entryId);
        }
        if (optSpecialChars && hasSuspiciousSpecialChars(postText)) {
            return new Match(null, "special-chars", version, entryId);
        }
        return null;
    }

    private static Match ruleMatch(Snapshot snapshot, String original, long version,
                                   String entryId) {
        String normalized = normalizeForMatch(original);
        Rule ahoRule = snapshot.aho.scan(normalized);
        if (ahoRule != null) {
            return new Match(ahoRule, ahoRule.pattern, version, entryId);
        }
        for (Rule rule : snapshot.allOfRules) {
            String matched = rule.match(original, normalized);
            if (matched != null) {
                return new Match(rule, matched, version, entryId);
            }
        }
        for (Rule rule : snapshot.regexRules) {
            String matched = rule.match(original, normalized);
            if (matched != null) {
                return new Match(rule, matched, version, entryId);
            }
        }
        return null;
    }

    /** Heuristic for "充满特殊符号/乱码" posts: symbols, control chars and private-use
     *  codepoints make up >= 25% of the visible text (excluding whitespace). */
    private static boolean hasSuspiciousSpecialChars(String text) {
        int total = 0;
        int suspicious = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            if (isEmojiCodePoint(codePoint) || codePoint == 0xFE0F
                    || codePoint == 0x200D || codePoint == 0x20E3) {
                continue;
            }
            total++;
            int type = Character.getType(codePoint);
            if (type == Character.CONTROL
                    || type == Character.FORMAT
                    || type == Character.SURROGATE
                    || type == Character.MATH_SYMBOL
                    || type == Character.CURRENCY_SYMBOL
                    || type == Character.MODIFIER_SYMBOL
                    || type == Character.OTHER_SYMBOL) {
                suspicious++;
            }
            if (codePoint >= 0xE000 && codePoint <= 0xF8FF) { // private use
                suspicious++;
            }
        }
        return total >= 3 && suspicious * 100 >= total * 25;
    }

    /** Returns true only when every non-whitespace code point belongs to an emoji sequence. */
    private static boolean isEmojiOnly(String text) {
        if (text == null || text.isEmpty()) return false;
        boolean hasEmoji = false;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            int nextOffset = offset + Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                offset = nextOffset;
                continue;
            }
            if (isEmojiCodePoint(codePoint)) {
                hasEmoji = true;
                offset = nextOffset;
                continue;
            }
            if (codePoint == 0xFE0F || codePoint == 0x200D || codePoint == 0x20E3) {
                if (!hasEmoji) return false;
                offset = nextOffset;
                continue;
            }
            if (isKeycapBase(codePoint)) {
                int keycapEnd = keycapEnd(text, offset);
                if (keycapEnd > nextOffset) {
                    hasEmoji = true;
                    offset = keycapEnd;
                    continue;
                }
            }
            return false;
        }
        return hasEmoji;
    }

    private static boolean isKeycapBase(int codePoint) {
        return codePoint >= '0' && codePoint <= '9'
                || codePoint == '#' || codePoint == '*';
    }

    private static int keycapEnd(String text, int offset) {
        int end = offset + Character.charCount(text.codePointAt(offset));
        if (end < text.length() && text.codePointAt(end) == 0xFE0F) {
            end += Character.charCount(text.codePointAt(end));
        }
        if (end < text.length() && text.codePointAt(end) == 0x20E3) {
            return end + Character.charCount(text.codePointAt(end));
        }
        return offset + Character.charCount(text.codePointAt(offset));
    }

    /** Detects emoji code points without treating every supplementary character as emoji. */
    private static boolean isEmojiCodePoint(int codePoint) {
        return codePoint >= 0x1F000 && codePoint <= 0x1FAFF
                || codePoint >= 0x2600 && codePoint <= 0x27BF
                || codePoint == 0x00A9 || codePoint == 0x00AE
                || codePoint == 0x203C || codePoint == 0x2049
                || codePoint == 0x2122 || codePoint == 0x2139
                || codePoint >= 0x2194 && codePoint <= 0x2199
                || codePoint >= 0x21A9 && codePoint <= 0x21AA
                || codePoint >= 0x231A && codePoint <= 0x231B
                || codePoint == 0x2328 || codePoint == 0x23CF
                || codePoint >= 0x23E9 && codePoint <= 0x23F3
                || codePoint >= 0x23F8 && codePoint <= 0x23FA
                || codePoint == 0x24C2
                || codePoint >= 0x25AA && codePoint <= 0x25AB
                || codePoint == 0x25B6 || codePoint == 0x25C0
                || codePoint >= 0x25FB && codePoint <= 0x25FE
                || codePoint >= 0x2B05 && codePoint <= 0x2B07
                || codePoint >= 0x2B1B && codePoint <= 0x2B1C
                || codePoint == 0x2B50 || codePoint == 0x2B55
                || codePoint >= 0x2934 && codePoint <= 0x2935
                || codePoint == 0x3030 || codePoint == 0x303D
                || codePoint == 0x3297 || codePoint == 0x3299;
    }

    static long getTotalBlocks() {
        return STAT_TOTAL.get();
    }

    static void recordBlock(Match match, String postText, String postUrl, String authorText) {
        if (match == null) return;
        if (wasAlreadyRecorded(match.entryId)) return;
        STAT_TOTAL.incrementAndGet();
        String sourceId = match.rule == null ? match.matchedText : match.rule.sourceId;
        String matchedRule = match.rule == null ? "" : preview(match.rule.pattern, 160);
        EVENT_QUEUE.add(new PendingBlock(
                preview(sourceId, 80),
                matchedRule,
                historyPreview(postText, postUrl, authorText),
                preview(authorText, 160),
                match.entryId));
        if (EVENT_QUEUE.size() >= MAX_EVENT_BATCH) {
            android.os.Handler handler = MAIN_HANDLER;
            if (handler != null) handler.removeCallbacks(FLUSH_RUNNABLE);
            flushEvents();
        }
    }

    private static boolean wasAlreadyRecorded(String entryId) {
        if (entryId == null || entryId.isEmpty()) return false;
        synchronized (RECORDED_ENTRY_IDS) {
            return RECORDED_ENTRY_IDS.put(entryId, Boolean.TRUE) != null;
        }
    }

    private static String historyPreview(String postText, String postUrl, String authorText) {
        if (postText != null && !postText.trim().isEmpty()) {
            return preview(postText, 160);
        }
        if (authorText != null && !authorText.trim().isEmpty()) {
            return preview("用户名: " + authorText, 160);
        }
        return preview(postUrl, 160);
    }

    private static void flushEvents() {
        scheduleNextFlush();
        flushBlockEvents();
        flushViewEvents();
    }

    private static void flushBlockEvents() {
        if (EVENT_QUEUE.isEmpty() || targetContext == null) {
            return;
        }
        List<PendingBlock> batch;
        synchronized (EVENT_QUEUE) {
            if (EVENT_QUEUE.isEmpty()) return;
            batch = new ArrayList<>(EVENT_QUEUE.subList(0, Math.min(EVENT_QUEUE.size(), MAX_EVENT_BATCH)));
            EVENT_QUEUE.subList(0, batch.size()).clear();
        }
        if (batch.isEmpty()) {
            return;
        }
        IO.execute(() -> {
            try {
                Context context = targetContext;
                if (context == null) return;
                final StringBuilder payload = new StringBuilder();
                for (PendingBlock block : batch) {
                    if (payload.length() > 0) payload.append('\n');
                    payload.append(block.sourceId).append('\t')
                            .append(block.preview).append('\t')
                            .append(block.postId == null ? "" : block.postId).append('\t')
                            .append(block.author).append('\t')
                            .append(block.matchedRule);
                }
                Intent intent = new Intent(Contract.ACTION_BLOCK_EVENTS)
                        .setPackage(Contract.MODULE_PACKAGE)
                        .putExtra(Contract.EXTRA_COUNT, batch.size())
                        .putExtra(Contract.EXTRA_ITEMS, payload.toString());
                context.sendBroadcast(intent);
            } catch (Throwable throwable) {
                HookEntry.log("failed to send block broadcast: " + throwable);
            }
        });
    }

    /**
     * Queues one "user opened this post" record. Called from the focal-post hook, so it
     * runs on the composition thread: keep it to a dedupe lookup plus a list add.
     */
    static void recordView(String postId, String url, String handle, String displayName,
                           String text) {
        if (!recordViews) return;
        if (postId == null || postId.isEmpty() || url == null || url.isEmpty()) return;
        long now = System.currentTimeMillis();
        synchronized (RECENT_VIEWS) {
            Long last = RECENT_VIEWS.get(postId);
            if (last != null && now - last < VIEW_REPEAT_WINDOW_MS) return;
            RECENT_VIEWS.put(postId, now);
        }
        VIEW_QUEUE.add(new PendingView(postId, url, preview(handle, 60),
                preview(displayName, 60), preview(text, 220)));
        if (VIEW_QUEUE.size() >= MAX_EVENT_BATCH) {
            android.os.Handler handler = MAIN_HANDLER;
            if (handler != null) handler.removeCallbacks(FLUSH_RUNNABLE);
            flushEvents();
        }
    }

    private static void flushViewEvents() {
        if (VIEW_QUEUE.isEmpty() || targetContext == null) {
            return;
        }
        List<PendingView> batch;
        synchronized (VIEW_QUEUE) {
            if (VIEW_QUEUE.isEmpty()) return;
            batch = new ArrayList<>(VIEW_QUEUE.subList(0,
                    Math.min(VIEW_QUEUE.size(), MAX_EVENT_BATCH)));
            VIEW_QUEUE.subList(0, batch.size()).clear();
        }
        if (batch.isEmpty()) {
            return;
        }
        IO.execute(() -> {
            try {
                Context context = targetContext;
                if (context == null) return;
                final StringBuilder payload = new StringBuilder();
                for (PendingView view : batch) {
                    if (payload.length() > 0) payload.append('\n');
                    payload.append(view.postId).append('\t')
                            .append(view.url).append('\t')
                            .append(view.handle).append('\t')
                            .append(view.displayName).append('\t')
                            .append(view.text);
                }
                Intent intent = new Intent(Contract.ACTION_VIEW_EVENTS)
                        .setPackage(Contract.MODULE_PACKAGE)
                        .putExtra(Contract.EXTRA_COUNT, batch.size())
                        .putExtra(Contract.EXTRA_ITEMS, payload.toString());
                context.sendBroadcast(intent);
            } catch (Throwable throwable) {
                HookEntry.log("failed to send view broadcast: " + throwable);
            }
        });
    }

    private static void scheduleNextFlush() {
        android.os.Handler handler = MAIN_HANDLER;
        if (handler == null) return;
        handler.removeCallbacks(FLUSH_RUNNABLE);
        handler.postDelayed(FLUSH_RUNNABLE, EVENT_FLUSH_MS);
    }

    private static void sendHeartbeat(String status) {
        lastHeartbeatStatus = status;
        Context context = targetContext;
        if (context == null) return;
        Intent intent = new Intent(Contract.ACTION_HEARTBEAT)
                .setPackage(Contract.MODULE_PACKAGE)
                .putExtra(Contract.EXTRA_STATUS, status)
                .putExtra(Contract.EXTRA_PROCESS, context.getApplicationInfo().processName)
                .putExtra(Contract.EXTRA_SNAPSHOT_VERSION, lastSnapshotVersion)
                .putExtra(Contract.EXTRA_TARGET_VERSION, safeVersionName(context))
                .putExtra(Contract.EXTRA_SELFCHECK, selfCheck());
        String fallbackLog = HookLogSink.drainFallback();
        if (fallbackLog != null) {
            intent.putExtra(Contract.EXTRA_LOG, fallbackLog);
        }
        context.sendBroadcast(intent);
    }

    private static void sendPeriodicHeartbeat() {
        reloadIfChanged(false);
        sendHeartbeat(lastHeartbeatStatus);
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(RuleBridge::sendPeriodicHeartbeat, HEARTBEAT_INTERVAL_MS);
    }

    private static String safeVersionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable throwable) {
            return "?";
        }
    }

    /** Keeps zero-width/whitespace-insensitive text for literal matching. */
    static String normalizeForMatch(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int offset = 0; offset < lower.length(); ) {
            int codePoint = lower.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || Character.getType(codePoint) == Character.FORMAT) {
                continue;
            }
            sb.appendCodePoint(codePoint);
        }
        return sb.toString();
    }

    private static String preview(String text, int limit) {
        if (text == null || text.isEmpty()) return "";
        int count = text.codePointCount(0, text.length());
        int end = text.offsetByCodePoints(0, Math.min(count, limit));
        return text.substring(0, end).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    static final class Match {
        final Rule rule;
        final String matchedText;
        final long snapshotVersion;
        final String entryId;

        Match(Rule rule, String matchedText, long snapshotVersion, String entryId) {
            this.rule = rule;
            this.matchedText = matchedText;
            this.snapshotVersion = snapshotVersion;
            this.entryId = entryId;
        }
    }

    private static final class PendingBlock {
        final String sourceId;
        final String matchedRule;
        final String preview;
        final String author;
        final String postId;

        PendingBlock(String sourceId, String matchedRule, String preview, String author,
                     String postId) {
            this.sourceId = sourceId;
            this.matchedRule = matchedRule;
            this.preview = preview;
            this.author = author;
            this.postId = postId;
        }
    }

    private static final class PendingView {
        final String postId;
        final String url;
        final String handle;
        final String displayName;
        final String text;

        PendingView(String postId, String url, String handle, String displayName, String text) {
            this.postId = postId;
            this.url = url;
            this.handle = handle;
            this.displayName = displayName;
            this.text = text;
        }
    }

    /** Aho-Corasick automaton over all normalized literal keywords. */
    private static final class AhoNode {
        final java.util.HashMap<Character, AhoNode> next = new java.util.HashMap<>();
        AhoNode fail;
        Rule rule;
    }

    private static final class AhoMatcher {
        final AhoNode root = new AhoNode();

        AhoMatcher(List<Rule> literalRules) {
            for (Rule rule : literalRules) {
                AhoNode cur = root;
                String pattern = rule.pattern;
                for (int i = 0; i < pattern.length(); i++) {
                    char c = pattern.charAt(i);
                    AhoNode next = cur.next.get(c);
                    if (next == null) {
                        next = new AhoNode();
                        cur.next.put(c, next);
                    }
                    cur = next;
                }
                cur.rule = rule;
            }
            java.util.ArrayDeque<AhoNode> queue = new java.util.ArrayDeque<>();
            for (AhoNode child : root.next.values()) {
                child.fail = root;
                queue.add(child);
            }
            while (!queue.isEmpty()) {
                AhoNode cur = queue.poll();
                for (java.util.Map.Entry<Character, AhoNode> entry : cur.next.entrySet()) {
                    char c = entry.getKey();
                    AhoNode child = entry.getValue();
                    AhoNode f = cur.fail;
                    while (f != null && !f.next.containsKey(c)) {
                        f = f.fail;
                    }
                    child.fail = (f == null) ? root : f.next.get(c);
                    if (child.rule == null && child.fail.rule != null) {
                        child.rule = child.fail.rule;
                    }
                    queue.add(child);
                }
            }
        }

        /** Returns the first rule matched anywhere in normalized text, or null. */
        Rule scan(String text) {
            AhoNode cur = root;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                while (cur != root && !cur.next.containsKey(c)) {
                    cur = cur.fail;
                }
                AhoNode next = cur.next.get(c);
                cur = (next == null) ? root : next;
                if (cur.rule != null) {
                    return cur.rule;
                }
            }
        return null;
        }
    }

    private static final class Snapshot {
        final long version;
        final List<Rule> rules;
        final List<Rule> literalRules;
        final List<Rule> allOfRules;
        final List<Rule> regexRules;
        final AhoMatcher aho;
        final String origin;

        Snapshot(long version, List<Rule> rules, String origin) {
            this.version = version;
            this.rules = rules;
            this.origin = origin;
            List<Rule> literals = new ArrayList<>();
            List<Rule> allOfs = new ArrayList<>();
            List<Rule> regexes = new ArrayList<>();
            for (Rule rule : rules) {
                switch (rule.kind) {
                    case Contract.KIND_LITERAL: literals.add(rule); break;
                    case Contract.KIND_ALL_OF: allOfs.add(rule); break;
                    default: regexes.add(rule); break;
                }
            }
            this.literalRules = Collections.unmodifiableList(literals);
            this.allOfRules = Collections.unmodifiableList(allOfs);
            this.regexRules = Collections.unmodifiableList(regexes);
            this.aho = new AhoMatcher(literals);
        }
    }
    private static final class Rule {
        final long id;
        final String sourceId;
        final String kind;
        final String pattern;
        final int priority;
        final Pattern regex;
        final List<String> allOf;

        private Rule(long id, String sourceId, String kind, String pattern, int priority,
                     Pattern regex, List<String> allOf) {
            this.id = id;
            this.sourceId = sourceId;
            this.kind = kind;
            this.pattern = pattern;
            this.priority = priority;
            this.regex = regex;
            this.allOf = allOf;
        }

        static Rule compile(long id, String sourceId, String kind, String pattern, int priority) {
            if (kind == null || pattern == null || pattern.isEmpty()) {
                throw new IllegalArgumentException("rule kind/pattern missing: " + id);
            }
            switch (kind) {
                case Contract.KIND_LITERAL:
                    String normalized = normalizeForMatch(pattern);
                    if (normalized.isEmpty()) {
                        throw new IllegalArgumentException("empty normalized literal: " + id);
                    }
                    return new Rule(id, sourceId, kind, normalized, priority, null, null);
                case Contract.KIND_REGEX:
                    try {
                        return new Rule(id, sourceId, kind, pattern, priority,
                                Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                                null);
                    } catch (PatternSyntaxException e) {
                        throw new IllegalArgumentException("invalid regex rule " + id, e);
                    }
                case Contract.KIND_ALL_OF:
                    List<String> parts = new ArrayList<>();
                    for (String part : pattern.split(String.valueOf(Contract.ALL_OF_SEPARATOR), -1)) {
                        String normalizedPart = normalizeForMatch(part);
                        if (!normalizedPart.isEmpty()) parts.add(normalizedPart);
                    }
                    if (parts.size() < 2) {
                        throw new IllegalArgumentException("ALL_OF needs two parts: " + id);
                    }
                    List<String> unmodifiable = Collections.unmodifiableList(parts);
                    return new Rule(id, sourceId, kind, pattern, priority, null, unmodifiable);
                default:
                    throw new IllegalArgumentException("unsupported kind " + kind + " for " + id);
            }

        }
        String match(String original, String normalized) {
            switch (kind) {
                case Contract.KIND_LITERAL:
                    return normalized.contains(pattern) ? pattern : null;
                case Contract.KIND_REGEX:
                    Matcher matcher = regex.matcher(original);
                    return matcher.find() ? matcher.group() : null;
                case Contract.KIND_ALL_OF:
                    for (String part : allOf) {
                        if (!normalized.contains(part)) return null;
                    }
                    return String.join(" + ", allOf);
                default:
        return null;
            }
        }
    }
}

package com.xadblock.module.data

import android.content.Context
import android.content.SharedPreferences

/** User-facing options stored by the module app; mirrored into the hook snapshot. */
object SettingsStore {

    private const val DEFAULT_MARK_TEXT = "[已拦截]"
    private const val LEGACY_MARK_TEXT = "已屏蔽"

    private const val NAME = "xadblock_settings"
    private const val KEY_DISPLAY_MODE = "display_mode"
    private const val KEY_OPT_USERNAME = "opt_username"
    private const val KEY_OPT_EMOJI = "opt_emoji"
    private const val KEY_OPT_SPECIAL_CHARS = "opt_special_chars"
    private const val KEY_OPT_GROK = "opt_grok"
    private const val KEY_SKIP_VERIFIED = "skip_verified"
    private const val KEY_MARK_TEXT = "mark_text"
    private const val KEY_WHITELIST_USERS = "whitelist_users"
    private const val KEY_RECORD_VIEWS = "record_views"
    private const val KEY_LOGGING_ENABLED = "logging_enabled"

    data class Settings(
        val displayMode: Int = Contract.DISPLAY_MODE_MARK,
        val optUsername: Boolean = true,
        val optEmoji: Boolean = true,
        val optSpecialChars: Boolean = true,
        val optGrok: Boolean = false,
        val markText: String = DEFAULT_MARK_TEXT,
        val skipVerified: Boolean = false,
        val recordViews: Boolean = true,
        val loggingEnabled: Boolean = true,
        val whitelistUsers: Set<String> = emptySet()
    ) {
        val isMarkMode: Boolean get() = displayMode == Contract.DISPLAY_MODE_MARK
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(context: Context): Settings {
        val prefs = prefs(context)
        val storedMarkText = prefs.getString(KEY_MARK_TEXT, DEFAULT_MARK_TEXT) ?: DEFAULT_MARK_TEXT
        return Settings(
            displayMode = prefs.getInt(KEY_DISPLAY_MODE, Contract.DISPLAY_MODE_MARK),
            optUsername = prefs.getBoolean(KEY_OPT_USERNAME, true),
            optEmoji = prefs.getBoolean(KEY_OPT_EMOJI, true),
            optSpecialChars = prefs.getBoolean(KEY_OPT_SPECIAL_CHARS, true),
            optGrok = prefs.getBoolean(KEY_OPT_GROK, false),
            skipVerified = prefs.getBoolean(KEY_SKIP_VERIFIED, false),
            recordViews = prefs.getBoolean(KEY_RECORD_VIEWS, true),
            loggingEnabled = prefs.getBoolean(KEY_LOGGING_ENABLED, true),
            whitelistUsers = prefs.getStringSet(KEY_WHITELIST_USERS, emptySet()).orEmpty().toSet(),
            markText = if (storedMarkText == LEGACY_MARK_TEXT) DEFAULT_MARK_TEXT else storedMarkText
        )
    }

    fun save(context: Context, settings: Settings) {
        ModuleLogger.setEnabled(settings.loggingEnabled)
        prefs(context).edit()
            .putInt(KEY_DISPLAY_MODE, settings.displayMode)
            .putBoolean(KEY_OPT_USERNAME, settings.optUsername)
            .putBoolean(KEY_OPT_EMOJI, settings.optEmoji)
            .putBoolean(KEY_OPT_SPECIAL_CHARS, settings.optSpecialChars)
            .putBoolean(KEY_OPT_GROK, settings.optGrok)
            .putBoolean(KEY_SKIP_VERIFIED, settings.skipVerified)
            .putBoolean(KEY_RECORD_VIEWS, settings.recordViews)
            .putBoolean(KEY_LOGGING_ENABLED, settings.loggingEnabled)
            .putStringSet(KEY_WHITELIST_USERS, settings.whitelistUsers.toSet())
            .putString(KEY_MARK_TEXT, settings.markText)
            .apply()
        RuleSnapshotStore.rebuild(context)
    }
}

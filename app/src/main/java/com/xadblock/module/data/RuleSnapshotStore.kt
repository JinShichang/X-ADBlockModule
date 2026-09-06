package com.xadblock.module.data

import android.content.Context
import android.os.ParcelFileDescriptor
import java.security.MessageDigest
import com.xadblock.module.XAdApplication

/**
 * Publishes the merged local ruleset through LibXposed Remote Preferences so the injected X
 * process can read it without relying on world-readable app files. Invoked after every mutation.
 */
object RuleSnapshotStore {
    private const val MAX_RULES = 50_000

    fun rebuild(context: Context) {
        val db = AppDatabase.get(context)
        val rules = db.ruleDao().allEnabled()
        val limited = if (rules.size > MAX_RULES) rules.subList(0, MAX_RULES) else rules

        val sb = StringBuilder(limited.size * 40)
        limited.forEach { rule ->
            when (rule.kind) {
                Contract.KIND_LITERAL,
                Contract.KIND_REGEX,
                Contract.KIND_ALL_OF -> {
                    sb.append(rule.kind).append('/').append(rule.pattern).append('\n')
                }
            }
        }
        val settings = SettingsStore.load(context)
        val data = sb.toString()
        val version = fingerprint(data, settings)
        // The file channel goes first: Remote Preferences reach a running target process as a
        // cached snapshot, which stays empty after the module app is reinstalled until that
        // process restarts. The remote file is read on demand and always current.
        val fileStatus = runCatching { publishRemoteFile(data, version, settings) }
            .fold({ "ok" }, { failure -> "failed: $failure" })
        ModuleLogger.log(
            "remote snapshot published version=$version rules=${limited.size} " +
                "bytes=${data.length} file=$fileStatus"
        )
        val prefs = XAdApplication.remotePreferences(Contract.PREF_SNAPSHOT)
        check(prefs.edit()
            .putString(Contract.KEY_SNAPSHOT_DATA, data)
            .putLong(Contract.KEY_SNAPSHOT_VERSION, version)
            .putInt(Contract.KEY_DISPLAY_MODE, settings.displayMode)
            .putBoolean(Contract.KEY_OPT_USERNAME, settings.optUsername)
            .putBoolean(Contract.KEY_OPT_EMOJI, settings.optEmoji)
            .putBoolean(Contract.KEY_OPT_SPECIAL_CHARS, settings.optSpecialChars)
            .putBoolean(Contract.KEY_OPT_GROK, settings.optGrok)
            .putBoolean(Contract.KEY_SKIP_VERIFIED, settings.skipVerified)
            .putBoolean(Contract.KEY_RECORD_VIEWS, settings.recordViews)
            .putBoolean(Contract.KEY_LOGGING_ENABLED, settings.loggingEnabled)
            .putStringSet(Contract.KEY_WHITELIST_USERS, settings.whitelistUsers)
            .putString(Contract.KEY_MARK_TEXT, settings.markText)
            .commit()) {
            "Remote Preferences 提交失败"
        }
    }

    /** Mirrors the snapshot (settings header + rules) into the module's remote file. */
    private fun publishRemoteFile(data: String, version: Long, settings: SettingsStore.Settings) {
        val header = buildString {
            append("#v=").append(version).append('\n')
            append("#mode=").append(settings.displayMode).append('\n')
            append("#optUsername=").append(settings.optUsername).append('\n')
            append("#optEmoji=").append(settings.optEmoji).append('\n')
            append("#optSpecialChars=").append(settings.optSpecialChars).append('\n')
            append("#optGrok=").append(settings.optGrok).append('\n')
            append("#skipVerified=").append(settings.skipVerified).append('\n')
            append("#recordViews=").append(settings.recordViews).append('\n')
            append("#loggingEnabled=").append(settings.loggingEnabled).append('\n')
            append("#mark=").append(settings.markText).append('\n')
            append("#whitelist=").append(settings.whitelistUsers.joinToString("\t")).append('\n')
        }
        val service = XAdApplication.requireXposedService()
        val descriptor = service.openRemoteFile(Contract.SNAPSHOT_FILE)
            ?: error("openRemoteFile 返回 null")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { stream ->
            stream.channel.truncate(0)
            stream.write(header.toByteArray(Charsets.UTF_8))
            stream.write(data.toByteArray(Charsets.UTF_8))
        }
    }

    private fun fingerprint(data: String, settings: SettingsStore.Settings): Long {
        val canonicalSettings = buildString {
            append(settings.displayMode).append('|')
            append(settings.optUsername).append('|')
            append(settings.optEmoji).append('|')
            append(settings.optSpecialChars).append('|')
            append(settings.optGrok).append('|')
            append(settings.skipVerified).append('|')
            append(settings.recordViews).append('|')
            append(settings.loggingEnabled).append('|')
            append(settings.markText).append('|')
            settings.whitelistUsers.sorted().forEach { append(it).append('|') }
        }
        val digestInput = data + '\u0000' + canonicalSettings
        val digest = MessageDigest.getInstance("MD5")
            .digest(digestInput.toByteArray(Charsets.UTF_8))
        var hash = 0L
        for (i in 0 until 8) {
            hash = (hash shl 8) or (digest[i].toLong() and 0xFF)
        }
        return if (hash == 0L) 1L else hash
    }
}

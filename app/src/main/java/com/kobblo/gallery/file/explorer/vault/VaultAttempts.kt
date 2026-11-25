package com.kobblo.gallery.file.explorer.vault

import android.content.Context
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.security.LockStore

/**
 * Wrong Vault passwords in a row, and the pause they've earned, on the same schedule as Kobblo's
 * lock: every fifth wrong try starts a pause of 30 seconds, then 1, 5, 15 and 60 minutes. Kept with the
 * Vault's own settings, so they stay on this phone and go with the Vault.
 */
internal object VaultAttempts {
    private const val KeyFailures = "vault_failures"
    private const val KeyLockout = "vault_lockout_until"

    private fun prefs(context: Context) = context.getSharedPreferences("kobblo_vault_prefs", Context.MODE_PRIVATE)

    /** How long the current pause has left, or 0. */
    fun lockedOutFor(context: Context, now: Long = System.currentTimeMillis()): Long =
        (prefs(context).getLong(KeyLockout, 0L) - now).coerceAtLeast(0L)

    /** Counts a wrong password, and starts a pause on every fifth in a row. */
    fun recordFailure(context: Context, now: Long = System.currentTimeMillis()) {
        val failures = prefs(context).getInt(KeyFailures, 0) + 1
        prefs(context).edit().apply {
            putInt(KeyFailures, failures)
            if (failures % LockStore.MaxAttempts == 0) putLong(KeyLockout, now + LockStore.lockoutMs(failures))
        }.commit()
    }

    /** Opened, by password, fingerprint or recovery key: the count starts again. */
    fun recordSuccess(context: Context) {
        prefs(context).edit().remove(KeyFailures).remove(KeyLockout).apply()
    }

    /** "Too many tries", with the wait in seconds, or in minutes, rounded up, from two minutes on. */
    fun waitMessage(context: Context, waitMs: Long): String =
        if (waitMs < 120_000L) ((waitMs + 999) / 1000).toInt().let { context.resources.getQuantityString(R.plurals.lock_too_many_seconds_count, it, it) }
        else ((waitMs + 59_999) / 60_000).toInt().let { context.resources.getQuantityString(R.plurals.lock_too_many_minutes_count, it, it) }
}

/** How long the Vault stays open in the background. Screen-off locks it whatever this says. */
object VaultLockAfter {
    private const val Key = "vault_lock_minutes"

    /** What Lock Vault After offers, in minutes. 0 is as soon as it's left. */
    val MinuteChoices = listOf(0, 1, 5, 15)

    fun minutes(context: Context): Int =
        context.getSharedPreferences("kobblo_prefs", Context.MODE_PRIVATE).getInt(Key, 5)

    fun setMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences("kobblo_prefs", Context.MODE_PRIVATE).edit().putInt(Key, minutes).apply()
    }
}

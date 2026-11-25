package com.kobblo.gallery.file.explorer.security

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How the Kobblo lock is entered. */
enum class LockType { Pin, Pattern, Password }

enum class LockCheck { Correct, Wrong, NotSet, LockedOut }

/** Why a new secret can't be used. */
enum class LockProblem { PinLength, PinDigits, PatternLength, PasswordLength, RetiredDefault }

/** The little the store needs from preferences, so tests can run without Android. */
internal interface LockPrefs {
    fun getString(key: String): String?
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getBoolean(key: String, default: Boolean): Boolean

    /** Applies every change at once and saves it before returning, or returns false and changes nothing. */
    fun commit(change: LockPrefsEdit.() -> Unit): Boolean
}

internal interface LockPrefsEdit {
    fun putString(key: String, value: String)
    fun putInt(key: String, value: Int)
    fun putLong(key: String, value: Long)
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
}

private class SharedLockPrefs(private val prefs: SharedPreferences) : LockPrefs {
    override fun getString(key: String) = prefs.getString(key, null)
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun commit(change: LockPrefsEdit.() -> Unit): Boolean {
        val editor = prefs.edit()
        object : LockPrefsEdit {
            override fun putString(key: String, value: String) { editor.putString(key, value) }
            override fun putInt(key: String, value: Int) { editor.putInt(key, value) }
            override fun putLong(key: String, value: Long) { editor.putLong(key, value) }
            override fun putBoolean(key: String, value: Boolean) { editor.putBoolean(key, value) }
            override fun remove(key: String) { editor.remove(key) }
        }.change()
        return editor.commit()
    }
}

/**
 * Kobblo's own lock: a PIN, a pattern or a password, separate from the phone's screen lock, so
 * knowing the phone's PIN doesn't open locked items or the app. It guards locked items and App Lock.
 * The Vault keeps its own password.
 *
 * Only a salted Argon2id hash is kept ([LockHash]), never the secret. A password set by an older
 * version, stored as a SHA-256, still works and is replaced the first time it's entered. Nothing stored
 * means no lock: older versions treated that as the password 0000, which this refuses as a new secret.
 *
 * Wrong tries are counted across launches. Every fifth in a row starts a pause, longer each round, so a
 * few honest slips cost 30 seconds while guessing a short PIN takes months.
 *
 * Checking and setting hash on [dispatcher], one at a time, so they're safe from the main thread.
 */
class LockStore internal constructor(
    private val prefs: LockPrefs,
    private val cost: Argon2Cost = Argon2Cost.Default,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()

    val isSet: Boolean get() = prefs.getString(KeyHash) != null

    /** The lock's kind. A password from an older version has no kind stored, and is a password. */
    val type: LockType?
        get() {
            if (!isSet) return null
            val name = prefs.getString(KeyType) ?: return LockType.Password
            return LockType.entries.firstOrNull { it.name == name } ?: LockType.Password
        }

    /** A PIN's length, so the pad can check as soon as enough digits are in. 0 for anything else. */
    val pinLength: Int get() = if (type == LockType.Pin) prefs.getInt(KeyPinLength, 0) else 0

    /** Fingerprint or face as a shortcut in front of the lock. Only offered once there's a lock. */
    val biometrics: Boolean get() = prefs.getBoolean(KeyBiometrics, false)

    fun setBiometrics(on: Boolean): Boolean = prefs.commit { putBoolean(KeyBiometrics, on) }

    suspend fun verify(secret: String): LockCheck = exclusive {
        val stored = prefs.getString(KeyHash) ?: return@exclusive LockCheck.NotSet
        if (lockedOutFor() > 0) return@exclusive LockCheck.LockedOut
        if (!LockHash.matches(secret, stored)) {
            recordFailure()
            return@exclusive LockCheck.Wrong
        }
        prefs.commit {
            remove(KeyFailures)
            remove(KeyLockout)
            // A full disk or a tight heap mustn't cost anyone an unlock. The next one tries again.
            if (LockHash.isLegacy(stored)) runCatching { putString(KeyHash, LockHash.hash(secret, cost)) }
        }
        LockCheck.Correct
    }

    /**
     * Sets a new lock, replacing any other. The caller has already made sure whoever is setting it may:
     * they entered the old one, or the phone's screen lock vouched for them.
     */
    suspend fun set(type: LockType, secret: String): Boolean = exclusive {
        if (problem(type, secret) != null) return@exclusive false
        val hash = LockHash.hash(secret, cost)
        prefs.commit {
            putString(KeyHash, hash)
            putString(KeyType, type.name)
            if (type == LockType.Pin) putInt(KeyPinLength, secret.length) else remove(KeyPinLength)
            remove(KeyFailures)
            remove(KeyLockout)
        }
    }

    /** Clears the lock and everything it switches on, after the phone's screen lock vouched for a reset. */
    fun clear(): Boolean = prefs.commit {
        remove(KeyHash)
        remove(KeyType)
        remove(KeyPinLength)
        remove(KeyFailures)
        remove(KeyLockout)
        remove(KeyBiometrics)
    }

    fun failedAttempts(): Int = prefs.getInt(KeyFailures, 0)

    /** How long the current pause has left, or 0. */
    fun lockedOutFor(): Long = (prefs.getLong(KeyLockout, 0L) - now()).coerceAtLeast(0L)

    /** Tries left before the next pause. */
    fun triesLeft(): Int = MaxAttempts - failedAttempts() % MaxAttempts

    private fun recordFailure() {
        val failures = failedAttempts() + 1
        prefs.commit {
            putInt(KeyFailures, failures)
            if (failures % MaxAttempts == 0) putLong(KeyLockout, now() + lockoutMs(failures))
        }
    }

    private suspend fun <T> exclusive(block: () -> T): T = mutex.withLock { withContext(dispatcher) { block() } }

    companion object {
        const val MaxAttempts = 5
        const val MinLength = 4
        const val MaxPinLength = 12

        // Older versions shipped with this as the password and printed it on the unlock dialog.
        private const val RetiredDefault = "0000"

        /** The pause after each round of [MaxAttempts] wrong tries in a row. The last repeats. */
        private val Lockouts = longArrayOf(30_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L)

        fun lockoutMs(failures: Int): Long = Lockouts[(failures / MaxAttempts - 1).coerceIn(0, Lockouts.lastIndex)]

        /** Why [secret] can't be a new lock of [type], or null if it can. */
        fun problem(type: LockType, secret: String): LockProblem? = when {
            type == LockType.Pin && secret.any { it !in '0'..'9' } -> LockProblem.PinDigits
            type == LockType.Pin && secret.length !in MinLength..MaxPinLength -> LockProblem.PinLength
            type == LockType.Pattern && secret.length < MinLength -> LockProblem.PatternLength
            type == LockType.Password && secret.length < MinLength -> LockProblem.PasswordLength
            secret == RetiredDefault -> LockProblem.RetiredDefault
            else -> null
        }

        // The keys older versions used for the password and fingerprint, so both carry over.
        internal const val KeyHash = "lock_password_hash"
        internal const val KeyBiometrics = "lock_biometric_enabled"
        internal const val KeyType = "lock_type"
        internal const val KeyPinLength = "lock_pin_length"
        internal const val KeyFailures = "lock_failures"
        internal const val KeyLockout = "lock_lockout_until"

        @Volatile private var instance: LockStore? = null

        /** The one store, so its one-at-a-time rule holds across the app. */
        fun get(context: Context): LockStore = instance ?: synchronized(this) {
            instance ?: LockStore(
                SharedLockPrefs(context.applicationContext.getSharedPreferences("kobblo_prefs", Context.MODE_PRIVATE))
            ).also { instance = it }
        }
    }
}

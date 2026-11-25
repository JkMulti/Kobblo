package com.kobblo.gallery.file.explorer.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class LockStoreTest {
    private val fastCost = Argon2Cost(memoryKib = 64, iterations = 1, parallelism = 1)

    private class FakePrefs : LockPrefs {
        val values = HashMap<String, Any>()
        var commitsSucceed = true

        override fun getString(key: String) = values[key] as String?
        override fun getInt(key: String, default: Int) = values[key] as Int? ?: default
        override fun getLong(key: String, default: Long) = values[key] as Long? ?: default
        override fun getBoolean(key: String, default: Boolean) = values[key] as Boolean? ?: default
        override fun commit(change: LockPrefsEdit.() -> Unit): Boolean {
            if (!commitsSucceed) return false
            object : LockPrefsEdit {
                override fun putString(key: String, value: String) { values[key] = value }
                override fun putInt(key: String, value: Int) { values[key] = value }
                override fun putLong(key: String, value: Long) { values[key] = value }
                override fun putBoolean(key: String, value: Boolean) { values[key] = value }
                override fun remove(key: String) { values.remove(key) }
            }.change()
            return true
        }
    }

    private var clock = 1_000_000L
    private fun store(prefs: FakePrefs) = LockStore(prefs, fastCost, Dispatchers.Unconfined) { clock }

    // What older versions wrote for a password.
    private fun legacyHash(password: String): String =
        MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun nothingStoredIsNoLock_notTheOldDefault() = runBlocking {
        val lock = store(FakePrefs())
        assertFalse(lock.isSet)
        assertNull(lock.type)
        assertEquals(LockCheck.NotSet, lock.verify("0000"))
    }

    @Test
    fun aPinIsStoredHashedAndOpensOnlyWithItself() = runBlocking {
        val prefs = FakePrefs()
        val lock = store(prefs)
        assertTrue(lock.set(LockType.Pin, "482913"))
        val stored = prefs.getString(LockStore.KeyHash)!!
        assertTrue(stored.startsWith("\$argon2id\$v=19\$"))
        assertFalse(stored.contains("482913"))
        assertEquals(LockType.Pin, lock.type)
        assertEquals(6, lock.pinLength)
        assertEquals(LockCheck.Correct, lock.verify("482913"))
        assertEquals(LockCheck.Wrong, lock.verify("482914"))
    }

    @Test
    fun theSameSecretHashesDifferentlyEachTime() = runBlocking {
        val prefs = FakePrefs()
        val lock = store(prefs)
        lock.set(LockType.Password, "correct horse")
        val first = prefs.getString(LockStore.KeyHash)
        lock.set(LockType.Password, "correct horse")
        assertNotEquals(first, prefs.getString(LockStore.KeyHash))
    }

    @Test
    fun anOlderPasswordStillOpens_asAPassword_andIsUpgraded() = runBlocking {
        val prefs = FakePrefs()
        prefs.values[LockStore.KeyHash] = legacyHash("hunter22")
        val lock = store(prefs)
        assertEquals(LockType.Password, lock.type)
        assertEquals(LockCheck.Correct, lock.verify("hunter22"))
        val upgraded = prefs.getString(LockStore.KeyHash)!!
        assertTrue(upgraded.startsWith("\$argon2id\$"))
        assertEquals(LockCheck.Correct, lock.verify("hunter22"))
    }

    @Test
    fun anOlderPasswordThatTodaysRulesRefuseStillOpens() = runBlocking {
        val prefs = FakePrefs()
        prefs.values[LockStore.KeyHash] = legacyHash("0000")
        assertEquals(LockCheck.Correct, store(prefs).verify("0000"))
    }

    @Test
    fun aDamagedValueOpensNothing() = runBlocking {
        val prefs = FakePrefs()
        prefs.values[LockStore.KeyHash] = "\$argon2id\$v=19\$m=64,t=1,p=1\$!!!\$???"
        val lock = store(prefs)
        assertEquals(LockCheck.Wrong, lock.verify(""))
        assertEquals(LockCheck.Wrong, lock.verify("0000"))
    }

    @Test
    fun theRulesForANewLock() {
        assertEquals(LockProblem.PinLength, LockStore.problem(LockType.Pin, "123"))
        assertEquals(LockProblem.PinLength, LockStore.problem(LockType.Pin, "1234567890123"))
        assertEquals(LockProblem.PinDigits, LockStore.problem(LockType.Pin, "12a4"))
        assertEquals(LockProblem.RetiredDefault, LockStore.problem(LockType.Pin, "0000"))
        assertEquals(LockProblem.RetiredDefault, LockStore.problem(LockType.Password, "0000"))
        assertEquals(LockProblem.PatternLength, LockStore.problem(LockType.Pattern, "012"))
        assertEquals(LockProblem.PasswordLength, LockStore.problem(LockType.Password, "abc"))
        assertNull(LockStore.problem(LockType.Pin, "2580"))
        assertNull(LockStore.problem(LockType.Pattern, "03478"))
        assertNull(LockStore.problem(LockType.Password, "abcd"))
    }

    @Test
    fun aRefusedSecretIsNotSaved() = runBlocking {
        val prefs = FakePrefs()
        val lock = store(prefs)
        assertFalse(lock.set(LockType.Pin, "0000"))
        assertFalse(lock.isSet)
    }

    @Test
    fun fiveWrongTriesPauseIt_andTheRightOneWaits() = runBlocking {
        val prefs = FakePrefs()
        val lock = store(prefs)
        lock.set(LockType.Pin, "2580")
        repeat(4) { assertEquals(LockCheck.Wrong, lock.verify("1111")) }
        assertEquals(1, lock.triesLeft())
        assertEquals(LockCheck.Wrong, lock.verify("1111"))
        assertEquals(30_000L, lock.lockedOutFor())
        // Even the right PIN waits out the pause.
        assertEquals(LockCheck.LockedOut, lock.verify("2580"))
        clock += 30_000L
        assertEquals(LockCheck.Correct, lock.verify("2580"))
        assertEquals(0, lock.failedAttempts())
    }

    @Test
    fun eachRoundOfWrongTriesPausesLonger() {
        assertEquals(30_000L, LockStore.lockoutMs(5))
        assertEquals(60_000L, LockStore.lockoutMs(10))
        assertEquals(5 * 60_000L, LockStore.lockoutMs(15))
        assertEquals(15 * 60_000L, LockStore.lockoutMs(20))
        assertEquals(60 * 60_000L, LockStore.lockoutMs(25))
        assertEquals(60 * 60_000L, LockStore.lockoutMs(100))
    }

    @Test
    fun clearingTheLockAlsoTurnsOffFingerprint() = runBlocking {
        val prefs = FakePrefs()
        val lock = store(prefs)
        lock.set(LockType.Pattern, "01258")
        lock.setBiometrics(true)
        assertTrue(lock.clear())
        assertFalse(lock.isSet)
        assertFalse(lock.biometrics)
    }

    @Test
    fun aSaveThatFailsReportsIt() = runBlocking {
        val prefs = FakePrefs()
        prefs.commitsSucceed = false
        assertFalse(store(prefs).set(LockType.Pin, "2580"))
    }

    @Test
    fun anAccentedPasswordMatchesHoweverItWasTyped() = runBlocking {
        val prefs = FakePrefs()
        val lock = store(prefs)
        lock.set(LockType.Password, "café")
        assertEquals(LockCheck.Correct, lock.verify("café"))
    }
}

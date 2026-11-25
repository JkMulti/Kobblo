package com.kobblo.gallery.file.explorer.vault

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.HapticsSettings
import com.kobblo.gallery.file.explorer.haptics.HapticsController
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.theme.KobbloTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Drives the real Vault screens against the real preferences of this install, so it refuses to run
 * if a vault is already here: it must only ever touch the throwaway vault it makes for itself.
 *
 * Run it under a separate application id on any device someone actually uses. `connectedAndroidTest`
 * uninstalls the app when it finishes, which would take a real Vault with it. A separate id still
 * shows as a second, identical app icon, so warn whoever owns the device before installing it.
 */
@RunWith(AndroidJUnit4::class)
class VaultRecoveryFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("kobblo_vault_prefs", Context.MODE_PRIVATE)
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)
    private val throwawayFile get() = File(VaultManager.getVaultDirectory(context), "throwaway-vault-test.txt")
    private var ownsVault = false
    private var closeCount = 0

    @Before
    fun requireNoExistingVault() {
        // A locked phone otherwise fails every test with a misleading "No compose hierarchies found".
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock the device first" }
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

        assumeFalse("A vault already exists on this install", prefs.all.isNotEmpty())
        assumeTrue("Vault files already exist on this install", VaultManager.getVaultFiles(context).isEmpty())
        ownsVault = true
    }

    /** It went in as plaintext, the way an older version would have left it. */
    private fun assertVaultFileIsEncryptedAndIntact() {
        assertFalse("Plaintext survived unlocking", throwawayFile.exists())
        val file = VaultManager.getVaultFiles(context).single { it.name == throwawayFile.name }
        assertFalse("Still stored in the clear", File(file.path).readText(Charsets.ISO_8859_1).contains(FILE_CONTENT))
        val copy = VaultManager.openDecryptedCopy(context, file)!!
        assertEquals(FILE_CONTENT, File(copy.path).readText())
        VaultManager.discardDecryptedCopy(copy.path)
    }

    @After
    fun removeThrowawayVault() {
        if (!ownsVault) return
        VaultManager.lock(context)
        prefs.edit().clear().commit()
        VaultManager.getVaultDirectory(context).deleteRecursively()
    }

    @Test
    fun newVault_recoversWithItsKeyAndKeepsItsFiles() {
        showVault()
        val recoveryKey = runSetupWizard(password = "first-password", hint = "throwaway hint")

        val stored = storedPrefs()
        assertFalse("Password reached disk", stored.contains("first-password"))
        assertFalse("Recovery key reached disk", stored.contains(recoveryKey))
        assertFalse("Legacy hash written", stored.contains("vault_password_hash"))
        assertFalse("Key screen left the window secure", isWindowSecure())

        throwawayFile.writeText(FILE_CONTENT)
        lock()

        unlock("wrong-password")
        waitForText("Incorrect password", substring = true)

        tap("Forgot Password? Use Recovery Key")
        waitForText("Use Your Recovery Key")
        assertTrue("Entering a key should block screenshots", isWindowSecure())

        pressBack()
        waitForText("Unlock Vault")
        assertEquals("Back should leave recovery, not close the vault", 0, closeCount)
        assertFalse("Leaving recovery left the window secure", isWindowSecure())
        tap("Forgot Password? Use Recovery Key")
        waitForText("Use Your Recovery Key")

        type("Recovery Key", recoveryKey.dropLast(1))
        tap("Continue")
        waitForText("That isn't a complete recovery key", substring = true)

        type("Recovery Key", RecoveryKey.generate())
        tap("Continue")
        waitForText("That key doesn't open this vault", substring = true)

        type("Recovery Key", RecoveryKey.formatted(recoveryKey).lowercase())
        tap("Continue")
        waitForText("Set a New Password")
        assertFalse("Choosing a password shouldn't block screenshots", isWindowSecure())

        type("New Password", "second-password")
        type("Retype Password", "second-password")
        tap("Save and Open Vault")
        waitForBrowser()
        waitForText("1 file ·", substring = true)
        assertVaultFileIsEncryptedAndIntact()
        assertEquals(listOf(throwawayFile.name), VaultManager.getVaultFiles(context).map { it.name })
        assertFalse("New password reached disk", storedPrefs().contains("second-password"))

        lock()
        unlock("first-password")
        waitForText("Incorrect password", substring = true)
        unlock("second-password")
        waitForBrowser()
        assertEquals(RecoveryKeyCheck.Valid, VaultManager.checkRecoveryKey(context, recoveryKey))
    }

    @Test
    fun legacyVault_keepsWorkingAndAdoptsARecoveryKey() {
        // Exactly what setupVault wrote before recovery keys.
        prefs.edit()
            .putBoolean("vault_setup_completed", true)
            .putString("vault_password_hash", sha256("legacy-password"))
            .putString("vault_password_hint", "throwaway hint")
            .putLong("vault_created_at", 1_700_000_000_000L)
            .commit()
        throwawayFile.writeText(FILE_CONTENT)

        showVault()
        waitForText("Unlock Vault")
        assertNoText("Forgot Password? Use Recovery Key")

        unlock("wrong-password")
        waitForText("Incorrect password", substring = true)
        assertTrue("Wrong password upgraded the vault", storedPrefs().contains("vault_password_hash"))

        unlock("legacy-password")
        waitForBrowser()
        waitForText("1 file ·", substring = true)
        val upgraded = storedPrefs()
        assertFalse("Legacy hash survived the upgrade", upgraded.contains("vault_password_hash"))
        assertFalse("Password reached disk", upgraded.contains("legacy-password"))

        compose.onNodeWithContentDescription("Password and Recovery Key").performClick()
        waitForText("Right now, your password is the only way back into this vault.")
        pressBack()
        waitForBrowser()

        tap("Add a Recovery Key")
        waitForText("Did you save your password to a file?")
        type("Vault Password", "wrong-password")
        tap("Continue")
        waitForText("Incorrect password", substring = true)
        type("Vault Password", "legacy-password")
        tap("Continue")
        val recoveryKey = readRecoveryKey()
        assertTrue("Key screen should block screenshots", isWindowSecure())
        acknowledgeAndFinish("Finish")

        waitForBrowser()
        assertNoText("Add a Recovery Key")
        assertEquals(RecoveryKeyCheck.Valid, VaultManager.checkRecoveryKey(context, recoveryKey))

        lock()
        tap("Forgot Password? Use Recovery Key")
        waitForText("Use Your Recovery Key")
        type("Recovery Key", recoveryKey)
        tap("Continue")
        waitForText("Set a New Password")
        type("New Password", "recovered-password")
        type("Retype Password", "recovered-password")
        tap("Save and Open Vault")
        waitForBrowser()
        waitForText("1 file ·", substring = true)
        assertVaultFileIsEncryptedAndIntact()

        lock()
        unlock("legacy-password")
        waitForText("Incorrect password", substring = true)
        unlock("recovered-password")
        waitForBrowser()
    }

    @Test
    fun securityScreen_replacesTheKeyAndChangesThePassword() {
        showVault()
        val firstKey = runSetupWizard(password = "first-password", hint = "")

        compose.onNodeWithContentDescription("Password and Recovery Key").performClick()
        waitForText("Replace Recovery Key")

        pressBack()
        waitForBrowser()
        assertEquals("Back should leave settings, not close the vault", 0, closeCount)
        compose.onNodeWithContentDescription("Password and Recovery Key").performClick()
        waitForText("Replace Recovery Key")

        tap("Replace Recovery Key")
        waitForText("Your current key stops working as soon as you finish.")
        type("Vault Password", "first-password")
        tap("Continue")
        val secondKey = readRecoveryKey()
        assertFalse("A replacement must be a new key", secondKey == firstKey)
        assertEquals("Replacing must wait until Finish", RecoveryKeyCheck.Valid, VaultManager.checkRecoveryKey(context, firstKey))
        acknowledgeAndFinish("Finish")
        waitForText("Replace Recovery Key")
        assertEquals(RecoveryKeyCheck.WrongKey, VaultManager.checkRecoveryKey(context, firstKey))
        assertEquals(RecoveryKeyCheck.Valid, VaultManager.checkRecoveryKey(context, secondKey))

        tap("Change Password")
        waitForText("Current Password")
        type("Current Password", "wrong-password")
        type("New Password", "second-password")
        type("Retype Password", "second-password")
        tap("Change Password")
        waitForText("Incorrect password", substring = true)
        type("Current Password", "first-password")
        tap("Change Password")
        waitForText("Replace Recovery Key")

        compose.onNodeWithContentDescription("Back").performClick()
        waitForBrowser()
        lock()
        unlock("first-password")
        waitForText("Incorrect password", substring = true)
        unlock("second-password")
        waitForBrowser()
        assertEquals(RecoveryKeyCheck.Valid, VaultManager.checkRecoveryKey(context, secondKey))
    }

    @Test
    fun copyKey_isMarkedSensitiveAndLeavesTheClipboard() {
        val recoveryKey = copyKeyDuringSetup()
        val clip = clipboard.primaryClip!!
        assertEquals(RecoveryKey.formatted(recoveryKey), clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))

        compose.onNodeWithContentDescription("Previous step").performClick()
        waitForText("Create Vault Password")
        assertFalse("Key stayed on the clipboard", clipboardHolds(recoveryKey))
    }

    @Test
    fun copyKey_leavesTheClipboardAfterAMinute() {
        val recoveryKey = copyKeyDuringSetup()

        compose.mainClock.advanceTimeBy(59_000)
        assertTrue("Key left the clipboard early", clipboardHolds(recoveryKey))

        compose.mainClock.advanceTimeBy(2_000)
        waitForText("Copy Key")
        assertFalse("Key stayed on the clipboard", clipboardHolds(recoveryKey))
    }

    @Test
    fun copyKey_leavesSomethingCopiedLaterAlone() {
        copyKeyDuringSetup()
        clipboard.setPrimaryClip(ClipData.newPlainText("copied later", "not the recovery key"))

        compose.mainClock.advanceTimeBy(61_000)
        waitForText("Copy Key")
        compose.onNodeWithContentDescription("Previous step").performClick()
        waitForText("Create Vault Password")
        assertEquals("not the recovery key", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    /** Off by default: this overwrites the device's clipboard, which on a real phone is someone's. */
    private fun copyKeyDuringSetup(): String {
        assumeTrue(
            "Pass -e vaultClipboard true to let this test overwrite the clipboard",
            InstrumentationRegistry.getArguments().getString("vaultClipboard") == "true"
        )
        showVault()
        goToKeyStep(password = "first-password", hint = "")
        val recoveryKey = readRecoveryKey()

        tap("Copy Key")
        waitForText("Copied")
        return recoveryKey
    }

    private fun clipboardHolds(recoveryKey: String): Boolean =
        clipboard.primaryClip?.getItemAt(0)?.text?.contains(recoveryKey.take(4)) == true

    private fun showVault() {
        compose.setContent {
            KobbloTheme {
                CompositionLocalProvider(LocalHaptics provides HapticsController(null) { HapticsSettings() }) {
                    VaultScreen(state = AppState(), onAction = {}, onClose = { closeCount++ })
                }
            }
        }
    }

    private fun goToKeyStep(password: String, hint: String) {
        tap("Set Up Vault Password")
        waitForText("Create Vault Password")
        type("Vault Password", password)
        type("Retype Password", password)
        if (hint.isNotEmpty()) type("Password Hint (Recommended)", hint)
        tap("Continue to Recovery Key")
    }

    private fun runSetupWizard(password: String, hint: String): String {
        waitForText("Welcome to Vault")
        goToKeyStep(password, hint)
        val recoveryKey = readRecoveryKey()
        assertTrue("Key screen should block screenshots", isWindowSecure())
        assertFalse("Finish must wait for the acknowledgement", VaultManager.isVaultSetup(context))
        acknowledgeAndFinish("Finish and Open Vault")
        waitForBrowser()
        assertTrue(VaultManager.isVaultSetup(context))
        return recoveryKey
    }

    private fun acknowledgeAndFinish(button: String) {
        compose.onNode(hasClickAction() and hasText("I saved my recovery key", substring = true))
            .performScrollTo()
            .performClick()
        tap(button)
    }

    private fun readRecoveryKey(): String {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(RECOVERY_KEY_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        val node = compose.onNodeWithTag(RECOVERY_KEY_TEST_TAG).fetchSemanticsNode()
        val key = node.config[SemanticsProperties.Text].joinToString("") { it.text }
        assertEquals(key, RecoveryKey.parse(key))
        return key
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    private fun lock() {
        compose.onNodeWithContentDescription("Lock Vault").performClick()
        waitForText("Unlock Vault")
    }

    private fun unlock(password: String) {
        type("Vault Password", password)
        tap("Unlock Vault")
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithContentDescription("Lock Vault").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Incorrect password", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun field(label: String): SemanticsNodeInteraction =
        compose.onNode(hasSetTextAction() and hasText(label))

    private fun type(label: String, text: String) {
        field(label).performScrollTo().performTextReplacement(text)
    }

    private fun tap(text: String) {
        // The keyboard animates in real time, not on the test clock, so it can move the layout out
        // from under a click. Closing it first keeps the button where the click lands.
        Espresso.closeSoftKeyboard()
        compose.onNode(hasClickAction() and hasText(text)).performScrollTo().performClick()
    }

    /** The open vault: its list, which alone has a Lock Vault button. */
    private fun waitForBrowser() {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithContentDescription("Lock Vault").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertNoText(text: String) {
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty())
    }

    private fun isWindowSecure(): Boolean {
        compose.waitForIdle()
        return compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    private fun storedPrefs(): String =
        File(context.applicationInfo.dataDir, "shared_prefs/kobblo_vault_prefs.xml").readText()

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val FILE_CONTENT = "Throwaway file for the Vault recovery test."
    }
}

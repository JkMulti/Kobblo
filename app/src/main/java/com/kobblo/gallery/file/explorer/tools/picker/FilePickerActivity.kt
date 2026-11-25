package com.kobblo.gallery.file.explorer.tools.picker

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import coil.Coil
import com.kobblo.gallery.file.explorer.DarkSurfaceStyle
import com.kobblo.gallery.file.explorer.HapticsSettings
import com.kobblo.gallery.file.explorer.ThemeMode
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.haptics.rememberHapticsController
import com.kobblo.gallery.file.explorer.tools.storage.StorageVolumes
import com.kobblo.gallery.file.explorer.ui.theme.KobbloTheme
import com.kobblo.gallery.file.explorer.viewer.decoder.ViewerImages

/**
 * Answers other apps asking for a file. It runs in the caller's task and shares nothing with the
 * main screen's state, so returning a file doesn't disturb what Kobblo itself was showing.
 */
// A FragmentActivity, for App Lock's fingerprint prompt.
class FilePickerActivity : androidx.fragment.app.FragmentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.kobblo.gallery.file.explorer.compat.AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        StorageVolumes.start(this)
        // The same one the main screen uses, since there's only one for the whole process.
        Coil.setImageLoader { ViewerImages.buildLoader(applicationContext) }

        val request = intent
        val filter = MimeFilter.from(
            type = request.type,
            extraMimeTypes = request.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList(),
            dataUri = request.dataString
        )
        val allowMultiple = request.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) &&
            request.action != Intent.ACTION_PICK
        val requester = callingPackage?.let { pkg ->
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                null
            }
        }

        setContent {
            PickerTheme {
                // Another app opening the picker gets past nothing: App Lock asks here too.
                com.kobblo.gallery.file.explorer.security.AppLockCover(this) {
                    PickerScreen(
                        filter = filter,
                        allowMultiple = allowMultiple,
                        requesterName = requester,
                        onCancel = {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                        onPicked = ::finishWith
                    )
                }
            }
        }
    }

    private fun finishWith(paths: List<String>) {
        val uris = paths.mapNotNull { StorageDocumentsProvider.uriFor(this, it) }
        if (uris.isEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val result = Intent().apply {
            data = uris.first()
            clipData = clipOf(uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Asking for a document is asking to keep it, the way the system's own picker allows.
            if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun clipOf(uris: List<Uri>): ClipData {
        val types = uris.map { contentResolver.getType(it) ?: ClipDescription.MIMETYPE_UNKNOWN }.distinct()
        val clip = ClipData(ClipDescription("Files", types.toTypedArray()), ClipData.Item(uris.first()))
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return clip
    }
}

/** The app's theme, read from the same settings the main screen saves. */
@Composable
internal fun PickerTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences("kobblo_prefs", android.content.Context.MODE_PRIVATE)
    val mode = try {
        ThemeMode.valueOf(prefs.getString("theme_mode", null) ?: "SYSTEM")
    } catch (_: Exception) {
        ThemeMode.SYSTEM
    }
    val surface = try {
        DarkSurfaceStyle.valueOf(prefs.getString("dark_surface_style", null) ?: "STANDARD")
    } catch (_: Exception) {
        DarkSurfaceStyle.STANDARD
    }
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val haptics = rememberHapticsController { HapticsSettings() }
    CompositionLocalProvider(LocalHaptics provides haptics) {
        KobbloTheme(
            darkTheme = dark,
            darkSurfaceStyle = surface,
            accent = com.kobblo.gallery.file.explorer.ui.design.KobbloAccent.fromName(prefs.getString(com.kobblo.gallery.file.explorer.TintPref, null)),
            content = content
        )
    }
}

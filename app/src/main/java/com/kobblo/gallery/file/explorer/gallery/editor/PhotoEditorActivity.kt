package com.kobblo.gallery.file.explorer.gallery.editor

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.LocalContext
import com.kobblo.gallery.file.explorer.ui.design.DarkKobbloColors
import com.kobblo.gallery.file.explorer.ui.design.withAccent
import com.kobblo.gallery.file.explorer.ui.design.KobbloMaterialShapes
import com.kobblo.gallery.file.explorer.ui.design.LocalKobbloColors
import com.kobblo.gallery.file.explorer.ui.design.kobbloMaterialTypography
import java.io.File

/**
 * The photo editor, as its own activity because it opens from the viewer and has to sit above it. It
 * also keeps a large photo's memory apart from the app's, and closing it lands exactly where it began.
 */
// A FragmentActivity, for App Lock's fingerprint prompt.
class PhotoEditorActivity : androidx.fragment.app.FragmentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.kobblo.gallery.file.explorer.compat.AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(ExtraPath)
        if (path == null) {
            finish()
            return
        }
        setContent {
            // The editor is always dark, with the tint picked in Settings.
            val tint = com.kobblo.gallery.file.explorer.ui.design.KobbloAccent.fromName(
                getSharedPreferences("kobblo_prefs", MODE_PRIVATE).getString(com.kobblo.gallery.file.explorer.TintPref, null)
            )
            val design = DarkKobbloColors.withAccent(tint)
            CompositionLocalProvider(LocalKobbloColors provides design) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = design.action,
                        onPrimary = design.onAction,
                        background = design.canvas,
                        onBackground = design.ink,
                        surface = design.canvas,
                        onSurface = design.ink,
                        scrim = design.scrim
                    ),
                    typography = kobbloMaterialTypography(),
                    shapes = KobbloMaterialShapes
                ) {
                    val scope = rememberCoroutineScope()
                    val context = LocalContext.current
                    val controller = remember { EditorController(context.applicationContext, path, scope) }
                    // Kept under the lock, so an edit in progress survives it.
                    com.kobblo.gallery.file.explorer.security.AppLockCover(this@PhotoEditorActivity, keepContent = true) {
                        EditorScreen(
                            controller = controller,
                            onClose = { finish() },
                            onOtherApp = { openInOtherApp(path) }
                        )
                    }
                }
            }
        }
    }

    private fun openInOtherApp(path: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
            val intent = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(com.kobblo.gallery.file.explorer.R.string.gallery_editor_other_app)))
        } catch (e: Exception) {
            android.util.Log.w("PhotoEditor", "No other app could take the photo")
        }
    }

    companion object {
        private const val ExtraPath = "path"

        /** Opens a local photo, or says no so the caller can hand it to another app instead. */
        fun open(context: Context, path: String): Boolean {
            if (!path.startsWith("/")) return false
            val file = File(path)
            if (!file.isFile || !file.canRead()) return false
            context.startActivity(Intent(context, PhotoEditorActivity::class.java).putExtra(ExtraPath, path))
            return true
        }
    }
}

package com.kobblo.gallery.file.explorer.explorer

import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.picker.StorageDocumentsProvider
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSheetContent
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSwitch
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveTheme
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Which outside app, if any, opens a kind of file instead of the viewer built into this one. The kind is
 * the file's extension, because that's what a person means by "open my PDFs with that".
 */
object ExplorerOpenWith {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("explorer_open_with", Context.MODE_PRIVATE)
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun defaultFor(extension: String): ComponentName? =
        prefs?.getString(extension, null)?.let { ComponentName.unflattenFromString(it) }

    fun setDefault(extension: String, component: ComponentName) {
        prefs?.edit()?.putString(extension, component.flattenToString())?.apply()
    }

    fun clearDefault(extension: String) {
        prefs?.edit()?.remove(extension)?.apply()
    }

    /**
     * Opens [file] with its kind's chosen app. False means there's no choice to honour, or the app has
     * since been uninstalled, and the app should carry on as if none had been made.
     */
    fun openWithDefault(context: Context, file: FileItem): Boolean {
        if (!file.path.startsWith("/") || file.type == "folder") return false
        val extension = extensionOf(file.name)
        if (extension.isEmpty()) return false
        val component = defaultFor(extension) ?: return false
        return try {
            context.startActivity(intentFor(context, file.path).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            clearDefault(extension)
            false
        }
    }

    fun launch(context: Context, path: String, component: ComponentName): Boolean = try {
        context.startActivity(intentFor(context, path).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    fun intentFor(context: Context, path: String): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, StorageDocumentsProvider.mimeOf(File(path).name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Apps that say they can view this file. This app is left out: it's where the person already is. */
    fun candidates(context: Context, path: String): List<ResolveInfo> {
        val pm = context.packageManager
        val intent = intentFor(context, path)
        val found = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return found
            .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.exported }
            .distinctBy { it.activityInfo.packageName + "/" + it.activityInfo.name }
            .sortedBy { it.loadLabel(pm).toString().lowercase(Locale.getDefault()) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenWithSheet(file: FileItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val extension = remember(file.name) { ExplorerOpenWith.extensionOf(file.name) }
    var chosen by remember(extension) { mutableStateOf(ExplorerOpenWith.defaultFor(extension)) }
    var always by remember { mutableStateOf(false) }
    val apps by produceState<List<ResolveInfo>?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            try {
                ExplorerOpenWith.candidates(context, file.path)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    KobbloSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        ExpressiveSheetContent(
            title = stringResource(R.string.explorer_open_with),
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            Text(
                text = file.name,
                style = KobbloType.footnote,
                color = colors.smoke,
                maxLines = 1,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            )
            val current = chosen
            if (current != null) {
                val label = remember(current) { labelOf(context, current) }
                KobbloGroup {
                    KobbloRow(
                        title = stringResource(R.string.explorer_opens_always, extension.uppercase(Locale.getDefault()), label),
                        trailing = { Text(stringResource(R.string.explorer_stop), style = KobbloType.callout, color = colors.action) },
                        onClick = {
                            ExplorerOpenWith.clearDefault(extension)
                            chosen = null
                        }
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            val list = apps
            when {
                list == null -> com.kobblo.gallery.file.explorer.ui.ExpressiveCircularLoader(size = 32.dp, color = colors.action)
                list.isEmpty() -> KobbloMessage(icon = AppIcons.OpenExternal, title = stringResource(R.string.explorer_no_apps))
                else -> {
                    // Above the apps, because tapping an app is what acts on it, and a long list would hide it.
                    if (extension.isNotEmpty()) {
                        KobbloGroup {
                            KobbloRow(
                                title = stringResource(R.string.explorer_always_for, extension.uppercase(Locale.getDefault())),
                                trailing = { ExpressiveSwitch(checked = always, onCheckedChange = { always = it }) },
                                onClick = { always = !always }
                            )
                        }
                        Spacer(Modifier.height(com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.groupGap))
                    }
                    KobbloGroup {
                        list.forEachIndexed { index, app ->
                            val component = ComponentName(app.activityInfo.packageName, app.activityInfo.name)
                            AppRow(app, showDivider = index < list.lastIndex) {
                                if (always && extension.isNotEmpty()) ExplorerOpenWith.setDefault(extension, component)
                                onDismiss()
                                ExplorerOpenWith.launch(context, file.path, component)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: ResolveInfo, showDivider: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val label = remember(app) { app.loadLabel(context.packageManager).toString() }
    val icon by produceState<ImageBitmap?>(null, app) {
        value = withContext(Dispatchers.IO) {
            try {
                app.loadIcon(context.packageManager).toBitmap(96, 96).asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    KobbloRow(
        title = label,
        leading = {
            val bitmap = icon
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, modifier = Modifier.size(30.dp).clip(KobbloShapes.xs))
            } else {
                Icon(AppIcons.Apps, contentDescription = null, tint = KobbloDesign.colors.smoke, modifier = Modifier.size(26.dp))
            }
        },
        showDivider = showDivider,
        onClick = onClick
    )
}

private fun labelOf(context: Context, component: ComponentName): String = try {
    val pm = context.packageManager
    pm.getActivityInfo(component, 0).loadLabel(pm).toString()
} catch (_: Exception) {
    component.packageName
}

package com.kobblo.gallery.file.explorer.tools.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.hub.HubScreen
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/** The last crash, from Settings. */
@Composable
fun CrashLogScreen() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(CrashLog.read(context)) }
    HubScreen(
        title = stringResource(R.string.crash_last_title),
        backLabel = stringResource(R.string.settings_title),
        trailingIcon = if (text != null) AppIcons.Trash else null,
        trailingDescription = stringResource(R.string.crash_delete),
        onTrailingClick = {
            CrashLog.delete(context)
            text = null
            Hub.back()
        }
    ) {
        val log = text
        if (log == null) {
            KobbloMessage(icon = AppIcons.File, title = stringResource(R.string.crash_none))
        } else {
            CrashLogBody(log)
        }
    }
}

/** What the app opens on after a crash, in place of the screen that failed. */
@Composable
fun CrashReportScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val log = remember { CrashLog.read(context).orEmpty() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.padding(horizontal = KobbloSpace.screen, vertical = KobbloSpace.md)) {
            Text(stringResource(R.string.crash_title), style = KobbloType.title, color = colors.ink)
            Spacer(Modifier.height(KobbloSpace.xxs))
            Text(stringResource(R.string.crash_subtitle), style = KobbloType.callout, color = colors.smoke)
        }
        CrashLogBody(log, onClose)
    }
}

@Composable
private fun ColumnScope.CrashLogBody(log: String, onClose: (() -> Unit)? = null) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    SelectionContainer(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.screen)
            .clip(KobbloShapes.group)
            .background(colors.snow)
    ) {
        Text(
            text = log,
            style = KobbloType.footnote.copy(fontFamily = FontFamily.Monospace),
            color = colors.ink,
            softWrap = false,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(KobbloSpace.sm)
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(KobbloSpace.screen),
        horizontalArrangement = Arrangement.spacedBy(KobbloSpace.xs)
    ) {
        if (onClose != null) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.crash_close)) }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { copyLog(context, log) }) { Text(stringResource(R.string.crash_copy)) }
        Button(onClick = { shareLog(context, log) }) { Text(stringResource(R.string.crash_share)) }
    }
}

private fun copyLog(context: Context, log: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.crash_share_subject), log))
    Toast.makeText(context, R.string.crash_copied, Toast.LENGTH_SHORT).show()
}

private fun shareLog(context: Context, log: String) {
    val subject = context.getString(R.string.crash_share_subject)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, log)
    }
    context.startActivity(Intent.createChooser(send, subject))
}

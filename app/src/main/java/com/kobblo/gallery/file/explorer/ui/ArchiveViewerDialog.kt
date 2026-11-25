package com.kobblo.gallery.file.explorer.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.archive.ArchiveEntryInfo
import com.kobblo.gallery.file.explorer.archive.ArchiveHandler
import com.kobblo.gallery.file.explorer.archive.ArchiveHandlerFactory
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.KobbloAlertAction
import com.kobblo.gallery.file.explorer.ui.design.KobbloAlertCancel
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloLoader
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Shows what's inside an archive without unpacking it. A protected archive asks for its password
 * first, and Extract All then hands that password on, so it's asked for once.
 */
@Composable
fun ArchiveViewerDialog(archiveFile: FileItem, onDismiss: () -> Unit, onExtractRequest: (password: String?) -> Unit) {
    val look = rememberArchiveLook(archiveFile)
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val peek = look.peek
    val locked = peek == ArchivePeek.Locked

    GradientAlertDialog(
        onDismissRequest = onDismiss,
        title = { ArchiveTitle(archiveFile.name, locked) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = KobbloSpace.xxs)) {
                when (peek) {
                    ArchivePeek.Looking -> Box(Modifier.fillMaxWidth().height(140.dp), Alignment.Center) { KobbloLoader() }
                    ArchivePeek.Unreadable -> Box(Modifier.fillMaxWidth().padding(KobbloSpace.md), Alignment.Center) {
                        Text(
                            text = stringResource(R.string.polish_archive_unreadable),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    ArchivePeek.Locked -> PasswordPrompt(look, onDone = { look.unlock(scope) })
                    is ArchivePeek.Open -> ArchiveContents(peek)
                }
            }
        },
        confirmButton = {
            if (locked) {
                KobbloAlertAction(
                    label = stringResource(R.string.unlock_action),
                    enabled = !look.checking && look.password.isNotEmpty(),
                    onClick = {
                        haptics.tap()
                        look.unlock(scope)
                    }
                )
            } else {
                // Nothing to extract until the look has come to something.
                KobbloAlertAction(
                    label = stringResource(R.string.view_extract_all),
                    enabled = peek is ArchivePeek.Open,
                    onClick = {
                        haptics.tap()
                        onExtractRequest((peek as? ArchivePeek.Open)?.password)
                    }
                )
            }
        },
        dismissButton = {
            KobbloAlertCancel(
                label = stringResource(R.string.close),
                onClick = {
                    haptics.tap()
                    onDismiss()
                }
            )
        }
    )
}

/**
 * What the dialog has found out about its archive, and what's been typed into it so far.
 *
 * Each look and each password tried goes to the engine afresh through [openArchive], since the engine
 * picks its handler by sniffing the file.
 */
private class ArchiveLook(private val openArchive: suspend () -> ArchiveInside) {
    var peek by mutableStateOf<ArchivePeek>(ArchivePeek.Looking)
        private set
    var password by mutableStateOf("")
        private set
    var passwordShown by mutableStateOf(false)
    var checking by mutableStateOf(false)
        private set

    /** Why the last password tried didn't open the archive. It stands until something else is typed. */
    var refusal by mutableStateOf<UnlockAttempt?>(null)
        private set

    suspend fun look() {
        peek = peekInto(openArchive)
    }

    fun type(text: String) {
        password = text
        refusal = null
    }

    /** Tries what's been typed. The check runs in [scope], so it ends with the dialog. */
    fun unlock(scope: CoroutineScope) {
        val tried = password
        if (tried.isEmpty()) {
            refusal = UnlockAttempt.NoPassword
            return
        }
        checking = true
        refusal = null
        scope.launch {
            when (val attempt = tryPassword(tried, openArchive)) {
                is UnlockAttempt.Opened -> peek = attempt.contents
                else -> refusal = attempt
            }
            checking = false
        }
    }
}

@Composable
private fun rememberArchiveLook(archiveFile: FileItem): ArchiveLook {
    val context = LocalContext.current
    val look = remember(archiveFile) {
        val source = archiveUri(archiveFile.path)
        ArchiveLook { EngineArchive(ArchiveHandlerFactory.create(context, source), source) }
    }
    LaunchedEffect(look) { look.look() }
    return look
}

/** An archive is named by a file path, or by a `content:` address when another app handed it over. */
private fun archiveUri(path: String): Uri =
    if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(File(path))

/** The archive engine's handler, bound to the one archive being looked at. */
private class EngineArchive(private val handler: ArchiveHandler, private val source: Uri) : ArchiveInside {
    override suspend fun isEncrypted(): Boolean = handler.isEncrypted(source)
    override suspend fun entries(password: String?): List<ArchiveEntryInfo> = handler.listEntries(source, password)
    override suspend fun accepts(password: String): Boolean = handler.testPassword(source, password)
}

/** What to tell someone whose password didn't open the archive. Null for the attempt that did. */
@get:StringRes
private val UnlockAttempt.explanation: Int?
    get() = when (this) {
        is UnlockAttempt.Opened -> null
        UnlockAttempt.NoPassword -> R.string.view_archive_enter_password
        UnlockAttempt.WrongPassword -> R.string.viewer_pdf_password_wrong
        UnlockAttempt.CheckFailed -> R.string.view_archive_password_check_failed
        UnlockAttempt.ContentsUnreadable -> R.string.view_archive_contents_unreadable
    }

/** The archive's name, behind a lock while it's keeping its contents to itself. */
@Composable
private fun ArchiveTitle(name: String, locked: Boolean) {
    val colors = KobbloDesign.colors
    Row(horizontalArrangement = Arrangement.spacedBy(KobbloSpace.xs), verticalAlignment = Alignment.CenterVertically) {
        if (locked) {
            Icon(AppIcons.Lock, stringResource(R.string.view_archive_encrypted), Modifier.size(24.dp), colors.action)
        }
        Text(name, color = colors.ink, fontWeight = FontWeight.SemiBold, overflow = TextOverflow.Ellipsis, maxLines = 1)
    }
}

@Composable
private fun PasswordPrompt(look: ArchiveLook, onDone: () -> Unit) {
    val colors = KobbloDesign.colors
    val small = MaterialTheme.typography.bodySmall
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KobbloSpace.sm)) {
        Text(
            text = stringResource(R.string.view_archive_password_prompt),
            color = colors.smoke,
            style = MaterialTheme.typography.bodyMedium
        )
        ArchivePasswordField(
            value = look.password,
            onValueChange = look::type,
            onDone = onDone,
            revealed = look.passwordShown,
            onReveal = { look.passwordShown = !look.passwordShown }
        )
        look.refusal?.explanation?.let { reason ->
            Text(stringResource(reason), color = MaterialTheme.colorScheme.error, style = small)
        }
        if (look.checking) {
            Row(modifier = Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                KobbloLoader(size = 20.dp, strokeWidth = 2.dp, color = colors.action)
                Spacer(modifier = Modifier.width(KobbloSpace.xs))
                Text(stringResource(R.string.view_archive_checking_password), color = colors.smoke, style = small)
            }
        }
    }
}

/** How many files there are, then every entry in the order the archive keeps them. */
@Composable
private fun ColumnScope.ArchiveContents(open: ArchivePeek.Open) {
    val colors = KobbloDesign.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = KobbloSpace.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = pluralStringResource(R.plurals.view_file_count, open.fileCount, open.fileCount),
            color = colors.ink,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge
        )
        if (open.password != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(KobbloSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Lock, stringResource(R.string.polish_unlocked), Modifier.size(14.dp), colors.action)
                Text(stringResource(R.string.polish_unlocked), color = colors.action, style = KobbloType.tab)
            }
        }
    }
    if (open.entries.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(100.dp), Alignment.Center) {
            Text(
                text = stringResource(R.string.view_archive_empty),
                color = colors.smoke,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    } else {
        // A long archive scrolls inside its own inset panel, so the dialog's buttons stay in reach.
        val panel = Modifier.heightIn(max = 320.dp).clip(KobbloShapes.sm).background(colors.canvas).padding(KobbloSpace.xxs)
        LazyColumn(modifier = panel) {
            items(open.entries) { entry -> ArchiveEntryRow(entry) }
        }
    }
}

@Composable
private fun ArchiveEntryRow(entry: ArchiveEntryInfo) {
    val colors = KobbloDesign.colors
    val folder = entry.isDirectory
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.xs, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(KobbloSpace.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (folder) AppIcons.Folder else AppIcons.File,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (folder) colors.action else colors.smoke
        )
        // The name gives way to the size beside it, and is cut short before it wraps.
        Text(entry.name, Modifier.weight(1f), colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        // A folder has no size of its own, and some archives don't record a file's.
        if (!folder && entry.sizeBytes > 0) {
            Text(FileSizes.label(entry.sizeBytes), color = colors.smoke, style = MaterialTheme.typography.bodySmall)
        }
    }
}

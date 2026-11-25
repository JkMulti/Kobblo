package com.kobblo.gallery.file.explorer.smart

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.gallery.GallerySheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.subhashh.lumen.ai.LumenAi
import com.subhashh.lumen.ai.MediaRef
import com.subhashh.lumen.ai.ModelPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The lines of text in [file], top to bottom, or null when Text in Photos is off, the file isn't a
 * photo the system knows, or there's no text. Already read when indexing got to it, otherwise read now.
 */
internal suspend fun readPhotoText(context: Context, file: FileItem): List<String>? {
    if (file.type != "image" || file.isLocked) return null
    if (!withContext(Dispatchers.IO) { SmartFeatures.isReady(context, ModelPack.Text) }) return null
    val ref = mediaRefFor(context, file.path) ?: return null
    return try {
        LumenAi.liveText(context, ref)?.lines?.map { it.text.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e("PhotoText", "Couldn't read the text in a photo", e)
        null
    }
}

/** The system's record of the photo at [path], in the same form Smart Features indexes it under. */
private suspend fun mediaRefFor(context: Context, path: String): MediaRef? = withContext(Dispatchers.IO) {
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.DATE_TAKEN,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.DISPLAY_NAME
    )
    try {
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            projection,
            "${MediaStore.Files.FileColumns.DATA} = ? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?",
            arrayOf(path, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()),
            null
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val id = c.getLong(0)
            val modified = c.getLong(3)
            MediaRef(
                id = id,
                uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                mimeType = c.getString(1) ?: "",
                isVideo = false,
                takenAt = c.getLong(2).takeIf { it > 0 } ?: (modified * 1000),
                durationMs = 0,
                modified = modified,
                name = c.getString(4) ?: ""
            )
        }
    } catch (_: SecurityException) {
        null
    }
}

/** The text found in a photo, to select, copy or share. */
@Composable
internal fun PhotoTextSheet(lines: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = lines.joinToString("\n")
    GallerySheet(onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.smart_text_title),
            style = KobbloType.headline,
            color = KobbloDesign.colors.ink,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        KobbloGroup {
            SelectionContainer {
                Text(
                    text = text,
                    style = KobbloType.body,
                    color = KobbloDesign.colors.ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = KobbloSpace.md, vertical = 12.dp)
                )
            }
        }
        Spacer(Modifier.height(KobbloSpace.groupGap))
        KobbloGroup {
            KobbloRow(
                title = stringResource(R.string.smart_text_copy_all),
                trailing = { Icon(AppIcons.Copy, contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(22.dp)) },
                showDivider = true,
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.smart_text_title), text))
                    onDismiss()
                }
            )
            KobbloRow(
                title = stringResource(R.string.smart_text_share),
                trailing = { Icon(AppIcons.Share, contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(22.dp)) },
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    runCatching { context.startActivity(Intent.createChooser(send, null)) }
                    onDismiss()
                }
            )
        }
    }
}

package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/**
 * The kinds of place that can be added: cloud accounts first, then servers reached by address.
 * Picking one closes this sheet and opens that kind's own form.
 */
@Composable
fun NewCloudConnectionSheet(
    onDismissRequest: () -> Unit,
    onConnectGoogleDrive: () -> Unit,
    onConnectMega: () -> Unit,
    onConnectDropbox: () -> Unit,
    onConnectNextcloud: () -> Unit,
    onConnectWebDav: () -> Unit,
    onConnectSftp: () -> Unit,
    onConnectFtp: () -> Unit,
    onConnectSmb: () -> Unit
) {
    fun kind(name: String, glyph: ImageVector, open: () -> Unit) = LocationKind(name, glyph) {
        onDismissRequest()
        open()
    }

    val accounts = listOf(
        kind("Google Drive", AppIcons.Cloud, onConnectGoogleDrive),
        kind("Mega", AppIcons.Cloud, onConnectMega),
        kind("Dropbox", AppIcons.Cloud, onConnectDropbox)
    )
    val servers = listOf(
        kind("Nextcloud", AppIcons.CloudSync, onConnectNextcloud),
        kind("WebDAV", AppIcons.Globe, onConnectWebDav),
        kind("SFTP", AppIcons.Shield, onConnectSftp),
        kind("FTP", AppIcons.FolderShared, onConnectFtp),
        kind("SMB", AppIcons.Server, onConnectSmb)
    )

    KobbloSheet(onDismissRequest = onDismissRequest) {
        ExpressiveSheetContent(title = stringResource(R.string.add_location)) {
            LocationKinds(accounts)
            Spacer(modifier = Modifier.height(16.dp))
            LocationKinds(servers)
        }
    }
}

/** One kind of location, under the name its service goes by everywhere. */
private class LocationKind(val name: String, val glyph: ImageVector, val pick: () -> Unit)

@Composable
private fun LocationKinds(kinds: List<LocationKind>) {
    val tint = KobbloDesign.colors.action
    KobbloGroup {
        kinds.forEachIndexed { index, kind ->
            KobbloRow(
                title = kind.name,
                leading = { Icon(kind.glyph, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp)) },
                trailing = { KobbloChevron() },
                showDivider = index < kinds.lastIndex,
                onClick = kind.pick
            )
        }
    }
}

package com.kobblo.gallery.file.explorer.transfer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.kobblo.gallery.file.explorer.MainActivity
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The ways in that don't start from inside the app. */
object TransferEntry {
    /** Opens straight on the Send tab, which is what makes a phone ready to receive. */
    const val ACTION_RECEIVE = "com.kobblo.gallery.file.explorer.action.RECEIVE"

    /** Accept, from a notification button on a version too old for the button itself to ask for an unlock. */
    const val ACTION_ACCEPT_PROMPT = "com.kobblo.gallery.file.explorer.action.ACCEPT_PROMPT"
    private const val SHORTCUT_ID = "receive_files"

    fun receiveIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION_RECEIVE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private var published = false

    /** Holding the app's icon offers this. It's added by code because a manifest one can't name a debug package. */
    fun publishShortcut(context: Context) {
        if (published) return
        published = true
        runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(
                context,
                ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                    .setShortLabel(context.getString(R.string.transfer_receive_files))
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_send))
                    .setIntent(receiveIntent(context))
                    .build()
            )
        }
    }

    /** Words shared from another app, when no file came with them. */
    fun sharedText(intent: Intent): String? =
        intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
}

/** A Quick Settings tile: ready to receive, or not, without opening anything. */
class ReceiveTileService : TileService() {
    private var watching: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        NearbyShareRepository.attach(this)
        watching?.cancel()
        watching = CoroutineScope(Dispatchers.Main.immediate).launch {
            NearbyShareRepository.isReceiving.collect(::refresh)
        }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val on = !NearbyShareRepository.isReceiving.value
        // Some versions won't start this from a tile, and the app itself always can.
        if (!NearbyShareRepository.toggleReceiving(this, on)) openApp()
    }

    private fun refresh(receiving: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (receiving) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.transfer_receive_files)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (receiving) R.string.transfer_tile_on else R.string.transfer_tile_off)
        }
        tile.updateTile()
    }

    // Lint wants only the PendingIntent form, which doesn't exist below Android 14. The Intent form is guarded to below it.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = TransferEntry.receiveIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

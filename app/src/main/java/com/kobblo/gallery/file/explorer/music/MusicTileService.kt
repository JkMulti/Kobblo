package com.kobblo.gallery.file.explorer.music

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kobblo.gallery.file.explorer.MainActivity
import com.kobblo.gallery.file.explorer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** A Quick Settings tile that plays and pauses, and shows what's playing. */
class MusicTileService : TileService() {
    private var watching: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watching?.cancel()
        watching = CoroutineScope(Dispatchers.Main.immediate).launch {
            MusicPlayer.get(this@MusicTileService).state.collectLatest { refresh(it) }
        }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val engine = MusicPlayer.get(this)
        if (engine.state.value.queue.isEmpty) {
            openApp()
        } else {
            engine.toggle()
        }
    }

    private fun refresh(state: MusicPlaybackState) {
        val tile = qsTile ?: return
        val track = state.current
        tile.state = if (state.isPlaying) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = track?.title ?: getString(R.string.music_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = track?.artist?.takeIf { it.isNotBlank() }
                ?: getString(if (state.isPlaying) R.string.music_tile_playing else R.string.music_tile_paused)
        }
        tile.updateTile()
    }

    // Lint wants only the PendingIntent form, which doesn't exist below Android 14. The Intent form is guarded to below it.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

package com.kobblo.gallery.file.explorer.music

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.kobblo.gallery.file.explorer.MainActivity
import com.kobblo.gallery.file.explorer.R

/**
 * Keeps playback alive when the app isn't on screen, and publishes it as a media session: the
 * notification, the lock screen, headset buttons and Bluetooth controls all work through this.
 *
 * It isn't exported. System UI and headsets talk to the platform session it publishes, not to this
 * service, and no other app needs to bind here: the queue holds the paths of the user's files.
 */
@OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {
    private var session: MediaSession? = null

    private val shuffleCommand = SessionCommand(ActionShuffle, Bundle.EMPTY)
    private val repeatCommand = SessionCommand(ActionRepeat, Bundle.EMPTY)

    private val handler = Handler(Looper.getMainLooper())
    private var lastPlayedAt = 0L

    private val playerListener = object : Player.Listener {
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refreshButtons()
        override fun onRepeatModeChanged(repeatMode: Int) = refreshButtons()
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            lastPlayedAt = SystemClock.elapsedRealtime()
        }
    }

    private val relaxForeground = Runnable { session?.let { onUpdateNotification(it, false) } }

    override fun onCreate() {
        super.onCreate()
        val engine = MusicPlayer.get(this)
        val player = engine.sessionPlayer

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(ChannelId)
                .setChannelName(R.string.music_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_music) }
        )

        val openPlayer = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(ActionOpenPlayer)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, player)
            .setId(SessionId)
            .setSessionActivity(openPlayer)
            .setCallback(SessionCallback())
            .build()
            .also { addSession(it) }

        player.addListener(playerListener)
        refreshButtons()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Stays in the foreground for a while after a pause. Otherwise Android stops a paused app's
     * service within a minute or so of leaving it, and the notification goes with it, just when
     * someone who paused for a phone call wants it to tap Play on.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        handler.removeCallbacks(relaxForeground)
        val pausedFor = SystemClock.elapsedRealtime() - lastPlayedAt
        val keep = !startInForegroundRequired && session.player.mediaItemCount > 0 && pausedFor < KeepForegroundWhilePausedMs
        if (keep) handler.postDelayed(relaxForeground, KeepForegroundWhilePausedMs - pausedFor)
        super.onUpdateNotification(session, startInForegroundRequired || keep)
    }

    /** Swiping the app away leaves music that's playing alone, but there's no reason to linger paused. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(relaxForeground)
        session?.let { current ->
            current.player.removeListener(playerListener)
            current.release()
        }
        session = null
        MusicPlayer.get(this).onServiceDestroyed()
        super.onDestroy()
    }

    /** Shuffle and repeat, which the system controls have no button of their own for. */
    private fun refreshButtons() {
        val current = session ?: return
        val player = current.player
        val shuffle = CommandButton.Builder(
            if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        )
            .setDisplayName(getString(R.string.music_shuffle))
            .setSessionCommand(shuffleCommand)
            .build()
        val repeat = CommandButton.Builder(
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            }
        )
            .setDisplayName(getString(R.string.music_repeat))
            .setSessionCommand(repeatCommand)
            .build()
        current.setCustomLayout(listOf(shuffle, repeat))
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(shuffleCommand)
                .add(repeatCommand)
                .build()
            // Nothing out there shows the queue, and handing it over is the most expensive thing the
            // session does: thousands of entries, on the main thread, every time the queue changes.
            // Without it, controllers still see the current song and can skip either way.
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_GET_TIMELINE)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val engine = MusicPlayer.get(this@MusicService)
            when (customCommand.customAction) {
                ActionShuffle -> engine.toggleShuffle()
                ActionRepeat -> engine.cycleRepeat()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    companion object {
        /** Opens the app on Now Playing. */
        const val ActionOpenPlayer = "com.kobblo.gallery.file.explorer.action.OPEN_PLAYER"

        private const val KeepForegroundWhilePausedMs = 10L * 60 * 1000
        private const val ChannelId = "music_playback"
        private const val SessionId = "kobblo_music"
        private const val ActionShuffle = "com.kobblo.gallery.file.explorer.music.SHUFFLE"
        private const val ActionRepeat = "com.kobblo.gallery.file.explorer.music.REPEAT"
    }
}

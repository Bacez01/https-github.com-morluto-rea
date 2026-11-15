package com.radiomonde

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Plays the stream in the background. Media3 shows the notification with play/pause and the
 * lock-screen controls, pauses when headphones are unplugged and handles calls (audio focus).
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sleepAt = 0L
    private val sleepRunnable = Runnable {
        sleepAt = 0L
        session?.player?.pause()
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(
                // Larger buffer than the default so a weak mobile connection does not cut the sound.
                DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 2_000, 4_000).build(),
            )
            .build()

        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        val activity = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(activity)
            .setCallback(Callback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Stops the service when the app is swiped away while nothing is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(sleepRunnable)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        if (minutes <= 0) {
            sleepAt = 0L
        } else {
            sleepAt = SystemClock.elapsedRealtime() + minutes * 60_000L
            handler.postDelayed(sleepRunnable, minutes * 60_000L)
        }
    }

    private fun sleepRemainingMs(): Long =
        if (sleepAt == 0L) 0L else (sleepAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    private inner class Callback : MediaSession.Callback {
        @OptIn(UnstableApi::class)
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SET_SLEEP, Bundle.EMPTY))
                .add(SessionCommand(CMD_GET_SLEEP, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = when (customCommand.customAction) {
            CMD_SET_SLEEP -> {
                setSleepTimer(args.getInt(EXTRA_MINUTES))
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            CMD_GET_SLEEP -> Futures.immediateFuture(
                SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putLong(EXTRA_REMAINING_MS, sleepRemainingMs()) }),
            )
            else -> super.onCustomCommand(session, controller, customCommand, args)
        }

        /** The controller sends the stream address in requestMetadata; turn it back into a playable item. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                if (uri == null) item else item.buildUpon().setUri(uri).build()
            }.toMutableList(),
        )
    }

    companion object {
        const val CMD_SET_SLEEP = "com.radiomonde.SET_SLEEP"
        const val CMD_GET_SLEEP = "com.radiomonde.GET_SLEEP"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_REMAINING_MS = "remaining_ms"
    }
}

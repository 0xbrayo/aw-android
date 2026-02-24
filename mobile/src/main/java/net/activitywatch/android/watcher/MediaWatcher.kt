package net.activitywatch.android.watcher

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import net.activitywatch.android.RustInterface
import org.json.JSONObject
import org.threeten.bp.Instant

/**
 * Watches media playback across all apps using MediaSession API.
 *
 * Requires Notification Access permission (Settings → Notification access).
 * Uses [MediaSessionManager] to listen for active media sessions and
 * [MediaController.Callback] to track metadata and playback state changes.
 *
 * Events are sent as heartbeats to the "aw-watcher-android-media" bucket.
 */
class MediaWatcher : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaWatcher"
        private const val BUCKET_ID = "aw-watcher-android-media"
        private const val BUCKET_TYPE = "app.media.activity"
        private const val PULSETIME = 60.0
    }

    private var ri: RustInterface? = null
    private var mediaSessionManager: MediaSessionManager? = null

    // Track last sent state to avoid duplicate heartbeats
    private var lastApp: String? = null
    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private var lastAlbum: String? = null
    private var lastState: String? = null

    // Active controllers and their callbacks
    private val controllerCallbacks = mutableMapOf<MediaSession.Token, MediaController.Callback>()

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        Log.i(TAG, "Active sessions changed, count: ${controllers?.size ?: 0}")
        updateControllers(controllers ?: emptyList())
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating MediaWatcher")

        ri = RustInterface(applicationContext).also {
            it.createBucketHelper(BUCKET_ID, BUCKET_TYPE)
        }

        try {
            mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(this, MediaWatcher::class.java)
            val controllers = mediaSessionManager?.getActiveSessions(componentName) ?: emptyList()
            Log.i(TAG, "Initial active sessions: ${controllers.size}")
            updateControllers(controllers)
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification access not granted: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Destroying MediaWatcher")

        // Clean up all controller callbacks
        for ((token, callback) in controllerCallbacks) {
            try {
                MediaController(this, token).unregisterCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering callback: ${e.message}")
            }
        }
        controllerCallbacks.clear()

        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing session listener: ${e.message}")
        }
    }

    /**
     * Updates tracked controllers: removes stale callbacks and adds new ones.
     */
    private fun updateControllers(controllers: List<MediaController>) {
        // Find tokens that are no longer active and remove their callbacks
        val activeTokens = controllers.map { it.sessionToken }.toSet()
        val staleTokens = controllerCallbacks.keys - activeTokens
        for (token in staleTokens) {
            controllerCallbacks.remove(token)?.let { callback ->
                try {
                    MediaController(this, token).unregisterCallback(callback)
                } catch (e: Exception) {
                    Log.w(TAG, "Error unregistering stale callback: ${e.message}")
                }
            }
        }

        // Register callbacks for new controllers
        for (controller in controllers) {
            if (controller.sessionToken !in controllerCallbacks) {
                val callback = createCallback(controller.packageName)
                controller.registerCallback(callback)
                controllerCallbacks[controller.sessionToken] = callback
                Log.i(TAG, "Registered callback for: ${controller.packageName}")

                // Send initial state if currently playing
                handleStateChange(controller.packageName, controller.metadata, controller.playbackState)
            }
        }
    }

    /**
     * Creates a [MediaController.Callback] for the given media app.
     */
    private fun createCallback(packageName: String): MediaController.Callback {
        return object : MediaController.Callback() {
            private var currentMetadata: MediaMetadata? = null

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                currentMetadata = metadata
                // Metadata changes often come before playback state, so we trigger a heartbeat
                handleStateChange(packageName, metadata, null)
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                handleStateChange(packageName, currentMetadata, state)
            }

            override fun onSessionDestroyed() {
                Log.i(TAG, "Session destroyed for: $packageName")
            }
        }
    }

    /**
     * Processes a media state change and sends a heartbeat if the state differs from the last sent.
     */
    private fun handleStateChange(
        packageName: String,
        metadata: MediaMetadata?,
        playbackState: PlaybackState?
    ) {
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        val state = when (playbackState?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_STOPPED -> "stopped"
            PlaybackState.STATE_BUFFERING -> "buffering"
            else -> "unknown"
        }

        // Skip if nothing changed
        if (packageName == lastApp && title == lastTitle && artist == lastArtist &&
            album == lastAlbum && state == lastState
        ) {
            return
        }

        // Skip if no meaningful metadata
        if (title.isEmpty() && artist.isEmpty()) {
            return
        }

        lastApp = packageName
        lastTitle = title
        lastArtist = artist
        lastAlbum = album
        lastState = state

        sendHeartbeat(packageName, title, artist, album, state)
    }

    /**
     * Sends a heartbeat event with the current media state.
     */
    private fun sendHeartbeat(
        app: String,
        title: String,
        artist: String,
        album: String,
        state: String
    ) {
        val data = JSONObject()
            .put("app", app)
            .put("title", title)
            .put("artist", artist)
            .put("album", album)
            .put("state", state)

        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        Log.i(TAG, "Media heartbeat: $data")
        ri?.heartbeatHelper(BUCKET_ID, now, 0.0, data, PULSETIME)
    }

    // Required by NotificationListenerService but not used for media tracking
    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}

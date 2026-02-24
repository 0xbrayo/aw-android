package net.activitywatch.android.watcher

import android.content.ComponentName
import android.content.pm.PackageManager
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
 * Watches active media sessions (music, podcasts, video) and logs playback events
 * to ActivityWatch using the NotificationListenerService API.
 *
 * Requires the user to grant "Notification Access" in system settings.
 *
 * Bucket: aw-watcher-android-media
 * Event data: app, title, artist, album, state (playing/paused/stopped)
 */
class MediaWatcher : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaWatcher"
        private const val BUCKET_ID = "aw-watcher-android-media"
        private const val BUCKET_TYPE = "media.playback"
        // Heartbeat pulsetime: merge events within 60s (same track playing continuously)
        private const val PULSETIME = 60.0

        fun isNotificationAccessGranted(context: android.content.Context): Boolean {
            val componentName = ComponentName(context, MediaWatcher::class.java)
            val enabledListeners = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return enabledListeners.contains(componentName.flattenToString())
        }
    }

    private var ri: RustInterface? = null
    private var sessionManager: MediaSessionManager? = null
    private val activeControllers = mutableMapOf<MediaSession.Token, MediaController>()
    private val activeCallbacks = mutableMapOf<MediaSession.Token, MediaController.Callback>()

    // Track last sent event to avoid duplicate heartbeats
    private var lastEventKey: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MediaWatcher created")
        ri = RustInterface(applicationContext)
        ri?.createBucketHelper(BUCKET_ID, BUCKET_TYPE)

        sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager
        registerActiveSessionListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "MediaWatcher destroyed")
        unregisterAllCallbacks()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // We rely on MediaSessionManager for media tracking, not individual notifications.
        // This callback is required by NotificationListenerService but we don't need it.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not needed for media tracking.
    }

    /**
     * Register a listener for active media session changes.
     * This is called once on service creation and handles all session lifecycle.
     */
    private fun registerActiveSessionListener() {
        val componentName = ComponentName(this, MediaWatcher::class.java)
        try {
            sessionManager?.addOnActiveSessionsChangedListener(
                { controllers -> onActiveSessionsChanged(controllers) },
                componentName
            )
            // Process currently active sessions
            val activeSessions = sessionManager?.getActiveSessions(componentName)
            if (activeSessions != null) {
                onActiveSessionsChanged(activeSessions)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification access not granted: ${e.message}")
        }
    }

    /**
     * Called when the list of active media sessions changes.
     * Registers callbacks for new sessions and cleans up stale ones.
     */
    private fun onActiveSessionsChanged(controllers: List<MediaController>?) {
        if (controllers == null) return

        val currentTokens = controllers.map { it.sessionToken }.toSet()

        // Remove callbacks for sessions that are no longer active
        val staleTokens = activeControllers.keys - currentTokens
        for (token in staleTokens) {
            val controller = activeControllers.remove(token)
            val callback = activeCallbacks.remove(token)
            if (controller != null && callback != null) {
                controller.unregisterCallback(callback)
                Log.d(TAG, "Unregistered callback for ${controller.packageName}")
            }
        }

        // Register callbacks for new sessions
        for (controller in controllers) {
            val token = controller.sessionToken
            if (token !in activeControllers) {
                val callback = createMediaCallback(controller)
                controller.registerCallback(callback)
                activeControllers[token] = controller
                activeCallbacks[token] = callback
                Log.i(TAG, "Registered callback for ${controller.packageName}")

                // Send initial state if already playing
                val state = controller.playbackState
                val metadata = controller.metadata
                if (state != null && metadata != null) {
                    handlePlaybackChange(controller, state, metadata)
                }
            }
        }
    }

    /**
     * Create a MediaController.Callback that logs playback state changes.
     */
    private fun createMediaCallback(controller: MediaController): MediaController.Callback {
        return object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                val metadata = controller.metadata ?: return
                if (state != null) {
                    handlePlaybackChange(controller, state, metadata)
                }
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                val state = controller.playbackState ?: return
                if (metadata != null) {
                    handlePlaybackChange(controller, state, metadata)
                }
            }

            override fun onSessionDestroyed() {
                val token = controller.sessionToken
                activeControllers.remove(token)
                activeCallbacks.remove(token)?.let { controller.unregisterCallback(it) }
                Log.d(TAG, "Session destroyed for ${controller.packageName}")
            }
        }
    }

    /**
     * Process a playback state or metadata change and send an event.
     */
    private fun handlePlaybackChange(
        controller: MediaController,
        state: PlaybackState,
        metadata: MediaMetadata
    ) {
        val packageName = controller.packageName ?: return

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        val playbackState = when (state.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_STOPPED -> "stopped"
            PlaybackState.STATE_BUFFERING -> "buffering"
            else -> return // Ignore transitional states (none, connecting, etc.)
        }

        // Skip events with no useful metadata
        if (title.isEmpty() && artist.isEmpty()) return

        // Resolve app name from package
        val appName = try {
            val pm = applicationContext.packageManager
            pm.getApplicationLabel(
                pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            ).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }

        // Build event data
        val data = JSONObject().apply {
            put("app", appName)
            put("package", packageName)
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("state", playbackState)
        }

        // Deduplicate: don't send identical heartbeats
        val eventKey = "$packageName|$title|$artist|$playbackState"
        if (eventKey == lastEventKey && playbackState == "playing") {
            // Same track still playing — let heartbeat merging handle it
            ri?.heartbeatHelper(BUCKET_ID, Instant.now(), 0.0, data, PULSETIME)
            return
        }
        lastEventKey = eventKey

        Log.i(TAG, "Media event: $playbackState — $artist - $title ($appName)")
        ri?.heartbeatHelper(BUCKET_ID, Instant.now(), 0.0, data, PULSETIME)
    }

    private fun unregisterAllCallbacks() {
        for ((token, controller) in activeControllers) {
            activeCallbacks[token]?.let { controller.unregisterCallback(it) }
        }
        activeControllers.clear()
        activeCallbacks.clear()
    }
}

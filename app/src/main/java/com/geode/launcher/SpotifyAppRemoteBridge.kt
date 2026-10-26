package com.geode.launcher

import android.util.Log
import android.widget.Toast
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.types.PlayerState
import java.lang.ref.WeakReference

/**
 * Small JNI-facing bridge for the Music Integrations Geode mod.
 * It only connects when a playback command is requested, not at game startup.
 */
object SpotifyAppRemoteBridge {
    private const val TAG = "SpotifyAppRemoteBridge"
    private const val CLIENT_ID = "003e981a97ba4d96ab3773420d6458fc"
    private const val REDIRECT_URI = "https://com.geode.launcher/callback"

    @Volatile private var activityRef: WeakReference<GeometryDashActivity>? = null
    @Volatile private var remote: SpotifyAppRemote? = null
    @Volatile private var pendingAction: Int? = null
    @Volatile private var playing = false
    @Volatile private var songTitle = ""
    @Volatile private var songArtist = ""

    @JvmStatic
    fun setActivity(activity: GeometryDashActivity?) {
        activityRef = activity?.let { WeakReference(it) }
        if (activity == null) {
            val oldRemote = remote
            remote = null
            pendingAction = null
            if (oldRemote != null) SpotifyAppRemote.disconnect(oldRemote)
        }
    }

    @JvmStatic
    fun requestControl(action: Int): Boolean {
        Log.i(TAG, "requestControl called with action=$action")
        if (action !in 0..3) {
            Log.w(TAG, "Rejected invalid action=$action")
            return false
        }
        val activity = activityRef?.get()
        if (activity == null) {
            Log.e(TAG, "No GeometryDashActivity registered; cannot control Spotify")
            return false
        }
        activity.runOnUiThread {
            Log.i(TAG, "Handling action=$action on UI thread")
            Toast.makeText(activity, "Music Integrations: botón recibido", Toast.LENGTH_SHORT).show()
            val connected = remote
            if (connected != null && connected.isConnected) {
                performAction(connected, action)
            } else {
                pendingAction = action
                connect(activity)
            }
        }
        return true
    }

    @JvmStatic
    fun isPlaying(): Boolean = playing

    @JvmStatic
    fun getSongTitle(): String = songTitle

    @JvmStatic
    fun getSongArtist(): String = songArtist

    @JvmStatic
    fun isConnected(): Boolean = remote?.isConnected == true

    private fun connect(activity: GeometryDashActivity) {
        Log.i(TAG, "Connecting to Spotify App Remote")
        Toast.makeText(activity, "Music Integrations: conectando con Spotify…", Toast.LENGTH_SHORT).show()
        if (remote?.isConnected == true) {
            pendingAction?.let { action ->
                pendingAction = null
                performAction(remote!!, action)
            }
            return
        }

        val params = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(activity, params, object : Connector.ConnectionListener {
            override fun onConnected(spotifyAppRemote: SpotifyAppRemote) {
                Log.i(TAG, "Spotify App Remote connected")
                Toast.makeText(activity, "Music Integrations: Spotify conectado", Toast.LENGTH_SHORT).show()
                remote = spotifyAppRemote
                spotifyAppRemote.playerApi.subscribeToPlayerState()
                    .setEventCallback { state: PlayerState ->
                        playing = state.isPaused.not()
                        songTitle = state.track?.name.orEmpty()
                        songArtist = state.track?.artists?.joinToString(", ") { it.name }.orEmpty()
                    }
                    .setErrorCallback { error ->
                        Log.w(TAG, "Player state subscription failed", error)
                    }

                pendingAction?.let { action ->
                    pendingAction = null
                    performAction(spotifyAppRemote, action)
                }
            }

            override fun onFailure(throwable: Throwable) {
                Log.e(TAG, "Spotify App Remote connection failed", throwable)
                val details = generateSequence(throwable as Throwable?) { it.cause }.take(3).joinToString(" <- ") { error -> error.javaClass.name + (error.message?.let { ": " + it } ?: "") }
                Toast.makeText(activity, "Spotify error: $details", Toast.LENGTH_LONG).show()
                remote = null
                pendingAction = null
            }
        })
    }

    private fun performAction(appRemote: SpotifyAppRemote, action: Int) {
        Log.i(TAG, "Performing Spotify playback action=$action")
        val result: CallResult<*> = when (action) {
            0 -> appRemote.playerApi.pause()
            1 -> appRemote.playerApi.resume()
            2 -> appRemote.playerApi.skipNext()
            3 -> appRemote.playerApi.skipPrevious()
            else -> return
        }
        result.setResultCallback {
            Log.i(TAG, "Spotify playback command $action succeeded")
        }
        result.setErrorCallback { error ->
            Log.e(TAG, "Spotify playback command $action failed", error)
        }
    }
}

package com.geode.launcher

import android.app.AlertDialog
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
        runCatching { SpotifyAppRemote.setDebugMode(true) }
            .onFailure { Log.w(TAG, "Could not enable Spotify SDK debug logging", it) }
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
                val causes = generateSequence(throwable as Throwable?) { it.cause }
                    .take(4)
                    .mapIndexed { index, error ->
                        val hierarchy = generateSequence(error.javaClass as Class<*>?) { it.superclass }
                            .take(5)
                            .joinToString(" extends ") { it.name }
                        val frames = error.stackTrace.take(4).joinToString("\n") { "  at $it" }
                        "Cause ${index + 1}: $hierarchy" +
                            (error.message?.let { "\nMessage: $it" } ?: "") +
                            (if (frames.isNotEmpty()) "\n$frames" else "")
                    }
                    .joinToString("\n\n")
                val pm = activity.packageManager
                val sdkDetectsSpotify = runCatching { SpotifyAppRemote.isSpotifyInstalled(activity) }
                    .onFailure { Log.e(TAG, "isSpotifyInstalled check threw", it) }
                    .getOrDefault(false)
                val packageNames = listOf(
                    "com.spotify.music",
                    "com.spotify.music.debug",
                    "com.spotify.music.canary",
                    "com.spotify.music.partners"
                )
                val packageReport = packageNames.joinToString("\\n") { packageName ->
                    val info = runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull()
                    if (info == null) {
                        "$packageName: not visible/not installed"
                    } else {
                        val appInfo = info.applicationInfo
                        val installer = runCatching {
                            if (android.os.Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(packageName).installingPackageName
                            else pm.getInstallerPackageName(packageName)
                        }.getOrNull() ?: "unknown"
                        val targetSdk = appInfo?.targetSdkVersion ?: -1
                        val enabled = appInfo?.enabled ?: false
                        val source = appInfo?.sourceDir ?: "unknown"
                        "$packageName: version=${info.versionName}, code=${info.longVersionCode}, enabled=$enabled, targetSdk=$targetSdk, installer=$installer, source=$source"
                    }
                }
                val launchable = runCatching { pm.getLaunchIntentForPackage("com.spotify.music") != null }.getOrDefault(false)
                val diagnostic = "Spotify App Remote connection failed\\n\\nSDK detects Spotify: $sdkDetectsSpotify\\nLaunchable: $launchable\\n\\n=== Spotify package candidates ===\\n$packageReport\\n\\n=== Exception chain ===\\n$causes"
                Log.e(TAG, diagnostic, throwable)
                AlertDialog.Builder(activity)
                    .setTitle("Diagnóstico de Spotify")
                    .setMessage(diagnostic)
                    .setPositiveButton("Copiar y cerrar") { _, _ ->
                        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spotify diagnostic", diagnostic))
                        Toast.makeText(activity, "Diagnóstico copiado", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
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

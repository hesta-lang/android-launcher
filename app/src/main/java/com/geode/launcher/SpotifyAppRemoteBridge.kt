package com.geode.launcher

import android.app.AlertDialog
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.types.PlayerState
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/**
 * Small JNI-facing bridge for the Music Integrations Geode mod.
 * It only connects when a playback command is requested, not at game startup.
 */
object SpotifyAppRemoteBridge {
    private const val TAG = "SpotifyAppRemoteBridge"
    private const val CLIENT_ID = "7314a0ab3c734b2caa4b483032cdd91f"
    private const val REDIRECT_URI = "https://com.geode.launcher/callback"

    @Volatile private var activityRef: WeakReference<GeometryDashActivity>? = null
    @Volatile private var remote: SpotifyAppRemote? = null
    @Volatile private var mediaBrowser: MediaBrowser? = null
    @Volatile private var mediaController: MediaController? = null
    @Volatile private var pendingAction: Int? = null
    @Volatile private var foregroundRetryUsed = false
    @Volatile private var connectionInProgress = false
    @Volatile private var playing = false
    @Volatile private var songTitle = ""
    @Volatile private var songArtist = ""
    // Short in-memory history of Spotify track URIs observed during this session.
    private val trackHistory = mutableListOf<String>()
    @Volatile private var currentTrackUri = ""
    @Volatile private var trackHistoryCursor = -1
    @Volatile private var lastAppRemoteFailure = "No App Remote failure recorded yet"
    @Volatile private var connectivityManager: ConnectivityManager? = null
    @Volatile private var networkCallbackRegistered = false

    @JvmStatic
    fun setActivity(activity: GeometryDashActivity?) {
        activityRef = activity?.let { WeakReference(it) }
        if (activity != null) registerNetworkCallback(activity)
        if (activity == null) {
            val oldRemote = remote
            remote = null
            pendingAction = null
            connectionInProgress = false
            foregroundRetryUsed = false
            if (oldRemote != null) SpotifyAppRemote.disconnect(oldRemote)
            runCatching { mediaController?.unregisterCallback(mediaControllerCallback) }
            mediaController = null
            runCatching { mediaBrowser?.disconnect() }
            mediaBrowser = null
        }
    }

    @JvmStatic
    fun requestControl(action: Int): Boolean {
        Log.i(TAG, "requestControl called with action=$action")
        if (action !in 0..4) {
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
            if (action == 4) {
                activity.requestLocalMusicFolder()
                return@runOnUiThread
            }
            if (OfflineMusicPlayer.isLocalMode() || OfflineMusicPlayer.isPlaying() || !hasValidatedInternet(activity)) {
                val handled = when (action) {
                    0 -> OfflineMusicPlayer.pause()
                    1 -> OfflineMusicPlayer.playOrResume(activity)
                    2 -> OfflineMusicPlayer.next()
                    3 -> OfflineMusicPlayer.previous()
                    else -> false
                }
                if (handled) {
                    playing = OfflineMusicPlayer.isPlaying()
                    Log.i(TAG, "Handled action=$action with local offline player")
                    return@runOnUiThread
                }
                if (!hasValidatedInternet(activity)) {
                    return@runOnUiThread
                }
            }
            val connected = remote
            if (connected != null && connected.isConnected) {
                performAction(connected, action)
            } else if (mediaController != null) {
                performMediaControllerAction(action)
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

    /**
     * Reads recent logcat entries belonging to this app process and keeps only
     * Spotify App Remote / locator / bridge messages. This is diagnostic-only;
     * Android may restrict which log entries are available on some devices.
     */
    private fun captureSpotifyLogs(): String {
        return try {
            val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "2000")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(2, TimeUnit.SECONDS)
            val matches = output.lineSequence().filter { line ->
                val lower = line.lowercase()
                lower.contains("spotifyappremote") ||
                    lower.contains("spotifylocator") ||
                    lower.contains("spotify app remote") ||
                    lower.contains("spotifyappremotebridge") ||
                    lower.contains("com.spotify.android.appremote") ||
                    lower.contains("com.spotify.protocol")
            }.toList().takeLast(250)
            if (matches.isEmpty()) {
                "No matching Spotify SDK log lines were readable from logcat. Android may restrict log access, or the SDK may use different log tags."
            } else {
                matches.joinToString("\n")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not capture Spotify SDK logcat", error)
            "Unable to read logcat: ${error.javaClass.name}: ${error.message}"
        }
    }

    private fun captureSpotifyDetectionReport(activity: GeometryDashActivity): String {
        val pm = activity.packageManager
        val sdkActivity = runCatching { SpotifyAppRemote.isSpotifyInstalled(activity) }
            .fold({ it.toString() }, { "ERROR ${it.javaClass.name}: ${it.message}" })
        val sdkApplication = runCatching {
            SpotifyAppRemote.isSpotifyInstalled(activity.applicationContext)
        }.fold({ it.toString() }, { "ERROR ${it.javaClass.name}: ${it.message}" })
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo("com.spotify.music", 0)
        }.fold(
            { info -> "FOUND version=${info.versionName}, versionCode=${info.longVersionCode}, enabled=${info.applicationInfo?.enabled}" },
            { error -> "NOT FOUND ${error.javaClass.name}: ${error.message}" }
        )
        val remoteService = runCatching {
            val component = ComponentName(
                "com.spotify.music",
                "com.spotify.interapp.service.service.AppProtocolRemoteService"
            )
            @Suppress("DEPRECATION")
            val info = pm.getServiceInfo(component, 0)
            "FOUND exported=${info.exported}, enabled=${info.enabled}, permission=${info.permission ?: "none"}"
        }.fold(
            { it },
            { error -> "NOT FOUND ${error.javaClass.name}: ${error.message}" }
        )
        return "SDK detects Spotify (activity context): $sdkActivity\n" +
            "SDK detects Spotify (application context): $sdkApplication\n" +
            "PackageManager Spotify package: $packageInfo\n" +
            "Explicit App Remote service: $remoteService"
    }

    private fun hasValidatedInternet(context: android.content.Context): Boolean {
        val manager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun registerNetworkCallback(activity: GeometryDashActivity) {
        val manager = activity.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        connectivityManager = manager
        if (networkCallbackRegistered) return
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    val currentActivity = activityRef?.get() ?: return
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (activityRef?.get() !== currentActivity || hasValidatedInternet(currentActivity)) return@postDelayed
                        if (remote?.isConnected == true && playing) {
                            remote?.playerApi?.pause()
                                ?.setErrorCallback { error -> Log.w(TAG, "Could not pause Spotify after Internet loss", error) }
                        }
                        if (OfflineMusicPlayer.playOrResume(currentActivity)) {
                            playing = OfflineMusicPlayer.isPlaying()
                            Log.i(TAG, "Internet lost; started local music fallback")
                        } else {
                            Log.w(TAG, "Internet lost but no playable local folder is configured")
                        }
                    }, 1800L)
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        val currentActivity = activityRef?.get() ?: return
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (activityRef?.get() !== currentActivity || hasValidatedInternet(currentActivity)) return@postDelayed
                            if (remote?.isConnected == true && playing) {
                                remote?.playerApi?.pause()
                                    ?.setErrorCallback { error -> Log.w(TAG, "Could not pause Spotify without validated Internet", error) }
                            }
                            if (OfflineMusicPlayer.playOrResume(currentActivity)) {
                                playing = OfflineMusicPlayer.isPlaying()
                                Log.i(TAG, "Network lost validated Internet; started local fallback")
                            }
                        }, 1800L)
                    }
                }

                override fun onAvailable(network: Network) {
                    val currentActivity = activityRef?.get() ?: return
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (activityRef?.get() !== currentActivity || !hasValidatedInternet(currentActivity)) return@postDelayed
                        if (OfflineMusicPlayer.isPlaying()) {
                            Log.i(TAG, "Internet returned while local music was playing; reconnecting to Spotify")
                            connect(currentActivity)
                        }
                    }, 1500L)
                }
            })
            networkCallbackRegistered = true
        }.onFailure { Log.w(TAG, "Could not register network callback", it) }
    }

    private fun connect(activity: GeometryDashActivity) {
        Log.i(TAG, "Connecting to Spotify App Remote")
        runCatching { SpotifyAppRemote.setDebugMode(true) }
            .onFailure { Log.w(TAG, "Could not enable Spotify SDK debug logging", it) }
        if (remote?.isConnected == true) {
            pendingAction?.let { action ->
                pendingAction = null
                performAction(remote!!, action)
            }
            return
        }

        if (connectionInProgress) {
            Log.i(TAG, "Spotify App Remote connection already in progress; coalescing request")
            return
        }
        connectionInProgress = true

        val params = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(activity, params, object : Connector.ConnectionListener {
            override fun onConnected(spotifyAppRemote: SpotifyAppRemote) {
                connectionInProgress = false
                Log.i(TAG, "Spotify App Remote connected")
                foregroundRetryUsed = false
                remote = spotifyAppRemote
                if (OfflineMusicPlayer.isPlaying()) {
                    spotifyAppRemote.playerApi.resume()
                        .setResultCallback {
                            OfflineMusicPlayer.stop()
                            playing = true
                            Log.i(TAG, "Spotify resumed after network recovery; local fallback stopped")
                        }
                        .setErrorCallback { error ->
                            Log.w(TAG, "Spotify resume failed after network recovery; keeping local music", error)
                        }
                }
                spotifyAppRemote.playerApi.subscribeToPlayerState()
                    .setEventCallback { state: PlayerState ->
                        playing = state.isPaused.not()
                        songTitle = state.track?.name.orEmpty()
                        songArtist = state.track?.artists?.joinToString(", ") { it.name }.orEmpty()
                        state.track?.uri?.let { rememberTrack(it) }
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
                connectionInProgress = false
                Log.e(TAG, "Spotify App Remote connection failed", throwable)
                lastAppRemoteFailure = generateSequence(throwable as Throwable?) { it.cause }
                    .take(6)
                    .joinToString("\n") { error ->
                        "${error.javaClass.name}: ${error.message ?: "(no message)"}"
                    }
                Log.e(TAG, "App Remote failure chain: $lastAppRemoteFailure")

                val isMissingSpotifyForSdk = generateSequence(throwable as Throwable?) { it.cause }
                    .any { it.javaClass.name == "com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp" }

                // Try App Remote once with Spotify in the foreground before the
                // MediaBrowser fallback. Previously the fallback returned early,
                // making the foreground retry below unreachable for this exception.
                if (isMissingSpotifyForSdk && !foregroundRetryUsed) {
                    val launchIntent = runCatching {
                        activity.packageManager.getLaunchIntentForPackage("com.spotify.music")
                    }.getOrNull()
                    if (launchIntent != null) {
                        foregroundRetryUsed = true
                        runCatching { activity.startActivity(launchIntent) }
                            .onSuccess {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (activityRef?.get() === activity && remote?.isConnected != true) {
                                        connect(activity)
                                    }
                                }, 1800L)
                            }
                            .onFailure { retryError ->
                                Log.w(TAG, "Could not foreground Spotify for one retry", retryError)
                                foregroundRetryUsed = false
                                connectMediaBrowserFallback(activity)
                            }
                        if (foregroundRetryUsed) return
                    }
                }

                if (!hasValidatedInternet(activity) && OfflineMusicPlayer.playOrResume(activity)) {
                    Log.i(TAG, "No validated Internet; started local music fallback")
                    playing = OfflineMusicPlayer.isPlaying()
                    return
                }

                if (isMissingSpotifyForSdk) {
                    Log.w(TAG, "Spotify App Remote locator still rejects the installed app after foreground retry; trying MediaBrowser fallback")
                    connectMediaBrowserFallback(activity)
                    return
                }

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
                    .onFailure { Log.e(TAG, "isSpotifyInstalled(activity) check threw", it) }
                    .getOrDefault(false)
                val sdkDetectsSpotifyFromAppContext = runCatching {
                    SpotifyAppRemote.isSpotifyInstalled(activity.applicationContext)
                }.onFailure { Log.e(TAG, "isSpotifyInstalled(applicationContext) check threw", it) }
                    .getOrDefault(false)
                val packageManagerProbe = runCatching {
                    val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        pm.getPackageInfo("com.spotify.music", android.content.pm.PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo("com.spotify.music", 0)
                    }
                    val appInfo = info.applicationInfo
                    "PackageManager sees Spotify: package=${info.packageName}, version=${info.versionName}, versionCode=${info.longVersionCode}, enabled=${appInfo?.enabled}, uid=${appInfo?.uid}, sourceDir=${appInfo?.sourceDir}"
                }.getOrElse { "PackageManager Spotify probe failed: ${it.javaClass.name}: ${it.message}" }
                val packageNames = listOf(
                    "com.spotify.music",
                    "com.spotify.music.debug",
                    "com.spotify.music.canary",
                    "com.spotify.music.partners"
                )
                val packageReport = packageNames.joinToString("\n") { packageName ->
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
                val launcherResolution = runCatching {
                    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                        .setPackage("com.spotify.music")
                    val matches = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.queryIntentActivities(intent, 0)
                    }
                    if (matches.isEmpty()) "No launcher activity resolved for com.spotify.music"
                    else matches.joinToString("\n") { match ->
                        val info = match.activityInfo
                        "activity=${info?.name}, exported=${info?.exported}, enabled=${info?.enabled}, package=${info?.packageName}"
                    }
                }.getOrElse { "Launcher resolution failed: ${it.javaClass.name}: ${it.message}" }
                val spotifyIntentProbe = runCatching {
                    val probes = listOf(
                        "spotify:" to android.net.Uri.parse("spotify:"),
                        "spotify://open" to android.net.Uri.parse("spotify://open"),
                        "https://open.spotify.com/" to android.net.Uri.parse("https://open.spotify.com/"),
                        "market://details?id=com.spotify.music" to android.net.Uri.parse("market://details?id=com.spotify.music")
                    )
                    probes.joinToString("\n") { (label, uri) ->
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                        val matches = if (android.os.Build.VERSION.SDK_INT >= 33) {
                            pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
                        } else {
                            @Suppress("DEPRECATION")
                            pm.queryIntentActivities(intent, 0)
                        }
                        val spotifyMatches = matches.filter { it.activityInfo?.packageName == "com.spotify.music" }
                        "$label -> totalMatches=${matches.size}, spotifyMatches=${spotifyMatches.joinToString { it.activityInfo?.name ?: "unknown" }.ifBlank { "none" }}"
                    }
                }.getOrElse { "Spotify URI intent probe failed: ${it.javaClass.name}: ${it.message}" }
                val serviceReport = runCatching {
                    val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        pm.getPackageInfo("com.spotify.music", android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_SERVICES.toLong()))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo("com.spotify.music", android.content.pm.PackageManager.GET_SERVICES)
                    }
                    val services = info.services.orEmpty()
                    if (services.isEmpty()) "Spotify package returned no declared services (or service metadata is not visible)"
                    else services.joinToString("\n") { service ->
                        "service=${service.name}, exported=${service.exported}, enabled=${service.enabled}, permission=${service.permission ?: "none"}, process=${service.processName ?: "default"}"
                    }
                }.getOrElse { "Service inspection failed: ${it.javaClass.name}: ${it.message}" }
                val appVersion = runCatching {
                    val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        pm.getPackageInfo(activity.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(activity.packageName, 0)
                    }
                    "${info.versionName} (${info.longVersionCode})"
                }.getOrDefault("unknown")

                val appSigningReport = runCatching {
                    val flags = if (android.os.Build.VERSION.SDK_INT >= 28) {
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                    } else {
                        @Suppress("DEPRECATION")
                        android.content.pm.PackageManager.GET_SIGNATURES
                    }
                    val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        pm.getPackageInfo(activity.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(flags.toLong()))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(activity.packageName, flags)
                    }
                    val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) {
                        info.signingInfo?.apkContentsSigners
                    } else {
                        @Suppress("DEPRECATION")
                        info.signatures
                    }
                    val sha1 = signatures.orEmpty().joinToString("; ") { signature ->
                        java.security.MessageDigest.getInstance("SHA-1")
                            .digest(signature.toByteArray())
                            .joinToString(":") { byte -> "%02X".format(byte) }
                    }
                    "Installed launcher signing SHA-1: ${sha1.ifBlank { "unavailable" }}"
                }.getOrElse { "Installed launcher signing SHA-1 check failed: ${it.javaClass.name}: ${it.message}" }
                val explicitRemoteServiceReport = runCatching {
                    val component = android.content.ComponentName(
                        "com.spotify.music",
                        "com.spotify.interapp.service.service.AppProtocolRemoteService"
                    )
                    val service = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        pm.getServiceInfo(component, android.content.pm.PackageManager.ComponentInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getServiceInfo(component, 0)
                    }
                    "Explicit App Remote service lookup: FOUND; component=${service.packageName}/${service.name}, exported=${service.exported}, enabled=${service.enabled}, permission=${service.permission ?: "none"}, process=${service.processName ?: "default"}"
                }.getOrElse { "Explicit App Remote service lookup: FAILED; ${it.javaClass.name}: ${it.message}" }
                val platformReport = "Android=${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}), device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, launcher=${activity.packageName} $appVersion, App Remote AAR=0.8.0"
                val spotifyLogs = captureSpotifyLogs()
                val diagnostic = "Spotify App Remote connection failed\n\n$platformReport\nSDK detects Spotify (Activity context): $sdkDetectsSpotify\nSDK detects Spotify (Application context): $sdkDetectsSpotifyFromAppContext\nLaunchable: $launchable\n$packageManagerProbe\n$appSigningReport\n$explicitRemoteServiceReport\n\n=== Spotify launcher activity resolution ===\n$launcherResolution\n\n=== Spotify URI intent resolution ===\n$spotifyIntentProbe\n\n=== Spotify declared services ===\n$serviceReport\n\n=== Spotify package candidates ===\n$packageReport\n\n=== Exception chain ===\n$causes\n\n=== SpotifyAppRemote / SpotifyLocator logs ===\n$spotifyLogs"
                Log.e(TAG, diagnostic, throwable)
                AlertDialog.Builder(activity)
                    .setTitle("Diagnóstico de Spotify")
                    .setMessage(diagnostic)
                    .setPositiveButton("Copiar y cerrar") { _, _ ->
                        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spotify diagnostic", diagnostic))
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
                remote = null
                pendingAction = null
            }
        })
    }

    /**
     * Fallback for Spotify versions that App Remote's own locator fails to recognize.
     * Uses Spotify's exported MediaBrowserService and Android media session controls;
     * it does not request NotificationListener access.
     */
    private fun connectMediaBrowserFallback(activity: GeometryDashActivity) {
        val existing = mediaBrowser
        if (existing?.isConnected == true && mediaController != null) {
            pendingAction?.let { action ->
                pendingAction = null
                performMediaControllerAction(action)
            }
            return
        }

        runCatching { mediaBrowser?.disconnect() }
        mediaController = null
        val browser = MediaBrowser(
            activity,
            ComponentName(
                "com.spotify.music",
                "com.spotify.mediabrowserservice.mediabrowserservice.SpotifyMediaBrowserService"
            ),
            object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    try {
                        val sessionToken = mediaBrowser?.sessionToken
                            ?: throw IllegalStateException("Spotify MediaBrowser session token is unavailable")
                        val controller = MediaController(activity, sessionToken)
                        mediaController = controller
                        controller.registerCallback(mediaControllerCallback)
                        controller.metadata?.let { updateTrackMetadata(it) }
                        controller.playbackState?.let { updatePlaybackState(it) }
                        Log.i(TAG, "Spotify MediaBrowser fallback connected")
                        activity.runOnUiThread {
                        }
                        pendingAction?.let { action ->
                            pendingAction = null
                            performMediaControllerAction(action)
                        }
                    } catch (error: Throwable) {
                        Log.e(TAG, "Could not create Spotify MediaController from browser session", error)
                        pendingAction = null
                        activity.runOnUiThread {
                        }
                    }
                }

                override fun onConnectionFailed() {
                    val diagnostic = buildString {
                        appendLine("Spotify MediaBrowser fallback: CONNECTION FAILED")
                        appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                        appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                        appendLine("Launcher: ${activity.packageName}")
                        appendLine("Spotify component: com.spotify.music/com.spotify.mediabrowserservice.mediabrowserservice.SpotifyMediaBrowserService")
                        appendLine("Spotify App Remote connected: ${remote?.isConnected == true}")
                        appendLine()
                        appendLine("=== Original Spotify App Remote failure ===")
                        appendLine(lastAppRemoteFailure)
                        appendLine()
                        appendLine("=== App Remote detection probes ===")
                        append(captureSpotifyDetectionReport(activity))
                        appendLine()
                        appendLine("=== Recent Spotify SDK logcat ===")
                        append(captureSpotifyLogs())
                        appendLine()
                        appendLine("MediaBrowser connected: ${mediaBrowser?.isConnected == true}")
                        appendLine("MediaController available: ${mediaController != null}")
                        appendLine("Pending action: ${pendingAction?.toString() ?: "none"}")
                        appendLine("Track title currently known: ${songTitle.ifBlank { "(empty)" }}")
                        appendLine()
                        append("Spotify's MediaBrowser service rejected or failed the external connection. This callback does not expose a more specific Android error.")
                    }
                    Log.e(TAG, diagnostic)
                    mediaBrowser = null
                    mediaController = null
                    pendingAction = null
                    activity.runOnUiThread {
                        AlertDialog.Builder(activity)
                            .setTitle("Diagnóstico del control Spotify")
                            .setMessage(diagnostic)
                            .setPositiveButton("Copiar diagnóstico") { _, _ ->
                                val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spotify MediaBrowser diagnostic", diagnostic))
                            }
                            .setNegativeButton("Cerrar", null)
                            .show()
                    }
                }

                override fun onConnectionSuspended() {
                    Log.w(TAG, "Spotify MediaBrowser connection suspended")
                    mediaController = null
                }
            },
            null
        )
        mediaBrowser = browser
        runCatching { browser.connect() }
            .onFailure { error ->
                Log.e(TAG, "Could not start Spotify MediaBrowser fallback", error)
                mediaBrowser = null
                mediaController = null
                pendingAction = null
                activity.runOnUiThread {
                }
            }
    }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata?.let { updateTrackMetadata(it) }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            state?.let { updatePlaybackState(it) }
        }
    }

    private fun updateTrackMetadata(metadata: MediaMetadata) {
        songTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        songArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.let { rememberTrack(it) }
    }

    private fun updatePlaybackState(state: PlaybackState) {
        playing = state.state == PlaybackState.STATE_PLAYING ||
            state.state == PlaybackState.STATE_BUFFERING
    }

    /**
     * Store a bounded history of track URIs. When available, replay the exact
     * prior track; if Spotify does not expose a usable URI, use native Previous.
     */
    private fun rememberTrack(uri: String) {
        if (!uri.startsWith("spotify:track:") || uri == currentTrackUri) return
        synchronized(trackHistory) {
            if (trackHistoryCursor >= 0 && trackHistoryCursor < trackHistory.lastIndex) {
                trackHistory.subList(trackHistoryCursor + 1, trackHistory.size).clear()
            }
            if (trackHistory.lastOrNull() != uri) trackHistory.add(uri)
            while (trackHistory.size > 30) trackHistory.removeAt(0)
            trackHistoryCursor = trackHistory.lastIndex
            currentTrackUri = uri
        }
        Log.d(TAG, "Recorded Spotify track in history")
    }

    private fun previousTrackUri(): String? = synchronized(trackHistory) {
        if (trackHistoryCursor > 0 && trackHistoryCursor < trackHistory.size) {
            trackHistoryCursor -= 1
            trackHistory[trackHistoryCursor]
        } else null
    }

    private fun playPreviousTrack(appRemote: SpotifyAppRemote) {
        val previousUri = previousTrackUri()
        if (previousUri == null) {
            Log.i(TAG, "No previous track URI in local history; using Spotify skipPrevious")
            appRemote.playerApi.skipPrevious()
                .setErrorCallback { Log.w(TAG, "Spotify skipPrevious fallback failed", it) }
            return
        }
        appRemote.playerApi.play(previousUri)
            .setResultCallback { Log.i(TAG, "Requested previous track from local history") }
            .setErrorCallback { error ->
                Log.w(TAG, "Could not play previous track URI; falling back to Spotify skipPrevious", error)
                appRemote.playerApi.skipPrevious()
                    .setErrorCallback { fallbackError -> Log.w(TAG, "Spotify skipPrevious fallback failed", fallbackError) }
            }
    }

    private fun playPreviousTrack(controller: MediaController) {
        val previousUri = previousTrackUri()
        if (previousUri == null) {
            Log.i(TAG, "No previous track URI in local history; using media-session skipToPrevious")
            controller.transportControls.skipToPrevious()
            return
        }
        runCatching {
            controller.transportControls.playFromMediaId(previousUri, null)
            Log.i(TAG, "Requested previous track from local history through media session")
        }.onFailure { error ->
            Log.w(TAG, "Could not request previous track URI through media session; using skipToPrevious", error)
            controller.transportControls.skipToPrevious()
        }
    }

    private fun performMediaControllerAction(action: Int) {
        val controller = mediaController
        if (controller == null) {
            Log.w(TAG, "No Spotify MediaController available for action=$action")
            return
        }
        runCatching {
            when (action) {
                0 -> controller.transportControls.pause()
                1 -> controller.transportControls.play()
                2 -> controller.transportControls.skipToNext()
                3 -> playPreviousTrack(controller)
                else -> return
            }
            Log.i(TAG, "Spotify media-session command $action dispatched")
        }.onFailure { error ->
            Log.e(TAG, "Spotify media-session command $action failed", error)
        }
    }

    private fun performAction(appRemote: SpotifyAppRemote, action: Int) {
        Log.i(TAG, "Performing Spotify playback action=$action")
        if (action == 3) {
            playPreviousTrack(appRemote)
            return
        }
        val result: CallResult<*> = when (action) {
            0 -> appRemote.playerApi.pause()
            1 -> appRemote.playerApi.resume()
            2 -> appRemote.playerApi.skipNext()
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

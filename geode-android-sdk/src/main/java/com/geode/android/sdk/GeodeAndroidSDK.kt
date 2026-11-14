package com.geode.android.sdk

import android.os.Build
import android.util.Log
import androidx.annotation.Keep

/**
 * Stable, versioned entry point shared by Android64 Geode mods.
 *
 * The launcher initializes this object before loading Geode mods. Native mods
 * can use the C ABI in geode_android_sdk.h after linking the AAR's .so.
 */
@Keep
object GeodeAndroidSDK {
    const val API_VERSION = 1
    const val VERSION = "0.1.0-alpha"

    private val initLock = Any()

    @Volatile
    private var bridgeLoaded = false

    @Volatile
    private var hostCallbacks: HostCallbacks? = null

    @Volatile
    private var launcherVersion = "unknown"

    @Volatile
    private var gameVersionCode = -1L

    @Volatile
    private var geodeVersion = "unknown"

    interface HostCallbacks {
        fun onDiagnosticEvent(name: String, attributesJson: String)
        fun onLevelStarted(levelId: String?, levelName: String?)
        fun onLevelEnded()
    }

    /**
     * Called by the launcher before loading Geode mods.
     * Returns false when the native bridge could not be loaded; the game can
     * continue without the optional SDK.
     */
    @JvmStatic
    fun initialize(
        launcherVersion: String,
        gameVersionCode: Long,
        geodeVersion: String,
        callbacks: HostCallbacks
    ): Boolean = synchronized(initLock) {
        this.launcherVersion = launcherVersion.ifBlank { "unknown" }
        this.gameVersionCode = gameVersionCode
        this.geodeVersion = geodeVersion.ifBlank { "unknown" }
        hostCallbacks = callbacks

        if (bridgeLoaded) return@synchronized true

        try {
            System.loadLibrary("geode_android_sdk")
            bridgeLoaded = true
            true
        } catch (error: UnsatisfiedLinkError) {
            Log.e("GeodeAndroidSDK", "Native SDK bridge could not be loaded", error)
            false
        } catch (error: SecurityException) {
            Log.e("GeodeAndroidSDK", "Native SDK bridge loading was blocked", error)
            false
        }
    }

    @JvmStatic
    fun getApiVersion(): Int = API_VERSION

    @JvmStatic
    fun getVersion(): String = VERSION

    @JvmStatic
    fun getCapabilitiesJson(): String =
        """["device.info","launcher.info","diagnostics.events","diagnostics.level-markers","graphics.context-query","graphics.gles-compat-helpers"]"""

    @JvmStatic
    fun getLauncherVersion(): String = launcherVersion

    @JvmStatic
    fun getGameVersionCode(): Long = gameVersionCode

    @JvmStatic
    fun getGeodeVersion(): String = geodeVersion

    @JvmStatic
    fun getAndroidSdkInt(): Int = Build.VERSION.SDK_INT

    @JvmStatic
    fun getPrimaryAbi(): String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    @JvmStatic
    fun isAvailable(): Boolean = bridgeLoaded && hostCallbacks != null

    // These static methods are called by the native C ABI bridge.
    @JvmStatic
    fun nativeIsAvailable(): Boolean = isAvailable()

    @JvmStatic
    fun nativeRecordDiagnosticEvent(name: String, attributesJson: String): Boolean {
        if (!isAvailable() || name.isBlank()) return false
        val callbacks = hostCallbacks ?: return false
        return try {
            callbacks.onDiagnosticEvent(name.take(80), attributesJson.take(8192))
            true
        } catch (error: Exception) {
            Log.e("GeodeAndroidSDK", "Diagnostic event callback failed", error)
            false
        }
    }

    @JvmStatic
    fun nativeOnLevelStarted(levelId: String?, levelName: String?): Boolean {
        if (!isAvailable()) return false
        return try {
            hostCallbacks?.onLevelStarted(levelId?.take(128), levelName?.take(180))
            true
        } catch (error: Exception) {
            Log.e("GeodeAndroidSDK", "Level-start callback failed", error)
            false
        }
    }

    @JvmStatic
    fun nativeOnLevelEnded(): Boolean {
        if (!isAvailable()) return false
        return try {
            hostCallbacks?.onLevelEnded()
            true
        } catch (error: Exception) {
            Log.e("GeodeAndroidSDK", "Level-end callback failed", error)
            false
        }
    }
}

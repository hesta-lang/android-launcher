package com.geode.launcher

import com.geode.android.sdk.GeodeAndroidSDK
import org.json.JSONObject

/**
 * Connects the public mod SDK callbacks to the launcher's own diagnostic recorder.
 * Diagnostic collection remains owned by the launcher; mods only submit events.
 */
object LauncherGeodeAndroidSdkHost : GeodeAndroidSDK.HostCallbacks {
    override fun onDiagnosticEvent(name: String, attributesJson: String) {
        val attributes = try {
            JSONObject(attributesJson)
        } catch (_: Exception) {
            JSONObject().put("raw_attributes", attributesJson.take(8192))
        }
        LauncherDiagnostics.recordEvent(name, attributes)
    }

    override fun onLevelStarted(levelId: String?, levelName: String?) {
        LauncherDiagnostics.onLevelStarted(levelId, levelName)
    }

    override fun onLevelEnded() {
        LauncherDiagnostics.onLevelEnded()
    }
}

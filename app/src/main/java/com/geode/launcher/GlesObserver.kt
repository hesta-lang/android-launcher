package com.geode.launcher

import androidx.annotation.Keep
import com.geode.android.sdk.GeodeAndroidSDK
import org.json.JSONObject

/**
 * Read-only snapshot of the EGL/GLES context already owned by Cocos.
 *
 * This object must be called from GLSurfaceView.Renderer callbacks only.
 * It never creates/makes current an EGL context and never submits GL commands.
 */
@Keep
object GlesObserver {
    private var contextGeneration = 0

    @Synchronized
    fun onContextCreated() {
        contextGeneration += 1
        val generation = contextGeneration
        if (!LauncherDiagnostics.isRecording()) return

        val eventName = if (generation == 1) {
            "gles_context_created"
        } else {
            "gles_context_recreated"
        }

        val snapshot = try {
            val json = GeodeAndroidSDK.nativeQueryGraphicsContext()
            if (json.isNullOrBlank()) {
                LauncherDiagnostics.recordEvent(
                    "gles_context_query_failed",
                    JSONObject()
                        .put("context_generation", generation)
                        .put("reason", "native_query_returned_empty")
                )
                return
            }
            JSONObject(json)
        } catch (error: UnsatisfiedLinkError) {
            LauncherDiagnostics.recordEvent(
                "gles_context_query_failed",
                JSONObject()
                    .put("context_generation", generation)
                    .put("reason", "graphics_query_native_method_unavailable")
                    .put("error", error.message ?: error.javaClass.simpleName)
            )
            return
        } catch (error: Exception) {
            LauncherDiagnostics.recordEvent(
                "gles_context_query_failed",
                JSONObject()
                    .put("context_generation", generation)
                    .put("reason", "graphics_query_exception")
                    .put("error", error.message ?: error.javaClass.simpleName)
            )
            return
        }

        snapshot.put("context_generation", generation)
        snapshot.put("query_source", "geode_android_graphics_query_current_context")
        snapshot.put("renderer_owner", "Cocos2dxRenderer")

        if (!snapshot.optBoolean("context_current", false)) {
            LauncherDiagnostics.recordEvent("gles_context_query_unavailable", snapshot)
            return
        }

        LauncherDiagnostics.recordEvent(eventName, snapshot)
    }
}

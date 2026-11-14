package com.geode.launcher

import androidx.annotation.Keep
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.cocos2dx.lib.Cocos2dxGLSurfaceView
import org.cocos2dx.lib.Cocos2dxRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Delegates every renderer callback to the original Cocos renderer.
 * Measurements describe CPU-side callback work and callback cadence only.
 */
@Keep
class DiagnosticsCocos2dxRenderer(
    surface: Cocos2dxGLSurfaceView
) : Cocos2dxRenderer(surface) {
    @Volatile private var previousFrameStartNs = 0L
    @Volatile private var gpuTimerAvailable = false

    // JNI timer-query bridge. All calls are made on the GLSurfaceView render thread.
    private external fun nativeInitGpuTimer(): Boolean
    private external fun nativeBeginGpuTimer(): Boolean
    private external fun nativeEndGpuTimer()
    private external fun nativePollGpuTimer(): Long

    fun resetFrameClock() {
        previousFrameStartNs = 0L
    }

    /**
     * Requests an immediate EGL swap interval on Cocos' current render thread.
     * EGL implementations are allowed to clamp interval 0, so the diagnostic
     * records the request result rather than claiming presentation is uncapped.
     */
    private fun requestUncappedSwapInterval(stage: String) {
        val attributes = JSONObject()
            .put("stage", stage)
            .put("requested_swap_interval", 0)

        try {
            val display = EGL14.eglGetCurrentDisplay()
            if (display == EGL14.EGL_NO_DISPLAY) {
                attributes
                    .put("egl_call_succeeded", false)
                    .put("reason", "no_current_egl_display")
                LauncherDiagnostics.recordEvent("egl_swap_interval_request", attributes)
                Log.w("GeodeLauncher", "Cannot request EGL swap interval 0: no current display")
                return
            }

            val success = EGL14.eglSwapInterval(display, 0)
            attributes.put("egl_call_succeeded", success)
            if (success) {
                Log.i("GeodeLauncher", "Requested EGL swap interval 0 at $stage")
            } else {
                val error = EGL14.eglGetError()
                attributes.put("egl_error", "0x${error.toString(16)}")
                Log.w(
                    "GeodeLauncher",
                    "EGL swap interval 0 request failed at $stage (EGL 0x${error.toString(16)})"
                )
            }
        } catch (error: Exception) {
            attributes
                .put("egl_call_succeeded", false)
                .put("exception", error.message ?: error.javaClass.simpleName)
            Log.w("GeodeLauncher", "EGL swap interval 0 request threw at $stage", error)
        }

        LauncherDiagnostics.recordEvent("egl_swap_interval_request", attributes)
    }

    /**
     * Record core GLES capabilities independently of the (often truncated)
     * GL_EXTENSIONS string so Bismuth ports can select the right GLES path.
     */
    private fun queryGlesCapabilities(version: String?): JSONObject {
        val result = JSONObject()
        val versionMatch = Regex("""OpenGL ES\s+(\d+)\.(\d+)""")
            .find(version.orEmpty())
        val major = versionMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minor = versionMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        val atLeast31 = major > 3 || (major == 3 && minor >= 1)
        val atLeast32 = major > 3 || (major == 3 && minor >= 2)

        result
            .put("major_version", major)
            .put("minor_version", minor)
            .put("supports_gles_30", major >= 3)
            .put("supports_gles_31", atLeast31)
            .put("supports_gles_32", atLeast32)
            .put("supports_compute_shader", atLeast31)
            .put("supports_shader_storage_buffers", atLeast31)
            .put("supports_draw_indirect", atLeast31)
            .put("supports_program_resource_query", atLeast31)
            .put("supports_map_buffer_range", major >= 3)

        try {
            val extensions = linkedSetOf<String>()
            if (major >= 3) {
                val count = IntArray(1)
                GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, count, 0)
                val safeCount = count[0].coerceIn(0, 4096)
                for (index in 0 until safeCount) {
                    GLES30.glGetStringi(GLES30.GL_EXTENSIONS, index)
                        ?.takeIf { it.isNotBlank() }
                        ?.let(extensions::add)
                }
            }

            result
                .put("extension_count", extensions.size)
                .put("supports_multi_draw_indirect", "GL_EXT_multi_draw_indirect" in extensions)
                .put("supports_shader_draw_parameters", "GL_EXT_shader_draw_parameters" in extensions)
                .put(
                    "supports_khr_debug",
                    atLeast32 || "GL_KHR_debug" in extensions
                )
                .put(
                    "supports_disjoint_timer_query",
                    "GL_EXT_disjoint_timer_query" in extensions ||
                        "GL_EXT_disjoint_timer_query_webgl2" in extensions
                )

            if (atLeast31) {
                val invocations = IntArray(1)
                val storageBindings = IntArray(1)
                GLES31.glGetIntegerv(
                    GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS,
                    invocations,
                    0
                )
                GLES31.glGetIntegerv(
                    GLES31.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS,
                    storageBindings,
                    0
                )
                result
                    .put("max_compute_work_group_invocations", invocations[0])
                    .put("max_shader_storage_buffer_bindings", storageBindings[0])
            }
        } catch (error: Exception) {
            result.put("capability_query_error", error.message ?: error.javaClass.simpleName)
        }

        return result
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        super.onSurfaceCreated(gl, config)
        requestUncappedSwapInterval("surface_created")
        gpuTimerAvailable = try {
            nativeInitGpuTimer()
        } catch (error: UnsatisfiedLinkError) {
            Log.w("GeodeLauncher", "GPU timer-query bridge is unavailable", error)
            false
        } catch (error: Exception) {
            Log.w("GeodeLauncher", "Could not initialize GPU timer queries", error)
            false
        }
        LauncherDiagnostics.recordEvent(
            "gpu_timer_initialized",
            JSONObject()
                .put("supported", gpuTimerAvailable)
                .put("method", "GL_EXT_disjoint_timer_query")
                .put("measurement", "asynchronous_gpu_command_execution")
        )
        try {
            if (gl != null) {
                val version = gl.glGetString(GL10.GL_VERSION)
                LauncherDiagnostics.recordGraphicsContext(
                    gl.glGetString(GL10.GL_VENDOR),
                    gl.glGetString(GL10.GL_RENDERER),
                    version,
                    gl.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION),
                    gl.glGetString(GL10.GL_EXTENSIONS),
                    queryGlesCapabilities(version)
                )
            }
        } catch (error: Exception) {
            LauncherDiagnostics.recordEvent(
                "gl_context_query_failed",
                JSONObject().put("message", error.message ?: error.javaClass.simpleName)
            )
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        super.onSurfaceChanged(gl, width, height)
        requestUncappedSwapInterval("surface_changed")
        LauncherDiagnostics.recordGraphicsSurface(width, height)
        previousFrameStartNs = 0L
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!LauncherDiagnostics.isRecording()) {
            previousFrameStartNs = 0L
            super.onDrawFrame(gl)
            return
        }

        val frameStartNs = SystemClock.elapsedRealtimeNanos()
        val intervalNs = if (previousFrameStartNs == 0L) 0L else frameStartNs - previousFrameStartNs
        previousFrameStartNs = frameStartNs

        // Poll an older query before starting this frame's query. The native bridge
        // never waits for the GPU and drops disjoint measurements instead of blocking.
        if (gpuTimerAvailable) {
            try {
                val completedGpuNs = nativePollGpuTimer()
                when {
                    completedGpuNs >= 0L ->
                        LauncherDiagnostics.recordGpuFrame(completedGpuNs, false)
                    completedGpuNs == -2L ->
                        LauncherDiagnostics.recordGpuFrame(0L, true)
                }
            } catch (error: Exception) {
                gpuTimerAvailable = false
                LauncherDiagnostics.recordEvent(
                    "gpu_timer_runtime_error",
                    JSONObject().put("message", error.message ?: error.javaClass.simpleName)
                )
                Log.w("GeodeLauncher", "Disabling GPU timer queries after a runtime error", error)
            }
        }

        val gpuQueryStarted = if (gpuTimerAvailable) {
            try {
                nativeBeginGpuTimer()
            } catch (error: Exception) {
                gpuTimerAvailable = false
                LauncherDiagnostics.recordEvent(
                    "gpu_timer_runtime_error",
                    JSONObject().put("message", error.message ?: error.javaClass.simpleName)
                )
                false
            }
        } else false

        val callbackStartNs = System.nanoTime()
        val cpuStartNs = Debug.threadCpuTimeNanos()
        try {
            super.onDrawFrame(gl)
        } finally {
            if (gpuQueryStarted) {
                try {
                    nativeEndGpuTimer()
                } catch (error: Exception) {
                    gpuTimerAvailable = false
                    LauncherDiagnostics.recordEvent(
                        "gpu_timer_runtime_error",
                        JSONObject().put("message", error.message ?: error.javaClass.simpleName)
                    )
                    Log.w("GeodeLauncher", "Could not close GPU timer query", error)
                }
            }
            val callbackWallNs = System.nanoTime() - callbackStartNs
            val cpuEndNs = Debug.threadCpuTimeNanos()
            val callbackCpuNs = if (cpuStartNs >= 0L && cpuEndNs >= cpuStartNs) {
                cpuEndNs - cpuStartNs
            } else {
                -1L
            }
            LauncherDiagnostics.recordFrame(intervalNs, callbackWallNs, callbackCpuNs)
        }
    }
}

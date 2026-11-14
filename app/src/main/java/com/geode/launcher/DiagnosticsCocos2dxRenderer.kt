package com.geode.launcher

import android.opengl.GLES20
import android.os.Debug
import android.os.SystemClock
import org.cocos2dx.lib.Cocos2dxGLSurfaceView
import org.cocos2dx.lib.Cocos2dxRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Delegates every renderer callback to the original Cocos renderer.
 * Measurements describe CPU-side callback work and callback cadence only.
 */
class DiagnosticsCocos2dxRenderer(
    surface: Cocos2dxGLSurfaceView
) : Cocos2dxRenderer(surface) {
    private var previousFrameStartNs = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        super.onSurfaceCreated(gl, config)
        try {
            if (gl != null) {
                LauncherDiagnostics.recordGraphicsContext(
                    gl.glGetString(GL10.GL_VENDOR),
                    gl.glGetString(GL10.GL_RENDERER),
                    gl.glGetString(GL10.GL_VERSION),
                    gl.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION),
                    gl.glGetString(GL10.GL_EXTENSIONS)
                )
            }
        } catch (error: Exception) {
            LauncherDiagnostics.recordEvent(
                "gl_context_query_failed",
                org.json.JSONObject().put("message", error.message ?: error.javaClass.simpleName)
            )
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        super.onSurfaceChanged(gl, width, height)
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

        val callbackStartNs = System.nanoTime()
        val cpuStartNs = Debug.threadCpuTimeNanos()
        try {
            super.onDrawFrame(gl)
        } finally {
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

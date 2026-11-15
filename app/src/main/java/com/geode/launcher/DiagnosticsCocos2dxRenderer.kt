package com.geode.launcher

import androidx.annotation.Keep
import android.os.Debug
import android.os.SystemClock
import org.cocos2dx.lib.Cocos2dxGLSurfaceView
import org.cocos2dx.lib.Cocos2dxRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Passive diagnostics wrapper around the original Cocos renderer.
 *
 * GLES capabilities are queried once from onSurfaceCreated while Cocos' own
 * EGL context is current. Frame rendering remains delegated to Cocos unchanged.
 */
@Keep
class DiagnosticsCocos2dxRenderer(
    surface: Cocos2dxGLSurfaceView
) : Cocos2dxRenderer(surface) {
    @Volatile private var previousFrameStartNs = 0L

    fun resetFrameClock() {
        previousFrameStartNs = 0L
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        super.onSurfaceCreated(gl, config)
        previousFrameStartNs = 0L
        GlesObserver.onContextCreated()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        super.onSurfaceChanged(gl, width, height)
        LauncherDiagnostics.recordGraphicsSurface(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!LauncherDiagnostics.isRecording()) {
            previousFrameStartNs = 0L
            super.onDrawFrame(gl)
            return
        }

        val frameStartNs = SystemClock.elapsedRealtimeNanos()
        val intervalNs = if (previousFrameStartNs == 0L) {
            0L
        } else {
            frameStartNs - previousFrameStartNs
        }
        previousFrameStartNs = frameStartNs

        val callbackStartNs = System.nanoTime()
        val cpuStartNs = Debug.threadCpuTimeNanos()
        try {
            // Preserve the original Cocos draw callback; no GL work is added per frame.
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

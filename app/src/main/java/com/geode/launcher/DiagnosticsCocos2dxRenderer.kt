package com.geode.launcher

import androidx.annotation.Keep
import android.os.Debug
import android.os.SystemClock
import org.cocos2dx.lib.Cocos2dxGLSurfaceView
import org.cocos2dx.lib.Cocos2dxRenderer
import javax.microedition.khronos.opengles.GL10

/**
 * CPU-only observer around the original Cocos renderer.
 *
 * No EGL calls, GLES queries, GL timer queries, buffer operations, or context
 * changes are performed here. Each rendering callback is delegated unchanged
 * to Cocos2dxRenderer.
 */
@Keep
class DiagnosticsCocos2dxRenderer(
    surface: Cocos2dxGLSurfaceView
) : Cocos2dxRenderer(surface) {
    @Volatile private var previousFrameStartNs = 0L

    fun resetFrameClock() {
        previousFrameStartNs = 0L
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
            // This is the original Cocos renderer; do not add GL work around it.
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

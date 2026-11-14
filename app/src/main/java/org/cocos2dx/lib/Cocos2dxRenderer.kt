package org.cocos2dx.lib

import android.opengl.GLSurfaceView
import android.os.Build
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@Suppress("unused", "KotlinJniMissingFunction")
open class Cocos2dxRenderer(private var handler: Cocos2dxGLSurfaceView) : GLSurfaceView.Renderer {
    companion object {
        @JvmStatic
        fun setAnimationInterval(@Suppress("UNUSED_PARAMETER") animationInterval: Double) {
            // this function is useless for gd
        }

        @JvmStatic
        private external fun nativeDeleteBackward()

        @JvmStatic
        private external fun nativeGetContentText(): String

        @JvmStatic
        private external fun nativeInsertText(text: String)

        @JvmStatic
        private external fun nativeKeyDown(keyCode: Int): Boolean

        @JvmStatic
        private external fun nativeKeyboardEvent(
            keyCode: Int,
            down: Boolean,
            isRepeat: Boolean,
            timestamp: Double,
            shiftPressed: Boolean,
            ctrlPressed: Boolean,
            altPressed: Boolean
        )

        @JvmStatic
        private external fun nativeKeyboardWillShow(offset: Int)

        @JvmStatic
        private external fun nativeOnPause()

        @JvmStatic
        private external fun nativeOnResume()

        @JvmStatic
        private external fun nativeScroll(x: Float, y: Float, scrollY: Float, scrollX: Float)

        @JvmStatic
        private external fun nativeRender()

        @JvmStatic
        private external fun nativeTextClosed()

        // timestamp was added in 2.208, but the jni method has no required type signature.
        // it doesn't hurt to unconditionally send one

        @JvmStatic
        private external fun nativeTouchesBegin(id: Int, x: Float, y: Float, timestamp: Double)

        @JvmStatic
        private external fun nativeTouchesCancel(
            ids: IntArray,
            xs: FloatArray,
            ys: FloatArray,
            timestamp: Double,
        )

        @JvmStatic
        private external fun nativeTouchesEnd(id: Int, x: Float, y: Float, timestamp: Double)

        @JvmStatic
        private external fun nativeTouchesMove(ids: IntArray, xs: FloatArray, ys: FloatArray, timestamp: Double)

        @JvmStatic
        private external fun nativeInit(width: Int, height: Int)
    }

    private var screenWidth = 0
    private var screenHeight = 0
    var sendResizeEvents = false
    var setFrameRate = false

    /**
     * Kept for source compatibility with launcher integrations.
     * The launcher intentionally does not impose a software frame-rate limit.
     */
    @Deprecated("Software frame-rate limiting is disabled in the launcher")
    fun limitFrameRate(@Suppress("UNUSED_PARAMETER") rate: Int) {
        // Deliberately no-op: render as soon as the EGL driver permits.
    }

    fun setScreenWidthAndHeight(surfaceWidth: Int, surfaceHeight: Int) {
        screenWidth = surfaceWidth
        screenHeight = surfaceHeight
    }

    override fun onSurfaceCreated(gl10: GL10?, eglConfig: EGLConfig?) {
        nativeInit(screenWidth, screenHeight)
    }

    override fun onSurfaceChanged(gl10: GL10?, width: Int, height: Int) {
        if (setFrameRate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            handler.updateRefreshRate()
        }

        println("renderer surfaceChanged: ${width}x${height}@${handler.display.refreshRate}fps")
    }

    override fun onDrawFrame(gl: GL10?) {
        // No Thread.sleep or software FPS cap. EGL/surface pacing is configured
        // by DiagnosticsCocos2dxRenderer on the active render thread.
        nativeRender()
    }

    fun handleActionDown(id: Int, x: Float, y: Float, timestamp: Double) {
        nativeTouchesBegin(id, x, y, timestamp)
    }

    fun handleActionUp(id: Int, x: Float, y: Float, timestamp: Double) {
        nativeTouchesEnd(id, x, y, timestamp)
    }

    fun handleActionCancel(ids: IntArray, xs: FloatArray, ys: FloatArray, timestamp: Double) {
        nativeTouchesCancel(ids, xs, ys, timestamp)
    }

    fun handleActionMove(ids: IntArray, xs: FloatArray, ys: FloatArray, timestamp: Double) {
        nativeTouchesMove(ids, xs, ys, timestamp)
    }

    fun handleKeyDown(keyCode: Int) {
        nativeKeyDown(keyCode)
    }

    fun handleKeyboardWillShow(offset: Int) {
        nativeKeyboardWillShow(offset)
    }

    fun handleKeyboardEvent(keyCode: Int, down: Boolean, isRepeat: Boolean, timestamp: Double, shiftPressed: Boolean, ctrlPressed: Boolean, altPressed: Boolean) {
        nativeKeyboardEvent(keyCode, down, isRepeat, timestamp, shiftPressed, ctrlPressed, altPressed)
    }

    fun handleScroll(x: Float, y: Float, scrollY: Float, scrollX: Float) {
        nativeScroll(x, y, scrollY, scrollX)
    }

    fun handleOnPause() {
        nativeOnPause()
    }

    fun handleOnResume() {
        nativeOnResume()
    }

    fun handleInsertText(text: String) {
        nativeInsertText(text)
    }

    fun handleDeleteBackward() {
        nativeDeleteBackward()
    }

    fun handleTextClosed() {
        nativeTextClosed()
    }

    fun getContentText(): String {
        return nativeGetContentText()
    }
}
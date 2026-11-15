#!/usr/bin/env python3
"""Static contract checks for the passive GLES observer."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

renderer = (ROOT / "app/src/main/java/com/geode/launcher/DiagnosticsCocos2dxRenderer.kt").read_text()
activity = (ROOT / "app/src/main/java/com/geode/launcher/GeometryDashActivity.kt").read_text()
observer = (ROOT / "app/src/main/java/com/geode/launcher/GlesObserver.kt").read_text()
graphics = (ROOT / "geode-android-sdk/src/main/cpp/geode_android_graphics.cpp").read_text()
sdk = (ROOT / "geode-android-sdk/src/main/java/com/geode/android/sdk/GeodeAndroidSDK.kt").read_text()

assert "super.onSurfaceCreated(gl, config)" in renderer
assert renderer.index("super.onSurfaceCreated(gl, config)") < renderer.index("GlesObserver.onContextCreated()")
assert "super.onDrawFrame(gl)" in renderer
assert "GeodeAndroidSDK.nativeQueryGraphicsContext()" in observer
assert "gles_context_created" in observer and "gles_context_recreated" in observer
assert "eglGetCurrentContext()" in graphics
assert "geode_android_graphics_query_current_context(&info)" in graphics
assert "Java_com_geode_android_sdk_GeodeAndroidSDK_nativeQueryGraphicsContext" in graphics
assert "nativeQueryGraphicsContext(): String?" in sdk

# Preserve the existing Cocos-owned EGL setup: ES 2 request and original config chooser.
assert "glSurfaceView.setEGLContextClientVersion(2)" in activity
assert "glSurfaceView.setEGLConfigChooser(EGLConfigChooser())" in activity

# The observer must remain query-only. It may not switch contexts or issue draw/state commands.
for forbidden in (
    "eglCreateContext",
    "eglMakeCurrent",
    "eglSwapInterval",
    "glDrawArrays",
    "glDrawElements",
    "glBindBuffer",
    "glUseProgram",
):
    assert forbidden not in graphics, f"forbidden GL/EGL operation found: {forbidden}"

print("PASS: passive GLES observer contract")

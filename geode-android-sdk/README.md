# Geode Android SDK

Shared Android host API for Geode mods running inside hesta-lang's Android64 launcher.

- SDK version: `0.1.0-alpha`
- API version: `1`
- ABIs: `arm64-v8a`, `armeabi-v7a`
- Minimum Android API: 23

## What is included

The AAR contains the Kotlin API and `libgeode_android_sdk.so`. The GitHub Actions artifact also includes the public C headers `geode_android_sdk.h`, `geode_android_graphics.h` and `geode_android_gl_compat.h`.

API v1 exposes:
- SDK/API version and capability discovery.
- Launcher version, Geometry Dash version code, Geode version, Android API level and primary ABI.
- Native diagnostic events.
- Native level-start and level-end markers forwarded to the launcher's diagnostics recorder.

The launcher initializes the SDK before loading Geode mods. If the bridge is unavailable, the game continues without these optional SDK features.

## C++ integration

Download the `geode-android-sdk-alpha` Actions artifact and extract the AAR:

```sh
mkdir -p external/geode-android-sdk/aar external/geode-android-sdk/include
unzip -q geode-android-sdk-release.aar -d external/geode-android-sdk/aar
cp geode_android_sdk.h geode_android_graphics.h geode_android_gl_compat.h external/geode-android-sdk/include/
```

In the mod's CMake project, link the native library for the active Android ABI:

```cmake
add_library(geode_android_sdk SHARED IMPORTED)
set_target_properties(geode_android_sdk PROPERTIES
    IMPORTED_LOCATION
    "${CMAKE_CURRENT_SOURCE_DIR}/external/geode-android-sdk/aar/jni/${ANDROID_ABI}/libgeode_android_sdk.so"
)
target_include_directories(${PROJECT_NAME} PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/external/geode-android-sdk/include"
)
target_link_libraries(${PROJECT_NAME} PRIVATE geode_android_sdk)
```

Example:

```cpp
#include "geode_android_sdk.h"

void report_level_start(const char* id, const char* name) {
    if (geode_android_sdk_get_api_version() < GEODE_ANDROID_SDK_API_VERSION) return;
    if (!geode_android_sdk_is_available()) return;

    geode_android_sdk_level_started(id, name);
    geode_android_sdk_record_diagnostic_event(
        "my_mod.level_loaded",
        R"({"source":"gameplay"})"
    );
}

void report_level_end() {
    geode_android_sdk_level_ended();
}
```

A return value of `1` means the launcher accepted the callback. It does not mean a remote telemetry service has received it. The diagnostics recorder may ignore events when no diagnostic session is active.

## Compatibility rules

- Check `geode_android_sdk_get_api_version()` before using newer functions.
- Check `geode_android_sdk_is_available()` before sending callbacks.
- Treat missing capabilities as unsupported; do not assume every launcher version exposes every capability.
- Keep calls short. File pickers, permission prompts and other UI operations will be added as asynchronous APIs in later versions.
- Do not call Android UI APIs directly from a Geode render/game thread.

## Android graphics bridge (API v1)

The graphics bridge is an opt-in query and compatibility layer for native Geode mods. It uses the EGL context already owned by Geometry Dash/Cocos; it does not create another context, change the game's EGL configuration, intercept global GL symbols, or replace the vanilla renderer.

`geode_android_graphics_query_current_context()` must be called from a Geode/Cocos render callback while the game's EGL context is current on that thread. It returns the actual GLES version, vendor, renderer, GLSL version, relevant feature flags and compute/SSBO limits. A return value of `0` means there was no current EGL context; negative values indicate invalid arguments. Hardware-specific flags must be checked at runtime.

The launcher-side observer also exposes the same native snapshot to its diagnostics recorder. It captures the full extension list and capability/limit data once from `onSurfaceCreated`, then writes `gles_context_created` or `gles_context_recreated` to the JSONL session. The observer does not issue draw calls, change GL state, poll the driver every frame, or create/reconfigure EGL contexts.

The `geode_android_gl_compat.h` helpers provide opt-in GLES equivalents for common desktop-porting gaps:

- Multi-draw indirect is emulated by issuing the GLES 3.1 single-command indirect draw once per command. This keeps command order but can cost more CPU/driver overhead than native multi-draw.
- `glMapBuffer` is implemented with `glMapBufferRange`.
- `glGetBufferSubData` is implemented by temporarily mapping the currently bound buffer for reading. This may synchronize with the GPU.

Linking example:

```cmake
target_link_libraries(${PROJECT_NAME} PRIVATE geode_android_sdk EGL GLESv3)
```

### Bismuth porting boundary

This bridge is a foundation, not a claim that desktop Bismuth runs unchanged on Android. Bismuth currently targets desktop OpenGL 4.3/4.6-era functionality, preprocesses GLSL as desktop 4.50/4.60, uses SPIR-V specialization code, and queries GPU compute units through Windows WGL/OpenCL interop. GLES 3.1 provides compute shaders, SSBOs and single indirect draws, but those desktop paths still need explicit Android implementations.

For a compatible independent Android renderer, the remaining work includes:
- a GLSL ES 3.10 shader path (including replacing desktop-only include directives and checking shader-language differences);
- replacing WGL/OpenCL GPU-core queries with GLES compute limits or a tunable scheduler;
- guarding desktop-only SPIR-V specialization, multi-draw, timer-query and debug-label paths;
- keeping all GL work on the game's current render thread and preserving Cocos' render state.

GL4ES is intentionally not installed as a global `libGL` replacement: its advertised desktop compatibility target is OpenGL 1.5/2.1 over GLES 1.1/2.0, which does not cover a compute/SSBO pipeline.

## Launcher EGL context selection

The `gles-observer-source` launcher branch preserves the existing Cocos EGL setup: `setEGLContextClientVersion(2)` and the original `EGLConfigChooser`. The observer only reports the actual version supplied by the driver. It does not request GLES 3.1 or silently change context configuration. A GLES 2.0 context will therefore report compute shaders, SSBOs and indirect drawing as unavailable.

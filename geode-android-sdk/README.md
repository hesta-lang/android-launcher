# Geode Android SDK

Shared Android host API for Geode mods running inside hesta-lang's Android64 launcher.

- SDK version: `0.1.0-alpha`
- API version: `1`
- ABIs: `arm64-v8a`, `armeabi-v7a`
- Minimum Android API: 23

## What is included

The AAR contains the Kotlin API and `libgeode_android_sdk.so`. The GitHub Actions artifact also includes the public C header `geode_android_sdk.h`.

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
cp geode_android_sdk.h external/geode-android-sdk/include/
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

A return value of `1` means the launcher accepted the callback. It does not mean a remote telemetry service has received the event. The diagnostics recorder may ignore events when no diagnostic session is active.

## Compatibility rules

- Check `geode_android_sdk_get_api_version()` before using newer functions.
- Check `geode_android_sdk_is_available()` before sending callbacks.
- Treat missing capabilities as unsupported; do not assume every launcher version exposes every API.
- Keep calls short. File pickers, permission prompts and other UI operations will be added as asynchronous APIs in later SDK versions.
- Do not call Android UI APIs directly from a Geode render/game thread.

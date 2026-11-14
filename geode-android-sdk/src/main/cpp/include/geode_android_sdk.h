#pragma once

#include <stdint.h>

#define GEODE_ANDROID_SDK_API_VERSION 1

#if defined(__GNUC__)
#define GEODE_ANDROID_SDK_EXPORT __attribute__((visibility("default")))
#else
#define GEODE_ANDROID_SDK_EXPORT
#endif

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Returns the SDK API version, independent of whether the Kotlin host is ready.
 */
GEODE_ANDROID_SDK_EXPORT int32_t geode_android_sdk_get_api_version(void);

/**
 * Returns a static JSON capability list. The pointer remains valid for the
 * lifetime of the loaded library.
 */
GEODE_ANDROID_SDK_EXPORT const char* geode_android_sdk_get_capabilities_json(void);

/** Returns 1 after the launcher has initialized the SDK, otherwise 0. */
GEODE_ANDROID_SDK_EXPORT int32_t geode_android_sdk_is_available(void);

/**
 * Sends a diagnostic event to the launcher's diagnostic recorder.
 * attributes_json must be a JSON object string; null is treated as "{}".
 */
GEODE_ANDROID_SDK_EXPORT int32_t geode_android_sdk_record_diagnostic_event(
    const char* name,
    const char* attributes_json
);

/** Marks a level start in launcher-owned diagnostics. Null strings are allowed. */
GEODE_ANDROID_SDK_EXPORT int32_t geode_android_sdk_level_started(
    const char* level_id,
    const char* level_name
);

/** Marks a level end in launcher-owned diagnostics. */
GEODE_ANDROID_SDK_EXPORT int32_t geode_android_sdk_level_ended(void);

#ifdef __cplusplus
}
#endif

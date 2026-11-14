#pragma once
#include <stdint.h>
#define GEODE_ANDROID_GRAPHICS_API_VERSION 1
#if defined(__GNUC__)
#define GEODE_ANDROID_GRAPHICS_EXPORT __attribute__((visibility("default")))
#else
#define GEODE_ANDROID_GRAPHICS_EXPORT
#endif
#ifdef __cplusplus
extern "C" {
#endif
enum geode_android_graphics_flags {
 GEODE_ANDROID_GRAPHICS_CONTEXT_CURRENT=1u<<0,
 GEODE_ANDROID_GRAPHICS_GLES31=1u<<1,
 GEODE_ANDROID_GRAPHICS_COMPUTE_SHADER=1u<<2,
 GEODE_ANDROID_GRAPHICS_SHADER_STORAGE_BUFFER=1u<<3,
 GEODE_ANDROID_GRAPHICS_DRAW_INDIRECT=1u<<4,
 GEODE_ANDROID_GRAPHICS_MULTI_DRAW_INDIRECT=1u<<5,
 GEODE_ANDROID_GRAPHICS_SHADER_DRAW_PARAMETERS=1u<<6,
 GEODE_ANDROID_GRAPHICS_PROGRAM_RESOURCE_QUERY=1u<<7,
 GEODE_ANDROID_GRAPHICS_MAP_BUFFER_RANGE=1u<<8,
 GEODE_ANDROID_GRAPHICS_KHR_DEBUG=1u<<9,
 GEODE_ANDROID_GRAPHICS_DISJOINT_TIMER_QUERY=1u<<10
};
typedef struct geode_android_graphics_info {
 uint32_t struct_size;
 uint32_t flags;
 int32_t major_version;
 int32_t minor_version;
 int32_t max_compute_work_group_invocations;
 int32_t max_shader_storage_buffer_bindings;
 int64_t max_shader_storage_block_size;
 int32_t max_compute_work_group_count[3];
 int32_t max_compute_work_group_size[3];
 char vendor[128];
 char renderer[256];
 char version[128];
 char shading_language_version[128];
} geode_android_graphics_info;
/* Call only from a render callback while the game's EGL context is current.
   Returns 1 on query, 0 without a current context, negative on bad arguments. */
GEODE_ANDROID_GRAPHICS_EXPORT int32_t geode_android_graphics_query_current_context(geode_android_graphics_info* info);
GEODE_ANDROID_GRAPHICS_EXPORT int32_t geode_android_graphics_has_extension(const char* extension);
GEODE_ANDROID_GRAPHICS_EXPORT void* geode_android_graphics_get_proc_address(const char* name);
#ifdef __cplusplus
}
#endif

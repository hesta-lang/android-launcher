#include "geode_android_sdk.h"

#include <jni.h>

#include <cstdint>

namespace {
JavaVM* g_vm = nullptr;
jclass g_sdk_class = nullptr;
jmethodID g_is_available = nullptr;
jmethodID g_record_event = nullptr;
jmethodID g_level_started = nullptr;
jmethodID g_level_ended = nullptr;

JNIEnv* acquire_env(bool& attached) {
    attached = false;
    if (g_vm == nullptr) return nullptr;

    JNIEnv* env = nullptr;
    const jint status = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_OK) return env;
    if (status != JNI_EDETACHED) return nullptr;

    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    attached = true;
    return env;
}

void release_env(bool attached) {
    if (attached && g_vm != nullptr) {
        g_vm->DetachCurrentThread();
    }
}

bool clear_exception(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionClear();
    return true;
}

jstring to_java_string(JNIEnv* env, const char* value) {
    return value == nullptr ? nullptr : env->NewStringUTF(value);
}

int32_t call_boolean_no_args(jmethodID method) {
    bool attached = false;
    JNIEnv* env = acquire_env(attached);
    if (env == nullptr || g_sdk_class == nullptr || method == nullptr) {
        release_env(attached);
        return 0;
    }

    const jboolean result = env->CallStaticBooleanMethod(g_sdk_class, method);
    const bool failed = clear_exception(env);
    release_env(attached);
    return !failed && result == JNI_TRUE ? 1 : 0;
}

int32_t call_boolean_two_strings(
    jmethodID method,
    const char* first,
    const char* second
) {
    bool attached = false;
    JNIEnv* env = acquire_env(attached);
    if (env == nullptr || g_sdk_class == nullptr || method == nullptr) {
        release_env(attached);
        return 0;
    }

    jstring first_value = to_java_string(env, first);
    jstring second_value = to_java_string(env, second);
    if (env->ExceptionCheck()) {
        clear_exception(env);
        if (first_value != nullptr) env->DeleteLocalRef(first_value);
        if (second_value != nullptr) env->DeleteLocalRef(second_value);
        release_env(attached);
        return 0;
    }

    const jboolean result = env->CallStaticBooleanMethod(
        g_sdk_class,
        method,
        first_value,
        second_value
    );
    const bool failed = clear_exception(env);

    if (first_value != nullptr) env->DeleteLocalRef(first_value);
    if (second_value != nullptr) env->DeleteLocalRef(second_value);
    release_env(attached);
    return !failed && result == JNI_TRUE ? 1 : 0;
}
} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    if (vm == nullptr) return JNI_ERR;

    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }

    jclass local_class = env->FindClass("com/geode/android/sdk/GeodeAndroidSDK");
    if (local_class == nullptr || env->ExceptionCheck()) {
        clear_exception(env);
        return JNI_ERR;
    }

    auto global_class = static_cast<jclass>(env->NewGlobalRef(local_class));
    env->DeleteLocalRef(local_class);
    if (global_class == nullptr || env->ExceptionCheck()) {
        clear_exception(env);
        return JNI_ERR;
    }

    const jmethodID is_available = env->GetStaticMethodID(
        global_class, "nativeIsAvailable", "()Z"
    );
    const jmethodID record_event = env->GetStaticMethodID(
        global_class, "nativeRecordDiagnosticEvent", "(Ljava/lang/String;Ljava/lang/String;)Z"
    );
    const jmethodID level_started = env->GetStaticMethodID(
        global_class, "nativeOnLevelStarted", "(Ljava/lang/String;Ljava/lang/String;)Z"
    );
    const jmethodID level_ended = env->GetStaticMethodID(
        global_class, "nativeOnLevelEnded", "()Z"
    );

    if (is_available == nullptr || record_event == nullptr ||
        level_started == nullptr || level_ended == nullptr || env->ExceptionCheck()) {
        clear_exception(env);
        env->DeleteGlobalRef(global_class);
        return JNI_ERR;
    }

    g_vm = vm;
    g_sdk_class = global_class;
    g_is_available = is_available;
    g_record_event = record_event;
    g_level_started = level_started;
    g_level_ended = level_ended;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*) {
    if (vm == nullptr || g_sdk_class == nullptr) return;

    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK && env != nullptr) {
        env->DeleteGlobalRef(g_sdk_class);
    }

    g_sdk_class = nullptr;
    g_is_available = nullptr;
    g_record_event = nullptr;
    g_level_started = nullptr;
    g_level_ended = nullptr;
    g_vm = nullptr;
}

extern "C" GEODE_ANDROID_SDK_EXPORT int32_t geode_android_sdk_get_api_version(void) {
    return GEODE_ANDROID_SDK_API_VERSION;
}

extern "C" GEODE_ANDROID_SDK_EXPORT const char* geode_android_sdk_get_capabilities_json(void) {
    return R"(["device.info","launcher.info","diagnostics.events","diagnostics.level-markers","graphics.context-query","graphics.gles-compat-helpers"])";
}

extern "C" GEODE_ANDROID_SDK_EXPORT int32_t geode_android_sdk_is_available(void) {
    return call_boolean_no_args(g_is_available);
}

extern "C" GEODE_ANDROID_SDK_EXPORT int32_t geode_android_sdk_record_diagnostic_event(
    const char* name,
    const char* attributes_json
) {
    if (name == nullptr || name[0] == '\0') return 0;
    return call_boolean_two_strings(g_record_event, name, attributes_json == nullptr ? "{}" : attributes_json);
}

extern "C" GEODE_ANDROID_SDK_EXPORT int32_t geode_android_sdk_level_started(
    const char* level_id,
    const char* level_name
) {
    return call_boolean_two_strings(g_level_started, level_id, level_name);
}

extern "C" GEODE_ANDROID_SDK_EXPORT int32_t geode_android_sdk_level_ended(void) {
    return call_boolean_no_args(g_level_ended);
}

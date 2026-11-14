#include <jni.h>
#include <EGL/egl.h>

#include <array>
#include <cstdint>
#include <limits>

namespace {

using GLenum = unsigned int;
using GLuint = unsigned int;
using GLint = int;
using GLsizei = int;
using GLuint64EXT = std::uint64_t;

constexpr GLenum GL_TIME_ELAPSED_EXT = 0x88BF;
constexpr GLenum GL_QUERY_RESULT_EXT = 0x8866;
constexpr GLenum GL_QUERY_RESULT_AVAILABLE_EXT = 0x8867;
constexpr GLenum GL_GPU_DISJOINT_EXT = 0x8FBB;
constexpr std::size_t QUERY_COUNT = 4;

// Return values from nativePollGpuTimer():
//   >= 0: completed GPU time in nanoseconds
//     -1: no result available yet
//     -2: GPU timer became disjoint; pending samples were discarded
//     -3: timer-query extension is unavailable
using GenQueriesFn = void (*)(GLsizei, GLuint*);
using DeleteQueriesFn = void (*)(GLsizei, const GLuint*);
using BeginQueryFn = void (*)(GLenum, GLuint);
using EndQueryFn = void (*)(GLenum);
using GetQueryObjectivFn = void (*)(GLuint, GLenum, GLint*);
using GetQueryObjectui64vFn = void (*)(GLuint, GLenum, GLuint64EXT*);
using GetIntegervFn = void (*)(GLenum, GLint*);

template <typename T>
T resolve(const char* name) {
    return reinterpret_cast<T>(eglGetProcAddress(name));
}

template <typename T>
T resolveCoreOrExt(const char* extensionName, const char* coreName) {
    auto function = resolve<T>(extensionName);
    return function ? function : resolve<T>(coreName);
}

struct GpuTimerState {
    GenQueriesFn genQueries = nullptr;
    DeleteQueriesFn deleteQueries = nullptr;
    BeginQueryFn beginQuery = nullptr;
    EndQueryFn endQuery = nullptr;
    GetQueryObjectivFn getQueryObjectiv = nullptr;
    GetQueryObjectui64vFn getQueryObjectui64v = nullptr;
    GetIntegervFn getIntegerv = nullptr;

    std::array<GLuint, QUERY_COUNT> queries{};
    std::array<bool, QUERY_COUNT> pending{};
    EGLContext context = EGL_NO_CONTEXT;
    std::size_t writeIndex = 0;
    std::size_t readIndex = 0;
    std::size_t activeIndex = QUERY_COUNT;
    bool initialized = false;
    bool active = false;
};

GpuTimerState g_timer;

void clearPending() {
    g_timer.pending.fill(false);
    g_timer.readIndex = g_timer.writeIndex;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_geode_launcher_DiagnosticsCocos2dxRenderer_nativeInitGpuTimer(
        JNIEnv*, jobject) {
    const EGLContext currentContext = eglGetCurrentContext();
    if (currentContext == EGL_NO_CONTEXT) {
        g_timer.initialized = false;
        return JNI_FALSE;
    }

    // The previous query objects can only be deleted when they belong to the
    // current context. A recreated EGL context has already discarded old objects.
    if (g_timer.initialized && g_timer.context == currentContext &&
        g_timer.deleteQueries != nullptr) {
        g_timer.deleteQueries(
                static_cast<GLsizei>(QUERY_COUNT),
                g_timer.queries.data());
    }

    g_timer = GpuTimerState{};
    g_timer.context = currentContext;

    g_timer.genQueries = resolveCoreOrExt<GenQueriesFn>("glGenQueriesEXT", "glGenQueries");
    g_timer.deleteQueries = resolveCoreOrExt<DeleteQueriesFn>("glDeleteQueriesEXT", "glDeleteQueries");
    g_timer.beginQuery = resolveCoreOrExt<BeginQueryFn>("glBeginQueryEXT", "glBeginQuery");
    g_timer.endQuery = resolveCoreOrExt<EndQueryFn>("glEndQueryEXT", "glEndQuery");
    g_timer.getQueryObjectiv = resolveCoreOrExt<GetQueryObjectivFn>(
            "glGetQueryObjectivEXT", "glGetQueryObjectiv");
    g_timer.getQueryObjectui64v = resolve<GetQueryObjectui64vFn>(
            "glGetQueryObjectui64vEXT");
    g_timer.getIntegerv = resolve<GetIntegervFn>("glGetIntegerv");

    if (g_timer.genQueries == nullptr || g_timer.deleteQueries == nullptr ||
        g_timer.beginQuery == nullptr || g_timer.endQuery == nullptr ||
        g_timer.getQueryObjectiv == nullptr ||
        g_timer.getQueryObjectui64v == nullptr || g_timer.getIntegerv == nullptr) {
        return JNI_FALSE;
    }

    g_timer.genQueries(static_cast<GLsizei>(QUERY_COUNT), g_timer.queries.data());
    for (const GLuint query : g_timer.queries) {
        if (query == 0) {
            g_timer.deleteQueries(
                    static_cast<GLsizei>(QUERY_COUNT),
                    g_timer.queries.data());
            g_timer.queries.fill(0);
            return JNI_FALSE;
        }
    }

    g_timer.initialized = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_geode_launcher_DiagnosticsCocos2dxRenderer_nativeBeginGpuTimer(
        JNIEnv*, jobject) {
    if (!g_timer.initialized || g_timer.active ||
        eglGetCurrentContext() != g_timer.context) {
        return JNI_FALSE;
    }

    const std::size_t index = g_timer.writeIndex;
    if (g_timer.pending[index]) {
        // Never reuse an unfinished query and never wait for the GPU.
        return JNI_FALSE;
    }

    g_timer.beginQuery(GL_TIME_ELAPSED_EXT, g_timer.queries[index]);
    g_timer.activeIndex = index;
    g_timer.active = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_geode_launcher_DiagnosticsCocos2dxRenderer_nativeEndGpuTimer(
        JNIEnv*, jobject) {
    if (!g_timer.initialized || !g_timer.active ||
        eglGetCurrentContext() != g_timer.context) {
        g_timer.active = false;
        g_timer.activeIndex = QUERY_COUNT;
        return;
    }

    g_timer.endQuery(GL_TIME_ELAPSED_EXT);
    g_timer.pending[g_timer.activeIndex] = true;
    g_timer.writeIndex = (g_timer.activeIndex + 1) % QUERY_COUNT;
    g_timer.activeIndex = QUERY_COUNT;
    g_timer.active = false;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_geode_launcher_DiagnosticsCocos2dxRenderer_nativePollGpuTimer(
        JNIEnv*, jobject) {
    if (!g_timer.initialized || eglGetCurrentContext() != g_timer.context) {
        return -3;
    }

    if (!g_timer.pending[g_timer.readIndex]) {
        return -1;
    }

    GLint disjoint = 0;
    g_timer.getIntegerv(GL_GPU_DISJOINT_EXT, &disjoint);
    if (disjoint != 0) {
        clearPending();
        return -2;
    }

    GLint available = 0;
    const GLuint query = g_timer.queries[g_timer.readIndex];
    g_timer.getQueryObjectiv(query, GL_QUERY_RESULT_AVAILABLE_EXT, &available);
    if (available == 0) {
        return -1;
    }

    GLuint64EXT elapsedNs = 0;
    g_timer.getQueryObjectui64v(query, GL_QUERY_RESULT_EXT, &elapsedNs);
    g_timer.pending[g_timer.readIndex] = false;
    g_timer.readIndex = (g_timer.readIndex + 1) % QUERY_COUNT;

    if (elapsedNs > static_cast<GLuint64EXT>(std::numeric_limits<jlong>::max())) {
        return -1;
    }
    return static_cast<jlong>(elapsedNs);
}

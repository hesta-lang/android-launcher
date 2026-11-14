package com.geode.launcher

import androidx.annotation.Keep
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

/**
 * Session recorder owned by the launcher. Frame callbacks only update fixed-size
 * in-memory windows; disk I/O and process sampling happen on a dedicated worker.
 */
@Keep
object LauncherDiagnostics {
    private const val TAG = "LauncherDiagnostics"
    private const val PREFS = "launcher_diagnostics"
    private const val PREF_ENABLED = "automatic_recording"
    private const val SAMPLE_PERIOD_MS = 1_000L
    private const val MAX_SESSIONS = 12
    private const val MAX_SESSION_BYTES = 8L * 1024L * 1024L

    @Volatile private var active = false
    @Volatile private var currentLevelActive = false
    @Volatile private var currentLevelId: String? = null
    @Volatile private var currentLevelName: String? = null
    @Volatile private var currentLevelSegmentIndex = 0L
    @Volatile private var currentLevelStartedElapsedMs = 0L

    private var worker: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    private var sampleTask: Runnable? = null
    private var writer: BufferedWriter? = null
    private var sessionFile: File? = null
    private var appContext: Context? = null
    private var refreshRateHz = 60.0
    private var lastSampleWallMs = 0L
    private var lastSampleCpuMs = 0L
    private var sessionStartElapsedMs = 0L

    private val gameFrames = FrameWindow()
    private val levelFrames = FrameWindow()
    private val totalGameFrames = AtomicLong(0)
    private val totalLevelFrames = AtomicLong(0)

    fun isRecording(): Boolean = active

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_ENABLED, enabled).apply()
    }

    @Synchronized
    fun startSession(context: Context, displayRefreshRateHz: Float) {
        if (active || !isEnabled(context)) return

        val directory = File(context.filesDir, "diagnostics/sessions")
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(TAG, "Could not create diagnostic session directory")
            return
        }

        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val file = File(directory, "launcher_diagnostics_$stamp.jsonl")
        val thread = HandlerThread("LauncherDiagnostics-Writer")
        thread.start()

        appContext = context.applicationContext
        refreshRateHz = displayRefreshRateHz.toDouble().takeIf { it.isFinite() && it > 1.0 } ?: 60.0
        sessionFile = file
        worker = thread
        val localHandler = Handler(thread.looper)
        handler = localHandler
        active = true
        currentLevelActive = false
        currentLevelId = null
        currentLevelName = null
        currentLevelSegmentIndex = 0L
        currentLevelStartedElapsedMs = 0L
        gameFrames.clear()
        levelFrames.clear()
        totalGameFrames.set(0)
        totalLevelFrames.set(0)
        sessionStartElapsedMs = SystemClock.elapsedRealtime()
        lastSampleWallMs = sessionStartElapsedMs
        lastSampleCpuMs = Process.getElapsedCpuTime()

        localHandler.post {
            try {
                writer = BufferedWriter(FileWriter(file, false), 16 * 1024)
                writeLine(deviceHeader(context))
                writeEvent("session_start", JSONObject()
                    .put("refresh_rate_hz", refreshRateHz)
                    .put("recording_format", "jsonl")
                    .put("frame_source", "Cocos2dxRenderer.onDrawFrame"))
                pruneOldSessions(directory)
            } catch (error: Exception) {
                Log.e(TAG, "Could not initialize diagnostic session", error)
                active = false
                try { writer?.close() } catch (_: Exception) {}
                writer = null
            }
        }

        val task = object : Runnable {
            override fun run() {
                if (!active) return
                try {
                    sampleProcess(context)
                    flushFrameWindows()
                    handler?.postDelayed(this, SAMPLE_PERIOD_MS)
                } catch (error: Exception) {
                    Log.e(TAG, "Diagnostic sampling failed", error)
                    handler?.postDelayed(this, SAMPLE_PERIOD_MS)
                }
            }
        }
        sampleTask = task
        localHandler.postDelayed(task, SAMPLE_PERIOD_MS)
    }

    fun recordEvent(name: String, attributes: JSONObject = JSONObject()) {
        if (!active) return
        val eventName = name.take(80)
        handler?.post {
            writeEvent(eventName, attributes)
        }
    }

    fun recordGraphicsContext(
        vendor: String?,
        renderer: String?,
        version: String?,
        shadingLanguageVersion: String?,
        extensions: String?
    ) {
        val data = JSONObject()
            .put("gl_vendor", vendor.orEmpty())
            .put("gl_renderer", renderer.orEmpty())
            .put("gl_version", version.orEmpty())
            .put("glsl_version", shadingLanguageVersion.orEmpty())
            .put("gl_extensions_truncated", extensions.orEmpty().take(4_000))
        recordEvent("gl_context_created", data)
    }

    fun recordGraphicsSurface(width: Int, height: Int) {
        recordEvent("gl_surface_changed", JSONObject()
            .put("width_px", width)
            .put("height_px", height))
    }

    /**
     * Called once for each Cocos renderer callback. This is CPU-side callback
     * timing, not a measurement of GPU execution time or buffer presentation.
     */
    fun recordFrame(intervalNs: Long, callbackWallNs: Long, callbackCpuNs: Long) {
        if (!active) return
        gameFrames.record(intervalNs, callbackWallNs, callbackCpuNs, refreshRateHz)
        totalGameFrames.incrementAndGet()

        if (currentLevelActive) {
            levelFrames.record(intervalNs, callbackWallNs, callbackCpuNs, refreshRateHz)
            totalLevelFrames.incrementAndGet()
        }
    }

    /**
     * JNI bridge for a compatible Geode integration. The launcher remains the
     * recorder; a game-side integration only supplies the level lifecycle marker.
     */
    @Keep
    @JvmStatic
    fun onLevelStarted(levelId: String?, levelName: String?) {
        currentLevelSegmentIndex += 1L
        currentLevelId = levelId?.take(128)
        currentLevelName = levelName?.take(180)
        currentLevelStartedElapsedMs = SystemClock.elapsedRealtime()
        currentLevelActive = true
        recordEvent("level_started", JSONObject()
            .put("level_segment_index", currentLevelSegmentIndex)
            .put("level_id", currentLevelId ?: JSONObject.NULL)
            .put("level_name", currentLevelName ?: JSONObject.NULL))
    }

    @Keep
    @JvmStatic
    fun onLevelEnded() {
        val now = SystemClock.elapsedRealtime()
        currentLevelActive = false
        recordEvent("level_ended", JSONObject()
            .put("level_segment_index", currentLevelSegmentIndex)
            .put("level_id", currentLevelId ?: JSONObject.NULL)
            .put("level_name", currentLevelName ?: JSONObject.NULL)
            .put("level_duration_ms", (now - currentLevelStartedElapsedMs).coerceAtLeast(0L)))
        currentLevelStartedElapsedMs = 0L
        // Keep the last identity until its final partial frame window is written.
    }

    @Synchronized
    fun endSession(reason: String) {
        if (!active) return
        active = false

        val localHandler = handler
        val localWorker = worker
        sampleTask?.let { localHandler?.removeCallbacks(it) }
        sampleTask = null

        if (localHandler == null) {
            try { writer?.close() } catch (_: Exception) {}
            writer = null
            localWorker?.quitSafely()
            worker = null
            return
        }

        localHandler.post {
            try {
                flushFrameWindows()
                writeEvent("session_end", JSONObject()
                    .put("reason", reason.take(80))
                    .put("duration_ms", (SystemClock.elapsedRealtime() - sessionStartElapsedMs).coerceAtLeast(0))
                    .put("game_frame_count_total", totalGameFrames.get())
                    .put("level_frame_count_total", totalLevelFrames.get())
                    .put("level_marker_active", currentLevelActive))
                writer?.flush()
                writer?.close()
            } catch (error: Exception) {
                Log.e(TAG, "Could not close diagnostic session cleanly", error)
            } finally {
                writer = null
                localWorker?.quitSafely()
                synchronized(this) {
                    if (handler === localHandler) handler = null
                    if (worker === localWorker) worker = null
                }
            }
        }
    }

    fun listSessions(context: Context): List<File> {
        val directory = File(context.filesDir, "diagnostics/sessions")
        return directory.listFiles { file ->
            file.isFile && file.name.endsWith(".jsonl")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun deviceHeader(context: Context): JSONObject {
        val metrics = context.resources.displayMetrics
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.getMemoryInfo(memory)

        val packageInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) {
            null
        }

        return JSONObject()
            .put("type", "device_info")
            .put("timestamp_unix_ms", System.currentTimeMillis())
            .put("launcher_version", packageInfo?.versionName ?: "unknown")
            .put("launcher_version_code", if (Build.VERSION.SDK_INT >= 28) packageInfo?.longVersionCode ?: 0L else packageInfo?.versionCode ?: 0)
            .put("package_name", context.packageName)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("product", Build.PRODUCT)
            .put("board", Build.BOARD)
            .put("hardware", Build.HARDWARE)
            .put("android_release", Build.VERSION.RELEASE)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("supported_abis", Build.SUPPORTED_ABIS.joinToString(","))
            .put("os_arch", System.getProperty("os.arch").orEmpty())
            .put("available_processors", Runtime.getRuntime().availableProcessors())
            .put("screen_width_px", metrics.widthPixels)
            .put("screen_height_px", metrics.heightPixels)
            .put("screen_density_dpi", metrics.densityDpi)
            .put("total_ram_mb", memory.totalMem / (1024L * 1024L))
            .put("initial_refresh_rate_hz", refreshRateHz)
    }

    private fun sampleProcess(context: Context) {
        val nowWall = SystemClock.elapsedRealtime()
        val nowCpu = Process.getElapsedCpuTime()
        val wallDelta = (nowWall - lastSampleWallMs).coerceAtLeast(1L)
        val cpuDelta = (nowCpu - lastSampleCpuMs).coerceAtLeast(0L)
        val processCpuPercent = cpuDelta.toDouble() * 100.0 / wallDelta.toDouble()
        lastSampleWallMs = nowWall
        lastSampleCpuMs = nowCpu

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val systemMemory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(systemMemory)

        val processMemory = Debug.MemoryInfo()
        Debug.getMemoryInfo(processMemory)

        val runtime = Runtime.getRuntime()
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            power.currentThermalStatus
        } else {
            -1
        }

        val threadCount = File("/proc/self/task").list()?.size ?: -1
        val payload = JSONObject()
            .put("elapsed_realtime_ms", nowWall)
            .put("process_cpu_percent_of_one_core", processCpuPercent)
            .put("process_cpu_time_ms", nowCpu)
            .put("process_pss_kb", processMemory.totalPss)
            .put("dalvik_pss_kb", processMemory.dalvikPss)
            .put("native_pss_kb", processMemory.nativePss)
            .put("other_pss_kb", processMemory.otherPss)
            .put("native_heap_allocated_kb", Debug.getNativeHeapAllocatedSize() / 1024L)
            .put("native_heap_size_kb", Debug.getNativeHeapSize() / 1024L)
            .put("java_heap_used_kb", (runtime.totalMemory() - runtime.freeMemory()) / 1024L)
            .put("java_heap_max_kb", runtime.maxMemory() / 1024L)
            .put("system_ram_available_kb", systemMemory.availMem / 1024L)
            .put("system_ram_low", systemMemory.lowMemory)
            .put("system_ram_threshold_kb", systemMemory.threshold / 1024L)
            .put("process_thread_count", threadCount)
            .put("thermal_status", thermalStatus)
            .put("thermal_status_label", thermalStatusLabel(thermalStatus))

        writeLine(JSONObject()
            .put("type", "process_sample")
            .put("timestamp_unix_ms", System.currentTimeMillis())
            .put("metrics", payload))
    }

    private fun thermalStatusLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unavailable"
    }

    private fun flushFrameWindows() {
        val all = gameFrames.drain()
        val level = levelFrames.drain()
        if (all.optInt("frame_count", 0) == 0 && level.optInt("frame_count", 0) == 0) return

        val data = JSONObject()
            .put("scope", "Cocos2dxRenderer.onDrawFrame")
            .put("refresh_rate_hz", refreshRateHz)
            .put("game_renderer", all)
            .put("level_renderer", level)
            .put("level_marker_active_at_flush", currentLevelActive)
            .put("level_segment_index", currentLevelSegmentIndex)
            .put("level_id", currentLevelId ?: JSONObject.NULL)
            .put("level_name", currentLevelName ?: JSONObject.NULL)

        writeLine(JSONObject()
            .put("type", "render_window")
            .put("timestamp_unix_ms", System.currentTimeMillis())
            .put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
            .put("metrics", data))
    }

    private fun writeEvent(name: String, attributes: JSONObject) {
        writeLine(JSONObject()
            .put("type", name)
            .put("timestamp_unix_ms", System.currentTimeMillis())
            .put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
            .put("attributes", attributes))
    }

    private fun writeLine(json: JSONObject) {
        try {
            val output = writer ?: return
            output.write(json.toString())
            output.newLine()
            output.flush()
        } catch (error: Exception) {
            Log.e(TAG, "Failed writing diagnostic record", error)
        }
    }

    private fun pruneOldSessions(directory: File) {
        val files = directory.listFiles { file ->
            file.isFile && file.name.endsWith(".jsonl")
        }?.sortedByDescending { it.lastModified() } ?: return

        var totalBytes = 0L
        files.forEachIndexed { index, file ->
            totalBytes += file.length()
            if (index >= MAX_SESSIONS || totalBytes > MAX_SESSION_BYTES) {
                if (file != sessionFile) file.delete()
            }
        }
    }

    private class FrameWindow {
        companion object {
            private const val CAPACITY = 600
        }

        private val intervals = LongArray(CAPACITY)
        private val callbackWall = LongArray(CAPACITY)
        private val callbackCpu = LongArray(CAPACITY)
        private var cursor = 0
        private var stored = 0
        private var frames = 0L
        private var overBudget = 0L
        private var overflowed = 0L

        @Synchronized
        fun record(intervalNs: Long, callbackWallNs: Long, callbackCpuNs: Long, refreshRate: Double) {
            if (stored == CAPACITY) overflowed++
            intervals[cursor] = intervalNs.coerceAtLeast(0L)
            callbackWall[cursor] = callbackWallNs.coerceAtLeast(0L)
            callbackCpu[cursor] = callbackCpuNs.coerceAtLeast(0L)
            cursor = (cursor + 1) % CAPACITY
            if (stored < CAPACITY) stored++
            frames++

            val targetPeriodNs = 1_000_000_000.0 / refreshRate
            if (intervalNs > targetPeriodNs * 1.5) overBudget++
        }

        @Synchronized
        fun clear() {
            cursor = 0
            stored = 0
            frames = 0
            overBudget = 0
            overflowed = 0
        }

        @Synchronized
        fun drain(): JSONObject {
            val intervalValues = intervals.copyOf(stored).filter { it > 0L }.toLongArray()
            val wallValues = callbackWall.copyOf(stored).filter { it > 0L }.toLongArray()
            val cpuValues = callbackCpu.copyOf(stored).filter { it > 0L }.toLongArray()

            val result = JSONObject()
                .put("frame_count", frames)
                .put("stored_samples", stored)
                .put("samples_over_capacity", overflowed)
                .put("intervals_over_1_5x_refresh_budget", overBudget)
                .put("frame_interval_ms", stats(intervalValues))
                .put("renderer_callback_wall_ms", stats(wallValues))
                .put("renderer_callback_thread_cpu_ms", stats(cpuValues))

            clear()
            return result
        }

        private fun stats(values: LongArray): JSONObject {
            if (values.isEmpty()) return JSONObject()
                .put("sample_count", 0)
                .put("avg", JSONObject.NULL)
                .put("p50", JSONObject.NULL)
                .put("p95", JSONObject.NULL)
                .put("max", JSONObject.NULL)

            val sorted = values.sortedArray()
            val p50Index = (ceil(sorted.size * 0.50).toInt() - 1).coerceIn(0, sorted.lastIndex)
            val p95Index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
            return JSONObject()
                .put("sample_count", sorted.size)
                .put("avg", values.average() / 1_000_000.0)
                .put("p50", sorted[p50Index] / 1_000_000.0)
                .put("p95", sorted[p95Index] / 1_000_000.0)
                .put("max", sorted.last() / 1_000_000.0)
        }
    }
}

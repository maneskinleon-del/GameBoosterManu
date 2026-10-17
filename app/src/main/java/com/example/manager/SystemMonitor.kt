package com.example.manager

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.data.repository.SystemMetrics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import android.os.SystemClock
import java.util.concurrent.TimeUnit
import com.example.BuildConfig

/** Parses a raw `temp` reading from /sys/class/thermal directories (thermal_zone + index) to Celsius.
 * Returns `null` for non-numeric, empty or implausible values (≤0 or >150°C):
 * prevents a false 0 from triggering profile restoration in the <=40°C Watchdog branch.
 * Pure function (no sysfs I/O) for JVM testability.
 */
internal fun parseThermal(raw: String): Float? {
    val temp = raw.trim().toFloatOrNull() ?: return null
    val celsius = if (temp > 1000) temp / 1000 else temp
    return if (celsius <= 0f || celsius > 150f) null else celsius
}

/** Ruta única de zonas térmicas — SystemMonitor y ThermalController la comparten. */
internal val SYSFS_THERMAL_DIR: File = File("/sys/class/thermal")

/** Whether the `type` of a thermal_zone is reasonably attributable to CPU.
 * Conservative prefix criterion: `cpu*` (covers `cpu-0-0`, `cpu0`, `cpuss-*`).
 * Does NOT attribute to CPU: `aoss-*` (SoC governor), `gpuss-*` (GPU), battery, charger
 * nor unknown — presenting any subsystem's max as "CPU" would be the misleading
 * attribution that Device Watch does (its zones are not copied).
 * Decision by `type`, NEVER by zone index (thermal_zone0 is not CPU: on real SoCs
 * it's often the PCB/package complete sensor).
 * NOTE per-chip: the `cpu*` prefix covers QUALCOMM `type`s. On MEDIATEK the CPU zone
 * types are typically `mtktscpu-*` (does not match the prefix) — the reader reports
 * Float.NaN → "N/D": conservative failure, never false data.
 */
internal fun isCpuThermalType(type: String): Boolean {
    return type.trim().lowercase().startsWith("cpu")
}

/** CPU thermal selection: discards non-CPU zones and invalid readings
 * (validation REUTILIZED from [parseThermal], not duplicated) and returns the
 * MAXIMUM of valid CPU readings, in Celsius.
 *
 * `Float.NaN` when no CPU zone with valid reading exists — never 0 (the Watchdog
 * would treat 0 as real temperature; NaN is the project's unavailable data).
 * Pure: I/O lives in [readCpuZoneSamples].
 */
internal fun maxCpuZoneTemp(samples: List<Pair<String, String>>): Float {
    var max = Float.NaN
    for ((type, raw) in samples) {
        if (!isCpuThermalType(type)) continue
        val celsius = parseThermal(raw) ?: continue
        if (max.isNaN() || celsius > max) max = celsius
    }
    return max
}

/** I/O enumeration of the `thermal_zoneN` tree: reads `type` and `temp` of each zone,
 * tolerating denied permissions, missing files, empty content and read errors
 * (each failure discards only that zone, without propagating exception or inventing 0).
 * Returns raw pairs (type, temp), unparsed.
 *
 * @param dir injectable for JVM tests (production: [SYSFS_THERMAL_DIR]).
 */
internal fun readCpuZoneSamples(dir: File): List<Pair<String, String>> =
    enumerateThermalZones(dir).map { it.type to it.temp }

/** (type, temp) of a thermal zone with its `temp` path, to re-read temperature
 * in subsequent ticks without re-enumerating (I/O saving: on ZTE about 70 zones
 * failed per tick due to SELinux). */
internal data class ThermalZoneSample(val zone: File, val type: String, val temp: String)

/** Enumeration I/O of the `thermal_zoneN` tree: reads `type` and `temp` of each zone,
 * tolerating denied permissions, missing files, empty content and read errors
 * (each failure discards only that zone, without propagating exception or inventing 0).
 * Returns the raw list of samples, unparsed.
 *
 * @param dir injectable for JVM tests (production: [SYSFS_THERMAL_DIR]).
 */
internal fun enumerateThermalZones(dir: File): List<ThermalZoneSample> {
    val zones = dir.listFiles()
        ?.filter { it.isDirectory && it.name.startsWith("thermal_zone") }
        ?: return emptyList()
    val samples = mutableListOf<ThermalZoneSample>()
    for (zone in zones) {
        val type = try { File(zone, "type").readText().trim() } catch (_: Exception) { continue }
        if (type.isEmpty()) continue
        val temp = try { File(zone, "temp").readText().trim() } catch (_: Exception) { continue }
        samples.add(ThermalZoneSample(zone, type, temp))
    }
    return samples
}

/** Cache of the thermal reader per instance: zones are enumerated ONCE (the
 * first call to [samples]); subsequent ticks do NOT re-enumerate — they only
 * re-read the `temp` of cached CPU zones (the `type` does not change in hot
 * kernel). Saving: on a SoC with ~70 zones, enumeration is ~140 sysfs openings
 * per tick, and where SELinux denies all (e.g. ZTE) are ~70 failures per cycle
 * for nothing.
 *
 * Two latches, with different semantics:
 *
 * - **Tick 1 (never had anything readable):** if the initial enumeration does
 *   NOT produce ANY CPU-valid zone (SELinux denies the tree from boot, etc.)
 *   it is abandoned forever: the app uid does not gain sysfs permission mid-session
 *   (same pattern as the Device Watch latch).
 *
 * - **Subsequent loss (had CPU-readable zones and they stop being read):** requires
 *   [LATCH_AFTER_CONSECUTIVE_FAILURES] (= 5) consecutive ticks without ANY valid
 *   CPU reading before latching. A POINTE failure (deep suspend that freezes sysfs
 *   for an instant, a tick that returns empty/0) does NOT kill the thermal monitor
 *   of a long-lived service: on the next tick it reads again and the failure counter
 *   resets. Only if the tree remains unreadable tick after tick (permission revoked,
 *   hardware powered off) is it reasonable to stop paying the retry cost.
 *
 * Tests create fresh instances; only production shares the singleton [THERMAL_TICK_CACHE].
 *
 * THREAD-SAFETY: access to ALL mutable state ([cachedCpuZones], [abandoned],
 * [consecutiveReadFailures]) occurs exclusively within the `synchronized(lock)` that
 * wraps all [samples] — consumers (SystemMonitor loop on Dispatchers.IO and
 * ThermalController.getCurrentTemp) share the singleton without additional serialization.
 * No `@Volatile` fields: the lock is the guarantee, not volatile visibility.
 */
internal class CpuThermalTickCache(private val dir: File) {
    private val lock = Any()
    private var cachedCpuZones: List<ThermalZoneSample>? = null
    private var abandoned = false
    private var consecutiveReadFailures = 0

    private companion object {
        /** Consecutive read failures before latch. */
        const val LATCH_AFTER_CONSECUTIVE_FAILURES = 5
    }

    /** Raw samples of the tick: on the first call returns ALL readable zones
     * (the [maxCpuZoneTemp] selector filters by type, prior contract of
     * [readCpuZoneSamples]); on subsequent calls only the cached CPU zones.
     * NEVER invents 0: empty list when nothing is readable.
     */
    fun samples(): List<Pair<String, String>> = synchronized(lock) {
        if (abandoned) return emptyList()
        val cached = cachedCpuZones
        if (cached == null) {
            val full = enumerateThermalZones(dir)
            val cpu = full.filter { isCpuThermalType(it.type) && parseThermal(it.temp) != null }
            cachedCpuZones = cpu
            if (cpu.isEmpty()) abandoned = true // no CPU readable: tick 2 does not retry
            full.map { it.type to it.temp }
        } else {
            val samples = cached.mapNotNull { z ->
                val temp = try { File(z.zone, "temp").readText().trim() } catch (_: Exception) { null }
                if (temp.isNullOrEmpty()) null else z.type to temp
            }
            if (maxCpuZoneTemp(samples).isNaN()) {
                // Reading loss: latch only after N consecutive failures —
                // a single failure does not kill the service thermal monitor.
                consecutiveReadFailures++
                if (consecutiveReadFailures >= LATCH_AFTER_CONSECUTIVE_FAILURES) {
                    abandoned = true
                }
                emptyList()
            } else {
                consecutiveReadFailures = 0
                samples
            }
        }
    }
}

/** Shared SystemMonitor + ThermalController cache over the real system path
 * ([SYSFS_THERMAL_DIR]) — one live enumeration per process.
 */
internal val THERMAL_TICK_CACHE = CpuThermalTickCache(SYSFS_THERMAL_DIR)

/** System monitoring of real-time system metrics.
 *
 * Collects CPU, RAM, battery, ping, pointer speed, DPI, etc.
 * every [MONITOR_INTERVAL_MS] milliseconds and exposes via StateFlow.
 *
 * Usage:
 *   val monitor = SystemMonitor(context)
 *   monitor.start()
 *   monitor.systemMetrics.collect { /* actualizar UI */ }
 *   monitor.stop()
 */
class SystemMonitor(private val context: Context) {

    companion object {
        private const val TAG = "SystemMonitor"
        private const val MONITOR_INTERVAL_MS = 2000L
        private const val EXTERNAL_DEVICE_INTERVAL_MS = 5000L
        /** Hard timeout on ping (ms); -w 1 of the binary is not reliable on all OEMs. */
        private const val PING_TIMEOUT_MS = 2_500L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── System metrics ──────────────────────────────────────
    private val _systemMetrics = MutableStateFlow(
        SystemMetrics(
            cpuUsage = 0, ramUsed = 0L, ramTotal = 0L,
            batteryLevel = 0, batteryTemp = 0f,
            // NaN (no 0f): StateFlow initial with 0f presented an invalid
            // valid value during the first ~2s (Watchdog <=40°C branch only
            // acts if there was a prior thermal episode, but the overlay/dashboard
            // would have shown "0°C"). This is the ONLY place in the project
            // that built SystemMetrics with a fake temp.
            cpuTemp = Float.NaN,
            gpuUsage = 0, ping = 0, dpi = 0,
            pointerSpeed = "0%", animationScale = "1x", refreshRate = "60 Hz",
            governor = "Schedutil", touchSampling = "N/D",
            activeGame = null, optimizerStatus = "Idle",
            fsmState = com.example.data.repository.FsmState.READY
        )
    )
    val systemMetrics: StateFlow<SystemMetrics> = _systemMetrics.asStateFlow()

    // ─── Pointer Speed ─────────────────────────────────────────
    private val _pointerSpeed = MutableStateFlow(0)
    val pointerSpeed: StateFlow<Int> = _pointerSpeed.asStateFlow()

    // ─── External Devices ──────────────────────────────────────
    private val _externalDevicesConnected = MutableStateFlow(false)
    val externalDevicesConnected: StateFlow<Boolean> = _externalDevicesConnected.asStateFlow()

    // Callback for external devices (used by GameSessionManager)
    var onExternalDeviceConnectedWhileGaming: ((Boolean) -> Unit)? = null

    // CPU monitoring via /proc/stat with delta
    private var lastCpuIdleTicks = 0L
    private var lastCpuTotalTicks = 0L
    private var lastCpuReadTime = 0L

    // State flag
    private var isRunning = false

    // Last measured ping (to avoid showing 0 before the first measurement)
    private var lastMeasuredPing = 0

    /** Last external detection reason; only logged when it changes (avoid 5s log spam). */
    @Volatile
    private var lastExternalReason: String? = null

    // To update external metrics (boost state, active game, etc.)
    var getBoostActive: (() -> Boolean)? = null
    var getActiveGame: (() -> String?)? = null
    var getFsmState: (() -> com.example.data.repository.FsmState)? = null
    var getActiveProfile: (() -> com.example.data.database.ProfileEntity?)? = null

    fun start() {
        if (isRunning) return
        isRunning = true

        // Real-time metrics loop (every 2s)
        scope.launch {
            while (isActive) {
                try {
                    collectMetrics()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
                delay(MONITOR_INTERVAL_MS)
            }
        }

        // External device detection loop (every 5s)
        scope.launch {
            while (isActive) {
                try {
                    val wasConnected = _externalDevicesConnected.value
                    val isConnected = checkExternalDevices()
                    _externalDevicesConnected.value = isConnected

                    if (isConnected && !wasConnected) {
                        onExternalDeviceConnectedWhileGaming?.invoke(isConnected)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
                delay(EXTERNAL_DEVICE_INTERVAL_MS)
            }
        }

        Log.d(TAG, "✅ Monitor iniciado")
    }

    private suspend fun collectMetrics() {
        val realRam = getRealRamInfo()
        val realBattery = getRealBatteryInfo()
        val realCpuTemp = getRealCpuTemp()
        val rawPointerSpeed = getRawPointerSpeedSuspend()
        val mappedPointerSpeed = mapRawSpeedToPercent(rawPointerSpeed)
        val realPing = getRealPing()
        val realCpuUsage = getRealCpuUsage()
        val estimatedGpuUsage = (realCpuUsage * 0.8).toInt().coerceIn(0, 100)

        val currentDpi = context.resources.configuration.densityDpi
        val isBoosted = getBoostActive?.invoke() ?: false
        val activeGame = getActiveGame?.invoke()
        val fsmState = getFsmState?.invoke() ?: com.example.data.repository.FsmState.READY
        val activeProfile = getActiveProfile?.invoke()

        _systemMetrics.value = SystemMetrics(
            cpuUsage = realCpuUsage,
            ramUsed = realRam.first,
            ramTotal = realRam.second,
            batteryLevel = realBattery.first,
            batteryTemp = realBattery.second,
            cpuTemp = realCpuTemp,
            gpuUsage = estimatedGpuUsage,
            ping = realPing,
            dpi = currentDpi,
            pointerSpeed = "$mappedPointerSpeed%",
            animationScale = if (isBoosted) "0x" else "1x",
            refreshRate = activeProfile?.refreshRate ?: "60 Hz",
            governor = activeProfile?.governor ?: "Schedutil",
            // F5-adj: no inventar Hz de touch (sampling real es OEM/sysfs)
            touchSampling = "N/D",
            activeGame = activeGame,
            optimizerStatus = if (isBoosted) "Boosted" else "Idle",
            fsmState = fsmState
        )

        _pointerSpeed.value = mappedPointerSpeed

        // DEBUG-only CSV logging: one line per tick, emitted via Log.i("ThermalSession", ...).
        // BuildConfig.DEBUG es constante en compilación: R8 elimina el bloque en release.
        if (BuildConfig.DEBUG) {
            val tsMs = SystemClock.elapsedRealtime()
            val bLevel = realBattery.first
            val bTemp = realBattery.second
            val cMax = if (realCpuTemp.isNaN()) "NaN" else String.format("%.1f", realCpuTemp)
            val perfil = activeProfile?.name ?: "null"
            // Build cpu-zone pairs from the thermal cache
            val cpuZones = THERMAL_TICK_CACHE.samples()
            val cpuParts = cpuZones.map { "${it.first}=${if (it.second.toFloatOrNull()?.isNaN() == true) "NaN" else it.second}" }
            val csvLine = "$tsMs,$bLevel,$bTemp,$cMax,$perfil,${cpuParts.joinToString(",")}"
            Log.i("ThermalSession", csvLine)
        }
    }

    // ─── Lecturas de métricas (sync) ──────────────────────────────

    private fun getRealRamInfo(): Pair<Long, Long> {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val totalRam = memoryInfo.totalMem / (1024 * 1024)
            val availRam = memoryInfo.availMem / (1024 * 1024)
            Pair(totalRam - availRam, totalRam)
        } catch (_: Exception) {
            Pair(2048L, 4096L)
        }
    }

    private fun getRealBatteryInfo(): Pair<Int, Float> {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else 80
            val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            Pair(pct, temp)
        } catch (_: Exception) {
            Pair(80, 36.5f)
        }
    }

    /** F5: no lectura real → NaN (no fingir 38°C). Watchdog no actúa sobre NaN.
     * Lector térmico ÚNICO: [THERMAL_TICK_CACHE] (tick 1: enumeración por `type`;
     * siguientes: solo `temp` de las zonas CPU cacheadas, con latch si nada es legible).
     * Ya no es un by-pass por índice (thermal_zone0 puede no ser CPU — ver commit acdff69).
     */
    private fun getRealCpuTemp(): Float =
        maxCpuZoneTemp(THERMAL_TICK_CACHE.samples())

    private fun getRealCpuUsage(): Int {
        return try {
            val statLines = File("/proc/stat").readLines()
            val cpuLine = statLines.firstOrNull { it.startsWith("cpu ") } ?: return 0
            val parts = cpuLine.split("\\s+".toRegex()).drop(1)
            if (parts.size < 4) return 0

            val user = parts[0].toLong()
            val nice = parts[1].toLong()
            val system = parts[2].toLong()
            val idle = parts[3].toLong()
            val total = user + nice + system + idle

            val prevTotal = lastCpuTotalTicks
            val prevIdle = lastCpuIdleTicks

            lastCpuTotalTicks = total
            lastCpuIdleTicks = idle
            lastCpuReadTime = System.currentTimeMillis()

            if (prevTotal == 0L) return 0

            val totalDelta = total - prevTotal
            val idleDelta = idle - prevIdle

            if (totalDelta <= 0) return 0

            ((totalDelta - idleDelta) * 100 / totalDelta).toInt().coerceIn(0, 100)
        } catch (_: Exception) {
            0
        }
    }

    /** Ping with hard timeout (F2): do not trust only `-w 1` of the binary.
     * Real RTT read from the binary's stdout (rtt min/avg/max/mdev → avg);
     * the process wall-clock is only a fallback if stdout is not parseable
     * (ping 2026-09-20 audit: the wall measured ~80 ms JVM spawn/exec overhead —
     * evidence PINGDIAG, wall 100–104 vs rtt 9–28, n=110 — and the overlay showed
     * ~5× the real latency). destroyForcibly if it hangs.
     */
    private suspend fun getRealPing(): Int {
        return withContext(Dispatchers.IO) {
            var process: Process? = null
            try {
                process = Runtime.getRuntime().exec(arrayOf("ping", "-c", "1", "-w", "1", "8.8.8.8"))
                val start = System.currentTimeMillis()
                val finished = withTimeoutOrNull(PING_TIMEOUT_MS) {
                    try {
                        process.waitFor(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    } catch (_: Exception) {
                        false
                    }
                }
                if (finished != true) {
                    try {
                        process.destroyForcibly()
                    } catch (_: Exception) {}
                    Log.w(TAG, "ping timeout/hang — using last value ($lastMeasuredPing)")
                    return@withContext lastMeasuredPing
                }
                if (process.exitValue() == 0) {
                    val wallMs = (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
                    // Fix (ping 2026-09-20): the real RTT lives in the binary's stdout,
                    // not in the wall-clock. Fallback: wall (previous behavior)
                    // if stdout is not parseable.
                    val stdout = try {
                        process.inputStream.bufferedReader().use { it.readText() }
                    } catch (_: Exception) { "" }
                    val rttAvgMs = Regex("""(\d+\.\d+)/(\d+\.\d+)/(\d+\.\d+)""").find(stdout)
                        ?.groupValues?.get(2)?.toFloatOrNull()?.toInt()
                    val ping = rttAvgMs ?: wallMs
                    Log.d(TAG, "ping=${ping}ms (rtt=${rttAvgMs ?: "n/d"}, wall=$wallMs)")
                    lastMeasuredPing = ping
                    ping
                } else {
                    lastMeasuredPing
                }
            } catch (e: Exception) {
                try {
                    process?.destroyForcibly()
                } catch (_: Exception) {}
                lastMeasuredPing
            }
        }
    }

    private suspend fun getRawPointerSpeedSuspend(): Int {
        return withContext(Dispatchers.IO) {
            try {
                val result = ShizukuExecutor.runCommand("settings get system pointer_speed")
                result.getOrNull()?.trim()?.toIntOrNull() ?: 0
            } catch (_: Exception) {
                0
            }
        }
    }

    private fun mapRawSpeedToPercent(raw: Int): Int {
        return (((raw + 7).toFloat() / 14f) * 100).toInt().coerceIn(0, 100)
    }

    // ─── External device detection ───────────────────────────────

    /** Logs the external detection reason only when it changes,
     * avoiding logcat spam every 5s.
     */
    private fun logExternalReason(reason: String) {
        if (lastExternalReason != reason) {
            Log.d(TAG, "external detected: reason=$reason")
            lastExternalReason = reason
        }
    }

    private suspend fun checkExternalDevices(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val command = "dumpsys input | grep -i -E 'scrcpy|external: true|ggmouse|flydigi|gamesir|mantis|panda|gamewolf'"
                val result = ShizukuExecutor.runCommand(command)
                val output = result.getOrNull()?.lowercase() ?: ""

                /** Trade-off: the dumpsys input grep searches device names and flags.
                 * scrcpy appears with the name 'scrcpy' in device names (NOT with
                 * IsExternal: true). GG Mouse appears as 'external: true'.
                 * As a fallback, ps -A detects mappers that do not report as HIDs
                 * (ztezscreenshot, vphone, etc.).
                 * The double layer avoids false negatives: if a mapper does not appear
                 * in dumpsys (scrcpy without --mouse=uhid), pgrep -f covers it;
                 * if there is no process in ps -A, the first grep covers it.
                 */
                if (output.contains("external: true")) {
                    logExternalReason("dumpsys:external_true")
                    return@withContext true
                }
                if (output.contains("ggmouse")) {
                    logExternalReason("dumpsys:ggmouse")
                    return@withContext true
                }
                if (output.contains("scrcpy")) {
                    logExternalReason("dumpsys:scrcpy")
                    return@withContext true
                }
                if (output.contains("flydigi")) {
                    logExternalReason("dumpsys:flydigi")
                    return@withContext true
                }
                if (output.contains("gamesir")) {
                    logExternalReason("dumpsys:gamesir")
                    return@withContext true
                }
                if (output.contains("mantis")) {
                    logExternalReason("dumpsys:mantis")
                    return@withContext true
                }
                if (output.contains("panda")) {
                    logExternalReason("dumpsys:panda")
                    return@withContext true
                }
                if (output.contains("gamewolf")) {
                    logExternalReason("dumpsys:gamewolf")
                    return@withContext true
                }

                // scrcpy process: pgrep -f matches the full cmdline (ps -A only shows
                // the argv[0] = app_process, never "scrcpy" nor "server.jar"). The bracket
                // in "Serve[r]" avoids self-match of the sh -c that runs the command.
                val scrcpyPids = ShizukuExecutor
                    .runCommand("pgrep -f 'com.genymobile.scrcpy.Serve[r]'")
                    .getOrNull().orEmpty()
                if (scrcpyPids.lines().any { it.trim().matches(Regex("\\d+")) }) {
                    logExternalReason("pgrep:scrcpy")
                    return@withContext true
                }

                // Active process detection (mappers that do appear in ps -A)
                val psCheck = ShizukuExecutor.runCommand("ps -A")
                val psOutput = psCheck.getOrNull()?.lowercase() ?: ""

                val psMatched = listOf(
                    "gg.mouse", "ztezscreenshot", "vphone",
                    "flydigi", "gamesir", "mantis", "panda", "gamewolf"
                ).firstOrNull { psOutput.contains(it) }
                if (psMatched != null) {
                    logExternalReason("ps:$psMatched")
                    return@withContext true
                }

                // Accessibility services check
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )?.lowercase() ?: ""

                val a11yMatched = listOf("gg.mouse", "flydigi", "mantis", "panda")
                    .firstOrNull { enabledServices.contains(it) }
                if (a11yMatched != null) {
                    logExternalReason("a11y:$a11yMatched")
                    return@withContext true
                }

                logExternalReason("none")
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "checkExternalDevices error: ${e.message}")
                false
            }
        }
    }

    // ─── On-demand external device verification ──────────

    /** Verifies external devices NOW, without waiting the 5s cycle.
     * Useful when a game is detected and we need to know RIGHT AWAY if there is a
     * mouse/keyboard.
     */
    suspend fun checkExternalDevicesNow(): Boolean {
        val result = checkExternalDevices()
        _externalDevicesConnected.value = result
        return result
    }

    // ─── Conversion utilities ────────────────────────────────────

    fun mapPercentToRawSpeed(percent: Int): Int {
        return ((percent / 100f) * 14 - 7).toInt().coerceIn(-7, 7)
    }

    // ─── Shutdown ────────────────────────────────────────────────

    fun stop() {
        isRunning = false
        scope.cancel()
        Log.d(TAG, "Monitor detenido")
    }
}
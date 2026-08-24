package com.example.manager

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.example.data.repository.SystemMetrics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Monitoreo de métricas del sistema en tiempo real.
 * 
 * Recolecta CPU, RAM, batería, ping, pointer speed, DPI, etc.
 * cada [MONITOR_INTERVAL_MS] milisegundos y los expone via StateFlow.
 * 
 * Uso:
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
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── Métricas del sistema ──────────────────────────────────────
    private val _systemMetrics = MutableStateFlow(
        SystemMetrics(
            cpuUsage = 0, ramUsed = 0L, ramTotal = 0L,
            batteryLevel = 0, batteryTemp = 0f, cpuTemp = 0f,
            gpuUsage = 0, ping = 0, dpi = 0,
            pointerSpeed = "0%", animationScale = "1x", refreshRate = "60 Hz",
            governor = "Schedutil", touchSampling = "120 Hz",
            activeGame = null, optimizerStatus = "Idle",
            fsmState = com.example.data.repository.FsmState.READY
        )
    )
    val systemMetrics: StateFlow<SystemMetrics> = _systemMetrics.asStateFlow()

    // ─── Pointer Speed ─────────────────────────────────────────────
    private val _pointerSpeed = MutableStateFlow(0)
    val pointerSpeed: StateFlow<Int> = _pointerSpeed.asStateFlow()

    // ─── External Devices ──────────────────────────────────────────
    private val _externalDevicesConnected = MutableStateFlow(false)
    val externalDevicesConnected: StateFlow<Boolean> = _externalDevicesConnected.asStateFlow()

    // Callback para external devices (lo usa GameSessionManager)
    var onExternalDeviceConnectedWhileGaming: ((Boolean) -> Unit)? = null

    // CPU monitoring via /proc/stat with delta
    private var lastCpuIdleTicks = 0L
    private var lastCpuTotalTicks = 0L
    private var lastCpuReadTime = 0L

    // Flag de estado
    private var isRunning = false

    // Último ping medido (para no mostrar 0 antes de la primera medición)
    private var lastMeasuredPing = 0

    // Para actualizar métricas externas (boost state, active game, etc.)
    var getBoostActive: (() -> Boolean)? = null
    var getActiveGame: (() -> String?)? = null
    var getFsmState: (() -> com.example.data.repository.FsmState)? = null
    var getActiveProfile: (() -> com.example.data.database.ProfileEntity?)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        if (isRunning) return
        isRunning = true

        // Loop de métricas en tiempo real (cada 2s)
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

        // Loop de detección de dispositivos externos (cada 5s)
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
            touchSampling = if (isBoosted) "360 Hz" else "120 Hz",
            activeGame = activeGame,
            optimizerStatus = if (isBoosted) "Boosted" else "Idle",
            fsmState = fsmState
        )

        _pointerSpeed.value = mappedPointerSpeed
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

    private fun getRealCpuTemp(): Float {
        val thermalFiles = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp"
        )
        for (file in thermalFiles) {
            try {
                val temp = File(file).readText().trim().toFloat()
                return if (temp > 1000) temp / 1000 else temp
            } catch (_: Exception) {}
        }
        return 38.0f
    }

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

    private suspend fun getRealPing(): Int {
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec("ping -c 1 -w 1 8.8.8.8")
                val start = System.currentTimeMillis()
                val exitValue = process.waitFor()
                if (exitValue == 0) {
                    val ping = (System.currentTimeMillis() - start).toInt()
                    lastMeasuredPing = ping
                    ping
                } else {
                    lastMeasuredPing
                }
            } catch (_: Exception) {
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

    // ─── Detección de dispositivos externos ────────────────────────

    private suspend fun checkExternalDevices(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val command = "dumpsys input | grep -i -E 'keyboard|mouse|ggmouse|external' | head -n 50"
                val result = ShizukuExecutor.runCommand(command)
                val output = result.getOrNull()?.lowercase() ?: ""

            val hasInputDevice = output.contains("external: true") ||
                    output.contains("ggmouse") ||
                    output.contains("flydigi") ||
                    output.contains("gamesir") ||
                    output.contains("mantis") ||
                    output.contains("panda") ||
                    output.contains("gamewolf")

                if (hasInputDevice) return@withContext true

                // Detección por procesos activos
                val psCheck = ShizukuExecutor.runCommand("ps -A")
                val psOutput = psCheck.getOrNull()?.lowercase() ?: ""

            val hasProcess = psOutput.contains("gg.mouse") ||
                    psOutput.contains("vphone") ||
                    psOutput.contains("flydigi") ||
                    psOutput.contains("gamesir") ||
                    psOutput.contains("mantis") ||
                    psOutput.contains("panda") ||
                    psOutput.contains("gamewolf") ||
                    psOutput.contains("hud") ||
                    (psOutput.contains("app_process") && psOutput.contains("server.jar"))

                if (hasProcess) return@withContext true

                // Check de servicios de accesibilidad
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )?.lowercase() ?: ""

            enabledServices.contains("gg.mouse") ||
                    enabledServices.contains("flydigi") ||
                    enabledServices.contains("mantis") ||
                    enabledServices.contains("panda")
            } catch (_: Exception) {
                false
            }
        }
    }

    // ─── Verificación on-demand de dispositivos externos ────────

    /**
     * Verifica dispositivos externos AHORA, sin esperar el ciclo de 5s.
     * Útil cuando se detecta un juego y necesitamos saber YA si hay mouse/teclado.
     */
    suspend fun checkExternalDevicesNow(): Boolean {
        val result = checkExternalDevices()
        _externalDevicesConnected.value = result
        return result
    }

    // ─── Utilidades de conversión ─────────────────────────────────

    fun mapPercentToRawSpeed(percent: Int): Int {
        return ((percent / 100f) * 14 - 7).toInt().coerceIn(-7, 7)
    }

    // ─── Shutdown ─────────────────────────────────────────────────

    fun stop() {
        isRunning = false
        scope.cancel()
        Log.d(TAG, "Monitor detenido")
    }
}

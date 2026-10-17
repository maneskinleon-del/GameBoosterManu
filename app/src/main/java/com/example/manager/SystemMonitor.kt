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
import java.util.concurrent.TimeUnit

/**
 * Parsea una lectura cruda de los archivos `temp` de /sys/class/thermal
 * (directorio `thermal_zone` + índice) a Celsius.
 * Devuelve `null` para no-numérico, vacío o valores implausibles (≤0 o >150°C):
 * evita que un 0 falso dispare la restauración de perfil de la rama `<= 40°C`
 * del Watchdog. Función pura (sin I/O sysfs) para que sea testeable en JVM puro.
 */
internal fun parseThermal(raw: String): Float? {
    val temp = raw.trim().toFloatOrNull() ?: return null
    val celsius = if (temp > 1000) temp / 1000 else temp
    return if (celsius <= 0f || celsius > 150f) null else celsius
}

/** Ruta única de zonas térmicas — SystemMonitor y ThermalController la comparten. */
internal val SYSFS_THERMAL_DIR: File = File("/sys/class/thermal")

/**
 * ¿El `type` de una thermal_zone es atribuible razonablemente a CPU?
 *
 * Criterio conservador por prefijo: `cpu*` (cubre `cpu-0-0`, `cpu0`,
 * `cpuss-*`, etc.). NO atribuye a CPU: `aoss-*` (director del SoC),
 * `gpuss-*` (GPU), batería, cargador ni desconocidos — presentar el máximo
 * de cualquier subsistema como "CPU" sería la atribución engañosa que
 * Device Watch hace (sus zonas no se copian).
 *
 * Se decide por el `type`, NUNCA por el índice de la zona (thermal_zone0 no
 * implica CPU: en SoCs reales es a menudo el sensor del PCB/paquete completo).
 *
 * NOTA por-chip: el prefijo `cpu*` cubre los `type` de QUALCOMM. En MEDIATEK el
 * tipo de las zonas CPU suele ser `mtktscpu-*` (no calza el prefijo) — ahí el
 * lector reporta Float.NaN → "N/D": fallo conservador, nunca dato falso.
 */
internal fun isCpuThermalType(type: String): Boolean {
    return type.trim().lowercase().startsWith("cpu")
}

/**
 * Selección térmica CPU: descarta zonas no-CPU y lecturas inválidas
 * (validación REUTILIZADA de [parseThermal], no duplicada) y devuelve el
 * MÁXIMO de las lecturas CPU válidas, en Celsius.
 *
 * `Float.NaN` cuando no hay ninguna zona CPU con lectura válida — nunca
 * 0 (el Watchdog trataría 0 como temperatura real; NaN es el dato
 * no-disponible del proyecto). Pura: la I/O vive en [readCpuZoneSamples].
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

/**
 * Enumeración I/O del árbol `thermal_zoneN`: lee `type` y `temp` de cada zona,
 * tolerando permisos denegados, archivos ausentes, contenido vacío y
 * errores de lectura (cada fallo descarta SOLO esa zona, sin propagar
 * excepción ni inventar 0). Devuelve pares (type, temp cruda), sin parsear.
 *
 * @param dir inyectable para tests JVM (producción: [SYSFS_THERMAL_DIR]).
 */
internal fun readCpuZoneSamples(dir: File): List<Pair<String, String>> =
    enumerateThermalZones(dir).map { it.type to it.temp }

/** Muestra (type, temp) de una zona térmica con la ruta de su `temp`, para releer
 *  temperatura en ticks posteriores sin re-enumerar (ahorro de I/O: en el ZTE
 *  unas 70 zonas fallaban por tick a causa de SELinux). */
internal data class ThermalZoneSample(val zone: File, val type: String, val temp: String)

/**
 * Enumeración I/O del árbol `thermal_zoneN`: lee `type` y `temp` de cada zona,
 * tolerando permisos denegados, archivos ausentes, contenido vacío y
 * errores de lectura (cada fallo descarta SOLO esa zona, sin propagar
 * excepción ni inventar 0). Devuelve la lista CRUDA de muestras, sin parsear.
 *
 * @param dir inyectable para tests JVM (producción: [SYSFS_THERMAL_DIR]).
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

/**
 * Cache del lector térmico por instancia: las zonas se enumeran UNA vez (la
 * primera llamada a [samples]); los ticks posteriores NO re-enumeran — releen
 * SOLO el `temp` de las zonas CPU cacheadas (el `type` no cambia en caliente
 * en el kernel). Ahorro: en un SoC con ~70 zonas, la enumeración es ~140
 * aperturas sysfs por tick, y donde SELinux deniega todo (p.ej. ZTE) son
 * ~70 fallos por ciclo para nada.
 *
 * Dos latches, con semántica distinta:
 *
 * - **Tick 1 (nunca hubo nada legible):** si la enumeración inicial no
 *   produce NI UNA zona CPU válida (SELinux deniega el árbol desde el arranque,
 *   etc.) se abandona para siempre: el uid de la app no gana permiso de sysfs
 *   a mitad de sesión (mismo patrón que el latch de Device Watch).
 *
 * - **Pérdida posterior (había zonas CPU legibles y dejan de leerse):** exige
 *   [LATCH_AFTER_CONSECUTIVE_FAILURES] (= 5) ticks consecutivos sin NINGUNA
 *   lectura CPU válida antes de latchar. Un fallo PUNTUAL (deep suspend que
 *   congela sysfs un instante, un tick que devuelve vacío/0) NO mata el
 *   monitor térmico de un servicio de larga vida: al tick siguiente vuelve a
 *   leer y el contador de fallos se resetea. Solo si el árbol permanece
 *   ilegible tick tras tick (permiso revocado, hardware apagado) es razonable
 *   dejar de pagar el costo de reintento.
 *
 * Los tests crean instancias frescas; solo producción comparte el singleton
 * [THERMAL_TICK_CACHE].
 *
 * THREAD-SAFETY: el acceso a TODO el estado mutable ([cachedCpuZones],
 * [abandoned], [consecutiveReadFailures]) ocurre exclusivamente dentro del
 * bloque `synchronized(lock)` que envuelve todo [samples] — los consumidores
 * (SystemMonitor loop en Dispatchers.IO y ThermalController.getCurrentTemp)
 * comparten el singleton sin serialización adicional. No hay campos `@Volatile`:
 * el lock es el garante, no la visibilidad volátil.
 */
internal class CpuThermalTickCache(private val dir: File) {
    private val lock = Any()
    private var cachedCpuZones: List<ThermalZoneSample>? = null
    private var abandoned = false
    private var consecutiveReadFailures = 0

    private companion object {
        /** Fallos consecutivos de LECTURA (post-enumeración) antes del latch. */
        const val LATCH_AFTER_CONSECUTIVE_FAILURES = 5
    }

    /**
     * Muestras crudas del tick: en la primera llamada devuelve TODAS las zonas
     * legibles (el selector [maxCpuZoneTemp] filtra por type, contrato previo
     * de [readCpuZoneSamples]); en las siguientes, solo las CPU cacheadas.
     * NEVER inventa 0: lista vacía cuando nada es legible.
     */
    fun samples(): List<Pair<String, String>> = synchronized(lock) {
        if (abandoned) return emptyList()
        val cached = cachedCpuZones
        if (cached == null) {
            val full = enumerateThermalZones(dir)
            val cpu = full.filter { isCpuThermalType(it.type) && parseThermal(it.temp) != null }
            cachedCpuZones = cpu
            if (cpu.isEmpty()) abandoned = true // nada CPU legible: ni el tick 2 reintenta
            full.map { it.type to it.temp }
        } else {
            val samples = cached.mapNotNull { z ->
                val temp = try { File(z.zone, "temp").readText().trim() } catch (_: Exception) { null }
                if (temp.isNullOrEmpty()) null else z.type to temp
            }
            if (maxCpuZoneTemp(samples).isNaN()) {
                // Pérdida de lectura: latch solo tras N fallos CONSECUTIVOS —
                // un fallo puntual no mata el monitor del servicio.
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

/**
 * Cache compartido SystemMonitor + ThermalController sobre la ruta real del
 * sistema ([SYSFS_THERMAL_DIR]) — una sola enumeración viva por proceso.
 */
internal val THERMAL_TICK_CACHE = CpuThermalTickCache(SYSFS_THERMAL_DIR)

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
        /** Tope duro sobre ping (ms); -w 1 del binario no es fiable en todos los OEM. */
        private const val PING_TIMEOUT_MS = 2_500L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── Métricas del sistema ──────────────────────────────────────
    private val _systemMetrics = MutableStateFlow(
        SystemMetrics(
            cpuUsage = 0, ramUsed = 0L, ramTotal = 0L,
            batteryLevel = 0, batteryTemp = 0f,
            // NaN (no 0f): StateFlow inicial con 0f presentaba un valor inválido
            // como válido durante los primeros ~2s (Watchdog rama <=40°C solo
            // actúa si hubo episodio térmico previo, pero el overlay/dashboard
            // habrían mostrado "0°C"). Este es el ÚNICO sitio del proyecto que
            // construía SystemMetrics con temp falsa.
            cpuTemp = Float.NaN,
            gpuUsage = 0, ping = 0, dpi = 0,
            pointerSpeed = "0%", animationScale = "1x", refreshRate = "60 Hz",
            governor = "Schedutil", touchSampling = "N/D",
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

    /** Último reason de detección externa; se loguea solo cuando cambia (evita spam 5s). */
    @Volatile
    private var lastExternalReason: String? = null

    // Para actualizar métricas externas (boost state, active game, etc.)
    var getBoostActive: (() -> Boolean)? = null
    var getActiveGame: (() -> String?)? = null
    var getFsmState: (() -> com.example.data.repository.FsmState)? = null
    var getActiveProfile: (() -> com.example.data.database.ProfileEntity?)? = null

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
            // F5-adj: no inventar Hz de touch (sampling real es OEM/sysfs)
            touchSampling = "N/D",
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

    /** F5: sin lectura real → NaN (no fingir 38°C). Watchdog no actúa sobre NaN.
     *  Lector térmico ÚNICO: [THERMAL_TICK_CACHE] (tick 1: enumeración por
     *  `type`; siguientes: solo `temp` de las zonas CPU cacheadas, con latch
     *  si nada es legible). Ya no es un by-pass por índice (thermal_zone0 puede
     *  no ser CPU — ver commit acdff69). */
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

    /**
     * Ping con tope duro (F2): no confiar solo en `-w 1` del binario.
     * RTT real leído del stdout del binario (línea rtt min/avg/max/mdev → avg);
     * el wall-clock del proceso queda solo como fallback si el stdout no es
     * parseable (auditoría ping 2026-09-20: el wall medía ~80 ms de overhead
     * spawn/exec de la JVM — evidencia PINGDIAG, wall 100–104 vs rtt 9–28, n=110 —
     * y el overlay mostraba ~5× la latencia real). destroyForcibly si cuelga.
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
                    Log.w(TAG, "ping timeout/hang — usando último valor ($lastMeasuredPing)")
                    return@withContext lastMeasuredPing
                }
                if (process.exitValue() == 0) {
                    val wallMs = (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
                    // Fix (auditoría ping 2026-09-20): el RTT real vive en el stdout del
                    // binario, no en el wall-clock. Fallback: wall (comportamiento previo)
                    // si el stdout no es parseable.
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

    // ─── Detección de dispositivos externos ────────────────────────

    /**
     * Loggea el reason de detección externa solo cuando cambia,
     * evitando spam de logcat cada 5s.
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

                /**
                 * Trade-off: el grep de dumpsys input busca device names y flags.
                 * scrcpy aparece con el nombre 'scrcpy' en device names (NO con
                 * IsExternal: true). GG Mouse aparece como 'external: true'.
                 * En su defecto, ps -A detecta mappers que no reportan como HIDs
                 * (ztezscreenshot, vphone, etc.).
                 * La doble capa evita falsos negativos: si un mapper no aparece en
                 * dumpsys (scrcpy sin --mouse=uhid), pgrep -f lo cubre; si no hay
                 * proceso en ps -A, el primer grep lo cubre.
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

                // Proceso scrcpy: pgrep -f matchea el cmdline completo (ps -A solo muestra
                // el argv[0] = app_process, nunca "scrcpy" ni "server.jar"). El bracket
                // en "Serve[r]" evita self-match del sh -c que ejecuta el comando.
                val scrcpyPids = ShizukuExecutor
                    .runCommand("pgrep -f 'com.genymobile.scrcpy.Serve[r]'")
                    .getOrNull().orEmpty()
                if (scrcpyPids.lines().any { it.trim().matches(Regex("\\d+")) }) {
                    logExternalReason("pgrep:scrcpy")
                    return@withContext true
                }

                // Detección por procesos activos (mappers que sí aparecen en ps -A)
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

                // Check de servicios de accesibilidad
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

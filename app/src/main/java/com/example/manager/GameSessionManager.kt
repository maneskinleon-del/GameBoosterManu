package com.example.manager

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.example.data.PreferenceManager
import com.example.data.database.AppDatabase
import com.example.data.database.LogEntity
import com.example.data.database.ProfileEntity
import com.example.data.repository.FsmState
import com.example.service.GameBoostService
import com.example.ui.FloatingPanelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Gestión de sesiones de juego: FSM, boost lifecycle, detección de juegos,
 * perfiles, y comandos privilegiados.
 * 
 * Ciclo de vida de una sesión:
 *   INITIALIZING → READY → GAME_ACTIVE → READY (o DEGRADED → RECOVERING)
 * 
 * Uso:
 *   val session = GameSessionManager(context, database, logDao, profileDao)
 *   session.onExternalDeviceDetected = { /* toggle ff_mouse profile */ }
 *   session.toggleBoost()
 */
class GameSessionManager(
    private val context: Context,
    private val database: AppDatabase,
    private val touchOptimizer: TouchOptimizer,
    private val ramManager: RamManager,
    private val networkOptimizer: NetworkOptimizer,
    private val systemTweaks: SystemTweaks,
    private val powerOptimizer: PowerOptimizer,
    private val isAutoDetectEnabled: () -> Boolean = { true },
    private val hasExternalDevices: () -> Boolean = { false },
    private val isMsaaEnabled: () -> Boolean = { false },
    private val checkExternalDevicesNow: suspend () -> Boolean = { false }
) {

    companion object {
        private const val TAG = "GameSession"
        private const val HYSTERESIS_DELAY_MS = 5000L
        private const val INIT_TIMEOUT_MS = 10_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val profileDao = database.profileDao()
    private val logDao = database.logDao()

    // ─── FSM State ─────────────────────────────────────────────────
    private val _fsmState = MutableStateFlow(FsmState.INITIALIZING)
    val fsmState: StateFlow<FsmState> = _fsmState.asStateFlow()

    // ─── Boost Active ──────────────────────────────────────────────
    private val _isBoostActive = MutableStateFlow(false)
    val isBoostActive: StateFlow<Boolean> = _isBoostActive.asStateFlow()

    // ─── Shizuku Connected ─────────────────────────────────────────
    private val _shizukuConnected = MutableStateFlow(false)
    val shizukuConnected: StateFlow<Boolean> = _shizukuConnected.asStateFlow()

    // ─── Simulated Game (foreground app) ───────────────────────────
    private val _simulatedGame = MutableStateFlow<String?>(null)
    val simulatedGame: StateFlow<String?> = _simulatedGame.asStateFlow()

    // ─── Mobilador Active ──────────────────────────────────────────
    private val _isMobiladorActive = MutableStateFlow(false)
    val isMobiladorActive: StateFlow<Boolean> = _isMobiladorActive.asStateFlow()

    // ─── ADS Pointer Active ────────────────────────────────────────
    private val _adsPointerActive = MutableStateFlow(false)
    val adsPointerActive: StateFlow<Boolean> = _adsPointerActive.asStateFlow()

    // ─── Available Governors ───────────────────────────────────────
    private val _availableGovernors = MutableStateFlow<List<String>>(emptyList())
    val availableGovernors: StateFlow<List<String>> = _availableGovernors.asStateFlow()

    // ─── Game Cache ────────────────────────────────────────────────
    private val _gamesCache = ConcurrentHashMap<String, String>()

    // ─── Hysteresis ────────────────────────────────────────────────
    private val hysteresisJob = AtomicReference<Job?>(null)
    private var manualOverrideActive = false

    // Callbacks para comunicación hacia afuera
    var onProfileApplied: ((ProfileManager.ProfileType) -> Unit)? = null

    // Para acceder al FloatingPanelManager desde el service
    var floatingPanelManager: FloatingPanelManager? = null

    // ─── Gaming DND ───────────────────────────────────────────────
    @Volatile
    private var originalZenMode: String? = null

    // Flag de inicialización
    private var isReady = false

    // ─── Init ──────────────────────────────────────────────────────

    suspend fun initialize(): Boolean {
        if (isReady) return true

        return try {
            withTimeout(INIT_TIMEOUT_MS) {
                checkRealShizuku()
                fetchAvailableGovernors()
                isReady = true
                _fsmState.value = FsmState.READY
                Log.d(TAG, "✅ GameSessionManager inicializado")
                true
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Init timed out, forcing READY")
            _fsmState.value = FsmState.READY
            isReady = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "Init error: ${e.message}, forcing READY")
            _fsmState.value = FsmState.READY
            isReady = true
            true
        }
    }

    private fun checkRealShizuku() {
        _shizukuConnected.value = ShizukuExecutor.isReady()
    }

    private suspend fun fetchAvailableGovernors() {
        try {
            val result = ShizukuExecutor.runCommand(
                "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors"
            )
            result.getOrNull()?.let {
                _availableGovernors.value = it.split(" ").filter { gov -> gov.isNotBlank() }
            }
        } catch (_: Exception) {}
    }

    // ─── Calbacks de Shizuku (para reconexión en caliente) ────────

    fun onShizukuReconnected() {
        val currentPkg = _simulatedGame.value
        if (currentPkg != null && _fsmState.value != FsmState.GAME_ACTIVE) {
            scope.launch {
                simulateGameLaunchInternal(currentPkg)
            }
        }
    }

    fun toggleShizukuState() {
        _shizukuConnected.value = ShizukuExecutor.isReady()
    }

    // ─── Boost Lifecycle ───────────────────────────────────────────

    fun toggleBoost() {
        val newState = !_isBoostActive.value
        Log.d(TAG, "toggleBoost() called. Old state: ${_isBoostActive.value}, New state: $newState")
        _isBoostActive.value = newState

        PreferenceManager.setServiceRunning(context, _isBoostActive.value)
        addLog("INFO", "Optimizer", "Boost mode: ${if (newState) "ON" else "OFF"}")

        if (newState) {
            Log.d(TAG, "Activating boost...")
            ensureBoostServiceRunning()
            applyBoostSettings()
        } else {
            Log.d(TAG, "Deactivating boost...")
            restoreSettings()
        }
    }

    private fun ensureBoostServiceRunning() {
        try {
            Log.d(TAG, "ensureBoostServiceRunning: Sending ACTION_START to GameBoostService")
            val intent = android.content.Intent(context, GameBoostService::class.java).apply {
                action = GameBoostService.ACTION_START
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            addLog("WARN", "Optimizer", "No se pudo iniciar GameBoostService: ${e.message}")
        }
    }

    private fun applyBoostSettings() {
        val msaaEnabled = isMsaaEnabled()
        touchOptimizer.applyOptimization(sensitivity = 10, isGamingMode = true)
        networkOptimizer.apply()
        systemTweaks.apply(enableMsaa = msaaEnabled)

        scope.launch {
            delay(5000)
            ramManager.clean(force = false)
        }

        scope.launch {
            val animCommands = listOf(
                "settings put global window_animation_scale 0",
                "settings put global transition_animation_scale 0",
                "settings put global animator_duration_scale 0"
            )
            executePrivilegedCommands(animCommands, tag = "BoostApplyAnim")
        }

        scope.launch {
            delay(2000)
            systemTweaks.clearLogs()
        }

        // Gaming DND — silenciar notificaciones durante el juego
        scope.launch {
            val zenResult = ShizukuExecutor.runCommand("settings get global zen_mode")
            if (zenResult.isSuccess) {
                val mode = zenResult.getOrNull()?.trim()
                if (!mode.isNullOrBlank() && mode != "null") {
                    originalZenMode = mode
                }
            }
            ShizukuExecutor.runCommand("settings put global zen_mode 2")
            addLog("INFO", "GamingDND", "🔇 No Molestar activado (zen_mode=2)")
        }
    }

    fun toggleMobilador() {
        val newState = !_isMobiladorActive.value
        _isMobiladorActive.value = newState
        addLog("INFO", "Mobilador", "Modo Mobilador: ${if (newState) "ACTIVADO" else "DESACTIVADO"}")

        if (newState) {
            scope.launch {
                val commands = listOf(
                    "settings put system pointer_speed 7",
                    "settings put system touch_report_rate 240",
                    "settings put secure long_press_timeout 120"
                )
                executePrivilegedCommands(commands, tag = "MobiladorOn")
            }
        }
    }

    private fun restoreSettings() {
        touchOptimizer.restore()
        networkOptimizer.restore()
        systemTweaks.restore()
        scope.launch {
            val commands = listOf(
                "settings put global window_animation_scale 1",
                "settings put global transition_animation_scale 1",
                "settings put global animator_duration_scale 1",
                "cmd power set-fixed-performance-mode-enabled false",
                "cmd power set-adaptive-power-saver-enabled true"
            )
            executePrivilegedCommands(commands, tag = "SettingsRestore")
        }

        // Restaurar modo No Molestar
        scope.launch {
            val restoreCmd = if (!originalZenMode.isNullOrBlank()) {
                "settings put global zen_mode $originalZenMode"
            } else {
                "settings put global zen_mode 0"
            }
            ShizukuExecutor.runCommand(restoreCmd)
            originalZenMode = null
            addLog("INFO", "GamingDND", "🔔 No Molestar restaurado")
        }
    }

    // ─── Game Detection ───────────────────────────────────────────

    suspend fun simulateGameLaunch(packageName: String?) {
        if (packageName == null) {
            triggerExitWithHysteresis()
            return
        }
        simulateGameLaunchInternal(packageName)
    }

    private suspend fun simulateGameLaunchInternal(packageName: String) {
        if (!isAutoDetectEnabled()) return

        addLog("DEBUG", "FSM_DIAG", "simulateGameLaunch($packageName) | fsmState=${_fsmState.value}")

        hysteresisJob.getAndSet(null)?.cancel()

        // Si el usuario eligió un perfil manualmente, no pisarlo con la
        // auto-detección (re-detección del mismo juego, reconexión de Shizuku, etc.).
        if (manualOverrideActive) {
            return
        }

        if (_simulatedGame.value != null && _simulatedGame.value != packageName) {
            manualOverrideActive = false
        }

        if (_simulatedGame.value == packageName && _fsmState.value == FsmState.GAME_ACTIVE) {
            return
        }

        val isMapper = packageName.contains("gg.mouse") ||
                packageName.contains("vphone") ||
                packageName.contains("scrcpy") ||
                packageName.contains("flydigi") ||
                packageName.contains("gamesir") ||
                packageName.contains("mantis") ||
                packageName.contains("panda") ||
                packageName.contains("gamewolf")

        val gameName = _gamesCache[packageName]
        val knownTencentGames = setOf(
            "com.tencent.ig", "com.tencent.tmgp.pubgm", "com.tencent.tmgp.sgame",
            "com.pubg.krmobile", "com.rekoo.pubgm"
        )
        val isPotentialGame = packageName.contains("freefire") ||
                knownTencentGames.any { packageName.startsWith(it) } ||
                packageName.contains("garena")

        val isGame = isMapper || gameName != null || isPotentialGame

        if (isGame) {
            val name = gameName ?: if (isMapper) "Mapper" else "Potential Game"
            addLog("INFO", "Monitor", "Game detected: $name ($packageName)")

            if (!_shizukuConnected.value) {
                addLog("WARN", "Monitor", "$name detectado pero Shizuku NO conectado")
                _simulatedGame.value = packageName
                return
            }

            _simulatedGame.value = packageName
            _fsmState.value = FsmState.GAME_ACTIVE

            applyHighPriorityOptimizations(packageName)

            scope.launch {
                delay(3000)
                powerOptimizer.suspendCachedApps(packageName)
            }

            // ─── FASE 2 y 3: Perfil según dispositivos externos ───
            // Forzar chequeo on-demand de dispositivos externos (no esperar ciclo de 5s)
            val externalConnected = if (isMapper) true else checkExternalDevicesNow()
            
            addLog("INFO", "Monitor", "Perfil: mapper=$isMapper, ext=$externalConnected, boostActivo=${_isBoostActive.value}")

            if (isMapper || externalConnected) {
                // Dispositivo externo o mapper → FF MOUSE DUO siempre
                addLog("INFO", "Monitor", "▶️ Aplicando perfil FF Mouse Duo")
                setActiveProfile("ff_mouse", isManual = false)
                if (!_isBoostActive.value) {
                    delay(500)
                    toggleBoost()
                }
            } else {
                // Juego táctil sin dispositivos externos
                val isFreeFire = packageName.contains("freefire") ||
                        packageName.contains("garena") ||
                        _gamesCache[packageName]?.lowercase()?.contains("free fire") == true

                if (isFreeFire) {
                    // Free Fire táctil → perfil FREE FIRE TOUCH
                    addLog("INFO", "Monitor", "▶️ Free Fire táctil detectado. Aplicando perfil FREE FIRE TOUCH")
                    setActiveProfile("free_fire_touch", isManual = false)
                }

                if (!_isBoostActive.value) {
                    delay(500)
                    toggleBoost()
                }
            }
        } else {
            triggerExitWithHysteresis()
        }
    }

    private suspend fun applyHighPriorityOptimizations(packageName: String) {
        // FASE 0: Game Mode API (Android 12+)
        enableGameMode(packageName)

        // Adaptive Power Saver Off
        executePrivilegedCommands(
            listOf("cmd power set-adaptive-power-saver-enabled false"),
            tag = "AdaptivePowerSaver_Off"
        )

        // FASE 1: Governor + Performance Mode
        val phase1Commands = listOf(
            "cmd power set-fixed-performance-mode-enabled true"
        )
        executePrivilegedCommands(phase1Commands, tag = "HighPriority_Phase1")

        // FASE 2: Refresh rate (delay 500ms)
        delay(500)
        executePrivilegedCommands(
            listOf(
                "settings put system peak_refresh_rate 120.0",
                "settings put system min_refresh_rate 90.0"
            ),
            tag = "HighPriority_Phase2"
        )


    }

    @SuppressLint("SoonDeprecated")
    private fun enableGameMode(packageName: String) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        try {
            val gameManager = context.getSystemService(Context.GAME_SERVICE) as android.app.GameManager
            val method = gameManager::class.java.getMethod(
                "setGameState",
                String::class.java,
                Boolean::class.javaPrimitiveType ?: Boolean::class.java
            )
            method.invoke(gameManager, packageName, true)
            addLog("DEBUG", "GameMode", "Game Mode API activado para $packageName")
        } catch (e: NoSuchMethodException) {
            addLog("WARN", "GameMode", "setGameState no encontrado")
        } catch (e: Exception) {
            addLog("WARN", "GameMode", "Error: ${e.message}")
        }
    }

    @SuppressLint("SoonDeprecated")
    private fun disableGameMode(packageName: String) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        try {
            val gameManager = context.getSystemService(Context.GAME_SERVICE) as android.app.GameManager
            val method = gameManager::class.java.getMethod(
                "setGameState",
                String::class.java,
                Boolean::class.javaPrimitiveType ?: Boolean::class.java
            )
            method.invoke(gameManager, packageName, false)
        } catch (_: Exception) {}
    }

    // ─── Exit with Hysteresis ─────────────────────────────────────

    private fun triggerExitWithHysteresis() {
        hysteresisJob.set(scope.launch {
            try {
                delay(HYSTERESIS_DELAY_MS)
                val oldGame = _simulatedGame.value

                if (oldGame != null || _fsmState.value == FsmState.GAME_ACTIVE) {
                    _simulatedGame.value = null
                    _fsmState.value = FsmState.READY
                    if (manualOverrideActive) {
                        // El usuario eligió un perfil manual: respetarlo. Solo
                        // limpiamos la flag para que la auto-detección funcione en
                        // el próximo juego, pero NO apagamos boost ni restauramos
                        // tweaks ni bajamos a balanced.
                        manualOverrideActive = false
                        return@launch
                    }
                    manualOverrideActive = false
                    addLog("INFO", "Monitor", "Salida de juego confirmada ($oldGame)")

                    executePrivilegedCommands(
                        listOf(
                            "cmd power set-fixed-performance-mode-enabled false",
                            "cmd power set-adaptive-power-saver-enabled true",
                            "cmd thermalservice reset"
                        ),
                        tag = "Restore"
                    )

                    if (oldGame != null) disableGameMode(oldGame)

                    networkOptimizer.restore()
                    systemTweaks.restore()

                    // ─── Perfil post-salida ───────────────────────
                    // Si aún hay dispositivos externos conectados (ggmouse, teclado,
                    // mapeador, etc.), mantener FF MOUSE DUO en lugar de ir a BALANCED.
                    // Así si el usuario solo abrió una notificación rápido, no pierde
                    // la configuración de mouse.
                    val externalStillConnected = checkExternalDevicesNow()
                    if (externalStillConnected) {
                        addLog("INFO", "Monitor", "Dispositivos externos siguen conectados. Manteniendo FF MOUSE DUO.")
                        // No cambiar perfil, queda ff_mouse si estaba en eso
                    } else {
                        if (!manualOverrideActive) {
                            setActiveProfile("balanced", isManual = false)
                        }
                    }

                    if (_isMobiladorActive.value) toggleMobilador()
                    ramManager.clean()
                }
            } catch (e: CancellationException) {
                throw e
            }
        })
    }

    // ─── Profile Management ───────────────────────────────────────

    fun setActiveProfile(id: String, isManual: Boolean = true) {
        if (isManual) {
            hysteresisJob.getAndSet(null)?.cancel()
            manualOverrideActive = true
        }

        scope.launch {
            profileDao.setActiveProfile(id)
            val profileEntity = profileDao.getProfileById(id)

            val profileType = when (profileEntity?.id) {
                "extreme" -> ProfileManager.ProfileType.EXTREME
                "balanced" -> ProfileManager.ProfileType.BALANCED
                "battery_saver" -> ProfileManager.ProfileType.POWER_SAVE
                "ff_mouse" -> ProfileManager.ProfileType.ADS
                "free_fire_touch" -> ProfileManager.ProfileType.FREE_FIRE_TOUCH
                else -> ProfileManager.ProfileType.entries.find {
                    it.name.equals(profileEntity?.name, true) ||
                            profileEntity?.name?.contains(it.name, ignoreCase = true) == true
                }
            }

            if (profileType != null) {
                ProfileManager.applyProfile(profileType)
                // Aplicar optimizaciones de red (DNS, WiFi low-latency, BT coex)
                networkOptimizer.apply()
                onProfileApplied?.invoke(profileType)
            }
        }
    }

    // ─── External device detection callback ────────────────────────

    /**
     * Llamado por SystemMonitor cuando se detecta un dispositivo externo
     * durante una sesión de juego activa.
     */
    fun onExternalDeviceDetectedWhileGaming(isConnected: Boolean) {
        if (isConnected && _fsmState.value == FsmState.GAME_ACTIVE) {
            addLog("INFO", "Monitor", "Dispositivo externo detectado en caliente. Aplicando perfil FF Mouse Duo.")
            if (!manualOverrideActive) {
                setActiveProfile("ff_mouse", isManual = false)
            }
        }
    }

    // ─── Quick Clean ──────────────────────────────────────────────

    fun quickClean() {
        ramManager.clean(force = true)
        systemTweaks.clearLogs()
    }

    // ─── FSM State setter ─────────────────────────────────────────

    fun setFsmState(state: FsmState) {
        _fsmState.value = state
        addLog("DEBUG", "FSM", "State changed to: $state")
    }

    // ─── Toggle Shizuku ───────────────────────────────────────────

    fun recheckShizuku() {
        _shizukuConnected.value = ShizukuExecutor.isReady()
    }

    // ─── Games Cache ──────────────────────────────────────────────

    fun getGameName(packageName: String): String? = _gamesCache[packageName]

    fun addGameToCache(packageName: String, displayName: String) {
        _gamesCache[packageName] = displayName
    }

    fun removeGameFromCache(packageName: String) {
        _gamesCache.remove(packageName)
    }

    fun clearGamesCache() {
        _gamesCache.clear()
    }

    // ─── SetForegroundApp (desde AccessibilityService o GameDetector) ─

    fun setForegroundApp(packageName: String) {
        scope.launch {
            simulateGameLaunch(packageName)
        }
    }

    /**
     * Señal de que el usuario salió de la app en foreground
     * (no se pudo detectar ninguna app activa).
     */
    fun onForegroundAppLost() {
        scope.launch {
            simulateGameLaunch(null)
        }
    }

    // ─── Compartir estado para acceso externo ─────────────────────

    fun getSimulatedGame(): String? = _simulatedGame.value

    // ─── Comandos Privilegiados ───────────────────────────────────

    suspend fun executePrivilegedCommands(commands: List<String>, tag: String = "Exec") {
        var successCount = 0
        var failCount = 0

        for (cmd in commands) {
            // Skip governor commands if kernel blocks writes (ZTE, Xiaomi, etc.)
            if (cmd.contains("scaling_governor") && !isGovernorWritable()) {
                addLog("DEBUG", tag, "Governor bloqueado por kernel — skip")
                continue
            }

            val res = ShizukuExecutor.runCommand(cmd)
            if (res.isSuccess) {
                successCount++
            } else {
                val fallbackOk = trySettingsApiFallback(cmd)
                if (fallbackOk) successCount++ else {
                    failCount++
                    val friendlyMsg = when {
                        cmd.contains("scaling_governor") -> "Governor no escribible (SELinux/Kernel bloquea)"
                        cmd.contains("renice") || cmd.contains("taskset") -> "Permisos insuficientes para $cmd"
                        else -> "Fallo: $cmd"
                    }
                    addLog("WARN", tag, friendlyMsg)
                }
            }
        }
        if (failCount > 0) {
            addLog("DEBUG", tag, "Comandos: $successCount OK, $failCount fallos")
        }
    }

    /**
     * Check if the scaling_governor is writable on this device.
     * Returns false if SELinux or kernel blocks writes.
     */
    private suspend fun isGovernorWritable(): Boolean {
        val testPath = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"
        val result = ShizukuExecutor.runCommand("[ -w \"$testPath\" ] && echo writable || echo readonly")
        return result.getOrNull()?.trim() == "writable"
    }

    private fun trySettingsApiFallback(cmd: String): Boolean {
        val regex = """^settings\s+(put)\s+(system|global|secure)\s+(\S+)\s+(\S+)$""".toRegex()
        val matchResult = regex.find(cmd.trim()) ?: return false
        val (_, scope, key, rawValue) = matchResult.groupValues

        return try {
            when (scope) {
                "system" -> {
                    val intValue = rawValue.toIntOrNull()
                    if (intValue != null)
                        android.provider.Settings.System.putInt(context.contentResolver, key, intValue)
                    else {
                        val floatValue = rawValue.toFloatOrNull()
                        if (floatValue != null)
                            android.provider.Settings.System.putFloat(context.contentResolver, key, floatValue)
                        else android.provider.Settings.System.putString(context.contentResolver, key, rawValue)
                    }
                }
                "global" -> {
                    val intValue = rawValue.toIntOrNull()
                    if (intValue != null)
                        android.provider.Settings.Global.putInt(context.contentResolver, key, intValue)
                    else android.provider.Settings.Global.putString(context.contentResolver, key, rawValue)
                }
                "secure" -> {
                    val intValue = rawValue.toIntOrNull()
                    if (intValue != null)
                        android.provider.Settings.Secure.putInt(context.contentResolver, key, intValue)
                    else android.provider.Settings.Secure.putString(context.contentResolver, key, rawValue)
                }
                else -> return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // ─── Logging ──────────────────────────────────────────────────

    fun addLog(level: String, tag: String, message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        scope.launch {
            logDao.insertLog(LogEntity(timestamp = timestamp, level = level, tag = tag, message = message))
            Log.d(tag, "[$level] $message")
        }
    }

    fun logAsync(level: String, tag: String, message: String) = addLog(level, tag, message)

    // ─── Shell sanitizer ─────────────────────────────────────────

    fun sanitizeShellArg(input: String): String {
        return input.filter { c ->
            c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' || c == '/'
        }
    }

    // ─── Shutdown ─────────────────────────────────────────────────

    fun shutdown() {
        hysteresisJob.getAndSet(null)?.cancel()
        scope.cancel()
        Log.d(TAG, "GameSessionManager detenido")
    }
}

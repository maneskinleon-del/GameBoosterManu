package com.example.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.example.data.PreferenceManager
import com.example.data.SettingToggle
import com.example.ui.FloatingPanelManager
import com.example.data.database.AppDatabase
import com.example.data.database.GameEntity
import com.example.data.database.LogEntity
import com.example.data.database.ProfileEntity
import com.example.manager.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray

data class SystemMetrics(
    val cpuUsage: Int,
    val ramUsed: Long,
    val ramTotal: Long,
    val batteryLevel: Int,
    val batteryTemp: Float,
    val cpuTemp: Float,
    val gpuUsage: Int,
    val ping: Int,
    val dpi: Int,
    val pointerSpeed: String,
    val animationScale: String,
    val refreshRate: String,
    val governor: String,
    val touchSampling: String,
    val activeGame: String?,
    val optimizerStatus: String,
    val fsmState: FsmState
)

enum class FsmState {
    INITIALIZING, READY, GAME_ACTIVE, DEGRADED, RECOVERING
}

/**
 * Facade principal de GameBoost Pro.
 *
 * Orquesta 3 subsistemas:
 * - [SystemMonitor]: métricas en tiempo real (CPU/RAM/batería)
 * - [ThermalController]: monitoreo térmico
 * - [GameSessionManager]: FSM, boost, detección de juegos, perfiles
 *
 * Mantiene: database, SettingToggles, game list, profile seeding.
 */
class GameBoostRepository private constructor(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val profileDao = database.profileDao()
    private val logDao = database.logDao()
    val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── Sub-managers ──────────────────────────────────────────────
    val thermalController = ThermalController(context)
    val systemMonitor = SystemMonitor(context)
    val gameDetector = GameDetector(context)

    // Managers externos (inyectados a GameSessionManager)
    private val touchOptimizer = TouchOptimizer(context)
    private val ramManager = RamManager(context, this)
    private val networkOptimizer = NetworkOptimizer(this)
    private val systemTweaks = SystemTweaks(this)
    private val powerOptimizer = PowerOptimizer(this)

    // GameSessionManager se crea aquí para evitar UninitializedPropertyAccessException
    private val sessionManager = GameSessionManager(
        context = context,
        database = database,
        touchOptimizer = touchOptimizer,
        ramManager = ramManager,
        networkOptimizer = networkOptimizer,
        systemTweaks = systemTweaks,
        powerOptimizer = powerOptimizer,
        isAutoDetectEnabled = { autoDetectGames.value },
        hasExternalDevices = { systemMonitor.externalDevicesConnected.value },
        isMsaaEnabled = { msaa.value },
        checkExternalDevicesNow = { systemMonitor.checkExternalDevicesNow() }
    )

    // Watchdog (depende de this)
    private val watchdogManager = WatchdogManager(context, this)

    // Dependency State Manager
    private val dependencyStateManager = DependencyStateManager(context)
    val dependencyState = dependencyStateManager.dependencyState

    /** Fuerza un refresh inmediato del estado de dependencias (ej: al volver de Settings). */
    fun refreshDependencyState() = dependencyStateManager.refreshAll()

    // Active profile cache (evita runBlocking en lambdas)
    @Volatile
    private var cachedActiveProfile: ProfileEntity? = null
    private val resourceGovernor = ResourceGovernor(context, this)

    val healthStatus = watchdogManager.healthStatus

    // ─── Setting Toggles ──────────────────────────────────────────
    val aggressiveOptimization = SettingToggle(context, "aggressive_optimization", false)
    val autoDetectGames = SettingToggle(context, "auto_detect_games", true)
    val forceScrcpyMode = SettingToggle(context, "force_scrcpy_mode", false)
    val thermalWatchdog = SettingToggle(context, "thermal_watchdog", true)
    val deepSleep = SettingToggle(context, "deep_sleep_optimizations", true)
    val msaa = SettingToggle(context, "msaa_enabled", false)

    val isAggressiveOptimizationEnabled: StateFlow<Boolean> get() = aggressiveOptimization.state
    val isAutoDetectGamesEnabled: StateFlow<Boolean> get() = autoDetectGames.state
    val isForceScrcpyModeEnabled: StateFlow<Boolean> get() = forceScrcpyMode.state
    val isThermalWatchdogEnabled: StateFlow<Boolean> get() = thermalWatchdog.state
    val isDeepSleepEnabled: StateFlow<Boolean> get() = deepSleep.state
    val isMsaaEnabled: StateFlow<Boolean> get() = msaa.state

    // ─── StateFlows delegados a SystemMonitor ─────────────────────
    val systemMetrics: StateFlow<SystemMetrics> get() = systemMonitor.systemMetrics
    val pointerSpeed: StateFlow<Int> get() = systemMonitor.pointerSpeed
    val externalDevicesConnected: StateFlow<Boolean> get() = systemMonitor.externalDevicesConnected

    // ─── StateFlows delegados a GameSessionManager ────────────────
    val fsmState: StateFlow<FsmState> get() = sessionManager.fsmState
    val isBoostActive: StateFlow<Boolean> get() = sessionManager.isBoostActive
    val shizukuConnected: StateFlow<Boolean> get() = sessionManager.shizukuConnected
    val simulatedGame: StateFlow<String?> get() = sessionManager.simulatedGame
    val isMobiladorActive: StateFlow<Boolean> get() = sessionManager.isMobiladorActive
    val adsPointerActive: StateFlow<Boolean> get() = sessionManager.adsPointerActive
    val availableGovernors: StateFlow<List<String>> get() = sessionManager.availableGovernors

    // ─── StateFlows desde DB ──────────────────────────────────────
    val profilesFlow: Flow<List<ProfileEntity>> = profileDao.getAllProfilesFlow()
    val logsFlow: Flow<List<LogEntity>> = logDao.getRecentLogsFlow()

    // Game list cache
    private val _gamesCache = mutableMapOf<String, String>()

    private val initTimeoutMs = 30_000L

    init {
        repositoryScope.launch {
            try {
                withTimeout(initTimeoutMs) {
                    // 1. Inicializar GameSessionManager
                    sessionManager.initialize()

                    // 2. Cargar datos
                    loadGamesFromDatabase()
                    seedDefaultProfilesIfEmpty()

                    // 3. Configurar callbacks entre managers
                    setupManagerCallbacks()

                    // 4. Iniciar monitores
                    thermalController.setup()
                    systemMonitor.start()
                    watchdogManager.start()
                    dependencyStateManager.start()
                    resourceGovernor.start()

                    // 5. Iniciar GameDetector (auto-detección de juegos)
                    gameDetector.onGameDetected = { pkg ->
                        Log.d("GameDetector", "🎮 Auto-detect: $pkg")
                        sessionManager.setForegroundApp(pkg)
                        // (d) Re-mostrar overlay si el boost está activo pero la vista fue
                        // ocultada (p.ej. botón x) y volviste al juego sin pasar por la app.
                        // No enciende boost ni perf mode: solo refleja el estado ya activo.
                        val fpm = FloatingPanelManager.getInstance(context)
                        if (sessionManager.isBoostActive.value && !fpm.isOverlayVisible()) {
                            fpm.show()
                        }
                    }
                    gameDetector.onGameExited = {
                        Log.d("GameDetector", "Salida de juego detectada")
                        sessionManager.onForegroundAppLost()
                    }
                    gameDetector.start()

                    // 6. Vincular SystemMonitor con GameSessionManager
                    systemMonitor.getBoostActive = { sessionManager.isBoostActive.value }
                    systemMonitor.getActiveGame = { sessionManager.getSimulatedGame() }
                    systemMonitor.getFsmState = { sessionManager.fsmState.value }
                    systemMonitor.getActiveProfile = { cachedActiveProfile }
                    systemMonitor.onExternalDeviceConnectedWhileGaming = { connected ->
                        sessionManager.onExternalDeviceDetectedWhileGaming(connected)
                    }

                    // 5.5. Escuchar cambios de perfil activo
                    launch {
                        profileDao.getAllProfilesFlow().collect { profiles ->
                            cachedActiveProfile = profiles.firstOrNull { it.isActive }
                        }
                    }

                    // 6. Vincular ThermalController con GameSessionManager
                    thermalController.onCriticalHeat = {
                        Log.w("GameBoostRepo", "🔥 Thermal critical — disabling boost")
                        if (sessionManager.isBoostActive.value) {
                            sessionManager.toggleBoost()
                        }
                    }
                    thermalController.onSevereHeat = {
                        Log.w("GameBoostRepo", "⚠️ Thermal severe — throttling")
                    }

                    addLog("INFO", "System", "GameBoost Pro Repository initialized (facade mode)")
                }
            } catch (e: TimeoutCancellationException) {
                addLog("WARN", "System", "Init timed out. Forcing READY.")
            } catch (e: Exception) {
                addLog("WARN", "System", "Init error: ${e.message}. Forcing READY.")
            }
        }
    }

    private fun setupManagerCallbacks() {
        sessionManager.onProfileApplied = { profile ->
            try {
                val panel = FloatingPanelManager.getInstance(context)
                panel.updateProfile(profile)
            } catch (e: Exception) {
                Log.w("GameBoostRepo", "Error updating panel: ${e.message}")
            }
        }
    }

    // ─── Métodos delegados a GameSessionManager ─────────────────

    fun toggleBoost() = sessionManager.toggleBoost()
    fun toggleMobilador() = sessionManager.toggleMobilador()
    fun toggleShizukuState() = sessionManager.recheckShizuku()
    fun setForegroundApp(packageName: String) = sessionManager.setForegroundApp(packageName)

    /**
     * Punto único para cambios de app en foreground (GameDetector y
     * UnifiedAccessibilityService). Filtra por juego para que apps que no lo son
     * (launcher, capturas, tiendas, gameassist) no disparen auto-detección ni
     * pisen la selección manual del usuario.
     */
    fun onForegroundAppChanged(packageName: String) {
        if (gameDetector.isGamePackage(packageName)) {
            sessionManager.setForegroundApp(packageName)
        } else {
            sessionManager.onForegroundAppLost()
        }
    }

    fun simulateGameLaunch(packageName: String?) {
        repositoryScope.launch { sessionManager.simulateGameLaunch(packageName) }
    }
    fun setActiveProfile(id: String, isManual: Boolean = true) =
        sessionManager.setActiveProfile(id, isManual)
    fun quickClean() = sessionManager.quickClean()
    fun setFsmState(state: FsmState) = sessionManager.setFsmState(state)

    suspend fun executePrivilegedCommands(commands: List<String>, tag: String = "Exec") =
        sessionManager.executePrivilegedCommands(commands, tag)

    suspend fun executePrivilegedCommand(command: String) =
        sessionManager.executePrivilegedCommands(command.split(";").map { it.trim() }.filter { it.isNotEmpty() }, tag = "LegacyExec")

    // ─── Toggles ─────────────────────────────────────────────────
    fun toggleAggressiveOptimization() = aggressiveOptimization.toggle()
    fun toggleThermalWatchdog() = thermalWatchdog.toggle()
    fun toggleAutoDetectGames() = autoDetectGames.toggle()
    fun toggleDeepSleep() = deepSleep.toggle()
    fun toggleForceScrcpyMode() = forceScrcpyMode.toggle()
    fun toggleMsaa() = msaa.toggle()

    // ─── Settings ────────────────────────────────────────────────

    fun setDpi(dpi: Int) {
        val clampedDpi = dpi.coerceIn(1, PreferenceManager.MAX_DPI)
        PreferenceManager.saveDpi(context, clampedDpi)
        repositoryScope.launch {
            val res = ShizukuExecutor.runCommand("wm density $clampedDpi")
            if (res.isFailure) {
                addLog("WARN", "DPI", "Cambio de DPI requiere Shizuku")
            }
        }
    }

    fun setPointerSpeed(speed: Int) {
        val raw = systemMonitor.mapPercentToRawSpeed(speed)
        repositoryScope.launch {
            val cmd = "settings put system pointer_speed $raw"
            val res = ShizukuExecutor.runCommand(cmd)
            if (res.isFailure) {
                try {
                    android.provider.Settings.System.putInt(
                        context.contentResolver, "pointer_speed", raw
                    )
                } catch (e: Exception) {
                    addLog("WARN", "Pointer", "Fallo: ${e.message}")
                }
            }
        }
    }

    // ─── Game List Management ────────────────────────────────────

    private suspend fun loadGamesFromDatabase() {
        val gameDao = database.gameDao()
        val games = gameDao.getAllGames()
        if (games.isEmpty()) {
            seedDefaultGames(gameDao)
            for (game in gameDao.getAllGames()) {
                _gamesCache[game.packageName] = game.displayName
                sessionManager.addGameToCache(game.packageName, game.displayName)
            }
        } else {
            for (game in games) {
                _gamesCache[game.packageName] = game.displayName
                sessionManager.addGameToCache(game.packageName, game.displayName)
            }
        }
    }

    private suspend fun seedDefaultGames(gameDao: com.example.data.database.GameDao) {
        try {
            val inputStream = context.resources.openRawResource(com.example.R.raw.default_games)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(jsonString)
            val gamesToInsert = mutableListOf<GameEntity>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                gamesToInsert.add(GameEntity(obj.getString("packageName"), obj.getString("displayName")))
            }
            gameDao.insertGames(gamesToInsert)
        } catch (e: Exception) {
            addLog("ERROR", "System", "Failed to seed games: ${e.message}")
        }
    }

    private suspend fun seedDefaultProfilesIfEmpty() {
        if (profileDao.getProfileCount() > 0) return

        val defaults = listOf(
            ProfileEntity("ff_mouse", "FF Mouse Duo", "Optimized for Free Fire with mouse/keyboard.", "⌨️", "Performance", "120 Hz", true, false),
            ProfileEntity("extreme", "Extreme Performance", "Full performance for competitive gaming.", "🔥", "Unlocked", "Adaptive", false, false),
            ProfileEntity("free_fire_touch", "Free Fire Touch", "Optimized touch settings for Free Fire.", "🎯", "Schedutil", "90 Hz", false, false),
            ProfileEntity("balanced", "Balanced", "Mixed mode for general use and gaming.", "⚖️", "Schedutil", "60/90 Hz", false, false),
            ProfileEntity("battery_saver", "Battery Saver", "Low power consumption, extended gameplay.", "🔋", "Powersave", "60 Hz", false, false)
        )
        for (p in defaults) profileDao.insertProfile(p)
    }

    suspend fun addGame(packageName: String, displayName: String) {
        val gameDao = database.gameDao()
        gameDao.insertGame(GameEntity(packageName, displayName))
        _gamesCache[packageName] = displayName
        sessionManager.addGameToCache(packageName, displayName)
    }

    suspend fun removeGame(packageName: String) {
        val displayName = _gamesCache[packageName] ?: packageName
        database.gameDao().deleteGame(GameEntity(packageName, displayName))
        _gamesCache.remove(packageName)
        sessionManager.removeGameFromCache(packageName)
    }

    suspend fun restoreDefaultGames() {
        val gameDao = database.gameDao()
        gameDao.clearAll()
        _gamesCache.clear()
        sessionManager.clearGamesCache()
        seedDefaultGames(gameDao)
        for (game in gameDao.getAllGames()) {
            _gamesCache[game.packageName] = game.displayName
            sessionManager.addGameToCache(game.packageName, game.displayName)
        }
    }

    // ─── Profile Management ──────────────────────────────────────

    suspend fun addCustomProfile(name: String, description: String, governor: String, refreshRate: String, icon: String) {
        val id = name.lowercase().replace(" ", "_")
        profileDao.insertProfile(ProfileEntity(id, name, description, icon, governor, refreshRate, isActive = false, isCustom = true))
    }

    suspend fun deleteProfile(id: String) {
        profileDao.getProfileById(id)?.let { if (it.isCustom) profileDao.deleteProfile(it) }
    }

    // ─── Power Optimizer (manual) ────────────────────────────────

    fun runBootOptimizer() = powerOptimizer.bootOptimizer()
    fun runDexOptimize(packageName: String) = powerOptimizer.dexOptimize(packageName)

    // ─── Metrics refresh ─────────────────────────────────────────

    fun refreshMetrics() { /* monitoring loop handles this */ }

    fun getDiagnosticReport(): String {
        return "FSM: ${sessionManager.fsmState.value}\nShizuku: ${sessionManager.shizukuConnected.value}\nBoost: ${sessionManager.isBoostActive.value}"
    }

    // ─── Clear Logs ──────────────────────────────────────────────

    suspend fun clearLogs() = logDao.clearLogs()

    // ─── Logging ─────────────────────────────────────────────────

    fun addLog(level: String, tag: String, message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        repositoryScope.launch {
            logDao.insertLog(LogEntity(timestamp = timestamp, level = level, tag = tag, message = message))
            Log.d(tag, "[$level] $message")
        }
    }

    fun logAsync(level: String, tag: String, message: String) = addLog(level, tag, message)

    // ─── Map pointer speed (delegado a SystemMonitor) ───────────
    fun mapPercentToRawSpeed(percent: Int): Int = systemMonitor.mapPercentToRawSpeed(percent)

    // ─── Shizuku diagnosis ───────────────────────────────────────

    fun getShizukuDiagnosis(context: Context): String {
        return buildString {
            appendLine("=== Shizuku Diagnosis ===")
            appendLine("Shizuku connected: ${sessionManager.shizukuConnected.value}")
            appendLine("Shizuku executor ready: ${ShizukuExecutor.isReady()}")
            appendLine("FSM State: ${sessionManager.fsmState.value}")
            appendLine("Boost active: ${sessionManager.isBoostActive.value}")
            appendLine("Active game: ${sessionManager.getSimulatedGame() ?: "none"}")
            appendLine("Mobilador: ${sessionManager.isMobiladorActive.value}")
            appendLine("Thermal status: ${thermalController.thermalStatus.value}")
        }
    }

    // ─── Singleton ───────────────────────────────────────────────

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: GameBoostRepository? = null

        fun getInstance(context: Context): GameBoostRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = GameBoostRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}

package com.example.manager

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Detección automática de juegos en primer plano.
 * 
 * Capas de detección:
 * 1. [UsageStatsManager] — API oficial de Android para consultar la app en foreground
 * 2. Shell fallback via `dumpsys window` — cuando UsageStats no tiene permisos
 * 
 * Integrado con [ProcessLifecycleOwner] para pausar el polling cuando la app
 * está en segundo plano y reanudarlo al volver al frente — ahorra batería.
 * 
 * Uso:
 *   val detector = GameDetector(context)
 *   detector.onGameDetected = { pkg -> session.setForegroundApp(pkg) }
 *   detector.start()
 *   detector.stop()
 */
class GameDetector(private val context: Context) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "GameDetector"
        private const val POLL_INTERVAL_MS = 3000L  // Cada 3s en foreground
        private const val BACKGROUND_INTERVAL_MS = 30_000L  // Cada 30s en background
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Última app detectada en foreground (para deduplicación)
    @Volatile
    private var lastForegroundApp: String? = null

    // Job del polling loop (se cancela/recrea con el lifecycle)
    private var pollJob: Job? = null

    // Flag de control general
    private var isStarted = false

    // PR2 (B4): la registration en start() es ASÍNCRONA (scope → Main). Sin este
    // flag, stop() quitaba un observer que pudo no haberse registrado jamás, y una
    // registration en vuelo podía aterrizar DESPUÉS del remove → observer fantasma.
    @Volatile
    private var observerRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Callbacks
    var onGameDetected: ((packageName: String) -> Unit)? = null
    var onGameExited: (() -> Unit)? = null

    // Paquetes ignorados (sistema, launcher, teclados, etc.)
    // R1 (C2): el polling es el ÁRBITRO de salida — su ignore list debe cubrir
    // las ventanas transitorias del OEM (evidencia forense 2026-09-13:
    // OVERLAY-DISAPPEAR-FORENSIC-2026-09-13.md). com.android.vending ya estaba;
    // se añaden los otros 2 paquetes evidenciados (no se amplía más allá).
    private val ignoredPackages = setOf(
        "android", "com.android.systemui", "com.android.settings",
        "com.android.launcher3", "com.google.android.apps.nexuslauncher",
        "com.google.android.inputmethod.latin", "com.touchtype.swiftkey",
        "com.samsung.android.honeyboard", "com.google.android.gms",
        "com.android.permissioncontroller", "com.android.vending",
        "com.google.android.gsf", "com.google.android.googlequicksearchbox",
        "com.android.deskclock", "com.android.calendar",
        "com.android.phone", "com.android.contacts",
        "com.google.android.apps.messaging",
        "com.zjx.ztezscreenshot",   // overlay de captura del ZTE (transitoria)
        "cn.nubia.gameassist"       // game assist del OEM (transitoria)
    )

    // Cache de juegos conocidos (package -> displayName)
    private val knownGames = mapOf(
        "com.dts.freefireth" to "Free Fire",
        "com.dts.freefiremax" to "Free Fire MAX",
        "com.garena.game.kgth" to "Free Fire (Garena)",
        "com.tencent.ig" to "PUBG Mobile",
        "com.tencent.tmgp.pubgm" to "PUBG Mobile (CN)",
        "com.tencent.tmgp.sgame" to "Honor of Kings",
        "com.pubg.krmobile" to "PUBG Mobile (KR)",
        "com.rekoo.pubgm" to "PUBG Mobile (Global)",
        "com.mobile.legends" to "Mobile Legends",
        "com.miHoYo.GenshinImpact" to "Genshin Impact",
        "com.miHoYo.Yuanshen" to "Genshin Impact (CN)",
        "com.activision.callofduty.shooter" to "COD Mobile",
        "com.tencent.tmgp.cod" to "COD Mobile (CN)",
        "com.ea.gp.fifamobile" to "FIFA Mobile",
        "com.kiloo.subwaysurf" to "Subway Surfers",
        "com.mojang.minecraftpe" to "Minecraft",
        "com.supercell.clashofclans" to "Clash of Clans",
        "com.supercell.brawlstars" to "Brawl Stars",
        "com.supercell.royale" to "Clash Royale",
        "com.roblox.client" to "Roblox",
        "com.epicgames.fortnite" to "Fortnite",
        "com.king.candycrushsaga" to "Candy Crush",
        "com.valvesoftware.android.steam.community" to "Steam",
        "com.discord" to "Discord"
    )

    // ─── Lifecycle (DefaultLifecycleObserver) ────────────────────

    override fun onResume(owner: LifecycleOwner) {
        Log.d(TAG, "App en foreground — polling cada ${POLL_INTERVAL_MS}ms")
        restartPoll(POLL_INTERVAL_MS)
    }

    override fun onPause(owner: LifecycleOwner) {
        Log.d(TAG, "App en background — polling reducido a cada ${BACKGROUND_INTERVAL_MS}ms")
        restartPoll(BACKGROUND_INTERVAL_MS)
    }

    // ─── Start / Stop ────────────────────────────────────────────

    fun start() {
        if (isStarted) return
        isStarted = true

        // Registrarse en ProcessLifecycleOwner (requiere Main thread)
        scope.launch {
            withContext(Dispatchers.Main) {
                try {
                    ProcessLifecycleOwner.get().lifecycle.addObserver(this@GameDetector)
                    observerRegistered = true
                    Log.d(TAG, "GameDetector registrado en ProcessLifecycleOwner")
                } catch (e: Exception) {
                    Log.e(TAG, "Error registrando en ProcessLifecycleOwner: ${e.message}")
                }
            }
        }

        // Iniciar polling inicial
        startPoll(POLL_INTERVAL_MS)
        Log.d(TAG, "GameDetector iniciado")
    }

    private fun startPoll(intervalMs: Long) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    pollForegroundApp()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
                delay(intervalMs)
            }
        }
    }

    private fun restartPoll(intervalMs: Long) {
        pollJob?.cancel()
        if (isStarted) {
            startPoll(intervalMs)
        }
    }

    private suspend fun pollForegroundApp() {
        val currentApp = getForegroundApp()

        if (currentApp != null && currentApp != lastForegroundApp) {
            // R1 (C2): el launcher es el destino canónico de "salí del juego".
            // Está en ignoredPackages (no debe disparar ENTRADA), pero si venimos
            // de un juego sí es una SALIDA real. Sin esta regla, al demotar la
            // autoridad de salida de Accessibility (C1), la salida al launcher
            // quedaría sin árbitro (el poll la ignoraba por completo).
            if (isLauncherPackage(currentApp)) {
                val wasInGame = lastForegroundApp != null && isGamePackage(lastForegroundApp!!)
                if (wasInGame) {
                    // R1 (C2): UsageStats es eventualmente consistente — durante el
                    // arranque de un juego (splash 5-15 s) puede seguir reportando el
                    // launcher aunque el juego YA esté en foreground. Contrastar con el
                    // focus real (vía shell) antes de declarar la salida; si el shell
                    // no está disponible, se mantiene el comportamiento previo (salida).
                    // Hallado en validación device (21:56:40: entrada a11y a FF frío,
                    // usage=launcher durante splash → salida falsa y re-flap).
                    val focusApp = getForegroundAppShellSuspend()
                    if (focusApp != null && isGamePackage(focusApp)) {
                        Log.d(TAG, "UsageStats stale (launcher) pero focus=$focusApp — no hay salida (splash)")
                        lastForegroundApp = focusApp
                    } else {
                        lastForegroundApp = currentApp
                        Log.d(TAG, "🏠 Launcher en foreground tras juego — salida real")
                        onGameExited?.invoke()
                    }
                }
                // si no venimos de un juego → ignorar (comportamiento previo)
            } else if (!ignoredPackages.contains(currentApp) &&
                currentApp != context.packageName) {

                lastForegroundApp = currentApp
                Log.d(TAG, "Foreground app cambiada: $currentApp")

                if (isGamePackage(currentApp)) {
                    Log.d(TAG, "🎮 Juego detectado: $currentApp")
                    onGameDetected?.invoke(currentApp)
                } else {
                    Log.d(TAG, "App no juego: $currentApp")
                    onGameExited?.invoke()
                }
            }
        }
    }

    /**
     * R1 (C2): el launcher (home screen) es el destino canónico de salida real
     * de un juego. Distingue "usuario fue al home" (salida) de ventanas del
     * sistema transitorias (systemui, capturas, keyguards — NO launchers).
     */
    internal fun isLauncherPackage(packageName: String): Boolean {
        return packageName.contains("launcher")
    }

    /**
     * R1 (C2): sondeo inmediato único, sin esperar el próximo tick del ciclo.
     * Lo invoca el exit-hint de Accessibility: la propuesta de salida se confirma
     * (o se descarta) con una lectura real del foreground en milisegundos, en vez
     * de esperar hasta 3 s el próximo tick. NO ejecuta el exit por sí mismo —
     * solo corre la misma lógica del arbiter (pollForegroundApp).
     */
    fun pokePoll() {
        scope.launch { pollForegroundApp() }
    }

    /**
     * R1 (C2): sincroniza la caché de dedup del árbitro cuando Accessibility
     * detectó la entrada por su ruta rápida. Sin esto, si el juego permanece en
     * foreground MENOS de un ciclo de polling, el detector nunca registra la
     * entrada y su arbitraje de salida descarta el launcher ("sin juego previo")
     * — el boost quedaría pegado. Hallado en validación en device (21:49:42:
     * entrada a11y, salida a t+3s ignorada; entrada >1 ciclo sí funcionaba).
     */
    fun notifyForegroundGame(packageName: String) {
        lastForegroundApp = packageName
    }

    // ─── Detección de foreground app ────────────────────────────

    /**
     * Obtiene la app en foreground usando UsageStatsManager.
     * Si no tiene permisos, usa fallback shell.
     */
    private suspend fun getForegroundApp(): String? {
        // Capa 1: UsageStatsManager (API oficial)
        if (hasUsageStatsPermission()) {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val beginTime = endTime - 2000

            try {
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    beginTime,
                    endTime
                )

                if (stats != null && stats.isNotEmpty()) {
                    val sorted = stats
                        .filter { it.lastTimeUsed >= beginTime }
                        .sortedByDescending { it.lastTimeUsed }

                    return sorted.firstOrNull()?.packageName
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "UsageStats sin permiso, usando fallback shell")
            } catch (e: Exception) {
                Log.w(TAG, "UsageStats error: ${e.message}")
            }
        }

        // Capa 2: Shell fallback via dumpsys window (ahora es suspend, sin runBlocking)
        return getForegroundAppShellSuspend()
    }

    /**
     * Fallback shell como suspend function (evita runBlocking).
     */
    private suspend fun getForegroundAppShellSuspend(): String? {
        return try {
            val result = ShizukuExecutor.runCommand(
                "dumpsys window | grep -m1 mCurrentFocus"
            )
            val output = result.getOrNull() ?: return null
            val match = Regex("""mCurrentFocus=.*?([\w.\-]+)/""").find(output)
            match?.groupValues?.getOrNull(1)
        } catch (e: Exception) {
            null
        }
    }

    // ─── Game detection ─────────────────────────────────────────

    // PR4 (C1): Exact-match contra el inventario + set curado EXTRA_GAME_PACKAGES.
    // La heurística de substrings anterior (p.ej. `contains("king.")`) matcheaba
    // cualquier paquete con ese substring (p.ej. "com.something.king.app",
    // "monkey.app"), y lo propio "tencent"/"garena"/"mapper" en apps no juego.
    // La salida de emergencia para un juego no listado es "agregar juego
    // manualmente" en la UI (GameBoostRepository.addGame).
    // NOTE: las keys de DISABLE no van aquí — onForegroundAppLost/arbitraje vive en pollForegroundApp.
    internal fun isGamePackage(packageName: String): Boolean {
        if (knownGames.containsKey(packageName)) return true
        return EXTRA_GAME_PACKAGES.contains(packageName)
    }

    /**
     * Paquetes de juego adicionales curados a mano (exact-match). TODO: promoción
     * automática cuando una app cumpla 3+ heurísticas de uso sostenido en foreground
     * es trabajo de PR futuro — esto es lista, no inferencia.
     */
    private val EXTRA_GAME_PACKAGES: Set<String> = setOf(
        "com.dts.freefireth",
        "com.dts.freefiremax",
        "com.garena.game.kgth",
        "com.tencent.ig",
        "com.tencent.tmgp.pubgm",
        "com.tencent.tmgp.sgame",
        "com.pubg.krmobile",
        "com.tencent.tmgp.cod",
        "com.activision.callofduty.shooter",
        "com.supercell.clashofclans",
        "com.supercell.brawlstars",
        "com.supercell.royale",
        "com.roblox.client",
        "com.epicgames.fortnite",
        "com.mojang.minecraftpe",
        "com.kiloo.subwaysurf",
        "com.mobile.legends",
        "com.ea.gp.fifamobile",
        "com.miHoYo.GenshinImpact",
        "com.king.candycrushsaga",
        "com.garena.game.codm",
        "com.garena.game.kgvn",
        "com.vng.pubgmobile",
        "com.ea.gp.apexlegendsmobilefps"
    )

    private fun hasUsageStatsPermission(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                val mode = appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
                mode == android.app.AppOpsManager.MODE_ALLOWED
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    // ─── Public API ─────────────────────────────────────────────

    fun resetState() {
        lastForegroundApp = null
    }

    /**
     * Devuelve el paquete actual en primer plano (UsageStats o fallback shell).
     * Usado por GameBoostService para re-detectar el juego al arrancar el servicio
     * tras la muerte del proceso.
     */
    suspend fun getCurrentForegroundApp(): String? = getForegroundApp()

    fun stop() {
        isStarted = false
        // PR2 (B4): cancelar ANTES de desregistrar — un start() en vuelo
        // (withContext(Main) aún no ejecutado) queda cancelado y no puede
        // re-registrar el observer DESPUÉS del remove.
        scope.cancel()  // Cancela pollJob y todos los hijos automáticamente
        if (observerRegistered) {
            observerRegistered = false
            // removeObserver es @MainThread (LifecycleRegistry.enforceMainThread):
            // el try/catch viejo tragaba el IllegalStateException cuando stop()
            // corría fuera de Main, dejando el observer registrado para siempre.
            mainHandler.post {
                try {
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deregistrando de ProcessLifecycleOwner: ${e.message}")
                }
            }
        }
        Log.d(TAG, "GameDetector detenido")
    }
}

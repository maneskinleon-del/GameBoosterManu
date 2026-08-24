package com.example.manager

import android.app.usage.UsageStatsManager
import android.content.Context
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

    // Callbacks
    var onGameDetected: ((packageName: String) -> Unit)? = null
    var onGameExited: (() -> Unit)? = null

    // Paquetes ignorados (sistema, launcher, teclados, etc.)
    private val ignoredPackages = setOf(
        "android", "com.android.systemui", "com.android.settings",
        "com.android.launcher3", "com.google.android.apps.nexuslauncher",
        "com.google.android.inputmethod.latin", "com.touchtype.swiftkey",
        "com.samsung.android.honeyboard", "com.google.android.gms",
        "com.android.permissioncontroller", "com.android.vending",
        "com.google.android.gsf", "com.google.android.googlequicksearchbox",
        "com.android.deskclock", "com.android.calendar",
        "com.android.phone", "com.android.contacts",
        "com.google.android.apps.messaging"
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
            if (!ignoredPackages.contains(currentApp) &&
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

    internal fun isGamePackage(packageName: String): Boolean {
        if (knownGames.containsKey(packageName)) return true

        return packageName.contains("freefire") ||
                packageName.contains("garena") ||
                packageName.contains("tencent") ||
                packageName.contains("miHoYo") ||
                packageName.contains("supercell") ||
                packageName.contains("mojang") ||
                packageName.contains("roblox") ||
                packageName.contains("epicgames") ||
                packageName.contains("activision") ||
                packageName.contains("ea.gp") ||
                packageName.contains("king.") ||
                packageName.contains("kiloo") ||
                packageName.contains("gg.mouse") ||
                packageName.contains("ggmouse") ||
                packageName.contains("mapper") ||
                packageName.contains("flydigi") ||
                packageName.contains("gamesir") ||
                packageName.contains("mantis") ||
                packageName.contains("panda") ||
                packageName.contains("gamewolf") ||
                packageName.contains("scrcpy") ||
                packageName.contains("vphone")
    }

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

    fun stop() {
        isStarted = false
        // Desregistrar del lifecycle para evitar leaks
        try {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        } catch (_: Exception) {}
        scope.cancel()  // Cancela pollJob y todos los hijos automáticamente
        Log.d(TAG, "GameDetector detenido")
    }
}

package com.example.manager

import com.example.data.repository.GameBoostRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * PowerOptimizer — Optimizaciones de energía y procesos tomadas de Neon Core.
 *
 * ## Funciones
 * - Force Doze — Fuerza el estado de suspensión profunda (ahorra batería entre partidas)
 * - Force Stop Background Apps — Cierra apps en segundo plano (libera RAM + CPU)
 * - Dex Optimize — Compila la app del juego a código máquina (carga más rápida)
 * - Boot Optimizer — Ejecuta optimización de dex en segundo plano (bg-dexopt-job)
 *
 * ## Comandos
 * - `dumpsys deviceidle force-idle` — Force Doze
 * - `am force-stop <pkg>` — Cierra apps en background ✅ (verificado)
 * - `cmd package compile -f -m speed <pkg>` — Dex compile
 * - `cmd package bg-dexopt-job` — Boot optimizer
 *
 * ## Nota
 * `cmd activity idle-systems` fue descartado porque NO EXISTE en AOSP
 * (verificado en ZTE Neo 2 5G Android 14). Se reemplazó por `am force-stop`.
 */
class PowerOptimizer(
    private val repository: GameBoostRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Flags ────────────────────────────────────────────────────

    @Volatile
    private var bootOptimizerRan = false

    @Volatile
    private var lastDexOptimizeTime: Long = 0L

    private val DEX_OPTIMIZE_COOLDOWN = 7 * 24 * 60 * 60 * 1000L // 7 días entre compilaciones

    // ── API Pública ──────────────────────────────────────────────

    /**
     * Fuerza el estado Doze (suspensión profunda) en el dispositivo.
     * Útil cuando la pantalla está apagada durante una partida (ej: carga larga, entre rondas).
     * Ahorra batería significativamente.
     *
     * Se integra con ResourceGovernor (screen off handler).
     */
    fun forceDoze() {
        repository.logAsync("DEBUG", "PowerOpt", "💤 Forzando Doze mode...")
        scope.launch {
            val result = ShizukuExecutor.runCommand("dumpsys deviceidle force-idle")
            if (result.isSuccess) {
                repository.logAsync("INFO", "PowerOpt", "💤 Doze mode activado")
            } else {
                repository.logAsync("WARN", "PowerOpt", "❌ Force Doze falló: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    /**
     * Cierra apps en segundo plano para liberar RAM + CPU.
     *
     * Usa `am force-stop` (verificado que funciona desde shell UID 2000)
     * en lugar de `cmd activity idle-systems` que no existe en AOSP.
     *
     * @param packageName Opcional: si se pasa, NO cierra este paquete (el juego activo)
     */
    fun suspendCachedApps(packageName: String? = null) {
        repository.logAsync("DEBUG", "PowerOpt", "💤 Cerrando apps en segundo plano...")
        scope.launch {
            var successCount = 0
            var failCount = 0

            // Obtener procesos en background
            val result = ShizukuExecutor.runCommand(
                "dumpsys activity processes | grep 'ProcessRecord{' | grep -v 'pid=0' | grep -oE ':[0-9a-f]+ [^ ]+' | awk '{print \$2}'"
            )

            val processes = if (result.isSuccess) {
                result.getOrNull()?.split("\n")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() && it.contains(".") && !it.startsWith("com.android.") && !it.startsWith("android") }
                    ?.distinct()
                    ?.filter { it != packageName }
                    ?.take(15)
                    ?: emptyList()
            } else {
                // Fallback: obtener procesos vía ps
                repository.logAsync("WARN", "PowerOpt", "⚠️ dumpsys falló, usando fallback ps...")
                val fallbackResult = ShizukuExecutor.runCommand("ps -A | grep u0_a | awk '{print \$NF}' | grep -E '^com\\.' | head -20")
                if (fallbackResult.isFailure) {
                    repository.logAsync("WARN", "PowerOpt", "❌ Fallback también falló")
                    return@launch
                }
                fallbackResult.getOrNull()
                    ?.split("\n")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() && it.contains(".") }
                    ?.distinct()
                    ?.filter { it != packageName }
                    ?.take(10)
                    ?: emptyList()
            }

            if (processes.isEmpty()) {
                repository.logAsync("DEBUG", "PowerOpt", "📭 No hay procesos en segundo plano para cerrar")
                return@launch
            }

            repository.logAsync("DEBUG", "PowerOpt", "📋 ${processes.size} procesos candidatos: ${processes.take(5).joinToString(", ")}...")

            // Cerrar cada proceso con am force-stop ✅ (verificado que funciona)
            for (pkg in processes) {
                val forceResult = ShizukuExecutor.runCommand("am force-stop $pkg")
                if (forceResult.isSuccess) {
                    successCount++
                } else {
                    failCount++
                    repository.logAsync("DEBUG", "PowerOpt", "⚠️ Fallo al cerrar $pkg: ${forceResult.exceptionOrNull()?.message?.take(60)}")
                }
                delay(50)
            }

            repository.logAsync("INFO", "PowerOpt", "💤 $successCount apps cerradas, $failCount fallos")
        }
    }

    /**
     * Compila el paquete del juego a código máquina (modo speed).
     * Esto mejora los tiempos de carga y reduce el lag durante el juego.
     *
     * Solo se ejecuta una vez cada 7 días por paquete para no desgastar
     * el almacenamiento (la compilación genera archivos .odex grandes).
     *
     * @param packageName Package del juego a compilar
     */
    fun dexOptimize(packageName: String) {
        val now = System.currentTimeMillis()
        if (now - lastDexOptimizeTime < DEX_OPTIMIZE_COOLDOWN) {
            repository.logAsync("DEBUG", "PowerOpt", "⏭️ Dex optimize en cooldown (7 días). Saltando.")
            return
        }

        repository.logAsync("INFO", "PowerOpt", "⚙️ Compilando $packageName a speed (dex2oat)...")
        scope.launch {
            val result = ShizukuExecutor.runCommand("cmd package compile -f -m speed $packageName")
            if (result.isSuccess) {
                lastDexOptimizeTime = now
                repository.logAsync("INFO", "PowerOpt", "✅ $packageName compilado a speed")
            } else {
                repository.logAsync("WARN", "PowerOpt", "❌ Dex compile falló: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    /**
     * Ejecuta optimización de dex en segundo plano (bg-dexopt-job).
     * Android ya ejecuta esto periódicamente, pero forzarlo al arrancar
     * la app asegura que las apps del sistema estén optimizadas.
     *
     * Solo se ejecuta UNA vez por sesión de la app (no por cada game boost).
     */
    fun bootOptimizer() {
        if (bootOptimizerRan) {
            repository.logAsync("DEBUG", "PowerOpt", "⏭️ Boot optimizer ya ejecutado esta sesión.")
            return
        }

        repository.logAsync("INFO", "PowerOpt", "🚀 Ejecutando boot optimizer (bg-dexopt-job)...")
        scope.launch {
            val result = ShizukuExecutor.runCommand("cmd package bg-dexopt-job")
            if (result.isSuccess) {
                bootOptimizerRan = true
                repository.logAsync("INFO", "PowerOpt", "✅ Boot optimizer completado")
            } else {
                repository.logAsync("WARN", "PowerOpt", "❌ Boot optimizer falló: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    /**
     * Resetea el flag de boot optimizer (útil para testing).
     */
    fun resetBootOptimizerFlag() {
        bootOptimizerRan = false
    }

    /**
     * Diagnóstico del estado del PowerOptimizer.
     */
    suspend fun diagnose(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ Power Optimizer Diagnosis ═══")

        // Verificar estado de deviceidle
        val idleState = ShizukuExecutor.runCommand("dumpsys deviceidle get deep")
        sb.appendLine("Device Idle: ${idleState.getOrNull()?.trim() ?: "No disponible"}")

        sb.appendLine("Boot Optimizer: ${if (bootOptimizerRan) "✅ Ejecutado" else "⏳ Pendiente"}")
        sb.appendLine("Dex Cooldown: ${if (System.currentTimeMillis() - lastDexOptimizeTime < DEX_OPTIMIZE_COOLDOWN) "⏳ En cooldown" else "✅ Listo"}")

        sb.appendLine("══════════════════════════════════")
        return sb.toString()
    }
}

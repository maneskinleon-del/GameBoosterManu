package com.example.manager

import com.example.data.repository.GameBoostRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * NetworkOptimizer — Optimizaciones de red tomadas de Neon Core.
 *
 * ## Funciones
 * - Private DNS (Google/Cloudflare) — Resolución DNS más rápida y segura
 * - Wi-Fi optimizations — bajo consumo, baja latencia, priorizar WiFi sobre BT
 *
 * ## Comandos aplicados (verificados ✅ en ZTE Neo 2 5G vía settings put global)
 * - `settings put global private_dns_spec dns.google` — Private DNS
 * - `settings put global wifi_power_save 0` / `wifi_low_latency_mode 1`
 * - `settings put global wifi_bt_coexistence 0` — priorizar WiFi sobre BT
 *
 * ## ⚠️ Nota: sysctl -w NO se usa
 * `sysctl -w` (TCP BBR, TCP Fast Open, etc.) requiere capacidades de kernel
 * bloqueadas por SELinux desde shell uid 2000 (Shizuku) en kernels stock.
 * Fue removido de APPLY/RESTORE. `diagnose()` solo LEE el estado actual
 * (sysctl sin -w) para informar, no para modificar.
 */
class NetworkOptimizer(
    private val repository: GameBoostRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Comandos ─────────────────────────────────────────────────

    private val APPLY_COMMANDS = listOf(
        // --- DNS y Rutas (Neon Core) ---
        "settings put global private_dns_spec dns.google",
        "settings put global wifi_watchdog_on 0",
        "settings put global wifi_scan_interval_ms 300000", // 5 min
        
        // --- Latencia (Neon Core) ---
        "settings put global wifi_power_save 0",
        "settings put global wifi_low_latency_mode 1",
        "settings put global wifi_bt_coexistence 0" // Priorizar WiFi sobre BT
    )

    private val RESTORE_COMMANDS = listOf(
        // Limpiar private DNS para que vuelva a automático
        "settings put global private_dns_spec \"\"",
        // Restaurar coexistencia WiFi/BT
        "settings put global wifi_bt_coexistence 1"
    )

    // ── API Pública ──────────────────────────────────────────────

    /**
     * Aplica las optimizaciones de red vía `settings put global` (verificadas ✅).
     * No usa `sysctl -w` (bloqueado por SELinux desde shell).
     */
    fun apply() {
        repository.logAsync("INFO", "NetworkOpt", "⚡ Aplicando optimizaciones de red (DNS, Wi-Fi low-latency, BT coex)...)")
        scope.launch {
            var successCount = 0
            var failCount = 0

            for (cmd in APPLY_COMMANDS) {
                val result = ShizukuExecutor.runCommand(cmd)
                if (result.isSuccess) {
                    successCount++
                    repository.logAsync("DEBUG", "NetworkOpt", "✅ OK: ${cmd.take(50)}")
                } else {
                    failCount++
                    repository.logAsync("WARN", "NetworkOpt", "❌ Falló: ${cmd.take(50)} — ${result.exceptionOrNull()?.message}")
                }
            }

            repository.logAsync("INFO", "NetworkOpt", "Red: $successCount OK, $failCount fallos")
        }
    }

    /**
     * Restaura las optimizaciones de red que se pueden revertir vía `settings put global`.
     */
    fun restore() {
        repository.logAsync("INFO", "NetworkOpt", "Restaurando configuración de red...")
        scope.launch {
            for (cmd in RESTORE_COMMANDS) {
                ShizukuExecutor.runCommand(cmd)
            }
        }
    }

    /**
     * Diagnóstico del estado de red post-optimización.
     */
    suspend fun diagnose(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ Network Optimizer Diagnosis ═══")

        // Verificar TCP congestion control
        val tcpCC = ShizukuExecutor.runCommand("sysctl net.ipv4.tcp_congestion_control")
        sb.appendLine("TCP CC: ${tcpCC.getOrNull() ?: "No disponible"}")

        // Verificar Private DNS
        val dns = ShizukuExecutor.runCommand("settings get global private_dns_spec")
        sb.appendLine("Private DNS: ${dns.getOrNull() ?: "No configurado"}")

        // Verificar TCP Fast Open
        val tfo = ShizukuExecutor.runCommand("sysctl net.ipv4.tcp_fastopen")
        sb.appendLine("TCP Fast Open: ${tfo.getOrNull() ?: "No disponible"}")

        // Verificar WiFi/BT coexistence
        val wifibt = ShizukuExecutor.runCommand("settings get global wifi_bt_coexistence")
        sb.appendLine("WiFi/BT Coex: ${wifibt.getOrNull()?.let { if (it == "0") "WiFi priority" else "Normal" } ?: "No disponible"}")

        sb.appendLine("══════════════════════════════════════")
        return sb.toString()
    }
}

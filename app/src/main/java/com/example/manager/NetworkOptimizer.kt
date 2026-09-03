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
 * - Private DNS real (DoT) — Google DNS (dns.google) en modo hostname/strict
 * - Wi-Fi optimizations — baja latencia, priorizar WiFi sobre BT
 *
 * ## Comandos aplicados (verificados ✅)
 * - `settings put global private_dns_mode hostname`
 * - `settings put global private_dns_specifier dns.google`
 * - `settings put global wifi_power_save 0` / `wifi_low_latency_mode 1`
 * - `settings put global wifi_bt_coexistence 0` — priorizar WiFi sobre BT
 *
 * ## ⚠️ Fix histórico
 * Antes se usaba `private_dns_spec` (key incorrecta / no-op). Android usa
 * `private_dns_mode` + `private_dns_specifier`. Solo aplica en modo hostname
 * (strict). En opportunistic la spec se ignora.
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

    // Valores originales para no pisar la config del usuario al restaurar
    @Volatile
    private var originalDnsMode: String? = null
    @Volatile
    private var originalDnsSpecifier: String? = null
    @Volatile
    private var originalWifiBtCoex: String? = null

    // ── Comandos ─────────────────────────────────────────────────

    private val APPLY_COMMANDS = listOf(
        // --- Private DNS real (DoT) — keys correctas de Android ---
        // hostname = modo strict en la UI de Ajustes
        "settings put global private_dns_mode hostname",
        "settings put global private_dns_specifier dns.google",

        // Limpiar residual legacy (por si quedó de versiones anteriores)
        "settings put global private_dns_spec ",

        "settings put global wifi_watchdog_on 0",
        "settings put global wifi_scan_interval_ms 300000", // 5 min

        // --- Latencia (Neon Core) ---
        "settings put global wifi_power_save 0",
        "settings put global wifi_low_latency_mode 1",
        "settings put global wifi_bt_coexistence 0" // Priorizar WiFi sobre BT
    )

    // ── API Pública ──────────────────────────────────────────────

    /**
     * Aplica las optimizaciones de red vía `settings put global` (verificadas ✅).
     * No usa `sysctl -w` (bloqueado por SELinux desde shell).
     */
    fun apply() {
        repository.logAsync("INFO", "NetworkOpt", "⚡ Aplicando optimizaciones de red (Private DNS real + Wi-Fi low-latency)...")
        scope.launch {
            backupOriginalValues()

            var successCount = 0
            var failCount = 0

            for (cmd in APPLY_COMMANDS) {
                val result = ShizukuExecutor.runCommand(cmd)
                if (result.isSuccess) {
                    successCount++
                    repository.logAsync("DEBUG", "NetworkOpt", "✅ OK: ${cmd.take(60)}")
                } else {
                    failCount++
                    repository.logAsync("WARN", "NetworkOpt", "❌ Falló: ${cmd.take(60)} — ${result.exceptionOrNull()?.message}")
                }
            }

            repository.logAsync("INFO", "NetworkOpt", "Red: $successCount OK, $failCount fallos")
        }
    }

    /**
     * Restaura DNS y WiFi/BT coexistence a los valores previos del usuario
     * (o a off/automático si no había backup).
     */
    fun restore() {
        repository.logAsync("INFO", "NetworkOpt", "Restaurando configuración de red...")
        scope.launch {
            val mode = originalDnsMode?.takeIf { it.isNotBlank() && it != "null" } ?: "off"
            val specifier = originalDnsSpecifier?.takeIf { it.isNotBlank() && it != "null" } ?: ""
            val wifiBt = originalWifiBtCoex?.takeIf { it.isNotBlank() && it != "null" } ?: "1"

            val restoreCmds = listOf(
                "settings put global private_dns_mode $mode",
                "settings put global private_dns_specifier $specifier",
                "settings put global private_dns_spec ", // limpiar legacy
                "settings put global wifi_bt_coexistence $wifiBt",
            )

            for (cmd in restoreCmds) {
                ShizukuExecutor.runCommand(cmd)
            }

            originalDnsMode = null
            originalDnsSpecifier = null
            originalWifiBtCoex = null
        }
    }

    /**
     * Diagnóstico del estado de red post-optimización.
     */
    suspend fun diagnose(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ Network Optimizer Diagnosis ═══")

        val tcpCC = ShizukuExecutor.runCommand("sysctl net.ipv4.tcp_congestion_control")
        sb.appendLine("TCP CC: ${tcpCC.getOrNull() ?: "No disponible"}")

        val dnsMode = ShizukuExecutor.runCommand("settings get global private_dns_mode")
        val dnsSpec = ShizukuExecutor.runCommand("settings get global private_dns_specifier")
        val dnsLegacy = ShizukuExecutor.runCommand("settings get global private_dns_spec")
        sb.appendLine("Private DNS mode: ${dnsMode.getOrNull() ?: "?"}")
        sb.appendLine("Private DNS specifier: ${dnsSpec.getOrNull() ?: "?"}")
        sb.appendLine("Private DNS legacy (spec): ${dnsLegacy.getOrNull() ?: "?"}")

        val tfo = ShizukuExecutor.runCommand("sysctl net.ipv4.tcp_fastopen")
        sb.appendLine("TCP Fast Open: ${tfo.getOrNull() ?: "No disponible"}")

        val wifibt = ShizukuExecutor.runCommand("settings get global wifi_bt_coexistence")
        sb.appendLine("WiFi/BT Coex: ${wifibt.getOrNull()?.let { if (it == "0") "WiFi priority" else "Normal" } ?: "No disponible"}")

        sb.appendLine("══════════════════════════════════════")
        return sb.toString()
    }

    // ── Internals ────────────────────────────────────────────────

    private suspend fun backupOriginalValues() {
        val mode = ShizukuExecutor.runCommand("settings get global private_dns_mode").getOrNull()?.trim()
        val specifier = ShizukuExecutor.runCommand("settings get global private_dns_specifier").getOrNull()?.trim()
        val wifiBt = ShizukuExecutor.runCommand("settings get global wifi_bt_coexistence").getOrNull()?.trim()

        if (!mode.isNullOrBlank() && mode != "null") originalDnsMode = mode
        if (!specifier.isNullOrBlank() && specifier != "null") originalDnsSpecifier = specifier
        if (!wifiBt.isNullOrBlank() && wifiBt != "null") originalWifiBtCoex = wifiBt
    }
}

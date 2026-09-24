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
 *
 * ## PR1-A1: integración SSOT (#5)
 * Cada `settings put/delete` exitoso se graba en la sesión persistida vía
 * [recordApplied] (inyectado por GameBoostRepository, mismo patrón que
 * TouchOptimizer). Antes apply()/restore() corrían fuera del funnel SSOT:
 * sus 7 keys no dejaban appliedValue. El backup en RAM de 3 keys se conserva
 * como capa 2 (best-effort solo si el restore SSOT deja fallos).
 */
// PR2 (test seam): ver BoostLogSink en SystemTweaks.kt — mismo patrón DI
// (ctor primario interno para tests JVM, secundario = el de producción).
class NetworkOptimizer internal constructor(
    private val repository: BoostLogSink,
    /** PR1b (V4): batch de records SSOT — se invoca UNA vez por operación. */
    private val recordBatch: (entries: List<Triple<String, String, String?>>) -> Unit = {}
) {
    constructor(
        repository: GameBoostRepository,
        recordBatch: (entries: List<Triple<String, String, String?>>) -> Unit = {}
    ) : this(BoostLogSink { l, t, m -> repository.logAsync(l, t, m) }, recordBatch)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** PR1b (V4): acumulador SSOT (ver SystemTweaks) — 1 commit por operación. */
    private val pendingRecords = mutableListOf<Triple<String, String, String?>>()

    private fun collectIfSettings(cmd: String, ok: Boolean) {
        if (!ok) return
        val p = cmd.trim().split(Regex("\\s+"))
        if (p.size >= 5 && p[0] == "settings" && p[1] == "put") {
            pendingRecords.add(Triple(p[2], p[3], p.drop(4).joinToString(" ")))
        } else if (p.size == 4 && p[0] == "settings" && p[1] == "delete") {
            pendingRecords.add(Triple(p[2], p[3], null))
        }
    }

    // PR1b (auditor #6): la cadena flushRecords → recordBatch → recordAppliedBatch
    // → BoostSessionStore.update es 100% NO-suspend — sin puntos de suspensión, una
    // CancellationException no puede interrumpir el commit aunque el scope esté en
    // estado cancelling. Si la cadena se vuelve suspend, envolver en
    // withContext(NonCancellable).
    private fun flushRecords() {
        if (pendingRecords.isEmpty()) return
        recordBatch(pendingRecords.toList())
        pendingRecords.clear()
    }

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

            try {
                for (cmd in APPLY_COMMANDS) {
                    val result = ShizukuExecutor.runCommand(cmd)
                    if (result.isSuccess) {
                        successCount++
                        // PR1-A1 + PR1b(V4): el apply alimenta la SSOT (batch al final)
                        collectIfSettings(cmd, ok = true)
                        repository.logAsync("DEBUG", "NetworkOpt", "✅ OK: ${cmd.take(60)}")
                    } else {
                        failCount++
                        repository.logAsync("WARN", "NetworkOpt", "❌ Falló: ${cmd.take(60)} — ${result.exceptionOrNull()?.message}")
                    }
                }
            } finally {
                // PR1b (auditor #6): flush garantizado en CUALQUIER salida del loop
                // (paridad con SystemTweaks.applyTweaks) — los records de las keys
                // ya aplicadas no se pierden a mitad del apply.
                flushRecords()
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
        execRestore(buildRestoreCommands())
    }

    /**
     * PR1b (V3): restore per-key — SOLO las keys con RESTORE_FAILED de la SSOT se
     * re-escriben desde el backup RAM. Las keys pedidas que este manager no cubre
     * se ignoran. Mismo build/H6 que restore(); solo cambia el filtro.
     */
    fun restoreOnly(failedKeys: Set<String>) {
        if (failedKeys.isEmpty()) return
        repository.logAsync("INFO", "NetworkOpt", "Restaurando ${failedKeys.size} keys fallidas desde backup RAM...")
        execRestore(buildRestoreCommands().filter { cmd -> keyOf(cmd) in failedKeys })
    }

    /** "settings put global <key> <v...>" → "global:<key>" (formato del reporte SSOT). */
    private fun keyOf(cmd: String): String {
        val p = cmd.trim().split(Regex("\\s+"))
        return if (p.size > 3) "${p[2]}:${p[3]}" else ""
    }

    private fun buildRestoreCommands(): List<String> {
        val mode = originalDnsMode?.takeIf { it.isNotBlank() && it != "null" } ?: "off"
        val specifier = originalDnsSpecifier?.takeIf { it.isNotBlank() && it != "null" } ?: ""
        val wifiBt = originalWifiBtCoex?.takeIf { it.isNotBlank() && it != "null" } ?: "1"

        // FIX H6 (read-back): cada valor validado contra SU dominio antes de
        // interpolarse. Inválido → NO se ejecuta ESE restore y se informa;
        // NUNCA se transforma ni se sustituye por otro valor. Los defaults
        // "off"/""/"1" son los preexistentes al fix (ausencia de backup),
        // no sustituciones de valores inválidos.
        val restoreCmds = mutableListOf<String>()
        if (RestoreValueValidators.isPrivateDnsMode(mode)) {
            restoreCmds.add("settings put global private_dns_mode $mode")
        } else {
            repository.logAsync(
                "ERROR", "NetworkOpt",
                "FIX H6: private_dns_mode original inválido ('$mode') — restore de este valor NO ejecutado"
            )
        }
        // El specifier solo se escribe si el usuario tenía uno; si estaba
        // ausente, se restaura la ausencia (un put con valor vacío produce
        // un usage error, EXIT=255).
        when {
            specifier.isNotBlank() && RestoreValueValidators.isDnsSpecifier(specifier) ->
                restoreCmds.add("settings put global private_dns_specifier $specifier")
            specifier.isNotBlank() -> repository.logAsync(
                "ERROR", "NetworkOpt",
                "FIX H6: private_dns_specifier original inválido ('$specifier') — restore de este valor NO ejecutado (se preserva byte a byte, sin transformar)"
            )
            else -> restoreCmds.add("settings delete global private_dns_specifier")
        }
        if (RestoreValueValidators.isNumeric(wifiBt)) {
            restoreCmds.add("settings put global wifi_bt_coexistence $wifiBt")
        } else {
            repository.logAsync(
                "ERROR", "NetworkOpt",
                "FIX H6: wifi_bt_coexistence original inválido ('$wifiBt') — restore de este valor NO ejecutado"
            )
        }
        return restoreCmds
    }

    private fun execRestore(cmds: List<String>) {
        scope.launch {
            try {
                for (cmd in cmds) {
                    val result = ShizukuExecutor.runCommand(cmd)
                    // PR1-A1: el restore también alimenta la SSOT (delete → null)
                    collectIfSettings(cmd, ok = result.isSuccess)
                }
            } finally {
                // PR1b (auditor #6): flush garantizado en cualquier salida del loop.
                flushRecords()
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

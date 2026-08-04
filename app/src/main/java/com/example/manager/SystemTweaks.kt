package com.example.manager

import com.example.data.repository.GameBoostRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SystemTweaks — Ajustes del sistema tomados de Neon Core.
 *
 * ## Funciones
 * - Disable BLE/GPS Scanning — Desactiva escaneo Bluetooth y GPS en segundo plano
 * - Disable HW Overlays — Fuerza renderizado por GPU (evita capas HW innecesarias)
 * - Disable Auto-Sync — Evita sincronización automática durante el juego
 * - Disable UI Window Blurs — Elimina desenfoques de la interfaz (libera GPU)
 * - Disable V-Sync — Reduce latencia de frames (menos input lag)
 * - Disable Digital Wellbeing — Desactiva servicios de bienestar digital (libera CPU)
 * - Force 4x MSAA — Mejora calidad gráfica (toggle)
 * - Clear Logs — Limpia logcat y dmesg (libera RAM del kernel)
 *
 * ## Comandos
 * - `settings put global ble_scan_always_enabled 0`
 * - `settings put global overlay_display_devices 0`
 * - `settings put global auto_sync 0`
 * - `settings put global disable_window_blurs 1`
 * - `settings put global debug.sf.disable_hwc_vds 1`
 * - `settings put global adaptive_connected_voice_enabled 0`
 * - `settings put global debug.gl.msaa 4`
 * - `logcat -c; dmesg -c` (solo clear)
 *
 * ## Nota
 * Estos comandos usan `settings put global` que funciona 100% con Shizuku.
 * No requieren root.
 */
class SystemTweaks(
    private val repository: GameBoostRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Valores originales para restauración
    @Volatile
    private var originalBleScan: String? = null
    @Volatile
    private var originalHwOverlays: String? = null
    @Volatile
    private var originalAutoSync: String? = null
    @Volatile
    private var originalWindowBlurs: String? = null
    @Volatile
    private var originalVsync: String? = null
    @Volatile
    private var originalDigitalWellbeing: String? = null
    @Volatile
    private var originalMsaa: String? = null

    // ── Comandos ─────────────────────────────────────────────────

    private val BACKUP_KEYS = listOf(
        "global:ble_scan_always_enabled",
        "global:overlay_display_devices",
        "global:auto_sync",
        "global:disable_window_blurs",
        "global:debug.sf.disable_hwc_vds",
        "global:adaptive_connected_voice_enabled",
        "global:debug.gl.msaa"
    )

    private fun getApplyCommands(enableMsaa: Boolean = false): List<String> = buildList {
        // --- Conectividad y Escaneo (Neon Core) ---
        add("settings put global ble_scan_always_enabled 0")
        add("settings put global wifi_scan_always_enabled 0")
        add("settings put global bluetooth_disabled_profiles 1") // Ahorro batería BT

        // --- Gráficos y Renderizado (Neon Core) ---
        add("settings put global overlay_display_devices 0")
        add("settings put global debug.hwui.renderer skiavk") // Forzar Skia Vulkan si está disponible
        add("settings put global debug.hwui.overdraw false")
        add("settings put global debug.hwui.show_dirty_regions false")
        add("settings put global debug.sf.disable_backpressure 1") // Reducir latencia de frames
        add("settings put global debug.sf.latch_unsignaled 1")
        add("settings put global disable_window_blurs 1") // Desactivar desenfoques UI
        add("settings put global debug.sf.disable_hwc_vds 1") // Desactivar V-Sync

        // --- Interfaz y Animaciones (Neon Core) ---
        add("settings put global auto_sync 0")
        add("settings put global window_animation_scale 0")
        add("settings put global transition_animation_scale 0")
        add("settings put global animator_duration_scale 0")
        add("settings put global send_action_app_error 0") // Disable system analytics

        // --- Optimizaciones de Proceso (Neon Core) ---
        add("settings put global activity_manager_constants max_cached_processes=128")
        add("settings put global low_power_trigger_level 0")

        // --- Bienestar Digital (Neon Core) ---
        add("settings put global adaptive_connected_voice_enabled 0") // Desactivar bienestar digital

        // --- MSAA (Neon Core) — 4x multisample anti-aliasing ---
        // ⚠️ GPU-intensive: puede bajar FPS en dispositivos mid-range.
        // Solo se aplica si el usuario lo habilita explícitamente (toggle MSAA).
        if (enableMsaa) {
            add("settings put global debug.gl.msaa 4")
        }
    }

    private fun getRestoreCommands(): List<String> = buildList {
        originalBleScan?.let { add("settings put global ble_scan_always_enabled $it") }
            ?: add("settings put global ble_scan_always_enabled 1")
        originalHwOverlays?.let { add("settings put global overlay_display_devices $it") }
            ?: add("settings put global overlay_display_devices 1")
        originalAutoSync?.let { add("settings put global auto_sync $it") }
            ?: add("settings put global auto_sync 1")
        originalWindowBlurs?.let { add("settings put global disable_window_blurs $it") }
            ?: add("settings put global disable_window_blurs 0")
        originalVsync?.let { add("settings put global debug.sf.disable_hwc_vds $it") }
            ?: add("settings put global debug.sf.disable_hwc_vds 0")
        originalDigitalWellbeing?.let { add("settings put global adaptive_connected_voice_enabled $it") }
            ?: add("settings put global adaptive_connected_voice_enabled 1")
        originalMsaa?.let { add("settings put global debug.gl.msaa $it") }
            ?: add("settings put global debug.gl.msaa 0")
    }

    // ── API Pública ──────────────────────────────────────────────

    /**
     * Aplica todos los ajustes del sistema.
     * Primero respalda los valores originales, luego aplica los nuevos.
     *
     * @param enableMsaa Si true, aplica Force 4x MSAA (GPU-intensive, puede bajar FPS
     *        en mid-range). Por defecto false — el usuario lo habilita vía toggle.
     */
    fun apply(enableMsaa: Boolean = false) {
        repository.logAsync("INFO", "SysTweaks", "⚡ Aplicando ajustes del sistema (Blurs/V-Sync/MSAA/DigitalWellbeing)... MSAA=${if (enableMsaa) "ON" else "OFF"}")
        scope.launch {
            backupOriginalValues()
            applyTweaks(enableMsaa)
        }
    }

    /**
     * Restaura los valores originales del sistema.
     */
    fun restore() {
        repository.logAsync("INFO", "SysTweaks", "Restaurando ajustes del sistema...")
        scope.launch {
            val commands = getRestoreCommands()
            for (cmd in commands) {
                ShizukuExecutor.runCommand(cmd)
            }
        }
    }

    /**
     * Limpia logs del sistema (logcat + dmesg).
     * Esto libera RAM del kernel usada por los buffers de log.
     * Se puede llamar independientemente de apply/restore.
     */
    fun clearLogs() {
        repository.logAsync("DEBUG", "SysTweaks", "🧹 Limpiando logs del sistema...")
        scope.launch {
            ShizukuExecutor.runCommand("logcat -c")
            ShizukuExecutor.runCommand("dmesg -c")
        }
    }

    /**
     * Diagnóstico del estado actual de los ajustes.
     */
    suspend fun diagnose(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ System Tweaks Diagnosis ═══")

        val ble = ShizukuExecutor.runCommand("settings get global ble_scan_always_enabled")
        sb.appendLine("BLE Scan: ${ble.getOrNull() ?: "No disponible"}")

        val hw = ShizukuExecutor.runCommand("settings get global overlay_display_devices")
        sb.appendLine("HW Overlays: ${hw.getOrNull() ?: "No disponible"}")

        val sync = ShizukuExecutor.runCommand("settings get global auto_sync")
        sb.appendLine("Auto Sync: ${sync.getOrNull() ?: "No disponible"}")

        val blurs = ShizukuExecutor.runCommand("settings get global disable_window_blurs")
        sb.appendLine("Window Blurs: ${blurs.getOrNull() ?: "No disponible"}")

        val vsync = ShizukuExecutor.runCommand("settings get global debug.sf.disable_hwc_vds")
        sb.appendLine("V-Sync: ${vsync.getOrNull()?.let { if (it == "1") "OFF" else "ON" } ?: "No disponible"}")

        val dWellbeing = ShizukuExecutor.runCommand("settings get global adaptive_connected_voice_enabled")
        sb.appendLine("Digital Wellbeing: ${dWellbeing.getOrNull()?.let { if (it == "0") "OFF" else "ON" } ?: "No disponible"}")

        val msaa = ShizukuExecutor.runCommand("settings get global debug.gl.msaa")
        sb.appendLine("MSAA: ${msaa.getOrNull()?.let { if (it == "4") "4x" else if (it == "0") "OFF" else "${it}x" } ?: "No disponible"}")

        sb.appendLine("════════════════════════════════")
        return sb.toString()
    }

    // ── Internals ────────────────────────────────────────────────

    private suspend fun backupOriginalValues() {
        for (key in BACKUP_KEYS) {
            val parts = key.split(":")
            val result = ShizukuExecutor.runCommand("settings get ${parts[0]} ${parts[1]}")
            val value = result.getOrNull()?.trim()
            if (!value.isNullOrBlank() && value != "null") {
                when (parts[1]) {
                    "ble_scan_always_enabled" -> originalBleScan = value
                    "overlay_display_devices" -> originalHwOverlays = value
                    "auto_sync" -> originalAutoSync = value
                    "disable_window_blurs" -> originalWindowBlurs = value
                    "debug.sf.disable_hwc_vds" -> originalVsync = value
                    "adaptive_connected_voice_enabled" -> originalDigitalWellbeing = value
                    "debug.gl.msaa" -> originalMsaa = value
                }
            }
        }
    }

    private suspend fun applyTweaks(enableMsaa: Boolean = false) {
        var successCount = 0
        var failCount = 0

        for (cmd in getApplyCommands(enableMsaa)) {
            val result = ShizukuExecutor.runCommand(cmd)
            if (result.isSuccess) {
                successCount++
                repository.logAsync("DEBUG", "SysTweaks", "✅ OK: ${cmd.take(50)}")

                // Validación post-aplicación: leer el setting para confirmar el cambio
                val verifyKey = extractVerifyKey(cmd)
                if (verifyKey != null) {
                    val verifyResult = ShizukuExecutor.runCommand("settings get global $verifyKey")
                    val actualValue = verifyResult.getOrNull()?.trim()
                    val expectedValue = extractExpectedValue(cmd)
                    if (actualValue == expectedValue) {
                        repository.logAsync("DEBUG", "SysTweaks", "🔍 Verificado: $verifyKey = $actualValue ✅")
                    } else {
                        repository.logAsync("WARN", "SysTweaks", "🔍 Verificación: $verifyKey esperado=$expectedValue, actual=$actualValue ⚠️")
                    }
                }
            } else {
                failCount++
                repository.logAsync("WARN", "SysTweaks", "❌ Falló: ${cmd.take(50)} — ${result.exceptionOrNull()?.message}")
            }
        }

        repository.logAsync("INFO", "SysTweaks", "Sistema: $successCount OK, $failCount fallos")
    }

    /**
     * Extrae el nombre del setting global de un comando "settings put global <key> <value>".
     * Retorna null si el comando no tiene formato de settings put global.
     */
    private fun extractVerifyKey(cmd: String): String? {
        val regex = """^settings\s+put\s+global\s+(\S+)\s+""".toRegex()
        return regex.find(cmd)?.groupValues?.getOrNull(1)
    }

    /**
     * Extrae el valor esperado de un comando "settings put global <key> <value>".
     */
    private fun extractExpectedValue(cmd: String): String? {
        val regex = """^settings\s+put\s+global\s+\S+\s+(\S+)$""".toRegex()
        return regex.find(cmd)?.groupValues?.getOrNull(1)
    }
}

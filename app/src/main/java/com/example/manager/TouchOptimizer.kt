package com.example.manager

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * TouchOptimizer — solo settings AOSP con efecto verificable.
 *
 * Backup alineado con F4 (BoostSession BackupEntry):
 * - value presente → restore con `settings put`
 * - key ausente (`settings get` → "null") → null en mapa → `settings delete`
 * - restore comando-a-comando; mapa se conserva si hay fallo parcial
 *
 * PLACEBO eliminados: touch_latency_reduction, touch_boost_enabled, high_touch_*,
 * touch_report_rate 240, touch_sensitivity genérico.
 */
class TouchOptimizer(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** key "ns:name" → original; null value = key ausente al capturar */
    private val originalSettings = mutableMapOf<String, String?>()
    private var isBackupDone = false

    fun applyOptimization(sensitivity: Int, isGamingMode: Boolean = false) {
        scope.launch {
            if (!isBackupDone) {
                backupSettings()
            }

            val commands = mutableListOf<String>()
            val mappedPointerSpeed =
                ((sensitivity.coerceIn(1, 10) / 10.0) * 14).roundToInt() - 7
            commands.add("settings put system pointer_speed $mappedPointerSpeed")

            val longPress = if (isGamingMode) 120 else 300
            commands.add("settings put secure long_press_timeout $longPress")

            commands.add("settings put secure accessibility_display_magnification_enabled 0")
            commands.add("settings put secure accessibility_autoclick_enabled 0")

            // Un comando por key (no join ";") para no ocultar exit intermedios
            for (cmd in commands) {
                ShizukuExecutor.runCommand(cmd)
            }
            Log.d("TouchOptimizer", "Aplicando (solo AOSP verificable): ${commands.size} cmds")
        }
    }

    private suspend fun backupSettings() {
        val keys = listOf(
            "system:pointer_speed",
            "secure:long_press_timeout",
            "secure:accessibility_display_magnification_enabled",
            "secure:accessibility_autoclick_enabled",
        )
        keys.forEach { key ->
            val parts = key.split(":")
            val result = ShizukuExecutor.runCommand("settings get ${parts[0]} ${parts[1]}")
            if (result.isFailure) {
                Log.w("TouchOptimizer", "Backup read failed for $key — no se registra")
                return@forEach
            }
            val raw = result.getOrNull()?.trim()
            originalSettings[key] = if (raw.isNullOrBlank() || raw == "null") null else raw
        }
        isBackupDone = true
        val absent = originalSettings.count { it.value == null }
        Log.d(
            "TouchOptimizer",
            "Backup: ${originalSettings.size} keys ($absent ausentes → delete en restore)"
        )
    }

    fun restore() {
        scope.launch {
            if (!isBackupDone || originalSettings.isEmpty()) {
                Log.w(
                    "TouchOptimizer",
                    "Restore omitido: sin backup en RAM (usa BoostSessionManager post-muerte)"
                )
                return@launch
            }
            var ok = 0
            var fail = 0
            for ((key, value) in originalSettings) {
                val parts = key.split(":")
                val ns = parts[0]
                val name = parts[1]
                val cmd = if (value == null) {
                    "settings delete $ns $name"
                } else {
                    "settings put $ns $name $value"
                }
                val result = ShizukuExecutor.runCommand(cmd)
                if (result.isSuccess) ok++ else {
                    fail++
                    Log.e(
                        "TouchOptimizer",
                        "Restore falló ($key): ${result.exceptionOrNull()?.message}"
                    )
                }
            }
            if (fail == 0) {
                Log.d("TouchOptimizer", "Ajustes táctiles restaurados ($ok ops)")
                originalSettings.clear()
                isBackupDone = false
            } else {
                Log.e(
                    "TouchOptimizer",
                    "Restore parcial ok=$ok fail=$fail — mapa conservado para reintento"
                )
            }
        }
    }
}

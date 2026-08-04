package com.example.manager

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class TouchOptimizer(private val context: Context) {
    // ── FIX: SupervisorJob evita que excepciones en hijos no cancelen el scope ni crasheen la app ──
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val originalSettings = mutableMapOf<String, String>()
    private var isBackupDone = false

    /**
     * Aplica las optimizaciones táctiles basadas en el módulo Touch Pro.
     * @param sensitivity Nivel de sensibilidad (1 a 10)
     * @param isGamingMode Si es verdadero, aplica el "Touch Boost" experimental
     */
    fun applyOptimization(sensitivity: Int, isGamingMode: Boolean = false) {
        scope.launch {
            if (!isBackupDone) {
                backupSettings()
            }

            val commands = mutableListOf<String>()

            // 1. Mapear sensibilidad (1-10) a pointer_speed (-7 a 7 en Android, pero usualmente 0-14 internamente)
            // El script JS mapeaba a -3 a +4. Ajustamos para el estándar de Android.
            val mappedPointerSpeed = ((sensitivity / 10.0) * 14).roundToInt() - 7
            commands.add("settings put system pointer_speed $mappedPointerSpeed")

            // 2. Sensibilidad táctil genérica (específico de algunas ROMs)
            val touchValue = ((sensitivity / 10.0) * 100).roundToInt()
            commands.add("settings put system touch_sensitivity $touchValue")
            commands.add("settings put system multi_touch_sensitivity $touchValue")

            // 3. Reducción de latencia y Timeout de pulsación larga
            val longPress = if (isGamingMode) 120 else 300
            commands.add("settings put secure long_press_timeout $longPress")
            
            // 4. Desactivar filtros de accesibilidad que añaden lag
            commands.add("settings put secure accessibility_display_magnification_enabled 0")
            commands.add("settings put secure accessibility_autoclick_enabled 0")

            // 5. Optimizaciones experimentales de latencia (Neon Core)
            if (isGamingMode) {
                commands.add("settings put system touch_latency_reduction 1")
                commands.add("settings put secure touch_boost_enabled 1")
                commands.add("settings put system high_touch_sensitivity_enable 1")
                commands.add("settings put system high_touch_polling_rate_enable 1")
                // Desactivar gestos de sistema que pueden interferir con el touch de juegos
                commands.add("settings put secure swipe_up_to_switch_apps_enabled 0")
                commands.add("settings put secure edge_prevent_mistouch_enabled 0")
            }

            // 6. Report Rate (Si el hardware lo soporta vía software settings)
            commands.add("settings put system touch_report_rate 240")

            val finalCommand = commands.joinToString("; ")
            Log.d("TouchOptimizer", "Aplicando optimizaciones: $finalCommand")
            ShizukuExecutor.runCommand(finalCommand)
        }
    }

    private suspend fun backupSettings() {
        val keys = listOf(
            "system:pointer_speed",
            "secure:long_press_timeout",
            "secure:accessibility_display_magnification_enabled",
            "secure:accessibility_autoclick_enabled",
            "secure:swipe_up_to_switch_apps_enabled",
        )

        keys.forEach { key ->
            val parts = key.split(":")
            val result = ShizukuExecutor.runCommand("settings get ${parts[0]} ${parts[1]}")
            result.getOrNull()?.let { value ->
                if (value != "null" && value.isNotBlank()) {
                    originalSettings[key] = value.trim()
                }
            }
        }
        isBackupDone = true
        Log.d("TouchOptimizer", "Backup completado: ${originalSettings.size} ajustes guardados")
    }

    fun restore() {
        scope.launch {
            if (originalSettings.isEmpty()) return@launch

            val commands = mutableListOf<String>()
            originalSettings.forEach { (key, value) ->
                val parts = key.split(":")
                commands.add("settings put ${parts[0]} ${parts[1]} $value")
            }
            
            // Valores por defecto para los que no siempre tienen backup
            commands.add("settings put system touch_latency_reduction 0")
            commands.add("settings put secure touch_boost_enabled 0")
            
            val finalCommand = commands.joinToString("; ")
            ShizukuExecutor.runCommand(finalCommand)
            Log.d("TouchOptimizer", "Ajustes táctiles restaurados")
        }
    }
}

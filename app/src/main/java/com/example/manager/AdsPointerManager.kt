package com.example.manager

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.example.data.repository.GameBoostRepository
import com.example.manager.exec.ExecOutcome
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import android.content.ComponentName

class AdsPointerManager(private val context: Context, private val repository: GameBoostRepository) {
    private val TAG = "AdsPointerManager"
    private var POINTER_SPEED_ADS_PERCENT = 40
    private var POINTER_SPEED_NORMAL_PERCENT = 80
    
    private var isAdsActive = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        repository.logAsync("INFO", "AdsPointer", "Manager initialized")
    }
    
    fun applyAdsSpeed() {
        if (!isAdsActive) {
            isAdsActive = true
            applyPointerSpeed(POINTER_SPEED_ADS_PERCENT, "ADS")
        }
    }
    
    fun applyNormalSpeed() {
        if (isAdsActive) {
            isAdsActive = false
            applyPointerSpeed(POINTER_SPEED_NORMAL_PERCENT, "Normal")
        }
    }
    
    private fun applyPointerSpeed(percent: Int, mode: String) {
        scope.launch {
            try {
                val rawSpeed = repository.mapPercentToRawSpeed(percent)
                // PR1b: vía el funnel SSOT (executePrivilegedCommand → funnel GSM →
                // recordAppliedCommand). Antes era runCommand directo + fallback
                // in-process: un bypass SSOT puro — el valor del botón ADS quedaba
                // "no nuestro" y el restore de pointer_speed degradaba a CONFLICT.
                // El funnel ya incluye el fallback Settings API y graba el appliedValue.
                val results = repository.executePrivilegedCommand(
                    "settings put system pointer_speed $rawSpeed"
                )
                if (results.all { it.outcome is ExecOutcome.EXECUTED }) {
                    repository.logAsync("DEBUG", "AdsPointer", "Pointer speed changed to $percent% ($mode) via funnel SSOT")
                } else {
                    repository.logAsync("WARN", "AdsPointer", "Fallo funnel para speed $percent% ($mode)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying pointer speed: ${e.message}")
            }
        }
    }

    private fun executeShizukuCommand(command: String) {
        scope.launch {
            ShizukuExecutor.runCommand(command)
        }
    }
    
    private fun applyPointerSpeedFallback(speed: Int) {
        try {
            Settings.System.putInt(
                context.contentResolver,
                "pointer_speed",
                speed
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in fallback: ${e.message}")
        }
    }
}

package com.example.manager

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.example.data.repository.GameBoostRepository
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
                val result = ShizukuExecutor.runCommand("settings put system pointer_speed $rawSpeed")
                if (result.isSuccess) {
                    repository.logAsync("DEBUG", "AdsPointer", "Pointer speed changed to $percent% ($mode) via Shizuku")
                } else {
                    applyPointerSpeedFallback(rawSpeed)
                    repository.logAsync("WARN", "AdsPointer", "Shizuku error, using fallback for speed $percent%")
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

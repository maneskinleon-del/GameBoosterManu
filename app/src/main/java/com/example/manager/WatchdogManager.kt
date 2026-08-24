package com.example.manager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.example.data.PreferenceManager
import com.example.data.repository.DependencyState
import com.example.data.repository.FsmState
import com.example.data.repository.GameBoostRepository
import com.example.service.GameBoostService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import rikka.shizuku.Shizuku
import kotlin.math.min
import kotlin.math.pow

/**
 * WatchdogManager avanzado (Heartbeat)
 * Implementa resiliencia con Backoff Exponencial y Doble Validación.
 * Ahora usa DependencyStateManager para el estado unificado de dependencias.
 */
class WatchdogManager(
    private val context: Context,
    private val repository: GameBoostRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchdogJob: Job? = null
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    
    // Configuración de Resiliencia
    private val CHECK_INTERVAL_NORMAL = 15000L
    private val CHECK_INTERVAL_SCREEN_OFF = 60000L
    private val MAX_CONSECUTIVE_FAILURES = 3
    private val MAX_RECOVERY_ATTEMPTS = 3
    private val RECOVERY_WINDOW = 300000L // 5 minutos (ventana para resetear contador)
    private val ABSOLUTE_RECOVERY_TIMEOUT = 300000L // 5 minutos (timeout absoluto para forzar DEGRADED)
    
    private var consecutiveFailures = 0
    private var recoveryAttempts = 0
    private var firstRecoveryTime = 0L
    private var isRecoveryInProgress = false
    private var lastServiceRestart = 0L

    data class HealthStatus(
        val shizukuAlive: Boolean,
        val accessibilityAlive: Boolean,
        val serviceAlive: Boolean,
        val batteryUnrestricted: Boolean,
        val lastCheck: Long = System.currentTimeMillis(),
        val restartCount: Int = 0,
        val statusMessage: String = "Healthy"
    )

    private val _healthStatus = MutableStateFlow(HealthStatus(false, false, false, false))
    val healthStatus = _healthStatus.asStateFlow()

    private var totalRestarts = 0

    fun start() {
        if (watchdogJob?.isActive == true) return
        repository.logAsync("INFO", "Heartbeat", "💓 Monitor de salud iniciado")
        
        watchdogJob = scope.launch {
            while (isActive) {
                try {
                    val isScreenOn = powerManager.isInteractive
                    val checkInterval = if (isScreenOn) CHECK_INTERVAL_NORMAL else CHECK_INTERVAL_SCREEN_OFF
                    
                    if (!isRecoveryInProgress) {
                        checkHealth()
                    } else {
                        // Durante recovery, seguimos verificando el timeout absoluto
                        // para no quedar atrapados en RECOVERING para siempre.
                        checkRecoveryTimeout()
                    }
                    
                    delay(checkInterval)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("Watchdog", "Error en Heartbeat loop: ${e.message}")
                    delay(5000)
                }
            }
        }
    }

    private suspend fun checkHealth() {
        // Usar el nuevo DependencyStateManager para obtener estado unificado
        val depState = repository.dependencyState.value
        
        val shizukuHealthy = depState.shizuku.state == com.example.data.repository.DependencyState.Shizuku.ShizukuState.ON
        val accessibility = depState.accessibility.state == com.example.data.repository.DependencyState.Accessibility.AccessibilityState.ACTIVE
        val serviceRunning = GameBoostService.isRunning
        val batteryUnrestricted = depState.batteryOptimization.state == com.example.data.repository.DependencyState.BatteryOptimization.BatteryState.UNRESTRICTED
        val shouldBeRunning = com.example.data.PreferenceManager.isServiceRunning(context)

        _healthStatus.value = HealthStatus(
            shizukuAlive = shizukuHealthy,
            accessibilityAlive = accessibility,
            serviceAlive = serviceRunning,
            batteryUnrestricted = batteryUnrestricted,
            restartCount = totalRestarts,
            statusMessage = repository.fsmState.value.name
        )

        if (!shouldBeRunning) return

        // 1. Manejo de Shizuku con Umbral de Fallos
        if (!shizukuHealthy) {
            consecutiveFailures++
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                if (repository.fsmState.value != FsmState.RECOVERING) {
                    repository.setFsmState(FsmState.DEGRADED)
                }
                repository.logAsync("ERROR", "Heartbeat", "💔 Heartbeat perdido ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES). Shizuku no responde.")
                triggerShizukuRecovery()
            } else {
                Log.w("Heartbeat", "⚠️ Fallo de salud detectado ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES)")
            }
        } else {
            if (consecutiveFailures > 0) {
                repository.logAsync("INFO", "Heartbeat", "💚 Salud restaurada")
                if (repository.fsmState.value == FsmState.DEGRADED || repository.fsmState.value == FsmState.RECOVERING) {
                    repository.setFsmState(FsmState.READY)
                }
            }
            consecutiveFailures = 0
        }

        // 2. Manejo de Servicio Principal
        if (!serviceRunning) {
            val now = System.currentTimeMillis()
            if (now - lastServiceRestart > 60000L) {
                totalRestarts++
                lastServiceRestart = now
                repository.logAsync("WARN", "Watchdog", "Servicio caído detectado. Reiniciando servicio principal...")
                startCoreService()
            }
        }

        // 3. Diagnóstico Térmico
        checkThermalHealth()
        
        // 4. Diagnóstico Accesibilidad
        if (!accessibility) {
            Log.w("Heartbeat", "Servicio de Accesibilidad inactivo")
        }
    }

    private suspend fun doubleCheckShizuku(): Boolean {
        // Validación 1: Ping rápido
        val ping = try { Shizuku.pingBinder() } catch (e: Exception) { false }
        if (!ping) return false

        // Validación 2: Comando real (como heartbeat.js)
        return try {
            val result = ShizukuExecutor.runCommand("echo 1")
            result.getOrNull()?.trim() == "1"
        } catch (e: Exception) {
            false
        }
    }

    private fun triggerShizukuRecovery() {
        if (isRecoveryInProgress) return
        
        val now = System.currentTimeMillis()
        
        // Resetear ventana de recovery
        if (now - firstRecoveryTime > RECOVERY_WINDOW) {
            recoveryAttempts = 0
            firstRecoveryTime = now
        }

        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            repository.logAsync("ERROR", "Heartbeat", "💀 Máximos intentos de recuperación alcanzados. Shizuku requiere intervención manual.")
            repository.setFsmState(FsmState.DEGRADED)
            return
        }

        isRecoveryInProgress = true
        repository.setFsmState(FsmState.RECOVERING)
        recoveryAttempts++
        
        scope.launch {
            try {
                // Backoff Exponencial: 2s, 4s, 8s...
                val delayMs = min(2000L * 2.0.pow(recoveryAttempts - 1).toLong(), 30000L)
                repository.logAsync("WARN", "Heartbeat", "🚑 Intento de recuperación $recoveryAttempts/$MAX_RECOVERY_ATTEMPTS (esperando ${delayMs/1000}s)")
                
                delay(delayMs)
                
                // Intentar forzar una actualización del estado de Shizuku
                repository.toggleShizukuState()
                
                delay(3000) // Dar tiempo a Shizuku para reaccionar
                
                if (doubleCheckShizuku()) {
                    repository.logAsync("INFO", "Heartbeat", "💚 Recuperación exitosa. Shizuku reconectado.")
                    repository.setFsmState(FsmState.READY)
                    recoveryAttempts = 0
                    consecutiveFailures = 0
                } else {
                    repository.logAsync("ERROR", "Heartbeat", "❌ Intento $recoveryAttempts falló.")
                    repository.setFsmState(FsmState.DEGRADED)
                }
            } finally {
                isRecoveryInProgress = false
            }
        }
    }

    private fun checkRecoveryTimeout() {
        val elapsed = System.currentTimeMillis() - firstRecoveryTime
        if (elapsed > ABSOLUTE_RECOVERY_TIMEOUT) {
            repository.logAsync("ERROR", "Heartbeat", "⏰ Tiempo máximo de recuperación agotado (${elapsed/1000}s). Forzando DEGRADED.")
            repository.setFsmState(FsmState.DEGRADED)
            isRecoveryInProgress = false
            recoveryAttempts = 0
        }
    }

    // Hysteresis térmica: evitar cambios bruscos de perfil
    private var lastThermalActionTime = 0L
    private val THERMAL_COOLDOWN_MS = 120000L  // 2 minutos de espera entre acciones térmicas
    private var lastProfileBeforeThermal: String? = null

    private suspend fun checkThermalHealth() {
        if (!repository.isThermalWatchdogEnabled.value) return

        val cpuTemp = repository.systemMetrics.value.cpuTemp
        val now = System.currentTimeMillis()

        // No actuar si estamos en cooldown térmico
        if (now - lastThermalActionTime < THERMAL_COOLDOWN_MS) return

        val profiles = repository.profilesFlow.first()
        val currentProfile = profiles.find { it.isActive }
        val currentProfileId = currentProfile?.id ?: ""

        when {
            // ⚠️ CRÍTICO: ≥ 55°C — forzar Battery Saver (antes era 50°C, subido para evitar disparos falsos)
            cpuTemp >= 55f -> {
                repository.logAsync("ERROR", "Watchdog", "🔥 TEMPERATURA CRÍTICA: ${cpuTemp.toInt()}°C. Aplicando ahorro de energía.")
                lastProfileBeforeThermal = currentProfileId
                lastThermalActionTime = now
                repository.setActiveProfile("battery_saver")
            }
            // 🌡️ ALTA: ≥ 50°C — solo bajar si está en Extreme (antes era 45°C)
            cpuTemp >= 50f -> {
                if (currentProfileId == "extreme") {
                    repository.logAsync("WARN", "Watchdog", "🌡️ Temperatura elevada: ${cpuTemp.toInt()}°C. Bajando perfil a BALANCED.")
                    lastProfileBeforeThermal = currentProfileId
                    lastThermalActionTime = now
                    repository.setActiveProfile("balanced")
                }
            }
            // ✅ Normal: ≤ 40°C y hay un perfil anterior guardado → restaurar
            cpuTemp <= 40f && lastProfileBeforeThermal != null -> {
                val restoreTo = lastProfileBeforeThermal ?: return
                lastProfileBeforeThermal = null
                repository.logAsync("INFO", "Watchdog", "✅ Temperatura normal (${cpuTemp.toInt()}°C). Restaurando perfil anterior: $restoreTo.")
                repository.setActiveProfile(restoreTo)
            }
        }
    }

    private fun startCoreService() {
        val intent = Intent(context, GameBoostService::class.java).apply {
            action = GameBoostService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop() {
        watchdogJob?.cancel()
        repository.logAsync("INFO", "Heartbeat", "💓 Monitor de salud desactivado")
    }
}

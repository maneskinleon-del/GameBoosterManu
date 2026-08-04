package com.example.manager

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitoreo térmico del dispositivo.
 * 
 * Dos capas de protección:
 * 1. [ThermalManager] API de Android 10+ (setGameState no disponible)
 * 2. Watchdog con umbrales de temperatura (sysfs fallback)
 * 
 * Uso:
 *   val thermal = ThermalController(context)
 *   thermal.onCriticalHeat = { /* desactivar boost */ }
 *   thermal.setup()
 */
class ThermalController(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Estado térmico actual
    private val _thermalStatus = MutableStateFlow(ThermalStatus.NORMAL)
    val thermalStatus: StateFlow<ThermalStatus> = _thermalStatus.asStateFlow()

    // Callback cuando hay calor crítico (para que GameSessionManager desactive boost)
    var onCriticalHeat: (() -> Unit)? = null
    var onSevereHeat: (() -> Unit)? = null

    // Cooldown para evitar reacciones repetidas
    private var lastActionTimestamp = 0L
    private val cooldownMs = 120_000L // 2 minutos

    // Flag de inicialización
    private var isSetup = false

    /**
     * Configura el monitoreo térmico.
     * Llama una sola vez — safe para múltiples llamadas.
     */
    fun setup() {
        if (isSetup) return
        isSetup = true

        // Capa 1: ThermalManager API (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setupThermalManagerApi()
        }

        // Capa 2: Monitoreo vía sysfs (fallback para dispositivos sin API)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setupSysfsFallback()
        }

        Log.d("ThermalController", "✅ Monitoreo térmico iniciado")
    }

    private fun setupThermalManagerApi() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.addThermalStatusListener(
                ContextCompat.getMainExecutor(context)
            ) { status ->
                val now = System.currentTimeMillis()
                if (now - lastActionTimestamp < cooldownMs) return@addThermalStatusListener

                when (status) {
                    PowerManager.THERMAL_STATUS_NONE,
                    PowerManager.THERMAL_STATUS_LIGHT -> {
                        _thermalStatus.value = ThermalStatus.NORMAL
                    }
                    PowerManager.THERMAL_STATUS_MODERATE -> {
                        _thermalStatus.value = ThermalStatus.WARM
                    }
                    PowerManager.THERMAL_STATUS_SEVERE -> {
                        _thermalStatus.value = ThermalStatus.SEVERE
                        lastActionTimestamp = now
                        Log.w("ThermalController", "⚠️ Calor severo detectado")
                        onSevereHeat?.invoke()
                    }
                    PowerManager.THERMAL_STATUS_CRITICAL -> {
                        _thermalStatus.value = ThermalStatus.CRITICAL
                        lastActionTimestamp = now
                        Log.e("ThermalController", "🔥 Calor crítico — desactivando boost")
                        onCriticalHeat?.invoke()
                    }
                    PowerManager.THERMAL_STATUS_EMERGENCY -> {
                        _thermalStatus.value = ThermalStatus.EMERGENCY
                        lastActionTimestamp = now
                        Log.e("ThermalController", "🔥🔥 EMERGENCIA térmica")
                        onCriticalHeat?.invoke()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ThermalController", "No se pudo registrar ThermalManager: ${e.message}")
            // Fallback a sysfs si la API falla
            setupSysfsFallback()
        }
    }

    private fun setupSysfsFallback() {
        scope.launch {
            val thermalFiles = listOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp",
                "/sys/devices/virtual/thermal/thermal_zone0/temp"
            )

            while (isActive) {
                try {
                    for (file in thermalFiles) {
                        try {
                            val text = java.io.File(file).readText().trim()
                            val temp = text.toFloat()
                            val tempC = if (temp > 1000) temp / 1000 else temp
                            val now = System.currentTimeMillis()

                            when {
                                tempC >= 55f -> {
                                    if (now - lastActionTimestamp >= cooldownMs) {
                                        _thermalStatus.value = ThermalStatus.CRITICAL
                                        lastActionTimestamp = now
                                        Log.e("ThermalController", "🔥 Temp $tempC°C — crítico vía sysfs")
                                        onCriticalHeat?.invoke()
                                    }
                                }
                                tempC >= 50f -> {
                                    if (now - lastActionTimestamp >= cooldownMs) {
                                        _thermalStatus.value = ThermalStatus.SEVERE
                                        lastActionTimestamp = now
                                        Log.w("ThermalController", "⚠️ Temp $tempC°C — severo vía sysfs")
                                        onSevereHeat?.invoke()
                                    }
                                }
                                tempC >= 45f -> {
                                    _thermalStatus.value = ThermalStatus.WARM
                                }
                                else -> {
                                    _thermalStatus.value = ThermalStatus.NORMAL
                                }
                            }
                            break // Usar el primer archivo que se pueda leer
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}

                delay(10_000) // Cada 10 segundos
            }
        }
    }

    /**
     * Lee la temperatura actual del CPU.
     * @return temperatura en °C, 0 si no se puede leer
     */
    fun getCurrentTemp(): Float {
        val thermalFiles = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp"
        )
        for (file in thermalFiles) {
            try {
                val temp = java.io.File(file).readText().trim().toFloat()
                return if (temp > 1000) temp / 1000 else temp
            } catch (_: Exception) {}
        }
        return 0f
    }

    fun shutdown() {
        scope.cancel()
        Log.d("ThermalController", "Monitoreo térmico detenido")
    }
}

enum class ThermalStatus {
    NORMAL,     // < 45°C
    WARM,       // 45-49°C
    SEVERE,     // 50-54°C
    CRITICAL,   // 55°C+
    EMERGENCY   // Emergencia (Android API)
}

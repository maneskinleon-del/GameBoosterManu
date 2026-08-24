package com.example.data.repository

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import com.example.data.PreferenceManager
import com.example.manager.ShizukuExecutor
import com.example.service.GameBoostService
import com.example.service.UnifiedAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Estados de las dependencias del sistema.
 * Usa terminología inequívoca para evitar ambigüedad.
 */
sealed interface DependencyState {
    data class Shizuku(
        val state: ShizukuState,
        val detail: String = ""
    ) : DependencyState {
        enum class ShizukuState {
            ON,           // Shizuku disponible y con permiso
            OFF,          // Shizuku no disponible o sin permiso
            NOT_INSTALLED, // Shizuku no instalado (pre-v11)
            UNKNOWN
        }
    }

    data class Accessibility(
        val state: AccessibilityState,
        val detail: String = ""
    ) : DependencyState {
        enum class AccessibilityState {
            ACTIVE,       // Servicio habilitado y corriendo
            INACTIVE,     // Servicio deshabilitado o no corriendo
            UNKNOWN
        }
    }

    data class BatteryOptimization(
        val state: BatteryState,
        val detail: String = ""
    ) : DependencyState {
        enum class BatteryState {
            UNRESTRICTED,  // App excluida de optimización de batería
            OPTIMIZED,     // App sujeta a optimización de batería (puede matar servicios)
            UNKNOWN
        }
    }

    data class GameBoostService(
        val state: ServiceState,
        val detail: String = ""
    ) : DependencyState {
        enum class ServiceState {
            RUNNING,      // Servicio corriendo (runtime)
            STOPPED,      // Servicio detenido
            CONFIGURED_BUT_STOPPED, // Configurado para correr pero no está corriendo
            UNKNOWN
        }
    }
}

/**
 * Estado agregado de todas las dependencias del sistema.
 * Se actualiza reactivamente y se expone via StateFlow para la UI.
 */
data class SystemDependencyState(
    val shizuku: DependencyState.Shizuku,
    val accessibility: DependencyState.Accessibility,
    val batteryOptimization: DependencyState.BatteryOptimization,
    val gameBoostService: DependencyState.GameBoostService,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    /** Indica si hay algún problema crítico */
    val hasIssues: Boolean
        get() = shizuku.state != DependencyState.Shizuku.ShizukuState.ON ||
                accessibility.state != DependencyState.Accessibility.AccessibilityState.ACTIVE ||
                batteryOptimization.state == DependencyState.BatteryOptimization.BatteryState.OPTIMIZED ||
                gameBoostService.state != DependencyState.GameBoostService.ServiceState.RUNNING

    /** Resumen legible para logging */
    fun toLogString(): String {
        return buildString {
            appendLine("[DependencyState]")
            appendLine("  Shizuku: ${shizuku.state} ${shizuku.detail}")
            appendLine("  Accessibility: ${accessibility.state} ${accessibility.detail}")
            appendLine("  Battery: ${batteryOptimization.state} ${batteryOptimization.detail}")
            appendLine("  GameBoostService: ${gameBoostService.state} ${gameBoostService.detail}")
        }
    }
}

/**
 * Manager centralizado para el estado de dependencias del sistema.
 * Centraliza la lógica de detección y expone StateFlow reactivo.
 */
class DependencyStateManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val contextRef = java.lang.ref.WeakReference(context)

    private val _dependencyState = MutableStateFlow(SystemDependencyState(
        shizuku = DependencyState.Shizuku(DependencyState.Shizuku.ShizukuState.UNKNOWN),
        accessibility = DependencyState.Accessibility(DependencyState.Accessibility.AccessibilityState.UNKNOWN),
        batteryOptimization = DependencyState.BatteryOptimization(DependencyState.BatteryOptimization.BatteryState.UNKNOWN),
        gameBoostService = DependencyState.GameBoostService(DependencyState.GameBoostService.ServiceState.UNKNOWN)
    ))
    val dependencyState: StateFlow<SystemDependencyState> = _dependencyState.asStateFlow()

    private var lastLoggedState: SystemDependencyState? = null

    /**
     * Inicia el monitoreo periódico del estado de dependencias.
     * Debe llamarse una sola vez al inicializar la app.
     */
    fun start() {
        scope.launch {
            // Verificación inicial inmediata
            refreshAll()

            // Loop periódico
            while (true) {
                try {
                    delay(15000) // Cada 15 segundos
                    refreshAll()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("DependencyState", "Error en loop de dependencias: ${e.message}")
                    delay(5000)
                }
            }
        }
    }

    /**
     * Fuerza una actualización inmediata de todos los estados.
     * Debe llamarse en onResume y cuando el usuario regresa de Settings.
     */
    fun refreshAll() {
        scope.launch {
            val ctx = context
            
            val shizuku = checkShizukuState(ctx)
            val accessibility = checkAccessibilityState(ctx)
            val battery = checkBatteryOptimization(ctx)
            val service = checkGameBoostServiceState(ctx)

            val newState = SystemDependencyState(
                shizuku = shizuku,
                accessibility = accessibility,
                batteryOptimization = battery,
                gameBoostService = service,
                lastUpdated = System.currentTimeMillis()
            )

            _dependencyState.value = newState
            
            // Log solo si cambió algo significativo
            if (lastLoggedState == null || hasSignificantChange(lastLoggedState!!, newState)) {
                Log.d("DependencyState", newState.toLogString())
                lastLoggedState = newState
            }
        }
    }

    private fun hasSignificantChange(old: SystemDependencyState, new: SystemDependencyState): Boolean {
        return old.shizuku.state != new.shizuku.state ||
               old.accessibility.state != new.accessibility.state ||
               old.batteryOptimization.state != new.batteryOptimization.state ||
               old.gameBoostService.state != new.gameBoostService.state
    }

    /**
     * Verifica el estado de Shizuku usando la API existente.
     */
    private suspend fun checkShizukuState(ctx: Context): DependencyState.Shizuku {
        return withContext(Dispatchers.IO) {
            val state = ShizukuExecutor.checkState()
            when (state) {
                ShizukuExecutor.State.Ready -> 
                    DependencyState.Shizuku(DependencyState.Shizuku.ShizukuState.ON, "Conectado y autorizado")
                ShizukuExecutor.State.PermissionDenied -> 
                    DependencyState.Shizuku(DependencyState.Shizuku.ShizukuState.OFF, "Permiso denegado")
                ShizukuExecutor.State.NotRunning -> 
                    DependencyState.Shizuku(DependencyState.Shizuku.ShizukuState.OFF, "Shizuku no corriendo")
                ShizukuExecutor.State.NotInstalled -> 
                    DependencyState.Shizuku(DependencyState.Shizuku.ShizukuState.NOT_INSTALLED, "Shizuku no instalado (pre-v11)")
            }
        }
    }

    /**
     * Verifica el estado del AccessibilityService usando AccessibilityManager.
     * NO abre Settings, solo lee el estado.
     */
    private suspend fun checkAccessibilityState(ctx: Context): DependencyState.Accessibility {
        return withContext(Dispatchers.Main.immediate) {
            val am = ctx.getSystemService(AccessibilityManager::class.java) ?: 
                return@withContext DependencyState.Accessibility(DependencyState.Accessibility.AccessibilityState.UNKNOWN, "AccessibilityManager no disponible")
            
            val expected = ComponentName(ctx, UnifiedAccessibilityService::class.java)
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            
            val isEnabled = enabledServices.any { 
                it.resolveInfo.serviceInfo.packageName == expected.packageName &&
                it.resolveInfo.serviceInfo.name == expected.className
            }
            
            val isRunning = UnifiedAccessibilityService.isServiceRunning
            
            if (isEnabled && isRunning) {
                DependencyState.Accessibility(DependencyState.Accessibility.AccessibilityState.ACTIVE, "Servicio habilitado y corriendo")
            } else if (isEnabled && !isRunning) {
                DependencyState.Accessibility(DependencyState.Accessibility.AccessibilityState.INACTIVE, "Habilitado pero servicio no corriendo")
            } else {
                DependencyState.Accessibility(DependencyState.Accessibility.AccessibilityState.INACTIVE, "Servicio deshabilitado")
            }
        }
    }

    /**
     * Verifica el estado de optimización de batería usando PowerManager.
     * SIN RESTRICCIÓN = app excluida de optimización (isIgnoringBatteryOptimizations == true)
     * OPTIMIZADA = app sujeta a optimización (puede ser matada por doze/app standby)
     */
    @SuppressLint("BatteryLife")
    private suspend fun checkBatteryOptimization(ctx: Context): DependencyState.BatteryOptimization {
        return withContext(Dispatchers.IO) {
            val pm = ctx.getSystemService(PowerManager::class.java) ?: 
                return@withContext DependencyState.BatteryOptimization(DependencyState.BatteryOptimization.BatteryState.UNKNOWN, "PowerManager no disponible")
            
            val packageName = ctx.packageName
            
            // API 23+ (Marshmallow+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
                if (isIgnoring) {
                    DependencyState.BatteryOptimization(DependencyState.BatteryOptimization.BatteryState.UNRESTRICTED, "App excluida de optimización de batería")
                } else {
                    DependencyState.BatteryOptimization(DependencyState.BatteryOptimization.BatteryState.OPTIMIZED, "Sujeta a optimización (Doze/App Standby)")
                }
            } else {
                // Pre-Marshmallow: no hay API de optimización de batería
                DependencyState.BatteryOptimization(DependencyState.BatteryOptimization.BatteryState.UNRESTRICTED, "API < 23, sin optimización de batería")
            }
        }
    }

    /**
     * Verifica el estado REAL del GameBoostService.
     * Diferencia entre:
     * - RUNNING: servicio corriendo (runtime)
     * - CONFIGURED_BUT_STOPPED: configurado para correr pero no está corriendo
     * - STOPPED: configurado para no correr
     */
    private suspend fun checkGameBoostServiceState(ctx: Context): DependencyState.GameBoostService {
        return withContext(Dispatchers.IO) {
            val isRunning = GameBoostService.isRunning
            val configured = PreferenceManager.isServiceRunning(ctx)
            
            when {
                isRunning -> DependencyState.GameBoostService(
                    DependencyState.GameBoostService.ServiceState.RUNNING, 
                    "Servicio corriendo (runtime)"
                )
                configured -> DependencyState.GameBoostService(
                    DependencyState.GameBoostService.ServiceState.CONFIGURED_BUT_STOPPED, 
                    "Configurado para correr pero no está corriendo (posible kill por LMK/MyOS)"
                )
                else -> DependencyState.GameBoostService(
                    DependencyState.GameBoostService.ServiceState.STOPPED, 
                    "Servicio detenido (configurado)"
                )
            }
        }
    }

    fun stop() {
        scope.cancel()
    }
}
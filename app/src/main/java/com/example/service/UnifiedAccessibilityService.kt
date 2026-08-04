package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.repository.GameBoostRepository
import com.example.manager.AdsPointerManager
import kotlinx.coroutines.*

/**
 * Servicio de accesibilidad unificado que reemplaza:
 * - GameOptimizerAccessibilityService (detección de foreground + cambio de perfiles)
 * - AdsPointerAccessibilityService (monitoreo del botón ADS en Free Fire)
 *
 * Un solo servicio, un solo lastPackage, sin duplicación de eventos.
 *
 * REGLAS:
 * - TYPE_WINDOW_STATE_CHANGED → dispara setForegroundApp() (detección de juego)
 * - TYPE_WINDOW_CONTENT_CHANGED → SOLO si simulatedGame != null (monitoreo botón ADS)
 * - El filtro está en el código, no en el XML de configuración.
 */
class UnifiedAccessibilityService : AccessibilityService() {

    companion object {
        var instance: UnifiedAccessibilityService? = null
        var isServiceRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var repository: GameBoostRepository? = null
    private var adsPointerManager: AdsPointerManager? = null

    @Volatile
    private var lastPackage: String? = null

    // Monitoreo del botón ADS (solo cuando hay juego activo)
    @Volatile
    private var aimMonitorJob: Job? = null

    // Throttle para TYPE_WINDOW_CONTENT_CHANGED (evita saturar)
    @Volatile
    private var lastContentEventTs = 0L
    private val CONTENT_EVENT_THROTTLE_MS = 500L

    private val AIM_IDS = listOf(
        "com.dts.freefireth:id/aim_button",
        "com.dts.freefiremax:id/aim_button",
        "com.garena.game.kgth:id/aim_button"
    )

    // Paquetes de sistema e IMEs que nunca deben disparar lógica de entrada/salida de juego
    private val ignoredPackages = setOf(
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "com.touchtype.swiftkey",
        "com.samsung.android.honeyboard",
        "android",
        "com.android.settings",
        "com.google.android.gms",
        "com.android.permissioncontroller"
    )

    // ─────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        val repo = GameBoostRepository.getInstance(applicationContext) ?: run {
            Log.e("UnifiedA11y", "GameBoostRepository no disponible; AccessibilityService no inicializado")
            return
        }
        repository = repo
        adsPointerManager = AdsPointerManager(applicationContext, repo)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true

        // Configuración en runtime: TYPE_WINDOW_CONTENT_CHANGED se declara aquí
        // pero el código lo ignora activamente salvo cuando hay juego detectado.
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info

        repository?.logAsync("INFO", "Accessibility", "UnifiedAccessibilityService connected")
    }

    override fun onInterrupt() {
        // Manejar interrupciones (requerido por AccessibilityService)
    }

    override fun onDestroy() {
        super.onDestroy()
        aimMonitorJob?.cancel()
        serviceScope.cancel()
        instance = null
        isServiceRunning = false
    }

    // ─────────────────────────────────────────────────────────
    // Manejo de eventos de accesibilidad
    // ─────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Ignoramos TYPE_WINDOW_CONTENT_CHANGED para detección de foreground.
                // Solo nos interesa si YA hay un juego activo (para buscar el botón ADS).
                // Esto evita que eventos de teclado, notificaciones o cambios internos
                // de UI disparen lógica de juego.
                if (repository?.simulatedGame?.value != null) {
                    handleContentChangedThrottled(packageName)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // Detección de foreground (reemplaza GameOptimizerAccessibilityService)
    // ─────────────────────────────────────────────────────────

    private fun handleWindowStateChanged(packageName: String) {
        // ── LOG: checkpoint #1 ──
        Log.d("FSM_DIAG", "UnifiedA11y.WINDOW_STATE_CHANGED: pkg=$packageName ts=${System.currentTimeMillis()}")

        // Filtrar paquetes del sistema y el propio paquete
        if (packageName == applicationContext.packageName ||
            ignoredPackages.contains(packageName)) {
            Log.d("FSM_DIAG", "UnifiedA11y SKIP (ignored/own): $packageName")
            return
        }

        // Deduplicación: solo procesar si la app cambió realmente
        if (packageName != lastPackage) {
            lastPackage = packageName

            // ── LOG: checkpoint #2 ──
            Log.d("FSM_DIAG", "UnifiedA11y.setForegroundApp($packageName) ts=${System.currentTimeMillis()}")

            repository?.setForegroundApp(packageName)

            // Si cambiamos de app, cancelamos el monitoreo del botón ADS
            // (el nuevo paquete será evaluado en el próximo WINDOW_CONTENT_CHANGED
            // si resulta ser un juego)
            aimMonitorJob?.cancel()
            aimMonitorJob = null
        } else {
            Log.d("FSM_DIAG", "UnifiedA11y SKIP (dup): $packageName")
        }
    }

    // ─────────────────────────────────────────────────────────
    // Monitoreo del botón ADS (reemplaza AdsPointerAccessibilityService)
    // ─────────────────────────────────────────────────────────

    private fun handleContentChangedThrottled(packageName: String) {
        val now = System.currentTimeMillis()
        if (now - lastContentEventTs < CONTENT_EVENT_THROTTLE_MS) return
        lastContentEventTs = now

        // Solo lanzar un monitor si no hay uno corriendo ya
        if (aimMonitorJob?.isActive != true) {
            aimMonitorJob = serviceScope.launch {
                findAndMonitorAimButton()
            }
        }
    }

    private suspend fun findAndMonitorAimButton() {
        try {
            val root = rootInActiveWindow ?: return
            val aimButton = findAimButton(root)
            if (aimButton != null) {
                monitorAimButton(aimButton)
            }
        } catch (e: Exception) {
            // findAccessibilityNodeInfosByViewId puede lanzar IllegalStateException
            // si la ventana se recicla antes de completar la búsqueda.
            Log.w("UnifiedA11y", "findAndMonitorAimButton: ${e.message}")
        }
    }

    private fun findAimButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in AIM_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty() && nodes[0].isVisibleToUser) {
                return nodes[0]
            }
        }
        return null
    }

    private fun monitorAimButton(button: AccessibilityNodeInfo) {
        aimMonitorJob?.cancel()
        aimMonitorJob = serviceScope.launch {
            var lastState = false
            while (isActive) {
                try {
                    val currentState = button.isSelected
                    if (currentState != lastState) {
                        lastState = currentState
                        if (currentState) {
                            adsPointerManager?.applyAdsSpeed()
                        } else {
                            adsPointerManager?.applyNormalSpeed()
                        }
                    }
                    delay(140)
                } catch (e: Exception) {
                    break
                }
            }
        }
    }
}

package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.PreferenceManager
import com.example.manager.ProfileManager
import com.example.manager.ShizukuExecutor
import com.example.ui.FloatingPanelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class GameBoostService : Service() {
    
    companion object {
        private const val TAG = "GameBoostService"
        private const val NOTIFICATION_ID = 1001
        /** PR3 (W2b): ventana para que la proyección materialice el hide antes del stop. */
        private const val OVERLAY_HIDE_GRACE_MS = 50L
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        
        var isRunning = false
            private set
        var currentProfile = ProfileManager.ProfileType.BALANCED
            private set
            
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ Servicio creado")
        isRunning = true

        // PR2 (B6): la re-detección ahora vive en el repository — espera el gate de
        // recovery/init (GameDetector listo) y se re-dispara en OnBinderReceived.
        try {
            com.example.data.repository.GameBoostRepository.getInstance(this)
                .redetectForegroundGame()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo programar re-detect foreground: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        // Iniciar watchdog anti-LMK (AlarmManager-based, persiste aunque maten el proceso)
        ServiceWatchdogReceiver.schedule(this)
        
        currentProfile = PreferenceManager.getProfile(this) ?: ProfileManager.ProfileType.BALANCED

        // Restaurar DPI y puntero guardados en cada inicio (no solo con ACTION_START)
        restoreSavedSettings()

        startMonitoring()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_START -> handleStart()
                ACTION_STOP -> handleStop()
            }
        }
        // START_STICKY + redelivery intent: asegura que el servicio se reinicie
        // automáticamente si es muerto por el sistema, y reintenta el último intent.
        return START_REDELIVER_INTENT
    }
    
    private fun handleStart() {
        Log.d(TAG, "Service started")
        // F4: si hay un recovery pendiente (residuos de una muerte previa), NO
        // re-aplicar perfiles — el observador de profilesFlow re-boostearía el
        // dispositivo y pisaría el baseline. La reaplicación solo es válida con
        // la sesión limpia (IDLE).
        try {
            val repo = com.example.data.repository.GameBoostRepository.getInstance(this)
            if (repo.boostSession.currentState() != com.example.manager.boostsession.BoostSessionState.IDLE) {
                Log.w(TAG, "handleStart: recovery pendiente (${repo.boostSession.currentState()}) — skip re-apply de perfil")
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleStart: no se pudo verificar sesión de boost: ${e.message}")
        }
        ProfileManager.init(this)
        currentProfile = ProfileManager.getCurrentProfile()
        ProfileManager.applyProfile(currentProfile)
        restoreSavedSettings()
        updateNotification("Active Profile: ${currentProfile.displayName}")
        
        // PR3 (W3): ELIMINADO el show() directo del arranque. Era redundante (si
        // isBoostActive ya es true, la PRIMERA emisión del combine de la proyección
        // — al suscribirse startMonitoring — muestra el overlay) y era un writer
        // fuera de la proyección (rompía el único-writer R1 C5).
    }

    private fun restoreSavedSettings() {
        serviceScope.launch {
            // F3B-Fix Issue 2: NO competir con el recovery del arranque. Este
            // writer toca pointer_speed (key del contrato F4); si el recovery
            // está resolviendo el baseline, esperar a que termine.
            try {
                val repo = com.example.data.repository.GameBoostRepository.getInstance(this@GameBoostService)
                repo.awaitRecoveryComplete()
                // PR1-NS3: además del recovery gate, NO correr durante una sesión
                // de boost activa (APPLYING/ACTIVE/RESTORING/RECOVERY_REQUIRED).
                // Este writer escribe pointer_speed por una ruta que NO pasa por el
                // funnel SSOT (ShizukuExecutor.runCommand directo): si corre durante
                // el boost, el appliedValue de pointer_speed queda desactualizado y
                // el restore degrada a Caso B (CONFLICT) — el valor original se
                // pierde para el restore verificado.
                val sessionState = repo.boostSession.currentState()
                if (sessionState != com.example.manager.boostsession.BoostSessionState.IDLE) {
                    Log.w(TAG, "restoreSavedSettings: skip — sesión de boost activa ($sessionState); los valores los aplica/restaura el boost")
                    return@launch
                }
            } catch (e: Exception) {
                Log.w(TAG, "restoreSavedSettings: no se pudo consultar gates: ${e.message}")
            }
            val savedDpi = PreferenceManager.getDpi(this@GameBoostService)
            if (savedDpi != -1) {
                val clampedDpi = savedDpi.coerceAtMost(PreferenceManager.MAX_DPI)
                if (clampedDpi != savedDpi) {
                    Log.w(TAG, "Saved DPI $savedDpi excede MAX_DPI, ajustando a $clampedDpi")
                    PreferenceManager.saveDpi(this@GameBoostService, clampedDpi)
                }
                Log.d(TAG, "Restoring saved DPI: $clampedDpi")
                ShizukuExecutor.runCommand("wm density $clampedDpi")
            }
            
            // PR1-NS3: solo escribir si el usuario guardó un valor. getPointerSpeed()
            // tiene default 50: escribirlo sin save previo inyectaba un valor no
            // elegido que además contaminaba el próximo baseline de pointer_speed.
            if (PreferenceManager.isPointerSpeedSaved(this@GameBoostService)) {
                val savedPointerSpeed = PreferenceManager.getPointerSpeed(this@GameBoostService)
                Log.d(TAG, "Restoring saved Pointer Speed: $savedPointerSpeed")
                ShizukuExecutor.runCommand("settings put system pointer_speed $savedPointerSpeed")
            } else {
                Log.d(TAG, "restoreSavedSettings: sin pointer_speed guardado — no se escribe")
            }
        }
    }
    
    private fun handleStop() {
        Log.d(TAG, "Service stopping")
        // PR3 (W2b, DECISIÓN B — auditada): NO se llama hide() directo. Un hide()
        // desde acá contradiría el único-writer (R1 C5) y podía dejar overlay fantasma
        // si otro código re-mostraba el panel. Se publica la INTENCIÓN por el canal de
        // proyección y se da una VENTANA (OVERLAY_HIDE_GRACE_MS) al observer para
        // materializarla: el observer vive en serviceScope, que sigue vivo hasta
        // onDestroy (posterior a stopSelf) — por eso el stop se DIFIERE, no se adelanta.
        // Alternativas evaluadas: A (hide directo, rompe el invariante) y C (flush
        // NonCancellable + máquina de estados, más código para una ventana de 50ms).
        // ¿Por qué 50ms? ESTIMADO, no medido: el camino completo es una emisión de
        // StateFlow (síncrona) + un dispatch IO→Main del collector + un post al
        // mainHandler del FPM — 3 saltos de main thread, ~1 frame cada uno. Valores
        // mayores (100/200ms) solo retrasan el teardown visible del servicio SIN
        // mejorar la corrección: si 50ms no alcanzan, el modo de falla es overlay
        // fantasma — y eso se arregla con C, no con un número más grande.
        // ESCALADO A C si un test de dispositivo muestra overlay fantasma tras el OFF.
        // Riesgo residual documentado (no se arregla acá): si serviceScope se cancela
        // ANTES de que el collector procese la emisión de setOverlayRequested(false)
        // (path anómalo: stopSelf externo, o kill del proceso entre el request y el
        // hide), el StateFlow queda en false pero nadie lo materializa → fantasma
        // hasta el próximo cambio de estado. En el path normal de handleStop no ocurre
        // (scope vivo durante la ventana). Ese hueco ES la justificación de C.
        try {
            com.example.data.repository.GameBoostRepository.getInstance(this)
                .setOverlayRequested(false)
        } catch (e: Exception) {
            Log.w(TAG, "handleStop: no se pudo publicar overlayRequest=false: ${e.message}")
        }
        // No forzar BALANCED al detener el servicio: el perfil activo lo gobierna
        // GameSessionManager (Room como única autoridad). applyProfile(BALANCED) aquí
        // era el autor estructural del síntoma "perfil → Balanced" (2026-09-12):
        // cada OFF de boost pisaba el perfil —manual o auto— elegido por el usuario.
        // Los settings del boost los restaura BoostSession.restoreVerified();
        // el perfil se restaura cuando corresponda (re-entry de juego o usuario).
        isRunning = false
        PreferenceManager.setServiceRunning(this, false)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            stopForeground(true)
            stopSelf()
        }, OVERLAY_HIDE_GRACE_MS)
        // onDestroy() se encarga de cancelar el watchdog
    }
    
    private fun startMonitoring() {
        Log.d(TAG, "startMonitoring() called")
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            Log.d(TAG, "Monitoring coroutine started")
            val repository = com.example.data.repository.GameBoostRepository.getInstance(this@GameBoostService)
            
            // Observe metrics and update floating panel
            launch {
                Log.d(TAG, "Starting metrics observer")
                try {
                    repository.systemMetrics.collect { metrics ->
                        FloatingPanelManager.getInstance(this@GameBoostService).updateMetrics(metrics)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Metrics observer error: ${e.message}")
                }
            }

            // Observe active profile and update floating panel
            launch {
                Log.d(TAG, "Starting profile observer")
                try {
                    repository.profilesFlow.collect { profiles ->
                        val activeProfileEntity = profiles.find { it.isActive }
                        activeProfileEntity?.let { entity ->
                            val type = when (entity.id) {
                                "extreme" -> ProfileManager.ProfileType.EXTREME
                                "ff_mouse" -> ProfileManager.ProfileType.ADS
                                "free_fire_touch" -> ProfileManager.ProfileType.FREE_FIRE_TOUCH
                                "balanced" -> ProfileManager.ProfileType.BALANCED
                                "battery_saver" -> ProfileManager.ProfileType.POWER_SAVE
                                else -> {
                                    ProfileManager.ProfileType.entries.find {
                                        it.displayName.contains(entity.name, true) ||
                                        entity.name.contains(it.name, true)
                                    }
                                }
                            }

                            // F4: durante un recovery pendiente/curso, la re-aplicación
                            // del perfil persistido pisaría el baseline que el recovery
                            // está restaurando. Solo re-aplicar con sesión limpia.
                            val sessionState = repository.boostSession.currentState()
                            if (sessionState != com.example.manager.boostsession.BoostSessionState.IDLE) {
                                Log.d(TAG, "Profile observer: skip re-apply (session=$sessionState)")
                                return@collect
                            }

                            type?.let {
                                if (currentProfile != it) {
                                    Log.d(TAG, "Service: Profile changed to ${it.displayName}")
                                    currentProfile = it
                                    ProfileManager.applyProfile(it)
                                    FloatingPanelManager.getInstance(this@GameBoostService).updateProfile(it)
                                    updateNotification("Active Profile: ${it.displayName}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Profile observer error: ${e.message}")
                }
            }

            // Observe boost state + overlay request → show/hide panel
            // R1 (C5): el servicio es el ÚNICO writer de FloatingPanelManager.show()/hide().
            // La visibilidad es una PROYECCIÓN del estado combinado (boost activo,
            // request de la UI); ningún otro componente decide la visibilidad.
            // request: null = seguir al boost; false = usuario lo ocultó (R3).
            launch {
                Log.d(TAG, "Starting overlay projection observer (R1 C5: single writer)")
                try {
                    combine(
                        repository.isBoostActive,
                        repository.overlayRequest
                    ) { boost, request -> request ?: boost }
                        .collect { wantVisible ->
                            Log.d(TAG, "🪟 Overlay projection: wantVisible=$wantVisible")
                            withContext(Dispatchers.Main) {
                                if (wantVisible) {
                                    FloatingPanelManager.getInstance(this@GameBoostService).show()
                                } else {
                                    FloatingPanelManager.getInstance(this@GameBoostService).hide()
                                    updateNotification("Optimizer Service Running")
                                }
                            }
                        }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Overlay projection observer error: ${e.message}")
                }
            }

            // PR3 (W2): observer de game-detection ELIMINADO. Era un SEGUNDO writer de
            // show() en paralelo a la proyección (y nunca ocultaba al salir del juego,
            // game == null). Redundante por construcción: simulateGameLaunchInternal
            // llama toggleBoost() → isBoostActive flipa → el combine de la proyección
            // reacciona. Ninguna dimensión nueva en el combine (decisión auditada).

            // Mantener viva la corrutina mientras el servicio esté activo
            try {
                while (isRunning) {
                    delay(5000)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Monitoring cancelled")
            }
            Log.d(TAG, "Monitoring coroutine ending (isRunning=false)")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        monitorJob?.cancel()
        serviceScope.cancel()
        // Cancelar watchdog cuando el servicio se detiene intencionalmente
        ServiceWatchdogReceiver.cancel(this)
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "gameboost_channel",
                "Game Boost Pro Service",
                NotificationManager.IMPORTANCE_HIGH  // Alta importancia para evitar que LMK mate el servicio
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, "gameboost_channel")
            .setContentTitle("Game Boost Pro")
            .setContentText("Optimizer Service Running")
            .setSmallIcon(R.drawable.ic_boost)
            .setContentIntent(pendingIntent)
            .setOngoing(true)  // Previene que el usuario descarte la notificación
            .setPriority(NotificationCompat.PRIORITY_MAX)  // Prioridad máxima para evitar LMK
            .build()
    }
    
    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, "gameboost_channel")
            .setContentTitle("Game Boost Pro")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_boost)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }
}

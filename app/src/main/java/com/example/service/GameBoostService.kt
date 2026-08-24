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

class GameBoostService : Service() {
    
    companion object {
        private const val TAG = "GameBoostService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE_PROFILE = "ACTION_UPDATE_PROFILE"
        
        var isRunning = false
            private set
        var currentProfile = ProfileManager.ProfileType.BALANCED
            private set
            
        var onProfileChanged: ((ProfileManager.ProfileType) -> Unit)? = null
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ Servicio creado")
        isRunning = true

        // Re-detectar el juego en foreground al arrancar (Opción B): solo restaurar
        // boost si hay un juego en primer plano. Delay + re-intento porque la consulta
        // inmediata da null (UsageStats no alcanza a registrar y el fallback shell tampoco).
        try {
            val repo = com.example.data.repository.GameBoostRepository.getInstance(this)
            serviceScope.launch {
                try {
                    delay(2000)
                    var fg = repo.gameDetector.getCurrentForegroundApp()
                    if (fg.isNullOrBlank()) {
                        delay(1500)
                        fg = repo.gameDetector.getCurrentForegroundApp()
                    }
                    if (!fg.isNullOrBlank() && repo.gameDetector.isGamePackage(fg)) {
                        repo.simulateGameLaunch(fg)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error en re-detección foreground: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo re-detectar foreground: ${e.message}")
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
                ACTION_UPDATE_PROFILE -> {
                    val profileName = intent.getStringExtra("profile")
                    profileName?.let {
                        try {
                            val profile = ProfileManager.ProfileType.valueOf(it)
                            handleProfileChange(profile)
                        } catch (e: Exception) {}
                    }
                }
            }
        }
        // START_STICKY + redelivery intent: asegura que el servicio se reinicie
        // automáticamente si es muerto por el sistema, y reintenta el último intent.
        return START_REDELIVER_INTENT
    }
    
    private fun handleStart() {
        Log.d(TAG, "Service started")
        ProfileManager.init(this)
        currentProfile = ProfileManager.getCurrentProfile()
        ProfileManager.applyProfile(currentProfile)
        restoreSavedSettings()
        updateNotification("Active Profile: ${currentProfile.displayName}")
        
        // Asegurar que el overlay se muestre si el boost ya está activo
        // (ej: cuando el servicio es reiniciado por el watchdog)
        try {
            val repo = com.example.data.repository.GameBoostRepository.getInstance(this)
            if (repo.isBoostActive.value) {
                Log.d(TAG, "Boost already active on start, ensuring overlay visible")
                FloatingPanelManager.getInstance(this).show()
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleStart: could not check boost state: ${e.message}")
        }
    }

    private fun restoreSavedSettings() {
        serviceScope.launch {
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
            
            val savedPointerSpeed = PreferenceManager.getPointerSpeed(this@GameBoostService)
            Log.d(TAG, "Restoring saved Pointer Speed: $savedPointerSpeed")
            ShizukuExecutor.runCommand("settings put system pointer_speed $savedPointerSpeed")
        }
    }
    
    private fun handleStop() {
        Log.d(TAG, "Service stopping")
        ProfileManager.restoreDefaults()
        isRunning = false
        PreferenceManager.setServiceRunning(this, false)
        stopForeground(true)
        stopSelf()
        // onDestroy() se encarga de cancelar el watchdog
    }
    
    private fun handleProfileChange(profile: ProfileManager.ProfileType) {
        currentProfile = profile
        ProfileManager.applyProfile(profile)
        updateNotification("Active Profile: ${profile.displayName}")
        onProfileChanged?.invoke(profile)
        FloatingPanelManager.getInstance(this).updateProfile(profile)
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

            // Observe active boost state and show/hide panel
            launch {
                Log.d(TAG, "Starting boost state observer")
                try {
                    repository.isBoostActive.collect { active ->
                        Log.d(TAG, "🚀 Boost Active Flow emission: $active")
                        withContext(Dispatchers.Main) {
                            if (active) {
                                FloatingPanelManager.getInstance(this@GameBoostService).show()
                            } else {
                                FloatingPanelManager.getInstance(this@GameBoostService).hide()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Boost observer error: ${e.message}")
                }
            }

            // Asegurar que el panel se muestre cuando se detecta un juego, si el boost está activo
            launch {
                Log.d(TAG, "Starting game detection observer")
                try {
                    repository.simulatedGame.collect { game ->
                        Log.d(TAG, "🎮 Simulated Game Flow emission: $game (Boost=${repository.isBoostActive.value})")
                        if (game != null && repository.isBoostActive.value) {
                            withContext(Dispatchers.Main) {
                                FloatingPanelManager.getInstance(this@GameBoostService).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Game detection observer error: ${e.message}")
                }
            }

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

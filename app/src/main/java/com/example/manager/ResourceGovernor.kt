package com.example.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.example.data.PreferenceManager
import com.example.data.repository.FsmState
import com.example.data.repository.GameBoostRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ResourceGovernor inspirado en resourcegov.js
 * Gestiona recursos del sistema según el estado de la pantalla.
 * 
 * Fase 2: Integración con PowerOptimizer.forceDoze()
 * Cuando la pantalla se apaga DURANTE un juego, se fuerza el modo Doze
 * para ahorrar batería (entre rondas, pantallas de carga largas, etc.)
 */
class ResourceGovernor(
    private val context: Context,
    private val repository: GameBoostRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!PreferenceManager.isDeepSleepEnabled(context ?: return)) return
            
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_SCREEN_ON -> handleScreenOn()
            }
        }
    }

    fun start() {
        if (isRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(screenReceiver, filter)
        isRegistered = true
        repository.logAsync("INFO", "ResourceGov", "Resource Governor activo")
    }

    private fun handleScreenOff() {
        repository.logAsync("INFO", "ResourceGov", "🌙 Suspensión detectada - Aplicando ahorro")
        scope.launch {
            // 0. Force Doze si hay un juego activo (Fase 2 - PowerOptimizer)
            if (repository.fsmState.value == FsmState.GAME_ACTIVE || repository.isBoostActive.value) {
                repository.logAsync("DEBUG", "ResourceGov", "🎮 Juego activo + pantalla apagada → Force Doze")
                repository.executePrivilegedCommand("dumpsys deviceidle force-idle")
            }

            // 1. Reducir animaciones para ahorrar ciclos de GPU/CPU
            repository.executePrivilegedCommand(
                "settings put global window_animation_scale 0.5; " +
                "settings put global transition_animation_scale 0.5; " +
                "settings put global animator_duration_scale 0.5"
            )

            // 2. Limitar procesos en segundo plano
            repository.executePrivilegedCommand("cmd activity set-process-limit 8")

            // 3. Limpieza de caché (PM trim-caches)
            repository.executePrivilegedCommand("pm trim-caches 128M")
            
            // 4. Drop caches (Kernel)
            repository.executePrivilegedCommand("sync; echo 1 > /proc/sys/vm/drop_caches")
        }
    }

    private fun handleScreenOn() {
        repository.logAsync("INFO", "ResourceGov", "☀️ Pantalla activa - Restaurando rendimiento")
        scope.launch {
            // 1. Restaurar animaciones (o mantener 0 si el boost está activo)
            val scale = if (repository.isBoostActive.value) 0 else 1
            repository.executePrivilegedCommand(
                "settings put global window_animation_scale $scale; " +
                "settings put global transition_animation_scale $scale; " +
                "settings put global animator_duration_scale $scale"
            )

            // 2. Quitar límite de procesos
            repository.executePrivilegedCommand("cmd activity set-process-limit -1")
        }
    }

    fun stop() {
        if (isRegistered) {
            try {
                context.unregisterReceiver(screenReceiver)
            } catch (e: Exception) {
                Log.e("ResourceGov", "Error al desregistrar receptor: ${e.message}")
            }
            isRegistered = false
        }
    }
}

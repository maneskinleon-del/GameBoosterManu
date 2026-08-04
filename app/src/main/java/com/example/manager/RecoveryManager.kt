package com.example.manager

import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.PreferenceManager
import com.example.data.database.AppDatabase
import com.example.data.repository.GameBoostRepository
import com.example.service.GameBoostService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * RecoveryManager inspirado en recovery.js
 * Herramienta para restaurar el sistema ante fallos o corrupción.
 */
class RecoveryManager(
    private val context: Context,
    private val repository: GameBoostRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val database = AppDatabase.getDatabase(context)

    data class RecoveryResult(
        val logsCleared: Boolean = false,
        val settingsReset: Boolean = false,
        val serviceRestarted: Boolean = false,
        val cachePurged: Boolean = false,
        val issuesFound: List<String> = emptyList()
    )

    private val _isRecovering = MutableStateFlow(false)
    val isRecovering = _isRecovering.asStateFlow()

    fun performFullRecovery(onComplete: (RecoveryResult) -> Unit) {
        scope.launch {
            _isRecovering.value = true
            repository.logAsync("INFO", "Recovery", "🛠️ Iniciando proceso de recuperación total...")
            
            var logsCleared = false
            var settingsReset = false
            var serviceRestarted = false
            var cachePurged = false
            val issues = mutableListOf<String>()

            // 1. Limpiar Logs
            try {
                database.logDao().clearLogs()
                logsCleared = true
                repository.logAsync("INFO", "Recovery", "✓ Base de datos de logs limpiada")
            } catch (e: Exception) {
                issues.add("Error al limpiar logs: ${e.message}")
            }

            delay(500)

            // 2. Reiniciar Servicio
            try {
                if (GameBoostService.isRunning) {
                    val stopIntent = Intent(context, GameBoostService::class.java).apply {
                        action = GameBoostService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                    delay(1000)
                }
                
                val startIntent = Intent(context, GameBoostService::class.java).apply {
                    action = GameBoostService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(startIntent)
                } else {
                    context.startService(startIntent)
                }
                serviceRestarted = true
                repository.logAsync("INFO", "Recovery", "✓ Motor de optimización reiniciado")
            } catch (e: Exception) {
                issues.add("Error al reiniciar servicio: ${e.message}")
            }

            delay(500)

            // 3. Purga de Caché del Kernel via Shizuku
            try {
                repository.executePrivilegedCommand("sync; echo 3 > /proc/sys/vm/drop_caches")
                cachePurged = true
                repository.logAsync("INFO", "Recovery", "✓ Caché del sistema purgada")
            } catch (e: Exception) {
                issues.add("Error al purgar caché: ${e.message}")
            }

            delay(500)

            // 4. Verificar integridad (Shizuku + Permisos)
            if (!rikka.shizuku.Shizuku.pingBinder()) {
                issues.add("CRÍTICO: Shizuku no responde")
            }
            
            _isRecovering.value = false
            repository.logAsync("INFO", "Recovery", "✅ Proceso de recuperación completado")
            
            onComplete(RecoveryResult(logsCleared, settingsReset, serviceRestarted, cachePurged, issues))
        }
    }
}

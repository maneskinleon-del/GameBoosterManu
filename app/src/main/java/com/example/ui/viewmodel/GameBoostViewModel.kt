package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.LogEntity
import com.example.data.database.ProfileEntity
import com.example.data.repository.DependencyState
import com.example.data.repository.GameBoostRepository
import com.example.data.repository.FsmState
import com.example.data.repository.SystemMetrics
import com.example.manager.ShizukuExecutor
import com.example.manager.boostsession.BoostSessionManager
import com.example.manager.boostsession.BoostSessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GameBoostViewModel(private val repository: GameBoostRepository) : ViewModel() {

    val profiles: StateFlow<List<ProfileEntity>> = repository.profilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<LogEntity>> = repository.logsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Tab Actividad — eventos importantes del boost (sin ruido DEBUG).
     * Evidencia real: los logs de INFO/WARN/ERROR provienen de acciones del
     * ciclo (apply, restore, recovery, errores). Solo lectura; la tabla Room
     * sigue siendo infraestructura interna (LIMIT 200 por el DAO).
     */
    val recentActivity: StateFlow<List<LogEntity>> = logs
        .map { list -> list.filter { it.level != "DEBUG" }.take(12) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Evidencia de la sesión SSOT (snapshot persistido; polling ligero — el store es archivo, no flow). */
    data class SessionEvidence(
        val state: BoostSessionState,
        val sessionId: String?,
        val updatedAt: Long,
        val baselineCount: Int,
        val appliedCount: Int,
        val appliedEntries: List<Pair<String, String>> = emptyList()
    )

    val sessionEvidence: StateFlow<SessionEvidence?> = flow {
        while (true) {
            val snap = repository.sessionSnapshot()
            emit(
                snap?.let {
                    SessionEvidence(
                        state = it.state,
                        sessionId = it.sessionId.ifBlank { null },
                        updatedAt = it.updatedAt,
                        baselineCount = it.baseline.size,
                        appliedCount = it.baseline.count { e -> e.appliedValue != null },
                        appliedEntries = it.baseline.filter { e -> e.appliedValue != null }
                            .map { e -> "${e.namespace}:${e.key}" to (e.appliedValue ?: "") }
                    )
                }
            )
            delay(3000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Último restore verificado (retenido en BoostSessionManager; no es estado nuevo). */
    val lastRestoreReport: StateFlow<BoostSessionManager.RestoreReport?> = flow {
        while (true) {
            emit(repository.boostSession.lastRestoreReport)
            delay(3000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val systemMetrics: StateFlow<SystemMetrics> = repository.systemMetrics
    val isBoostActive: StateFlow<Boolean> = repository.isBoostActive
    val fsmState: StateFlow<FsmState> = repository.fsmState
    val shizukuConnected: StateFlow<Boolean> = repository.shizukuConnected
    val simulatedGame: StateFlow<String?> = repository.simulatedGame
    val pointerSpeed: StateFlow<Int> = repository.pointerSpeed
    val adsPointerActive: StateFlow<Boolean> = repository.adsPointerActive
    val availableGovernors: StateFlow<List<String>> = repository.availableGovernors
    val isMobiladorActive: StateFlow<Boolean> = repository.isMobiladorActive
    val externalDevicesConnected: StateFlow<Boolean> = repository.externalDevicesConnected
    val healthStatus = repository.healthStatus
    val dependencyState = repository.dependencyState

    /** Fuerza un refresh inmediato del estado de dependencias (ej: al volver de Settings). */
    fun refreshDependencyState() = repository.refreshDependencyState()
    val isAggressiveOptimizationEnabled = repository.isAggressiveOptimizationEnabled
    val isThermalWatchdogEnabled = repository.isThermalWatchdogEnabled
    val isAutoDetectGamesEnabled = repository.isAutoDetectGamesEnabled
    val isDeepSleepEnabled = repository.isDeepSleepEnabled
    val isMsaaEnabled = repository.isMsaaEnabled

    fun setActiveProfile(id: String) {
        viewModelScope.launch {
            repository.setActiveProfile(id)
        }
    }

    fun toggleBoost() {
        viewModelScope.launch {
            repository.toggleBoost()
        }
    }

    fun quickClean() {
        viewModelScope.launch {
            repository.quickClean()
        }
    }

    fun toggleShizukuState() {
        viewModelScope.launch {
            repository.toggleShizukuState()
        }
    }

    fun toggleMobilador() {
        viewModelScope.launch {
            repository.toggleMobilador()
        }
    }

    fun simulateGameLaunch(packageName: String?) {
        viewModelScope.launch {
            repository.simulateGameLaunch(packageName)
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    fun addCustomProfile(name: String, description: String, governor: String, refreshRate: String, icon: String) {
        viewModelScope.launch {
            repository.addCustomProfile(name, description, governor, refreshRate, icon)
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            repository.deleteProfile(id)
        }
    }

    fun setDpi(dpi: Int) {
        repository.setDpi(dpi)
    }

    fun setPointerSpeed(speed: Int) {
        repository.setPointerSpeed(speed)
    }

    fun refreshMetrics() {
        viewModelScope.launch {
            repository.refreshMetrics()
        }
    }

    fun getDiagnosticReport(): String {
        return repository.getDiagnosticReport()
    }

    fun getShizukuDiagnosis(context: Context): String {
        return com.example.manager.ShizukuExecutor.diagnose(context)
    }

    fun toggleAggressiveOptimization() = repository.toggleAggressiveOptimization()
    fun toggleThermalWatchdog() = repository.toggleThermalWatchdog()
    fun toggleAutoDetectGames() = repository.toggleAutoDetectGames()
    fun toggleDeepSleep() = repository.toggleDeepSleep()
    fun toggleMsaa() = repository.toggleMsaa()

    // Factory Provider
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(GameBoostViewModel::class.java)) {
                val repository = GameBoostRepository.getInstance(context.applicationContext)
                @Suppress("UNCHECKED_CAST")
                return GameBoostViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

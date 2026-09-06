package com.example.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.example.data.PreferenceManager
import com.example.data.repository.FsmState
import com.example.data.repository.GameBoostRepository
import com.example.manager.boostsession.BoostSessionManager
import com.example.manager.boostsession.BoostSessionState
import com.example.manager.exec.ExecOutcome
import com.example.manager.exec.ExecResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ResourceGovernor — gestiona recursos del sistema según el estado de la pantalla.
 *
 * F1-CP3: eliminadas las mutaciones no compatibles con el modelo reversible:
 * - [set-process-limit] (global, sin read-back persistible, redundante con F4)
 * - [drop_caches] (irreversible, root-only, global)
 * - [force-idle] (global, irreconfigurable vía restore; queda como manual en PowerOptimizer)
 *
 * Conserva únicamente mutaciones reversibles: animation scales (ya en contrato F4)
 * y trim-caches (efímero PM-managed).
 */
class ResourceGovernor(
    private val context: Context,
    private val repository: GameBoostRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRegistered = false
    /** Serializa eventos screen-off/on para evitar carreras entre OFF y ON concurrentes. */
    private val screenMutex = Mutex()

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
        repository.logAsync("INFO", "ResourceGov", "🌙 Suspensión detectada")
        scope.launch {
            screenMutex.withLock {
                if (!isMutationsAllowed()) return@withLock

                // 1. Reducir animaciones — mutaciones reversibles (3 keys en contrato F4)
                // F1-CP4: write → read-back → verify por cada key
                val animWrite = repository.executePrivilegedCommand(
                    "settings put global window_animation_scale 0.5; " +
                    "settings put global transition_animation_scale 0.5; " +
                    "settings put global animator_duration_scale 0.5"
                )
                val animVerified = verifyAnimWrite(animWrite, listOf(
                    Triple("global", "window_animation_scale", "0.5"),
                    Triple("global", "transition_animation_scale", "0.5"),
                    Triple("global", "animator_duration_scale", "0.5")
                ))
                logExecResults("AnimsDown", animVerified)

                // 2. Limpieza de caché (PM trim-caches) — efímero, self-heal, sin persistir
                val cacheResults = repository.executePrivilegedCommand("pm trim-caches 128M")
                logExecResults("TrimCaches", cacheResults)
            }
        }
    }

    private fun handleScreenOn() {
        repository.logAsync("INFO", "ResourceGov", "☀️ Pantalla activa")
        scope.launch {
            screenMutex.withLock {
                if (!isMutationsAllowed()) return@withLock

                val scale = if (repository.isBoostActive.value) 0 else 1
                val animWrite = repository.executePrivilegedCommand(
                    "settings put global window_animation_scale $scale; " +
                    "settings put global transition_animation_scale $scale; " +
                    "settings put global animator_duration_scale $scale"
                )
                val animVerified = verifyAnimWrite(animWrite, listOf(
                    Triple("global", "window_animation_scale", scale.toString()),
                    Triple("global", "transition_animation_scale", scale.toString()),
                    Triple("global", "animator_duration_scale", scale.toString())
                ))
                logExecResults("AnimsRestore", animVerified)
            }
        }
    }

    /**
     * F1-CP5: gate de estado. ResourceGovernor solo ejecuta mutaciones cuando la
     * sesión F4 se encuentra en un estado consistente (IDLE, ACTIVE, RESTORED).
     * Bloquea durante APPLYING/RESTORING/RECOVERY_REQUIRED para evitar interferir
     * con operaciones de sesión en curso.
     *
     * @return true si las mutaciones están permitidas, false si se bloquearon.
     */
    private fun isMutationsAllowed(): Boolean {
        val state = repository.boostSession.currentState()
        return when (state) {
            BoostSessionState.IDLE,
            BoostSessionState.ACTIVE,
            BoostSessionState.RESTORED,
            BoostSessionState.BASELINE_CAPTURED -> true
            BoostSessionState.APPLYING,
            BoostSessionState.RESTORING,
            BoostSessionState.RECOVERY_REQUIRED -> {
                repository.logAsync("WARN", "ResourceGov",
                    "Mutaciones bloqueadas — sesión en estado $state")
                false
            }
        }
    }

    /**
     * Verifica por read-back que las 3 anim scales adoptaron el valor esperado.
     * F1-CP4: reutiliza [BoostSessionManager.SettingRead] (F4) sin duplicar abstracciones.
     *
     * @param writeResult Resultado del batch write (EXECUTED = corrió, sin verificar aún).
     * @param expectedListOf Triple(namespace, key, expectedValue) para cada key escrita.
     * @return Lista de [ExecResult] con [ExecResult.verified] marcado por read-back.
     */
    private suspend fun verifyAnimWrite(
        writeResult: List<ExecResult>,
        expectedListOf: List<Triple<String, String, String>>
    ): List<ExecResult> {
        if (writeResult.isEmpty()) return writeResult
        val first = writeResult.first()
        if (first.isFailure) return writeResult

        val verified = mutableListOf<ExecResult>()
        for ((ns, key, expected) in expectedListOf) {
            val readRes = repository.executePrivilegedCommand("settings get $ns $key")
            val readExec = readRes.firstOrNull() ?: ExecResult(
                outcome = ExecOutcome.PROCESS_START_FAILED("empty read result"),
                command = "settings get $ns $key"
            )
            val deviceVal = when (val out = readExec.outcome) {
                is ExecOutcome.EXECUTED -> BoostSessionManager.SettingRead.Present(out.stdout.trim())
                is ExecOutcome.EXIT_NONZERO -> BoostSessionManager.SettingRead.ReadFailed("exit=${out.exit}")
                else -> BoostSessionManager.SettingRead.ReadFailed("read failed: ${readExec.outcome}")
            }
            val match = when (deviceVal) {
                is BoostSessionManager.SettingRead.Present -> deviceVal.value == expected
                else -> false
            }
            verified += ExecResult(
                outcome = first.outcome,
                verified = match,
                deviceValue = deviceVal,
                command = first.command
            )
        }
        return verified
    }

    /**
     * Registra el resultado de cada sub-comando de un batch.
     * F1-CP2: el exit 0 ya NO se registra como "éxito"; se distingue
     * entre EXECUTED (sin verificar) y fallos reales.
     */
    private fun logExecResults(tag: String, results: List<ExecResult>) {
        for (r in results) {
            when (r.outcome) {
                is ExecOutcome.EXECUTED -> {
                    if (r.verified) {
                        repository.logAsync("DEBUG", "ResourceGov",
                            "$tag: VERIFICADO: ${r.command.take(50)}")
                    } else {
                        repository.logAsync("WARN", "ResourceGov",
                            "$tag: ejecutado (sin verificar): ${r.command.take(50)}")
                    }
                }
                is ExecOutcome.EXIT_NONZERO -> {
                    repository.logAsync("ERROR", "ResourceGov",
                        "$tag: fallo exit=${(r.outcome as ExecOutcome.EXIT_NONZERO).exit}: ${r.command.take(50)}")
                }
                is ExecOutcome.STDERR_ERROR -> {
                    repository.logAsync("ERROR", "ResourceGov",
                        "$tag: stderr error: ${(r.outcome as ExecOutcome.STDERR_ERROR).stderr.take(80)}")
                }
                is ExecOutcome.PRIVILEGE_UNAVAILABLE -> {
                    repository.logAsync("ERROR", "ResourceGov",
                        "$tag: privilegio no disponible: ${r.command.take(50)}")
                }
                is ExecOutcome.PROCESS_START_FAILED -> {
                    repository.logAsync("ERROR", "ResourceGov",
                        "$tag: proceso no iniciado: ${(r.outcome as ExecOutcome.PROCESS_START_FAILED).reason}")
                }
                is ExecOutcome.TIMEOUT -> {
                    repository.logAsync("ERROR", "ResourceGov",
                        "$tag: timeout tras ${(r.outcome as ExecOutcome.TIMEOUT).timeoutMs}ms: ${r.command.take(50)}")
                }
            }
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

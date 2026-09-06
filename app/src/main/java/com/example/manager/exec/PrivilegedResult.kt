package com.example.manager.exec

import com.example.manager.boostsession.BoostSessionManager

/**
 * Resultado estructurado de una ejecución privilegiada.
 *
 * F1-DESIGN §5: reemplaza el fire-and-forget Unit por información
 * precisa del resultado de cada comando. Reutiliza SettingRead de F4
 * para el read-back sin duplicar abstracciones.
 *
 * Regla central: exit 0 != "aplicado correctamente".
 */
sealed class ExecOutcome {
    /** No se pudo arrancar el proceso shell. */
    data class PROCESS_START_FAILED(val reason: String?) : ExecOutcome()

    /** El proceso corrió pero terminó con exit != 0. */
    data class EXIT_NONZERO(val exit: Int, val stderr: String?) : ExecOutcome()

    /** Exit 0 pero el stderr contiene un error del comando interno. */
    data class STDERR_ERROR(val stderr: String) : ExecOutcome()

    /** El comando excedió el tiempo máximo. */
    data class TIMEOUT(val timeoutMs: Long) : ExecOutcome()

    /** No hay Shizuku/Rish disponibles y el fallback Runtime no tiene privilegios. */
    data class PRIVILEGE_UNAVAILABLE(val reason: String?) : ExecOutcome()

    /** El comando corrió con exit 0. El efecto NO fue verificado por read-back. */
    data class EXECUTED(val stdout: String) : ExecOutcome()
}

/**
 * Resultado de una ejecución privilegeada con su verificación.
 *
 * @param outcome Resultado del proceso shell.
 * @param verified true si se hizo read-back y el device adoptó el valor esperado.
 * @param deviceValue Si se hizo read-back, el valor releído del dispositivo (reusa SettingRead de F4).
 * @param command El comando original ejecutado (para logging).
 */
data class ExecResult(
    val outcome: ExecOutcome,
    val verified: Boolean = false,
    val deviceValue: BoostSessionManager.SettingRead? = null,
    val command: String = ""
) {
    /** true solo si el comando corrió Y el efecto fue verificado por read-back. */
    val isFullyVerified: Boolean get() = verified && outcome is ExecOutcome.EXECUTED

    /** true si el comando se ejecutó (exit 0) aunque no esté verificado. */
    val wasExecuted: Boolean get() = outcome is ExecOutcome.EXECUTED

    /** true si hubo cualquier tipo de fallo (start, exit, privilege, timeout). */
    val isFailure: Boolean get() = outcome !is ExecOutcome.EXECUTED
}

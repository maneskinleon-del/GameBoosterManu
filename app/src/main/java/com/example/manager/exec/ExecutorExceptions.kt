package com.example.manager.exec

/**
 * Excepciones y constantes compartidas del pipeline privilegiado (Shizuku / Rish).
 * Evita acoplar ShizukuExecutor ↔ RishExecutor por tipos anidados.
 */
object ExecutorDefaults {
    /** Timeout por comando. Evita corrutinas IO colgadas (F2). */
    const val DEFAULT_TIMEOUT_MS: Long = 8_000L
}

/** El proceso no terminó dentro del timeout; se invoca destroyForcibly. */
class CommandTimeoutException(
    val timeoutMs: Long,
    message: String = "timeout after ${timeoutMs}ms"
) : Exception(message)

/**
 * No hay backend privilegiado disponible (Shizuku ni Rish).
 * No implica que el comando sea inválido — solo que no se pudo ejecutar como shell.
 */
class PrivilegeUnavailableException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Hubo backend privilegiado, el comando se lanzó, pero falló (exit≠0, stderr, etc.).
 * [cause] conserva el error original para el caller/UI.
 */
class CommandFailedException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

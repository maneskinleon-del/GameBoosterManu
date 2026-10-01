package com.example.manager

/**
 * Funciones puras para el selector de mapper (sin Android, sin coroutines).
 *
 * SEGURIDAD: el paquete elegido por el usuario termina dentro de un comando que
 * corre con uid shell vía Shizuku. Por eso NUNCA se concatena sin pasar por
 * [validatePackageName]; [buildPgrepCommand] vuelve a validar (defensa en profundidad).
 *
 * Limitación conocida: un proceso vivo indica que la app está abierta,
 * no que el mapeo esté activo.
 */
object MapperDetection {

    /** Nombre de paquete Android: segmentos ASCII separados por '.', cada uno empieza con letra. */
    private val PACKAGE_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    private const val MAX_LENGTH = 255

    fun validatePackageName(input: String): Boolean =
        input.length <= MAX_LENGTH && PACKAGE_REGEX.matches(input)

    /**
     * Construye `pgrep -f '^[c]om\.x\.y([: ]|$)'`, o null si el paquete no es válido.
     *
     * - `^` ancla al inicio del cmdline: un proceso de app Android se llama como su paquete,
     *   así que no coincide con el `sh -c` que ejecuta el comando.
     * - `[c]` en el primer carácter: evita el self-match si algún día se quita el ancla.
     * - `\.` escapa los puntos (en regex, '.' coincide con cualquier carácter).
     * - `([: ]|$)`: acepta el proceso principal y los secundarios `paquete:servicio`,
     *   pero no `paquete2` ni `paquete.otro`.
     *
     * Los caracteres válidos del paquete son [A-Za-z0-9_.], por lo que no pueden
     * cerrar las comillas simples ni introducir metacaracteres de shell.
     */
    fun buildPgrepCommand(packageName: String): String? {
        if (!validatePackageName(packageName)) return null
        val first = packageName.first()
        val rest = packageName.substring(1).replace(".", "\\.")
        return "pgrep -f '^[$first]$rest([: ]|\$)'"
    }

    /** True si la salida de pgrep contiene al menos un PID. */
    fun hasPid(pgrepOutput: String?): Boolean =
        pgrepOutput.orEmpty().lines().any { it.trim().matches(Regex("\\d+")) }
}

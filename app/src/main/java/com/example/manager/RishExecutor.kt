package com.example.manager

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * RishExecutor — Ejecuta comandos shell privilegiados usando Rish como backend.
 *
 * ## ¿Qué es Rish?
 * Rish (Remote Shell) es un binario que funciona como Shizuku pero sin la app de Shizuku.
 * Se ejecuta via ADB o Termux y proporciona un shell con uid=2000 (shell).
 *
 * ## ¿Por qué Rish?
 * - Funciona en dispositivos sin Shizuku instalado
 * - Mismo nivel de acceso que Shizuku (uid 2000)
 * - No requiere la app Shizuku corriendo en segundo plano
 *
 * ## Requisitos
 * - El binario `rish` debe estar presente en `/data/local/tmp/rish`
 * - La variable de entorno `RISH_APPLICATION_ID` debe apuntar al package de la app
 *
 * ## Verificado en ZTE nubia Neo 2 (MyOS 13)
 * ✅ Rish funciona con `RISH_APPLICATION_ID=com.termux` como uid 2000 (shell)
 * ✅ Comandos: settings, cmd power, am, pm, renice, taskset
 */
object RishExecutor {
    private const val TAG = "RishExecutor"
    private const val RISH_BINARY = "/data/local/tmp/rish"

    sealed class State {
        object BinaryNotFound : State()
        object Ready : State()
        object Error : State()

        override fun toString(): String = when (this) {
            BinaryNotFound -> "Rish binary not found at $RISH_BINARY"
            Ready -> "Ready"
            Error -> "Error"
        }
    }

    /**
     * Verifica si el binario rish existe y es ejecutable.
     */
    fun checkState(): State {
        return try {
            val file = java.io.File(RISH_BINARY)
            if (file.exists() && file.canExecute()) State.Ready else State.BinaryNotFound
        } catch (e: Exception) {
            State.BinaryNotFound
        }
    }

    fun isReady(): Boolean = checkState() == State.Ready

    /**
     * Ejecuta un comando shell via Rish.
     *
     * Utiliza ProcessBuilder para ejecutar el binario rish con el comando como argumento.
     * La variable RISH_APPLICATION_ID se setea para que Rish sepa qué app lo invoca.
     *
     * @param command Comando shell a ejecutar (ej. "settings put system pointer_speed 7")
     * @return Result<String> con stdout en éxito, stderr/exception en fallo
     */
    suspend fun runCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        val state = checkState()
        if (state != State.Ready) {
            return@withContext Result.failure(IllegalStateException("Rish no disponible: $state"))
        }

        try {
            val processBuilder = ProcessBuilder(RISH_BINARY, "-c", command)
            processBuilder.environment()?.apply {
                put("RISH_APPLICATION_ID", "com.example")
            }

            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()

            if (process.exitValue() == 0) {
                Result.success(output.trim())
            } else {
                val err = error.trim().ifBlank { "exit=${process.exitValue()}" }
                Result.failure(RuntimeException(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Ejecuta un comando shell via Runtime.exec() como fallback final.
     * No tiene privilegios especiales, pero puede ejecutar comandos básicos.
     *
     * @param command Comando shell a ejecutar
     * @return Result<String> con stdout o error
     */
    suspend fun runCommandFallback(command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()

            if (process.exitValue() == 0) {
                Result.success(output.trim())
            } else {
                val err = error.trim().ifBlank { "exit=${process.exitValue()}" }
                Result.failure(RuntimeException(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Diagnóstico del estado de Rish.
     */
    fun diagnose(): String {
        val state = checkState()
        val sb = StringBuilder()
        sb.appendLine("═══ Rish Executor Diagnosis ═══")
        sb.appendLine("State: $state")

        when (state) {
            State.Ready -> {
                val file = java.io.File(RISH_BINARY)
                sb.appendLine("Binary: ${file.absolutePath}")
                sb.appendLine("Size: ${file.length()} bytes")
                sb.appendLine("Executable: ${file.canExecute()}")
                sb.appendLine("Readable: ${file.canRead()}")
            }
            State.BinaryNotFound -> {
                sb.appendLine("Binary not found at: $RISH_BINARY")
                sb.appendLine("Install rish via Termux or ADB:")
                sb.appendLine("  adb shell /data/local/tmp/rish -c 'sh /storage/emulated/0/Android/data/moe.shizuku.manager/files/start.sh'")
            }
            State.Error -> {
                sb.appendLine("Unknown error state")
            }
        }
        sb.appendLine("═══════════════════════════════")
        return sb.toString()
    }
}

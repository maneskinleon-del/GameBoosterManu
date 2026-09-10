package com.example.manager

import android.content.Context
import android.util.Log
import com.example.manager.exec.CommandFailedException
import com.example.manager.exec.CommandTimeoutException
import com.example.manager.exec.ExecutorDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * RishExecutor — shell uid 2000 vía binario rish.
 *
 * F2: drain paralelo + waitFor(timeout); nunca waitFor() infinito en el catch.
 * RISH_APPLICATION_ID: [setApplicationId] / Context, no hardcode de package.
 */
object RishExecutor {
    private const val TAG = "RishExecutor"
    private const val RISH_BINARY = "/data/local/tmp/rish"
    private const val DEFAULT_TIMEOUT_MS = ExecutorDefaults.DEFAULT_TIMEOUT_MS
    private const val POLL_INTERVAL_MS = 25L

    private val applicationIdRef = AtomicReference("unknown")

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

    fun setApplicationId(packageName: String) {
        if (packageName.isNotBlank()) {
            applicationIdRef.set(packageName)
        }
    }

    fun setApplicationId(context: Context) = setApplicationId(context.packageName)

    private fun resolveApplicationId(): String {
        val current = applicationIdRef.get()
        if (current != "unknown" && current.isNotBlank()) return current
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getMethod("currentApplication").invoke(null) as? android.app.Application
            app?.packageName?.also { applicationIdRef.set(it) } ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    fun checkState(): State {
        return try {
            val file = java.io.File(RISH_BINARY)
            if (file.exists() && file.canExecute()) State.Ready else State.BinaryNotFound
        } catch (_: Exception) {
            State.BinaryNotFound
        }
    }

    fun isReady(): Boolean = checkState() == State.Ready

    suspend fun runCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        if (checkState() != State.Ready) {
            return@withContext Result.failure(
                IllegalStateException("Rish no disponible: ${checkState()}")
            )
        }
        runDrained(command, DEFAULT_TIMEOUT_MS) {
            val pb = ProcessBuilder(RISH_BINARY, "-c", command)
            pb.environment()?.put("RISH_APPLICATION_ID", resolveApplicationId())
            pb.start()
        }
    }

    suspend fun runCommandFallback(command: String): Result<String> = withContext(Dispatchers.IO) {
        runDrained(command, DEFAULT_TIMEOUT_MS) {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        }
    }

    private suspend fun runDrained(
        command: String,
        timeoutMs: Long,
        start: () -> Process
    ): Result<String> = coroutineScope {
        val process = try {
            start()
        } catch (e: Exception) {
            return@coroutineScope Result.failure(e)
        }

        val stdoutDeferred = async(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                ""
            }
        }
        val stderrDeferred = async(Dispatchers.IO) {
            try {
                process.errorStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                ""
            }
        }

        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "waitFor(timeout) no soportado (${e.message}); sondeando exitValue()")
            pollUntilExited(process, timeoutMs)
        }

        if (!finished) {
            try {
                process.destroyForcibly()
            } catch (_: Exception) {
            }
            stdoutDeferred.cancel()
            stderrDeferred.cancel()
            Log.e(TAG, "TIMEOUT ${timeoutMs}ms: ${command.take(60)}")
            return@coroutineScope Result.failure(CommandTimeoutException(timeoutMs))
        }

        val output = try {
            stdoutDeferred.await()
        } catch (_: Exception) {
            ""
        }
        val error = try {
            stderrDeferred.await()
        } catch (_: Exception) {
            ""
        }
        val exit = try {
            process.exitValue()
        } catch (_: Exception) {
            -1
        }
        if (exit == 0) {
            Result.success(output.trim())
        } else {
            val err = error.trim().ifBlank { "exit=$exit" }
            Result.failure(CommandFailedException("Rish/Runtime: $err"))
        }
    }

    fun diagnose(): String {
        val state = checkState()
        val sb = StringBuilder()
        sb.appendLine("═══ Rish Executor Diagnosis ═══")
        sb.appendLine("State: $state")
        sb.appendLine("RISH_APPLICATION_ID: ${resolveApplicationId()}")
        when (state) {
            State.Ready -> {
                val file = java.io.File(RISH_BINARY)
                sb.appendLine("Binary: ${file.absolutePath}")
                sb.appendLine("Size: ${file.length()} bytes")
            }
            State.BinaryNotFound -> {
                sb.appendLine("Binary not found at: $RISH_BINARY")
            }
            State.Error -> sb.appendLine("Unknown error state")
        }
        return sb.toString()
    }

    /**
     * Espera acotada vía sondeo: NUNCA waitFor() sin límite (regresión F2).
     */
    private fun pollUntilExited(process: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // Shizuku/Rish RemoteProcess lanza IllegalArgumentException (Binder), no
            // IllegalThreadStateException. Cualquier excepción = "aún no ha salido".
            val exited = try {
                process.exitValue()
                true
            } catch (e: android.os.DeadObjectException) {
                Log.e(TAG, "Binder muerto durante sondeo — Shizuku/Rish probablemente cayó")
                return false // falla ya: sondear el deadline completo contra un binder muerto no aporta nada
            } catch (_: Exception) {
                false
            }
            if (exited) return true
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }
}

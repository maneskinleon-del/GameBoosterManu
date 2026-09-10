package com.example.manager

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.example.manager.exec.CommandFailedException
import com.example.manager.exec.CommandTimeoutException
import com.example.manager.exec.ExecutorDefaults
import com.example.manager.exec.PrivilegeUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * ShizukuExecutor — pipeline privilegiado con timeouts (F2) y señales de error honestas (F3).
 *
 * - drain paralelo de stdout/stderr (evita deadlock de pipe 64KB)
 * - waitFor(timeout); si el overload no existe → false + destroyForcibly (NUNCA waitFor() infinito)
 * - Rish / Runtime como fallback; PRIVILEGE_UNAVAILABLE solo si ningún backend pudo lanzar
 * - exit≠0 con backend vivo → CommandFailedException (no enmascarar como privilegio)
 *
 * TODO: migrar a UserService cuando Shizuku elimine newProcess.
 */
object ShizukuExecutor {
    private const val TAG = "ShizukuExecutor"
    private const val REQUEST_CODE = 1001
    private const val POLL_INTERVAL_MS = 25L
    const val DEFAULT_TIMEOUT_MS = ExecutorDefaults.DEFAULT_TIMEOUT_MS


    sealed class State {
        object NotInstalled : State()
        object NotRunning : State()
        object PermissionDenied : State()
        object Ready : State()

        override fun toString(): String = when (this) {
            NotInstalled -> "Not Installed (Pre-V11)"
            NotRunning -> "Not Running"
            PermissionDenied -> "Permission Denied"
            Ready -> "Ready"
        }
    }

    private var permissionListener: Shizuku.OnRequestPermissionResultListener? = null

    fun checkState(): State {
        if (!isShizukuAvailable()) return State.NotRunning
        return when {
            Shizuku.isPreV11() -> State.NotInstalled
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.Ready
            else -> State.PermissionDenied
        }
    }

    private fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun isReady(): Boolean = checkState() == State.Ready

    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }
        permissionListener?.let { Shizuku.removeRequestPermissionResultListener(it) }
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                permissionListener?.let { Shizuku.removeRequestPermissionResultListener(it) }
                permissionListener = null
            }
        }
        permissionListener = listener
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST_CODE)
    }

    suspend fun runCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        var lastFailure: Throwable? = null

        // 1. Shizuku
        if (checkState() == State.Ready) {
            val shizukuResult = runOnProcess(command, "Shizuku") {
                createShizukuProcess(command)
            }
            if (shizukuResult.isSuccess) return@withContext shizukuResult
            lastFailure = shizukuResult.exceptionOrNull()
            // Timeout o fallo real del comando: no disfrazar de privilegio
            if (lastFailure is CommandTimeoutException || lastFailure is CommandFailedException) {
                return@withContext shizukuResult
            }
            Log.w(TAG, "Shizuku falló (${lastFailure?.message}). Intentando Rish...")
        }

        // 2. Rish
        if (RishExecutor.isReady()) {
            val rishResult = RishExecutor.runCommand(command)
            if (rishResult.isSuccess) return@withContext rishResult
            lastFailure = rishResult.exceptionOrNull()
            if (lastFailure is CommandTimeoutException || lastFailure is CommandFailedException) {
                return@withContext rishResult
            }
            Log.w(TAG, "Rish falló (${lastFailure?.message})")
        }

        // 3. Runtime sin privilegios (último recurso)
        val fallback = RishExecutor.runCommandFallback(command)
        if (fallback.isSuccess) return@withContext fallback
        lastFailure = fallback.exceptionOrNull() ?: lastFailure

        val msg = "PRIVILEGE_UNAVAILABLE tras intentar backends: ${command.take(80)}"
        Result.failure(PrivilegeUnavailableException(msg, lastFailure))
    }

    private suspend fun runOnProcess(
        command: String,
        label: String,
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

        val finished = drainProcess(process, DEFAULT_TIMEOUT_MS)
        if (!finished) {
            try {
                process.destroyForcibly()
            } catch (_: Exception) {
            }
            // Cancelar drains colgados
            stdoutDeferred.cancel()
            stderrDeferred.cancel()
            Log.e(TAG, "TIMEOUT ${DEFAULT_TIMEOUT_MS}ms [$label]: ${command.take(60)}")
            return@coroutineScope Result.failure(CommandTimeoutException(DEFAULT_TIMEOUT_MS))
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
            Log.d(TAG, "✅ $label OK: ${command.take(60)}")
            Result.success(output.trim())
        } else {
            val err = error.trim().ifBlank { "exit=$exit" }
            Log.w(TAG, "⚠️ $label exit≠0: $err")
            Result.failure(CommandFailedException("$label: $err"))
        }
    }

    /**
     * Espera acotada. Si waitFor(timeout, unit) lanza (Shizuku 13.x RemoteProcess
     * no soporta el overload), hace sondeo de exitValue() cada POLL_INTERVAL_MS
     * hasta el deadline. NUNCA waitFor() sin límite (regresión F2).
     */
    private fun drainProcess(process: Process, timeoutMs: Long): Boolean {
        return try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "waitFor(timeout) no soportado (${e.message}); sondeando exitValue()")
            pollUntilExited(process, timeoutMs)
        }
    }

    private fun pollUntilExited(process: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // Shizuku RemoteProcess lanza IllegalArgumentException (Binder: "process
            // hasn't exited"), NO IllegalThreadStateException como un Process local.
            // Cualquier excepción durante el sondeo = "aún no ha salido"; el deadline acota.
            val exited = try {
                process.exitValue()
                true
            } catch (e: android.os.DeadObjectException) {
                Log.e(TAG, "Binder muerto durante sondeo — Shizuku probablemente cayó")
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

    private fun createShizukuProcess(command: String): Process {
        val cmdArray = arrayOf("sh", "-c", command)
        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            return method.invoke(null, cmdArray, null, null) as Process
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku.newProcess() falló (${e.javaClass.simpleName}): ${e.message}")
            throw e
        }
    }

    fun initFromContext(context: Context) {
        RishExecutor.setApplicationId(context.packageName)
    }

    fun diagnose(context: Context): String {
        val state = checkState()
        return "Shizuku State: $state\n" +
            if (state == State.Ready) "API Version: ${Shizuku.getVersion()}"
            else "Please check Shizuku app."
    }

    fun forceReconnect(context: Context): Boolean = isReady()
}

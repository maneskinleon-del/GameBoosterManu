package com.example.manager

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ShizukuExecutor optimizado siguiendo el modelo de ShizukuManager.
 */
object ShizukuExecutor {
    private const val TAG = "ShizukuExecutor"
    private const val REQUEST_CODE = 1001

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
    } catch (e: Throwable) {
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
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                onResult(granted)
                permissionListener?.let { Shizuku.removeRequestPermissionResultListener(it) }
                permissionListener = null
            }
        }
        permissionListener = listener
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /**
     * Ejecuta un comando shell via Shizuku, devolviendo un Result.
     *
     * Si Shizuku no está disponible, intenta automáticamente con:
     *   1. Rish binary (/data/local/tmp/rish) — mismo uid 2000 que Shizuku
     *   2. Runtime.exec() — sin privilegios, comando normal de app
     *
     * Esto hace que la app sea usable incluso sin Shizuku instalado/iniciado.
     *
     * Shizuku.newProcess() es una API interna (no pública) accesible solo por reflection.
     * Está deprecada en favor de UserService, pero sigue presente en Shizuku v13.x.
     *
     * TODO: Migrar a UserService API cuando Shizuku elimine newProcess.
     *   Ver: https://github.com/RikkaApps/Shizuku-API#userservice
     */
    suspend fun runCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        // 1. Intentar con Shizuku (máxima prioridad)
        val state = checkState()
        if (state == State.Ready) {
            try {
                val process = createShizukuProcess(command)

                val output = process.inputStream.bufferedReader().use { it.readText() }
                val error = process.errorStream.bufferedReader().use { it.readText() }
                process.waitFor()

                if (process.exitValue() == 0) {
                    Log.d(TAG, "✅ Shizuku OK: ${command.take(60)}")
                    return@withContext Result.success(output.trim())
                } else {
                    val err = error.trim().ifBlank { "exit=${process.exitValue()}" }
                    Log.w(TAG, "⚠️ Shizuku falló: $err. Intentando fallback...")
                    // Fall through to fallback
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Excepción Shizuku: ${e.message}. Intentando fallback...")
                // Fall through to fallback
            }
        }

        // 2. Fallback: Rish (uid 2000, mismos privilegios que Shizuku)
        if (RishExecutor.isReady()) {
            val rishResult = RishExecutor.runCommand(command)
            if (rishResult.isSuccess) {
                Log.d(TAG, "✅ Rish fallback OK: ${command.take(60)}")
                return@withContext rishResult
            }
            Log.w(TAG, "⚠️ Rish fallback falló: ${rishResult.exceptionOrNull()?.message}")
        }

        // 3. Fallback final: Runtime.exec() sin privilegios
        Log.w(TAG, "⚠️ Usando Runtime.exec() como fallback final: ${command.take(60)}")
        RishExecutor.runCommandFallback(command)
    }

    /**
     * Crea un proceso Shizuku para ejecutar un comando shell.
     * Usa reflection sobre Shizuku.newProcess() (API interna en Shizuku v13.x).
     *
     * NOTA: newProcess está deprecado. Si una versión futura de Shizuku lo elimina,
     * intentamos un fallback via Runtime.exec() para evitar que la app crashee.
     *
     * TODO: Migrar a UserService API cuando Shizuku elimine newProcess.
     *   Ver: https://github.com/RikkaApps/Shizuku-API#userservice
     */
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
        } catch (e: NoSuchMethodException) {
            // Fallback seguro: Runtime.exec() normal (sin privilegios Shizuku)
            Log.w(TAG, "Shizuku.newProcess() no encontrado, usando Runtime.exec() como fallback.")
            return Runtime.getRuntime().exec(cmdArray)
        } catch (e: IllegalAccessException) {
            // Fallback para cuando isAccessible no funciona (Android 12+ restrictions)
            Log.w(TAG, "Shizuku.newProcess() acceso denegado, usando Runtime.exec() como fallback.")
            return Runtime.getRuntime().exec(cmdArray)
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku.newProcess() error inesperado: ${e.message}, usando Runtime.exec() como fallback.")
            return Runtime.getRuntime().exec(cmdArray)
        }
    }

    fun diagnose(context: Context): String {
        val state = checkState()
        return "Shizuku State: $state\n" + if (state == State.Ready) "API Version: ${Shizuku.getVersion()}" else "Please check Shizuku app."
    }

    fun forceReconnect(context: Context): Boolean = isReady()
}

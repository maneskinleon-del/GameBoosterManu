package com.example.service

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class ShizukuServiceConnection : IShizukuService.Stub() {
    
    companion object {
        private const val TAG = "ShizukuServiceConnection"
        private const val COMMAND_TIMEOUT_MS = 15000L
    }
    
    override fun executeCommand(command: String): String? {
        Log.d(TAG, "📝 Ejecutando comando: $command")
        
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val readThread = Thread {
                try {
                    reader.useLines { lines ->
                        lines.forEach { line ->
                            output.append(line).append("\n")
                            Log.d(TAG, "📤 Output: $line")
                        }
                    }
                } catch (e: Exception) { }
            }
            
            val errorOutput = StringBuilder()
            val errorReadThread = Thread {
                try {
                    errorReader.useLines { lines ->
                        lines.forEach { line ->
                            errorOutput.append(line).append("\n")
                            Log.w(TAG, "⚠️ Error Output: $line")
                        }
                    }
                } catch (e: Exception) { }
            }
            
            readThread.start()
            errorReadThread.start()
            
            val startTime = System.currentTimeMillis()
            var isFinished = false
            var exitCode = -1
            
            while (System.currentTimeMillis() - startTime < COMMAND_TIMEOUT_MS) {
                try {
                    exitCode = process.exitValue()
                    isFinished = true
                    break
                } catch (e: IllegalThreadStateException) {
                    Thread.sleep(100)
                }
            }
            
            if (!isFinished) {
                Log.e(TAG, "❌ Timeout ejecutando comando: $command")
                process.destroy()
                return "Error: Timeout ejecutando comando"
            }
            
            readThread.join(1000)
            errorReadThread.join(1000)
            
            Log.d(TAG, "✅ Comando completado con código: $exitCode")
            
            if (exitCode == 0) {
                output.toString().trim().ifEmpty { "Success" }
            } else {
                val err = errorOutput.toString().trim().ifEmpty { output.toString().trim() }
                Log.e(TAG, "❌ Comando falló con código $exitCode: $err")
                "Error: $err"
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Excepción ejecutando comando: ${e.message}")
            "Exception: ${e.message}"
        }
    }
}

package com.example.manager

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.example.data.PreferenceManager
import com.example.data.repository.GameBoostRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RamManager(
    private val context: Context,
    private val repository: GameBoostRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val protectedApps = mutableSetOf(
        "com.android.systemui",
        "com.google.android.gms",
        "rikka.shizuku",
        "moe.shizuku",
        "com.gg.mouse",
        "com.vphone.helper",
        context.packageName
    )

    private val killList = listOf(
        "com.facebook.orca", "com.facebook.katana",
        "com.instagram.android", "com.whatsapp",
        "com.spotify.music", "com.google.android.youtube",
        "com.netflix.mediaclient", "com.amazon.mShop.android.shopping"
    )

    fun clean(force: Boolean = false) {
        scope.launch {
            val ramInfo = getRamInfo()
            val threshold = if (repository.isAggressiveOptimizationEnabled.value) 70 else 85
            
            if (!force && ramInfo.percent < threshold) {
                repository.logAsync("DEBUG", "RamManager", "Limpieza omitida: ${ramInfo.percent}% usado")
                return@launch
            }

            repository.logAsync("INFO", "RamManager", "Iniciando limpieza de RAM (${ramInfo.percent}% usado)")
            
            // 1. Kill priority list only (sin tocar background packages para no estresar al sistema)
            var killedCount = 0
            if (force && repository.isAggressiveOptimizationEnabled.value) {
                killList.forEach { pkg ->
                    if (!shouldProtect(pkg)) {
                        if (killPackage(pkg)) killedCount++
                        delay(100)
                    }
                }
            } else {
                killList.forEach { pkg ->
                    if (!shouldProtect(pkg)) {
                        killPackage(pkg)
                    }
                }
            }

            // 2. Limpieza de cache segura (sin drop_caches)
            cleanCache()

            // 3. GC
            System.gc()

            delay(1000)
            val afterRam = getRamInfo()
            val freed = (afterRam.free - ramInfo.free).coerceAtLeast(0)
            
            repository.logAsync("INFO", "RamManager", "Limpieza completada: $killedCount apps cerradas. +${freed}MB liberados")
        }
    }

    private fun getRamInfo(): RamInfo {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val total = memInfo.totalMem / (1024 * 1024)
        val free = memInfo.availMem / (1024 * 1024)
        val used = total - free
        val percent = (used.toFloat() / total * 100).toInt()
        return RamInfo(total, used, free, percent)
    }

    private suspend fun getBackgroundPackages(): List<String> {
        return try {
            val result = ShizukuExecutor.runCommand("dumpsys activity recents | grep 'Recent #' | grep -o 'A=[^ ]*' | cut -d= -f2")
            result.getOrNull()?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() && it.contains(".") }?.distinct() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun shouldProtect(pkg: String): Boolean {
        // No cerrar la app actual ni el juego activo
        val activeGame = repository.systemMetrics.value.activeGame
        if (pkg == activeGame || pkg == context.packageName) return true
        
        // No cerrar apps del sistema si no es modo agresivo
        if (!repository.isAggressiveOptimizationEnabled.value) {
            if (pkg.startsWith("com.android.") || pkg.startsWith("com.google.") || pkg.startsWith("android")) return true
        }

        return protectedApps.any { pkg.contains(it) }
    }

    private suspend fun killPackage(pkg: String): Boolean {
        return try {
            val result = ShizukuExecutor.runCommand("am force-stop $pkg")
            result.isSuccess
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun cleanCache() {
        val commands = listOf(
            "pm trim-caches 128M"  // Solo trim-caches, sin drop_caches (evita lags extremos y kills del LMK)
        )
        ShizukuExecutor.runCommand(commands.joinToString("; "))
    }

    data class RamInfo(val total: Long, val used: Long, val free: Long, val percent: Int)
}

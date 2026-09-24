package com.example.manager

import android.content.Context

/**
 * C4 (PR4): fuente de refresh rates del panel, inyectable.
 *
 * - Producción ([SystemRatesProvider]): lee el settings provider vía Shizuku
 *   (las mismas keys que los writers), con cache TTL 60 s.
 * - Tests: cualquier stub determinista (p.ej. `{ listOf(60f, 90f, 120f) }`).
 */
fun interface RefreshRateProvider {
    suspend fun supportedRefreshRates(): List<Float>
}

class SystemRatesProvider(@Suppress("UNUSED_PARAMETER") private val context: Context) : RefreshRateProvider {
    private var cachedRates: List<Float> = emptyList()
    private var ratesCacheAt = 0L

    companion object {
        private const val RATES_CACHE_TTL_MS = 60_000L
    }

    override suspend fun supportedRefreshRates(): List<Float> {
        val now = System.currentTimeMillis()
        if (now - ratesCacheAt < RATES_CACHE_TTL_MS && cachedRates.isNotEmpty()) return cachedRates
        val values = readRatesFromSettings()
        if (values.isNotEmpty()) {
            cachedRates = values
            ratesCacheAt = now
        }
        return cachedRates
    }

    private suspend fun readRatesFromSettings(): List<Float> {
        val out = mutableSetOf<Float>()
        for (key in listOf("peak_refresh_rate", "min_refresh_rate")) {
            val raw = ShizukuExecutor.runCommand("settings get system $key")
                .getOrNull()?.trim()
            val v = raw?.toFloatOrNull()
            if (v != null && v.isFinite() && v > 0f) out.add(v)
        }
        return out.toList()
    }
}

/**
 * Clamp conservador de un refresh pedido a los rates soportados.
 * Round-DOWN: el valor escrito siempre es soportado (under-promise; nunca
 * tearing por pedir un rate que el panel no acepta en la configuración actual).
 * Lista vacía → default seguro 60.
 */
object DisplayRates {
    fun clampRefreshRate(requested: Float, supported: List<Float>): Float {
        if (supported.isEmpty()) return 60f
        val atOrBelow = supported.filter { it <= requested }
        return if (atOrBelow.isNotEmpty()) atOrBelow.max()
        else supported.min()
    }
}

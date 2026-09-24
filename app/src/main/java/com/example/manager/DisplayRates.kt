package com.example.manager

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * C4 (PR4): fuente de refresh rates del panel, inyectable.
 *
 * Producción ([PanelRatesProvider]): Display.getSupportedModes() (API 23, minSdk 24).
 * No lee `settings get system peak_refresh_rate`: ese valor es el último escrito
 * por el propio boost y envenena el clamp del siguiente apply (fail-open).
 * Tests: cualquier stub determinista (p.ej. `{ listOf(60f, 90f, 120f) }`).
 */
fun interface RefreshRateProvider {
    suspend fun supportedRefreshRates(): List<Float>
}

class PanelRatesProvider(private val context: Context) : RefreshRateProvider {
    private var cachedRates: List<Float> = emptyList()
    private var ratesCacheAt = 0L

    companion object {
        private const val RATES_CACHE_TTL_MS = 60_000L
    }

    override suspend fun supportedRefreshRates(): List<Float> {
        val now = System.currentTimeMillis()
        if (now - ratesCacheAt < RATES_CACHE_TTL_MS && cachedRates.isNotEmpty()) return cachedRates
        val values = readPanelModes()
        cachedRates = values
        ratesCacheAt = now
        return cachedRates
    }

    private fun readPanelModes(): List<Float> {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return listOf(60f)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return listOf(60f)
        return display.supportedModes
            .map { it.refreshRate }
            .filter { it.isFinite() && it > 0f }
            .distinct()
            .sorted()
            .ifEmpty { listOf(60f) }
    }
}

/**
 * Clamp conservador de un refresh pedido a los rates soportados.
 * Round-DOWN: el valor escrito siempre es soportado (under-promise; nunca
 * tearing por pedir un rate que el panel no acepta en la configuración actual).
 * Lista vacía → default seguro 60.
 * Si el pedido está por debajo de todos los modos, se usa el mínimo del panel
 * (nunca un rate inventado).
 */
object DisplayRates {
    fun clampRefreshRate(requested: Float, supported: List<Float>): Float {
        if (supported.isEmpty()) return 60f
        val atOrBelow = supported.filter { it <= requested }
        return if (atOrBelow.isNotEmpty()) atOrBelow.max()
        else supported.min()
    }
}

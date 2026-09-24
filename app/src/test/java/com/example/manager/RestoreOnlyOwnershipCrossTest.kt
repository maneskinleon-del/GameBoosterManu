package com.example.manager

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PR2 — Cross-test de ownership de restoreOnly (REQUERIDO por el auditor de PR1):
 * forzar RESTORE_FAILED en una key PERTENECIENTE a TouchOptimizer
 * (`secure:long_press_timeout` — secure, no colisiona con SystemTweaks ni
 * NetworkOptimizer) y verificar sobre los métodos REALES:
 *
 *  1. `TouchOptimizer.restoreOnly(failedKeys)` SÍ escribe.
 *  2. `SystemTweaks.restoreOnly(failedKeys)` NO emite ningún comando.
 *  3. `NetworkOptimizer.restoreOnly(failedKeys)` NO emite ningún comando.
 *
 * E2E (no helpers extraídos): ShizukuExecutor.commandInterceptor (seam PR2) observa
 * cada comando ANTES de los backends; SystemTweaks/NetworkOptimizer se construyen
 * vía el ctor interno BoostLogSink (seam PR2) — sin Room, sin Context, sin
 * Robolectric (que esta suite nunca ha ejercitado).
 *
 * Controles anti-vacío: cada manager SÍ emite para una key propia con el mismo
 * setup — un "no emite" solo cuenta si el mismo camino SÍ emite cuando le toca.
 * Si fallara el invariante de ownership en el futuro (p.ej. pointer_speed migra de
 * manager, o restoreOnly pierde su filtro), este test revienta.
 */
class RestoreOnlyOwnershipCrossTest {

    private val emitted = CopyOnWriteArrayList<String>()
    private val failedTouchKey = setOf("secure:long_press_timeout")

    @Before
    fun installInterceptor() {
        emitted.clear()
        ShizukuExecutor.commandInterceptor = { cmd ->
            emitted.add(cmd)
            if (cmd.startsWith("settings get ")) {
                val p = cmd.split(" ")
                // Fakes del backup: el original del usuario para long_press es 400
                Result.success(
                    when ("${p[2]}:${p[3]}") {
                        "secure:long_press_timeout" -> "400"
                        "system:pointer_speed" -> "3"
                        else -> "null"
                    }
                )
            } else {
                Result.success("")
            }
        }
    }

    @After
    fun uninstallInterceptor() {
        ShizukuExecutor.commandInterceptor = null
        emitted.clear()
    }

    /** Solo writes (put/delete) — los settings get del backup no cuentan. */
    private fun writes(): List<String> =
        emitted.filter { it.startsWith("settings put") || it.startsWith("settings delete") }

    /** Espera ≥ min writes y luego una ventana de gracia para detectar extras tardíos. */
    private fun awaitWrites(min: Int, timeoutMs: Long = 5_000): List<String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && writes().size < min) {
            Thread.sleep(20)
        }
        Thread.sleep(250) // ventana para writes no deseados que lleguen tarde
        val w = writes()
        if (w.size < min) {
            fail("timeout: esperaba ≥$min writes, vi ${w.size}: $w (emitted=$emitted)")
        }
        return w
    }

    /** Durante [graceMs] no debe aparecer NINGÚN write — falla ante el primero. */
    private fun assertNoWrites(owner: String, graceMs: Long = 800) {
        val deadline = System.currentTimeMillis() + graceMs
        while (System.currentTimeMillis() < deadline) {
            if (writes().isNotEmpty()) {
                fail("$owner NO debe emitir comandos para failedKeys=$failedTouchKey — emitió: ${writes()}")
            }
            Thread.sleep(20)
        }
    }

    // ── El cross-test requerido ─────────────────────────────────────────────

    @Test
    fun `cross - RESTORE_FAILED en long_press_timeout solo lo escribe TouchOptimizer`() {
        // Setup: backup RAM real de TouchOptimizer (4 keys) vía apply + interceptor
        val touch = TouchOptimizer(Application(), recordApplied = { _, _, _ -> })
        touch.applyOptimization(sensitivity = 10, isGamingMode = true)
        assertEquals("apply debe escribir las 4 keys de Touch", 4, awaitWrites(4).size)
        emitted.clear()

        // 1) TouchOptimizer.restoreOnly SÍ escribe (su key, su backup, su valor original)
        touch.restoreOnly(failedTouchKey)
        assertEquals(
            listOf("settings put secure long_press_timeout 400"),
            awaitWrites(1)
        )

        // 2) SystemTweaks.restoreOnly NO emite ningún comando
        emitted.clear()
        SystemTweaks(BoostLogSink { _, _, _ -> }).restoreOnly(failedTouchKey)
        assertNoWrites("SystemTweaks")

        // 3) NetworkOptimizer.restoreOnly NO emite ningún comando
        emitted.clear()
        NetworkOptimizer(BoostLogSink { _, _, _ -> }).restoreOnly(failedTouchKey)
        assertNoWrites("NetworkOptimizer")
    }

    // ── Controles anti-vacío: el mismo camino SÍ emite para keys propias ───

    @Test
    fun `control - SystemTweaks si emite para su propia key`() {
        val st = SystemTweaks(BoostLogSink { _, _, _ -> })
        // Sin backup en RAM (instancia fresca), getRestoreCommands usa el default
        // de auto_sync (línea: "auto_sync" -> put 1) — el filtro por ownership lo deja pasar.
        st.restoreOnly(setOf("global:auto_sync"))
        assertEquals(listOf("settings put global auto_sync 1"), awaitWrites(1))
    }

    @Test
    fun `control - NetworkOptimizer si emite para su propia key`() {
        val net = NetworkOptimizer(BoostLogSink { _, _, _ -> })
        // Sin backup, buildRestoreCommands usa el default pre-H6 de private_dns_mode.
        net.restoreOnly(setOf("global:private_dns_mode"))
        assertEquals(listOf("settings put global private_dns_mode off"), awaitWrites(1))
    }
}

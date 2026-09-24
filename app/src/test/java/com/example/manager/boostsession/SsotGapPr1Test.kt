package com.example.manager.boostsession

import com.example.manager.BoostLogSink
import com.example.manager.ShizukuExecutor
import com.example.manager.SystemTweaks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests de regresión PR#1 "Cerrar la brecha SSOT" (A1/A3/NS3):
 *
 * - A1: los writers fuera del funnel (SystemTweaks/NetworkOptimizer, ahora con
 *   recordApplied inyectado — patrón TouchOptimizer) dejan appliedValue en la
 *   sesión → el restore VERIFICA en vez de degradar a CONFLICT. Se demuestra con
 *   pointer_speed, key dinámica (BoostKeys.appliedValueOf → null): pre-PR1 su
 *   restore NUNCA podía verificarse sin el record.
 * - A3: recordApplied/markActive usan RMW atómico (store.update) — updates
 *   concurrentes no se pierden y markActive no pisa records previos.
 * - NS3/Race 4.D: clearIf es condicional y atómico (solo borra la sesión propia).
 *
 * JVM puro: reutiliza FakeDevice de BoostSessionManagerTest (emulador del
 * pipeline settings get/put/delete).
 */
class SsotGapPr1Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: BoostSessionStore
    private lateinit var device: BoostSessionManagerTest.FakeDevice
    private lateinit var mgr: BoostSessionManager

    @Before
    fun setup() {
        store = BoostSessionStore(File(tmp.root, "boost_session.json"))
        device = BoostSessionManagerTest.FakeDevice(
            mapOf(
                "global:ble_scan_always_enabled" to "1",
                "system:pointer_speed" to "3"
            )
        )
        mgr = BoostSessionManager(
            store,
            { cmd -> device.run(cmd) },
            log = { _, _, _ -> }
        )
    }

    // ── A1: writer externo con recordApplied → restore VERIFIED ────────

    @Test
    fun `writer externo con recordApplied - restore VERIFIED`() = runBlocking {
        assertTrue(mgr.beginApply())
        // Writer con SSOT (patrón PR1-A1): puso "7" en el dispositivo Y lo grabó.
        device.settings["system:pointer_speed"] = "7"
        mgr.recordApplied("system", "pointer_speed", "7")

        val report = mgr.restoreVerified()
        assertEquals(RestoreResult.RESTORE_VERIFIED, report.results["system:pointer_speed"])
        assertEquals("3", device.settings["system:pointer_speed"])
    }

    @Test
    fun `writer externo SIN recordApplied (brecha pre-PR1) - degrada a CONFLICT`() = runBlocking {
        assertTrue(mgr.beginApply())
        // Brecha histórica: el writer puso "7" pero NO grabó (SystemTweaks pre-PR1).
        device.settings["system:pointer_speed"] = "7"

        val report = mgr.restoreVerified()
        // pointer_speed es dinámica (appliedValueOf → null): sin appliedValue no
        // hay Caso A → el restore conserva el valor del usuario y degrada.
        assertEquals(RestoreResult.RESTORE_CONFLICT, report.results["system:pointer_speed"])
        assertEquals("7", device.settings["system:pointer_speed"])
    }

    // ── A3: atomicidad de recordApplied / markActive ───────────────────

    @Test
    fun `recordApplied concurrente en todas las keys - ninguna se pierde`() = runBlocking {
        assertTrue(mgr.beginApply())
        val keys = BoostKeys.all // 34 entradas (ns, key)
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(keys.size)
        for ((ns, key) in keys) {
            pool.submit {
                try {
                    mgr.recordApplied(ns, key, "v_$key")
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        val snap = mgr.sessionSnapshot()!!
        assertEquals(BoostSessionState.APPLYING, snap.state)
        assertEquals(keys.size, snap.baseline.size)
        val lost = snap.baseline.filter { it.appliedValue == null }
        assertTrue("keys sin appliedValue (perdidas por race): $lost", lost.isEmpty())
    }

    @Test
    fun `markActive no pisa records previos`() = runBlocking {
        assertTrue(mgr.beginApply())
        mgr.recordApplied("global", "ble_scan_always_enabled", "0")
        mgr.markActive()

        val snap = mgr.sessionSnapshot()!!
        assertEquals(BoostSessionState.ACTIVE, snap.state)
        assertEquals(
            "0",
            snap.baseline.first { it.key == "ble_scan_always_enabled" }.appliedValue
        )
    }

    // ── NS3 / Race 4.D: clearIf condicional atómico ────────────────────

    @Test
    fun `clearIf con sesion ajena - no borra`() = runBlocking {
        assertTrue(mgr.beginApply())
        assertFalse(store.clearIf { it.sessionId == "bs_otra_sesion" })
        assertNotNull(mgr.sessionSnapshot())
    }

    @Test
    fun `clearIf con sesion propia RESTORED - borra`() = runBlocking {
        assertTrue(mgr.beginApply())
        device.settings["system:pointer_speed"] = "7"
        mgr.recordApplied("system", "pointer_speed", "7")
        val report = mgr.restoreVerified()
        assertTrue(report.allOk) // → sesión en RESTORED con sessionId intacto

        assertTrue(store.clearIf { it.state == BoostSessionState.RESTORED })
        assertNull(mgr.sessionSnapshot())
    }

    // ── V2: cobertura de las 34 keys (no solo pointer_speed) ──────────

    @Test
    fun `apply con batch cubre las 34 keys - cero dinamicas sin appliedValue`() = runBlocking {
        assertTrue(mgr.beginApply())
        // Ciclo de boost completo: TODOS los writers (SystemTweaks 20 + Network 7 +
        // Touch 4 + GSM 3: zen/anims/refreses) aplican y graban en batch.
        mgr.recordAppliedBatch(BoostKeys.all.map { (ns, key) -> Triple(ns, key, "v_$key") })

        val snap = mgr.sessionSnapshot()!!
        assertEquals(34, snap.baseline.size)
        val missing = snap.baseline.filter { it.appliedValue == null }
        assertTrue("keys sin appliedValue (brecha A1): $missing", missing.isEmpty())

        // Aserción del auditor: las DINÁMICAS (sin entrada en la tabla estática)
        // son las que pre-PR1 NUNCA podían verificarse — deben quedar cubiertas.
        val dinamicas = snap.baseline.filter { BoostKeys.appliedValueOf(it.namespace, it.key) == null }
        assertTrue("el test debe ejercitar keys dinámicas", dinamicas.isNotEmpty())
        assertTrue("dinámicas sin appliedValue: $dinamicas", dinamicas.all { it.appliedValue != null })

        // Cierre: restore de las 34 → VERIFIED (sin CONFLICT, sin FAILED)
        val report = mgr.restoreVerified()
        assertTrue("report.allOk=false: ${report.results.filterValues { it != RestoreResult.RESTORE_VERIFIED }}", report.allOk)
        assertEquals(34, report.results.size)
    }

    @Test
    fun `restore desde conflicto parcial no pisa keys verificadas (V3 per-key)`() = runBlocking {
        assertTrue(mgr.beginApply())
        mgr.recordAppliedBatch(BoostKeys.all.map { (ns, key) -> Triple(ns, key, "v_$key") })
        // El usuario cambia UNA key durante el boost (ventana) → esa será CONFLICT;
        // el resto debe restaurar sin que un fallback completo las pise.
        device.settings["global:auto_sync"] = "user_value"
        val report = mgr.restoreVerified()
        assertEquals(RestoreResult.RESTORE_CONFLICT, report.results["global:auto_sync"])
        assertTrue(report.allOk) // conflicto no es fallo
        // La key conservada conserva el valor del usuario (nadie la pisó)
        assertEquals("user_value", device.settings["global:auto_sync"])
    }

    // ── NS3: premisa del gate del servicio ─────────────────────────────

    @Test
    fun `gate NS3 - currentState no es IDLE durante sesion activa`() = runBlocking {
        // GameBoostService.restoreSavedSettings corta si currentState() != IDLE.
        // El gate vive en el Service (no testeable en JVM); este test fija el
        // contrato del que depende: toda sesión activa es no-IDLE.
        assertTrue(mgr.beginApply())
        assertTrue(mgr.currentState() != BoostSessionState.IDLE)
        mgr.markActive()
        assertTrue(mgr.currentState() != BoostSessionState.IDLE)
    }

    // ── V4 (reescrito PR2 — determinista): se CUENTA update(), sin wall-clock ──
    // El test original medía un RATIO de tiempos sobre fsync y flakeaba con carga
    // concurrente (2.88x bajo carga vs 12.4x en reposo — misma clase de assert
    // ambiental que V1). La propiedad real es "batch = 1 update atómico, no N":
    // un conteo de llamadas no puede flakear.

    @Test
    fun `V4 determinista - batch = 1 update, por-key = N updates`() = runBlocking {
        val updates = java.util.concurrent.atomic.AtomicInteger(0)
        val counted = BoostSessionStore(
            File(tmp.root, "v4_count.json"),
            updateObserver = { updates.incrementAndGet() }
        )
        val countedMgr = BoostSessionManager(counted, { Result.success("") }, log = { _, _, _ -> })

        // Archivo fresco: beginApply crea la sesión vía save() — no debe contar update()
        assertTrue(countedMgr.beginApply())
        updates.set(0)

        // Batch (PR1b): 34 keys en UNA sola entrada RMW
        countedMgr.recordAppliedBatch(BoostKeys.all.map { (ns, key) -> Triple(ns, key, "v_$key") })
        assertEquals("recordAppliedBatch debe hacer exactamente 1 update", 1, updates.get())

        // Anti-patrón pre-PR1b: recordApplied por key = N updates — esto es lo que
        // el batch elimina; si alguien revierte el diseño, esta rama documenta el "antes"
        // y el assert de arriba caza el "después" roto.
        updates.set(0)
        BoostKeys.all.forEach { (ns, key) -> countedMgr.recordApplied(ns, key, "v_$key") }
        assertEquals(
            "recordApplied por-key debe hacer N updates (inversión del batch)",
            BoostKeys.all.size,
            updates.get()
        )
    }

    @Test
    fun `V4 determinista - SystemTweaks apply deja exactamente 1 update batch`() {
        val updates = java.util.concurrent.atomic.AtomicInteger(0)
        val puts = java.util.concurrent.atomic.AtomicInteger(0)
        val counted = BoostSessionStore(
            File(tmp.root, "v4_apply.json"),
            updateObserver = { updates.incrementAndGet() }
        )
        val countedMgr = BoostSessionManager(counted, { Result.success("") }, log = { _, _, _ -> })

        // Seam PR2: observa los comandos reales de apply ANTES de los backends
        ShizukuExecutor.commandInterceptor = { cmd ->
            if (cmd.startsWith("settings put")) puts.incrementAndGet()
            Result.success("")
        }
        try {
            val st = SystemTweaks(
                BoostLogSink { _, _, _ -> },
                recordBatch = { entries -> countedMgr.recordAppliedBatch(entries) }
            )
            st.apply(enableMsaa = false)

            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && (updates.get() == 0 || puts.get() < 10)) {
                Thread.sleep(20)
            }
            Thread.sleep(300) // ventana para updates tardíos no deseados (reversión por-key)

            assertTrue(
                "apply debió emitir settings puts (puts=${puts.get()}) — no-vacío",
                puts.get() >= 10
            )
            assertEquals(
                "SystemTweaks.apply debe commitear exactamente 1 update batch, no N",
                1,
                updates.get()
            )
        } finally {
            ShizukuExecutor.commandInterceptor = null
        }
    }
}

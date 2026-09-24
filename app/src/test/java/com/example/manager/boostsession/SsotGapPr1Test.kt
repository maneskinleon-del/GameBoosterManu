package com.example.manager.boostsession

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
}

package com.example.manager.boostsession

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * R1 (C4): tests JVM de la guarda de estado en la SSOT.
 *
 * Criterio de aceptación #5 del plan (R1-IMPLEMENTATION-PLAN-2026-09-13):
 * "restore() no puede ser seguido por un markActive() huérfano" — es decir,
 * la secuencia del zombie evidenciada en OVERLAY-DISAPPEAR-FORENSIC-2026-09-13.md
 * (toggle → exit a t<8s → RESTORED → markActive del Job diferido → ACTIVE ilegal)
 * queda IMPOSIBLE en la capa SSOT, independientemente de la cancelación del Job (C3).
 *
 * El resto de criterios (T1-T4, T6, T7: entrada FF, transitoria ≠ salida,
 * polling-only, salida real, autoridad única, overlay proyección) se validan
 * en dispositivo con el repro forense — GameSessionManager/GameBoostRepository
 * están acoplados a Android (Room/SharedPreferences/Service) y no son
 * testeables en JVM sin refactor fuera del alcance de R1 (decisión del usuario:
 * "adapted split").
 *
 * JVM puro: CommandRunner fake con mapa en memoria (mismo patrón que
 * BoostSessionManagerTest).
 */
class GameLifecycleR1Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: BoostSessionStore
    private lateinit var device: FakeDevice
    private lateinit var logs: MutableList<String>
    private lateinit var mgr: BoostSessionManager

    /** Emulador mínimo del pipeline settings (patrón BoostSessionManagerTest). */
    class FakeDevice(initial: Map<String, String> = emptyMap()) {
        val settings = initial.toMutableMap()
        var failPutFor: Set<String> = emptySet()

        fun run(cmd: String): Result<String> {
            val parts = cmd.split(" ")
            return when {
                parts[0] == "settings" && parts[1] == "get" ->
                    Result.success(settings["${parts[2]}:${parts[3]}"] ?: "null")
                parts[0] == "settings" && parts[1] == "put" -> {
                    val id = "${parts[2]}:${parts[3]}"
                    if (id in failPutFor) Result.failure(RuntimeException("simulated failure"))
                    else {
                        settings[id] = parts.drop(4).joinToString(" ")
                        Result.success("")
                    }
                }
                parts[0] == "settings" && parts[1] == "delete" -> {
                    val id = "${parts[2]}:${parts[3]}"
                    if (id in failPutFor) Result.failure(RuntimeException("simulated failure"))
                    else {
                        settings.remove(id)
                        Result.success("")
                    }
                }
                else -> Result.failure(IllegalArgumentException("unknown cmd"))
            }
        }
    }

    @Before
    fun setup() {
        store = BoostSessionStore(File(tmp.root, "boost_session.json"))
        device = FakeDevice()
        logs = mutableListOf()
        mgr = BoostSessionManager(
            store,
            { cmd -> device.run(cmd) },
            log = { l: String, t: String, m: String -> logs.add("[$l/$t] $m") }
        )
    }

    /**
     * T5-núcleo (secuencia EXACTA del forense 13:58): beginApply → restore
     * (exit a t<8s) → RESTORED → el markActive diferido del Job llega tarde.
     * La guarda C4 lo rechaza: la sesión PERMANECE en RESTORED.
     */
    @Test
    fun `orphan markActive after restore does NOT flip RESTORED to ACTIVE`() = runBlocking {
        // Boost ON: baseline capturado del device "stock" y apply con valores boosteados
        device.settings["global:auto_sync"] = "1"
        assertTrue(mgr.beginApply())
        assertEquals(BoostSessionState.APPLYING, mgr.currentState())
        device.settings["global:auto_sync"] = "0"                 // writer aplica
        mgr.recordApplied("global", "auto_sync", "0")             // SSOT graba el aplicado

        // Exit a t<8s → restore verificado → RESTORED
        val report = mgr.restoreVerified()
        assertTrue(report.allOk)
        assertEquals(BoostSessionState.RESTORED, mgr.currentState())

        // t=8s: el Job huérfano ejecuta markActiveIfApplying() → DEBE ser inerte
        mgr.markActiveIfApplying()
        assertEquals("zombie: RESTORED no puede volver a ACTIVE (R1 C4)", BoostSessionState.RESTORED, mgr.currentState())
        assertTrue("la guarda debe registrar el rechazo", logs.any { it.contains("markActive ignorado") })
    }

    /** Camino normal: APPLYING → ACTIVE (la guarda no rompe el flujo legítimo). */
    @Test
    fun `markActive from APPLYING still transitions to ACTIVE`() = runBlocking {
        assertTrue(mgr.beginApply())
        mgr.markActiveIfApplying()
        assertEquals(BoostSessionState.ACTIVE, mgr.currentState())
        assertFalse(logs.any { it.contains("markActive ignorado") })
    }

    /** La guarda también protege los otros estados no-APPLYING (RECOVERY_REQUIRED). */
    @Test
    fun `markActive from RECOVERY_REQUIRED is ignored`() = runBlocking {
        device.settings["global:auto_sync"] = "1"
        assertTrue(mgr.beginApply())
        device.settings["global:auto_sync"] = "0"
        mgr.recordApplied("global", "auto_sync", "0")
        device.failPutFor = setOf("global:auto_sync")             // el restore del valor falla
        val report = mgr.restoreVerified()
        assertFalse(report.allOk)
        assertEquals(BoostSessionState.RECOVERY_REQUIRED, mgr.currentState())

        mgr.markActiveIfApplying()
        assertEquals(BoostSessionState.RECOVERY_REQUIRED, mgr.currentState())
    }

    /** Sin sesión (IDLE): markActive es no-op total. */
    @Test
    fun `markActive with no session is a no-op`() {
        mgr.markActiveIfApplying()
        assertEquals(BoostSessionState.IDLE, mgr.currentState())
    }

    /**
     * Tolerancia BASELINE_CAPTURED → ACTIVE (decisión de diseño C4): es el estado
     * intermedio legítimo de un apply recién comenzado; se permite para no
     * fragmentar el camino normal.
     */
    @Test
    fun `markActive from BASELINE_CAPTURED is tolerated`() {
        store.save(
            BoostSession(
                BoostSessionState.BASELINE_CAPTURED,
                listOf(BackupEntry("global", "auto_sync", "1", 1L, "s")),
                "s", 1L
            )
        )
        mgr.markActiveIfApplying()
        assertEquals(BoostSessionState.ACTIVE, mgr.currentState())
    }
}

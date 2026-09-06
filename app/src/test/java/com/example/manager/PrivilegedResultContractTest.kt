package com.example.manager

import com.example.manager.boostsession.BoostSessionManager
import com.example.manager.exec.ExecOutcome
import com.example.manager.exec.ExecResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests F1 del contrato de ejecución estructurado (T1-T3 del diseño F1).
 *
 * Estos tests validan la semántica del contrato [ExecResult]/[ExecOutcome]
 * y la regla central: exit 0 != "aplicado". Son JVM puros (la clase de
 * contrato no depende de Android).
 *
 * Las eliminaciones estructurales (set-process-limit/drop_caches/force-idle)
 * y el lifecycle gate se verifican en ResourceGovernorContractTest.
 */
class PrivilegedResultContractTest {

    // ── T1: structured execution result ─────────────────────────────
    @Test
    fun `EXECUTED devuelve un resultado pero NO esta verificado`() {
        val r = ExecResult(
            outcome = ExecOutcome.EXECUTED("ok"),
            command = "settings put global x 1"
        )
        assertTrue(r.wasExecuted)          // corrió, exit 0
        assertFalse(r.verified)            // pero NO hay read-back
        assertFalse(r.isFullyVerified)     // No es "aplicado correctamente"
        assertFalse(r.isFailure)           // no es un fallo
    }

    @Test
    fun `solo EXECUTED con read-back coincide es fully verified`() {
        val ok = ExecResult(
            outcome = ExecOutcome.EXECUTED("ok"),
            verified = true,
            deviceValue = BoostSessionManager.SettingRead.Present("0.5"),
            command = "settings put global window_animation_scale 0.5"
        )
        assertTrue(ok.isFullyVerified)
        assertTrue(ok.verified)
    }

    // ── T2: failed execution != success ─────────────────────────────
    @Test
    fun `EXIT_NONZERO nunca es exito`() {
        val r = ExecResult(
            outcome = ExecOutcome.EXIT_NONZERO(1, "Permission denied"),
            command = "cmd activity set-process-limit"
        )
        assertFalse(r.wasExecuted)
        assertFalse(r.isFullyVerified)
        assertTrue(r.isFailure)
    }

    @Test
    fun `exit 0 con stderr error tampoco es exito`() {
        val r = ExecResult(
            outcome = ExecOutcome.STDERR_ERROR("write error: Permission denied"),
            command = "echo 1 > /proc/sys/vm/drop_caches"
        )
        assertTrue(r.isFailure)
        assertFalse(r.wasExecuted)
    }

    @Test
    fun `privilege unavailable no es exito`() {
        val r = ExecResult(
            outcome = ExecOutcome.PRIVILEGE_UNAVAILABLE("no shizuku"),
            command = "cmd activity set-process-limit"
        )
        assertTrue(r.isFailure)
        assertFalse(r.verified)
    }

    // ── T3: read-back mismatch ─────────────────────────────────────
    @Test
    fun `Present esperado con valor distinto = NO verificado`() {
        // write a 0.5 pero read-back devuelve 1.0 → mismatch → verified=false
        val r = ExecResult(
            outcome = ExecOutcome.EXECUTED("ok"),
            verified = false,
            deviceValue = BoostSessionManager.SettingRead.Present("1.0"),
            command = "settings put global animator_duration_scale 0.5"
        )
        assertFalse(r.isFullyVerified)
    }

    @Test
    fun `ReadFailed no es exito`() {
        // read failure → NUNCA success
        val r = ExecResult(
            outcome = ExecOutcome.EXECUTED("ok"),
            verified = false,
            deviceValue = BoostSessionManager.SettingRead.ReadFailed("shizuku down"),
            command = "settings put global window_animation_scale 0.5"
        )
        assertFalse(r.verified)
        assertFalse(r.isFullyVerified)
    }

    @Test
    fun `Absent en read-back de una key esperada = no verificado`() {
        val r = ExecResult(
            outcome = ExecOutcome.EXECUTED("ok"),
            verified = false,
            deviceValue = BoostSessionManager.SettingRead.Absent,
            command = "settings put global window_animation_scale 0.5"
        )
        assertFalse(r.verified)
    }

    // ── Casos de contrato completo ─────────────────────────────────
    @Test
    fun `all outcome types are distinguishable`() {
        val outcomes = listOf(
            ExecOutcome.EXECUTED("o"),
            ExecOutcome.EXIT_NONZERO(1, "e"),
            ExecOutcome.STDERR_ERROR("e"),
            ExecOutcome.PROCESS_START_FAILED("r"),
            ExecOutcome.TIMEOUT(5000),
            ExecOutcome.PRIVILEGE_UNAVAILABLE("r")
        )
        assertEquals(6, outcomes.map { it::class }.toSet().size)
    }
}

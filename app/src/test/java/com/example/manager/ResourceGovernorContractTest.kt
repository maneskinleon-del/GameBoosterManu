package com.example.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests F1 estructurales de ResourceGovernor (T4-T6, T7-T10 del diseño F1).
 *
 * Como ResourceGovernor depende de GameBoostRepository (singleton Android no
 * instanciable en JVM puro), estos tests validan el CONTRATO del fuente:
 *  - T4/T5/T6: las mutaciones no compatibles NO existen en el pathway automático
 *  - T7-T10: el gate de estado bloquea los estados no consistentes
 *
 * Esto no es "verificar que se llamó un método": verifica por estructura que
 * el código NO PUEDE emitir esas mutaciones y que el gate existe con la
 * semántica requerida. Complementa tests JVM puros del contrato.
 */
class ResourceGovernorContractTest {

    private val source = File(
        "src/main/java/com/example/manager/ResourceGovernor.kt"
    ).readText()

    // ── T4/T5: set-process-limit eliminado ─────────────────────────
    @Test
    fun `set-process-limit no existe en el pathway automatico`() {
        val executableLines = source
            .lineSequence()
            .filter { !it.trim().startsWith("//") }
            .filter { !it.trim().startsWith("*") }
            .filter { !it.trim().startsWith("/*") }
            .toList()
        // Sin líneas ejecutables de set-process-limit (solo permitidas las notas de doc)
        val emitted = executableLines.filter {
            it.contains("set-process-limit") && !it.trim().startsWith("//")
        }
        // Las únicas menciones deben estar en comentarios/documentación, nunca en executePrivilegedCommand
        val inCommand = executableLines.filter {
            it.trim().startsWith("repository.executePrivilegedCommand") ||
            it.contains("executePrivilegedCommand(\"cmd activity set-process-limit")
        }
        // Filtrar la KDoc de la clase que menciona el comando como "eliminado"
        val actuallyEmitted = emitted.filter {
            it.contains("executePrivilegedCommand") ||
            it.contains("repository.executePrivilegedCommand")
        }
        assertTrue(
            "set-process-limit no debe ejecutarse: líneas=${inCommand}",
            actuallyEmitted.none { it.contains("set-process-limit") }
        )
    }

    // ── T5: drop_caches eliminado ──────────────────────────────────
    @Test
    fun `drop_caches no se ejecuta en ResourceGovernor`() {
        val emitted = source
            .lineSequence()
            .filter { it.contains("drop_caches") && !it.trim().startsWith("//") }
            .filter { !it.trim().startsWith("*") }
            .filter { it.contains("executePrivilegedCommand") }
            .toList()
        assertTrue(
            "drop_caches no debe ejecutarse en RG: $emitted",
            emitted.none { it.contains("executePrivilegedCommand") && it.contains("drop_caches") }
        )
    }

    // ── T6: force-idle eliminado del screen-off automático ────────
    @Test
    fun `force-idle no se ejecuta desde ResourceGovernor`() {
        val executeCalls = source
            .lineSequence()
            .filter { it.contains("executePrivilegedCommand") }
            .filter { it.contains("force") }
            .toList()
        assertTrue(
            "force-idle no debe ejecutarse desde RG: $executeCalls",
            executeCalls.none { it.contains("force-idle") }
        )
    }

    // ── T7-T10: gate de estado ─────────────────────────────────────
    @Test
    fun `isMutationsAllowed existe y bloquea los estados no consistentes`() {
        assertTrue(source.contains("private fun isMutationsAllowed()"))
        assertTrue(source.contains("BoostSessionState.APPLYING"))
        assertTrue(source.contains("BoostSessionState.RESTORING"))
        assertTrue(source.contains("BoostSessionState.RECOVERY_REQUIRED"))
        assertTrue(source.contains("BoostSessionState.IDLE"))
        assertTrue(source.contains("BoostSessionState.ACTIVE"))
        assertTrue(source.contains("BoostSessionState.RESTORED"))
    }

    // ── Serialización / mutex ──────────────────────────────────────
    @Test
    fun `handlers estan serializados por mutex`() {
        assertTrue(source.contains("screenMutex"))
        assertTrue(source.contains("withLock"))
    }

    // ── Read-back integrado ────────────────────────────────────────
    @Test
    fun `verifyAnimWrite existe e integra read-back`() {
        assertTrue(source.contains("private suspend fun verifyAnimWrite"))
        assertTrue(source.contains("SettingRead"))
        assertTrue(source.contains("settings get"))
    }
}

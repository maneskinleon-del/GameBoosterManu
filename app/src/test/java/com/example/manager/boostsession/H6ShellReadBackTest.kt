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
 * TEST DIRIGIDO H6 → REGRESIÓN POST-FIX (2026-09-22).
 *
 * PRE-FIX (evidencia original): un valor adversarial del settings provider
 * ("1; echo GB_H6_PROOF > <sandbox>") capturado en el baseline volvía
 * interpolado crudo en "settings put global zen_mode <original>" (Caso A de
 * restoreVerified) y sh -c ejecutaba la 2ª instrucción.
 *
 * POST-FIX (contrato actual): restoreVerified valida el valor read-back contra
 * el dominio de la key (RestoreValueValidators — REGLA: rechazar, nunca mutar).
 * El payload NO debe llegar a runCommand() y el marker NO debe crearse.
 *
 * El fake replica fielmente el último tramo real de producción
 * (sh -c <string> sin quoting, ShizukuExecutor.kt:177-183 / RishExecutor.kt:85-88)
 * para que cualquier fuga del payload al shell sea observable. Marcador INOCUO,
 * confinado al TemporaryFolder de JUnit (se borra solo).
 */
class H6ShellReadBackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: BoostSessionStore
    private val executed = mutableListOf<String>()

    companion object {
        const val PROOF_FILE = "h6-shell-interpreted"
    }

    /** Marcador inocuo apuntando al sandbox de ESTA ejecución. */
    private fun markerFor(sandbox: File): String =
        "1; echo GB_H6_PROOF > '${File(sandbox, PROOF_FILE).absolutePath}'"

    /**
     * Fake con capa provider (mapa) + capa shell REAL en cada put: ejecuta
     * literalmente sh -c <cmd>. Si el payload llegara al comando, el marker
     * aparecería → el test falla.
     */
    private inner class ShellFakeDevice {
        val settings = mutableMapOf<String, String>()

        fun run(cmd: String): Result<String> {
            executed.add(cmd)
            val parts = cmd.split(" ")
            return when {
                parts[0] == "settings" && parts[1] == "get" ->
                    Result.success(settings["${parts[2]}:${parts[3]}"] ?: "null")

                parts[0] == "settings" && parts[1] == "put" -> {
                    // Último tramo REAL, fiel a producción: SIN quoting añadido.
                    try {
                        ProcessBuilder("sh", "-c", cmd)
                            .start()
                            .waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (_: Exception) {
                        // sin shell disponible: la capa provider sigue siendo válida
                    }
                    settings["${parts[2]}:${parts[3]}"] = parts.drop(4).joinToString(" ")
                    Result.success("")
                }

                parts[0] == "settings" && parts[1] == "delete" -> {
                    settings.remove("${parts[2]}:${parts[3]}")
                    Result.success("")
                }

                else -> Result.failure(IllegalArgumentException("unknown cmd: $cmd"))
            }
        }
    }

    @Before
    fun setup() {
        store = BoostSessionStore(File(tmp.root, "boost_session.json"))
        executed.clear()
    }

    /** H6A — REGRESIÓN: el payload adversarial del baseline NO llega al shell. */
    @Test
    fun `H6A payload adversarial del baseline es rechazado y NUNCA llega al shell`() = runBlocking {
        val sandbox = tmp.newFolder("h6-sandbox")
        val marker = markerFor(sandbox)
        val device = ShellFakeDevice()
        val mgr = BoostSessionManager(
            store,
            { cmd -> device.run(cmd) },
            log = { _, _, _ -> }
        )

        // 1. Baseline capturado con zen_mode = payload (provider corrupto/manipulado)
        device.settings["global:zen_mode"] = marker
        assertTrue(mgr.beginApply())
        mgr.markActive()
        assertEquals(marker, store.load()!!.baseline.first { it.key == "zen_mode" }.originalValue)

        // 2. El writer aplica el valor boost (como GSM:292, sin pasar por comandos aquí)
        device.settings["global:zen_mode"] = "2"

        // 3. Restore: Caso A (current==aplicado) → el valor inválido debe RECHAZARSE:
        //    RESTORE_FAILED, sin ejecutar runCommand para esa key.
        val report = mgr.restoreVerified()
        assertEquals(RestoreResult.RESTORE_FAILED, report.results["global:zen_mode"])
        assertFalse("un baseline con payload inválido ⇒ restore no es allOk", report.allOk)

        // B/C: ningún comando put de zen_mode construido ni ejecutado con el payload
        val zenPuts = executed.filter { it.startsWith("settings put global zen_mode") }
        assertTrue(
            "el valor rechazado NO debe llegar a runCommand() (puts ejecutados: $zenPuts)",
            zenPuts.isEmpty()
        )

        // D: el shell REAL nunca recibió el payload → marker NO creado
        assertFalse(
            "el marker NO debe existir: el payload nunca alcanzó sh -c",
            File(sandbox, PROOF_FILE).exists()
        )
    }

    /** H6A2 — legitimidad end-to-end: valores legítimos se restauran byte a byte. */
    @Test
    fun `H6A2 valor legitimo del provider se restaura intacto`() = runBlocking {
        val device = ShellFakeDevice()
        val mgr = BoostSessionManager(
            store,
            { cmd -> device.run(cmd) },
            log = { _, _, _ -> }
        )
        // Valor legítimo clave de la auditoría (sanitizeShellArg lo corrompería)
        device.settings["global:private_dns_specifier"] = "DoT.example.com/eUplink"
        assertTrue(mgr.beginApply())
        mgr.markActive()
        device.settings["global:private_dns_specifier"] = "dns.google" // writer aplica
        mgr.recordApplied("global", "private_dns_specifier", "dns.google")

        val report = mgr.restoreVerified()
        assertEquals(RestoreResult.RESTORE_VERIFIED, report.results["global:private_dns_specifier"])
        assertEquals(
            "restaurado byte a byte, sin transformación",
            "DoT.example.com/eUplink",
            device.settings["global:private_dns_specifier"]
        )
    }

    /**
     * H6B — control negativo conservado: el fake semántico (split por tokens) de
     * la suite preexistente no puede observar shell; el guarda rechaza igualmente.
     */
    @Test
    fun `H6B el fake semantico no ve shell pero el guarda rechaza igual`() = runBlocking {
        val sandbox = tmp.newFolder("h6-sandbox-b")
        val marker = markerFor(sandbox)
        val settings = mutableMapOf("global:zen_mode" to marker)
        val semantic: suspend (String) -> Result<String> = { cmd ->
            val parts = cmd.split(" ")
            when {
                parts[0] == "settings" && parts[1] == "get" ->
                    Result.success(settings["${parts[2]}:${parts[3]}"] ?: "null")
                parts[0] == "settings" && parts[1] == "put" -> {
                    settings["${parts[2]}:${parts[3]}"] = parts.drop(4).joinToString(" ")
                    Result.success("")
                }
                parts[0] == "settings" && parts[1] == "delete" -> {
                    settings.remove("${parts[2]}:${parts[3]}")
                    Result.success("")
                }
                else -> Result.failure(IllegalArgumentException("unknown"))
            }
        }
        val mgr = BoostSessionManager(store, semantic, log = { _, _, _ -> })
        assertTrue(mgr.beginApply())
        mgr.markActive()
        settings["global:zen_mode"] = "2"

        // POST-FIX: el rechazo por dominio opera independientemente del harness
        assertEquals(RestoreResult.RESTORE_FAILED, mgr.restoreVerified().results["global:zen_mode"])
        assertFalse(File(sandbox, PROOF_FILE).exists())
    }
}

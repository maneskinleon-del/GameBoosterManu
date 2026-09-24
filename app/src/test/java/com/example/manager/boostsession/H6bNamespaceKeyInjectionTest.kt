package com.example.manager.boostsession

import com.example.manager.RestoreValueValidators
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
 * TEST DIRIGIDO H6b → REGRESIÓN POST-FIX (2026-09-23).
 *
 * PRE-FIX (auditoría T4B post-A1, hallazgo #1 CONFIRMADO): restoreVerified()
 * interpolaba entry.namespace y entry.key del baseline persistido directamente
 * en "settings get/put/delete <ns> <key>" → sh -c sin quoting. H6 validaba solo
 * originalValue; un boost_session.json manipulado (root/adb; allowBackup=true)
 * con key = "x; echo GB_H6B_PROOF > <sandbox>" ejecutaba la 2ª instrucción.
 *
 * POST-FIX (contrato actual): gate estructural fail-closed sobre namespace
 * (dominio real del proyecto: global|system|secure) y key (token shell-inerte)
 * ANTES de cualquier runCommand() — incluida la relectura del restore.
 * REGLA: rechazar, nunca mutar; el valor válido pasa byte a byte.
 *
 * El fake replica el último tramo REAL de producción (sh -c <string> sin
 * quoting, ShizukuExecutor/RishExecutor) para que cualquier fuga del payload
 * sea observable vía marcador inocuo confinado al TemporaryFolder.
 */
class H6bNamespaceKeyInjectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: BoostSessionStore
    private val executed = mutableListOf<String>()

    companion object {
        const val PROOF_FILE = "h6b-shell-interpreted"
    }

    private fun markerFor(sandbox: File): String =
        "echo GB_H6B_PROOF > '${File(sandbox, PROOF_FILE).absolutePath}'"

    /** Entrada de baseline con key inyectada (el JSON tampered es la única vía). */
    private fun entry(
        ns: String,
        key: String,
        original: String?,
        applied: String? = null
    ) = BackupEntry(ns, key, original, capturedAt = 1L, sessionId = "h6b", appliedValue = applied)

    /**
     * Fake con capa provider (mapa) + capa shell REAL en CADA comando: igual que
     * producción, TODO comando (get/put/delete) pasa literalmente por sh -c <cmd>
     * sin quoting. Cualquier fuga del payload por cualquier rama crea el marcador.
     */
    private inner class ShellFakeDevice {
        val settings = mutableMapOf<String, String>()

        /** Último tramo REAL, fiel a producción: sh -c SIN quoting añadido. */
        private fun shell(cmd: String) {
            try {
                ProcessBuilder("sh", "-c", cmd)
                    .start()
                    .waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }

        fun run(cmd: String): Result<String> {
            executed.add(cmd)
            val parts = cmd.split(" ")
            return when {
                parts[0] == "settings" && parts[1] == "get" -> {
                    shell(cmd) // vector real: el get también interpola ns/key
                    Result.success(settings["${parts[2]}:${parts[3]}"] ?: "null")
                }
                parts[0] == "settings" && parts[1] == "put" -> {
                    shell(cmd)
                    settings["${parts[2]}:${parts[3]}"] = parts.drop(4).joinToString(" ")
                    Result.success("")
                }
                parts[0] == "settings" && parts[1] == "delete" -> {
                    shell(cmd)
                    settings.remove("${parts[2]}:${parts[3]}")
                    Result.success("")
                }
                else -> Result.failure(IllegalArgumentException("unknown cmd: $cmd"))
            }
        }
    }

    /** Persiste una sesión tampered directamente en el store REAL (vía del atacante). */
    private fun seedTampered(state: BoostSessionState, entries: List<BackupEntry>) {
        assertTrue(
            store.save(BoostSession(state, entries, "h6b_session", System.currentTimeMillis()))
        )
    }

    @Before
    fun setup() {
        store = BoostSessionStore(File(tmp.root, "boost_session.json"))
        executed.clear()
    }

    // ── Casos adversariales del plan (validadores puros) ──────────────

    @Test
    fun `H6b validadores - casos adversariales rechazados y legitimos preservados`() {
        val marker = "echo GB_H6B_PROOF > /tmp/x"
        // 1-8: namespace y key con metacaracteres shell
        assertFalse("ns con ';'", RestoreValueValidators.isValidSettingsNamespace("global;$marker"))
        assertFalse("key con ';'", RestoreValueValidators.isValidSettingsKey("zen_mode;$marker"))
        assertFalse("ns con '&&'", RestoreValueValidators.isValidSettingsNamespace("global && $marker"))
        assertFalse("key con '|'", RestoreValueValidators.isValidSettingsKey("zen_mode|$marker"))
        assertFalse("ns con redirección", RestoreValueValidators.isValidSettingsNamespace("global > /x"))
        assertFalse("key con \$()", RestoreValueValidators.isValidSettingsKey("a\$(id)b"))
        assertFalse("ns con backtick", RestoreValueValidators.isValidSettingsNamespace("gl`id`obal"))
        assertFalse("key con newline", RestoreValueValidators.isValidSettingsKey("zen_mode\nid"))
        assertFalse("ns vacío", RestoreValueValidators.isValidSettingsNamespace(""))
        assertFalse("ns case-sensitive (AOSP minúscula)", RestoreValueValidators.isValidSettingsNamespace("Global"))
        assertFalse("key vacía", RestoreValueValidators.isValidSettingsKey(""))
        assertFalse("key con espacio", RestoreValueValidators.isValidSettingsKey("zen mode"))

        // 10: legítimos → preservados (dominio real + keys legacy token-safe)
        assertTrue(RestoreValueValidators.isValidSettingsNamespace("global"))
        assertTrue(RestoreValueValidators.isValidSettingsNamespace("system"))
        assertTrue(RestoreValueValidators.isValidSettingsNamespace("secure"))
        assertTrue(RestoreValueValidators.isValidSettingsKey("zen_mode"))
        assertTrue("puntos de keys reales", RestoreValueValidators.isValidSettingsKey("debug.hwui.renderer"))
        assertTrue("legacy removida de BoostKeys sigue válida (token-safe)", RestoreValueValidators.isValidSettingsKey("touch_sensitivity"))
        assertTrue(RestoreValueValidators.isValidSettingsKey("accessibility_display_magnification_enabled"))
    }

    // ── Integración: el payload NO alcanza sh -c ──────────────────────

    @Test
    fun `H6b key adversarial del baseline es rechazada y NUNCA llega al shell`() = runBlocking {
        val sandbox = tmp.newFolder("h6b-sandbox")
        val evilKey = "zen_mode; ${markerFor(sandbox)}"
        val device = ShellFakeDevice()
        val mgr = BoostSessionManager(store, { cmd -> device.run(cmd) }, log = { _, _, _ -> })

        // Sesión tampered: key con payload, originalValue LEGÍTIMO (H6 lo pasaría)
        seedTampered(
            BoostSessionState.ACTIVE,
            listOf(entry("global", evilKey, original = "2", applied = "2"))
        )
        // El "provider" contiene el valor aplicado bajo la key inyectada
        device.settings["global:zen_mode;"] = "2"

        val report = mgr.restoreVerified()

        // Rechazo fail-closed de la entrada (semántica de fallo existente)
        assertEquals(RestoreResult.RESTORE_FAILED, report.results["global:$evilKey"])
        assertFalse("allOk=false con entrada rechazada", report.allOk)

        // NINGÚN comando (ni siquiera el settings get de relectura) recibió el payload
        assertTrue(
            "runCommand no debe recibir comandos con el payload: $executed",
            executed.none { it.contains("GB_H6B_PROOF") }
        )
        // El shell REAL nunca interpretó el payload → marker NO existe
        assertFalse(
            "el marker NO debe existir: el payload nunca alcanzó sh -c",
            File(sandbox, PROOF_FILE).exists()
        )
    }

    @Test
    fun `H6b namespace adversarial es rechazado antes de cualquier runCommand`() = runBlocking {
        val sandbox = tmp.newFolder("h6b-sandbox-ns")
        val evilNs = "global; ${markerFor(sandbox)}"
        val device = ShellFakeDevice()
        val mgr = BoostSessionManager(store, { cmd -> device.run(cmd) }, log = { _, _, _ -> })

        seedTampered(
            BoostSessionState.ACTIVE,
            listOf(entry(evilNs, "zen_mode", original = "2", applied = "2"))
        )

        val report = mgr.restoreVerified()

        assertEquals(RestoreResult.RESTORE_FAILED, report.results["$evilNs:zen_mode"])
        assertFalse(report.allOk)
        assertTrue("sin comandos con payload: $executed", executed.none { it.contains("GB_H6B_PROOF") })
        assertFalse("runCommand nunca fue invocado con el ns inyectado", executed.any { it.contains("global;") })
        assertFalse(File(sandbox, PROOF_FILE).exists())
    }

    /**
     * 9: caso adversarial en el camino de `settings delete` (original==null).
     * Sin el fix, esta entrada SÍ llegaría al delete (H6 saltea null y el
     * Caso A matchea por appliedValue) → marker creado por sh -c.
     */
    @Test
    fun `H6b settings delete con key adversarial tambien esta protegido`() = runBlocking {
        val sandbox = tmp.newFolder("h6b-sandbox-del")
        val evilKey = "pointer_speed; ${markerFor(sandbox)}"
        val device = ShellFakeDevice()
        val mgr = BoostSessionManager(store, { cmd -> device.run(cmd) }, log = { _, _, _ -> })

        seedTampered(
            BoostSessionState.ACTIVE,
            listOf(entry("system", evilKey, original = null, applied = "7"))
        )
        device.settings["system:pointer_speed;"] = "7"

        val report = mgr.restoreVerified()

        assertEquals(RestoreResult.RESTORE_FAILED, report.results["system:$evilKey"])
        assertFalse(report.allOk)
        assertTrue(
            "el delete NO debe construirse con la key inyectada: $executed",
            executed.none { it.startsWith("settings delete") && it.contains("GB_H6B_PROOF") }
        )
        assertTrue("ningún comando recibió el payload: $executed", executed.none { it.contains("GB_H6B_PROOF") })
        assertFalse(File(sandbox, PROOF_FILE).exists())
    }

    // ── Caso positivo: legítimo → byte a byte, put y delete ──────────

    @Test
    fun `H6b positivo - restore legítimo produce los comandos exactos byte a byte`() = runBlocking {
        val device = ShellFakeDevice()
        val mgr = BoostSessionManager(store, { cmd -> device.run(cmd) }, log = { _, _, _ -> })

        // Sesión LEGÍTIMA (como las que produce beginApply en producción)
        seedTampered(
            BoostSessionState.ACTIVE,
            listOf(
                entry("global", "private_dns_specifier", original = "DoT.example.com/eUplink", applied = "dns.google"),
                entry("system", "pointer_speed", original = null, applied = "7")
            )
        )
        device.settings["global:private_dns_specifier"] = "dns.google"
        device.settings["system:pointer_speed"] = "7"

        val report = mgr.restoreVerified()

        assertEquals(RestoreResult.RESTORE_VERIFIED, report.results["global:private_dns_specifier"])
        assertEquals(RestoreResult.RESTORE_VERIFIED, report.results["system:pointer_speed"])
        assertTrue("put con ns/key/valor exactos", executed.contains("settings put global private_dns_specifier DoT.example.com/eUplink"))
        assertTrue("delete con ns/key exactos", executed.contains("settings delete system pointer_speed"))
        assertEquals("restaurado byte a byte (caracteres legítimos intactos)", "DoT.example.com/eUplink", device.settings["global:private_dns_specifier"])
        assertTrue("restore legítimo completo", report.allOk)
    }
}

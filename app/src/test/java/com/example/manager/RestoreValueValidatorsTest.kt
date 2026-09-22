package com.example.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM puros de los validadores de dominio del FIX H6 (2026-09-22).
 *
 * Contrato: son PREDICADOS — un valor válido pasa byte a byte (sin mutación;
 * no existe API de transformación), un valor inválido se rechaza para que el
 * caller NO ejecute el comando.
 *
 * Casos adversariales exigidos por la auditoría H6 + el valor legítimo
 * "DoT.example.com/eUplink" que sanitizeShellArg corrompería (motivo por el
 * que ese filtro queda PROHIBIDO para read-back).
 */
class RestoreValueValidatorsTest {

    // ── zen_mode (dominio numérico 0..3 AOSP) ─────────────────────

    @Test
    fun `zen_mode acepta valores AOSP 0-3`() {
        for (v in listOf("0", "1", "2", "3")) {
            assertTrue("zen_mode '$v' debe ser válido", RestoreValueValidators.isZenMode(v))
        }
    }

    @Test
    fun `zen_mode rechaza fuera de rango y no-numéricos`() {
        for (v in listOf("4", "-1", "99", "1.5", "", "null", "1; echo H6", "1 && echo H6")) {
            assertFalse("zen_mode '$v' debe ser inválido", RestoreValueValidators.isZenMode(v))
        }
    }

    // ── numérico (pointer_speed, long_press_timeout, wifi_bt, ...) ──

    @Test
    fun `numerico acepta enteros negativos y decimales finitos`() {
        for (v in listOf("0", "-7", "120", "120.0", "0.5", "300000")) {
            assertTrue("numérico '$v' debe ser válido", RestoreValueValidators.isNumeric(v))
        }
    }

    @Test
    fun `numerico rechaza metacaracteres shell y no-finitos`() {
        // Payloads adversariales exigidos:
        assertTrue(RestoreValueValidators.isNumeric("1; echo H6").not())
        assertTrue(RestoreValueValidators.isNumeric("1 && echo H6").not())
        assertTrue(RestoreValueValidators.isNumeric("`echo H6`").not())
        assertTrue(RestoreValueValidators.isNumeric("\$(echo H6)").not())
        assertTrue(RestoreValueValidators.isNumeric("1 > /tmp/h6").not())
        assertTrue(RestoreValueValidators.isNumeric("1\necho H6").not()) // newline
        assertTrue(RestoreValueValidators.isNumeric("1 echo H6").not())  // espacio
        assertTrue(RestoreValueValidators.isNumeric("Infinity").not())
        assertTrue(RestoreValueValidators.isNumeric("NaN").not())
        assertTrue(RestoreValueValidators.isNumeric("").not())
    }

    // ── identifier token (renderer, booleanos) ────────────────────

    @Test
    fun `identifierToken acepta valores AOSP de una pieza`() {
        for (v in listOf("skiavk", "false", "true", "gl")) {
            assertTrue(RestoreValueValidators.isIdentifierToken(v))
        }
    }

    @Test
    fun `identifierToken rechaza shell y espacios`() {
        for (v in listOf("skiavk;rm", "false &", "a b", "\$(x)", "", "a\nb")) {
            assertFalse(RestoreValueValidators.isIdentifierToken(v))
        }
    }

    // ── private_dns_mode (enum AOSP) ──────────────────────────────

    @Test
    fun `dnsMode acepta solo la enum AOSP`() {
        for (v in listOf("off", "opportunistic", "hostname")) {
            assertTrue(RestoreValueValidators.isPrivateDnsMode(v))
        }
        for (v in listOf("strict", "1", "off; echo H6", "", "hostname -x")) {
            assertFalse(RestoreValueValidators.isPrivateDnsMode(v))
        }
    }

    // ── private_dns_specifier (RFC-1123 + "/eUplink" opcional) ────

    @Test
    fun `dnsSpecifier conserva valores legitimos byte a byte`() {
        // El valor legítimo clave de la auditoría: con "/" de uplink.
        // Los validadores son predicados: no existe API que lo transforme.
        val legit = "DoT.example.com/eUplink"
        assertTrue(RestoreValueValidators.isDnsSpecifier(legit))
        assertTrue(RestoreValueValidators.isValidRestoreValue("private_dns_specifier", legit))
        assertTrue(RestoreValueValidators.isDnsSpecifier("dns.google"))
        assertTrue(RestoreValueValidators.isDnsSpecifier("one.one.one.one"))
        assertTrue(RestoreValueValidators.isDnsSpecifier("a-b.example-host.com"))
    }

    @Test
    fun `dnsSpecifier rechaza metacaracteres y malformaciones`() {
        for (v in listOf(
            "dns.google; echo H6",
            "dns.google && echo H6",
            "\$(echo H6).com",
            "`x`.com",
            "dns.google > /tmp/h6",
            "dns.google/x/y",     // dos "/"
            "dns.google/",        // uplink vacío
            ".dns.google",        // label vacío
            "dns..google",        // label vacío
            "-dns.google",        // label inicia con "-"
            "dns.google-",        // label termina con "-"
            "dns google.com",     // espacio
            "dns.google\nevil",   // newline
            "",
        )) {
            assertFalse("dnsSpecifier '$v' debe ser inválido", RestoreValueValidators.isDnsSpecifier(v))
        }
    }

    // ── activity_manager_constants (blob k=v,k=v) ─────────────────

    @Test
    fun `activityManagerConstants acepta blobs legitimos`() {
        for (v in listOf("max_cached_processes=128", "cached_processes=32", "a=1,b=2,c=3")) {
            assertTrue(RestoreValueValidators.isActivityManagerConstants(v))
        }
    }

    @Test
    fun `activityManagerConstants rechaza blobs con shell o malformados`() {
        for (v in listOf(
            "max_cached_processes=128; echo H6",
            "a=1,,b=2",   // parte vacía
            "a==1",       // doble "="
            "a=1 b=2",    // espacio (no es separador del dominio)
            "=1",
            "a=",
            "",
        )) {
            assertFalse(RestoreValueValidators.isActivityManagerConstants(v))
        }
    }

    // ── fallback estructural para keys desconocidas ───────────────

    @Test
    fun `fallback para keys desconocidas es conservador`() {
        assertTrue(RestoreValueValidators.isValidRestoreValue("key_desconocida_legacy", "v1.2-alpha=1,ok"))
        for (v in listOf("1; echo H6", "a b", "a\nb", "a&b", "a|b", "\$(x)", "`x`", "")) {
            assertFalse("fallback debe rechazar '$v'", RestoreValueValidators.isValidRestoreValue("key_desconocida_legacy", v))
        }
    }

    // ── dispatcher por key (el punto único que usan los restores) ──

    @Test
    fun `isValidRestoreValue enruta cada key a su dominio`() {
        // zen_mode → numérico 0..3
        assertTrue(RestoreValueValidators.isValidRestoreValue("zen_mode", "2"))
        assertFalse(RestoreValueValidators.isValidRestoreValue("zen_mode", "1; echo H6"))
        // TouchOptimizer → numéricos
        assertTrue(RestoreValueValidators.isValidRestoreValue("pointer_speed", "-6"))
        assertFalse(RestoreValueValidators.isValidRestoreValue("pointer_speed", "-6; id"))
        assertTrue(RestoreValueValidators.isValidRestoreValue("long_press_timeout", "120"))
        assertTrue(RestoreValueValidators.isValidRestoreValue("accessibility_autoclick_enabled", "0"))
        // NetworkOptimizer → enums/hostnames
        assertTrue(RestoreValueValidators.isValidRestoreValue("private_dns_mode", "hostname"))
        assertFalse(RestoreValueValidators.isValidRestoreValue("private_dns_mode", "hostname; x"))
        assertTrue(RestoreValueValidators.isValidRestoreValue("private_dns_specifier", "DoT.example.com/eUplink"))
        // SystemTweaks → tokens/blob
        assertTrue(RestoreValueValidators.isValidRestoreValue("debug.hwui.renderer", "skiavk"))
        assertTrue(RestoreValueValidators.isValidRestoreValue("activity_manager_constants", "max_cached_processes=128"))
    }

    // ── metacaracteres residuales: comillas y backslash (gap de review H6) ──

    @Test
    fun `comillas simples y backslash son rechazados por todos los validadores`() {
        // Kotlin: "1\x" no compila (escape inválido), así que el backslash se escribe
        // escapado "1\\x". El String que llega al validador es 1\x (3 chars: '1',
        // backslash LITERAL, 'x') — se aserta la longitud para dejarlo demostrado.
        val backslash = "1\\x"
        assertEquals("el backslash debe ser un carácter literal, no un escape", 3, backslash.length)
        val adversariales = listOf("1'x", backslash)

        for (v in adversariales) {
            assertFalse("isZenMode debe rechazar '$v'", RestoreValueValidators.isZenMode(v))
            assertFalse("isNumeric debe rechazar '$v'", RestoreValueValidators.isNumeric(v))
            assertFalse("isIdentifierToken debe rechazar '$v'", RestoreValueValidators.isIdentifierToken(v))
            assertFalse("isPrivateDnsMode debe rechazar '$v'", RestoreValueValidators.isPrivateDnsMode(v))
            assertFalse("isDnsSpecifier debe rechazar '$v'", RestoreValueValidators.isDnsSpecifier(v))
            assertFalse("isActivityManagerConstants debe rechazar '$v'", RestoreValueValidators.isActivityManagerConstants(v))
            assertFalse("isSafeSettingsToken debe rechazar '$v'", RestoreValueValidators.isSafeSettingsToken(v))
            assertFalse(
                "dispatcher (fallback legacy) debe rechazar '$v'",
                RestoreValueValidators.isValidRestoreValue("key_desconocida_legacy", v)
            )
        }
    }
}

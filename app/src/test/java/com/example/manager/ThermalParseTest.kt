package com.example.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests de [parseThermal] (plan-audit temperatura 2026-10-15).
 * Casos del GO del operador: milidegrees → Celsius, y rechazo de
 * valores implausibles (0, negativos, >150°C) y no-numéricos.
 */
class ThermalParseTest {

    @Test
    fun `45000 milidegrees se convierte a 45C`() {
        assertEquals(45.0f, parseThermal("45000")!!, 0.001f)
    }

    @Test
    fun `0 se rechaza como implausivel`() {
        assertNull(parseThermal("0"))
    }

    @Test
    fun `negativo se rechaza como implausivel`() {
        assertNull(parseThermal("-5"))
    }

    @Test
    fun `999999 se rechaza por 999999C fuera de rango`() {
        assertNull(parseThermal("999999"))
    }

    @Test
    fun `450 se rechaza por 450C fuera de rango`() {
        assertNull(parseThermal("450"))
    }

    @Test
    fun `texto no numerico se rechaza`() {
        assertNull(parseThermal("abc"))
    }

    @Test
    fun `cadena vacia se rechaza`() {
        assertNull(parseThermal(""))
    }

    @Test
    fun `milidegrees con espacios se recorta y convierte`() {
        assertEquals(45.0f, parseThermal("  45000  ")!!, 0.001f)
    }

    @Test
    fun `celsius directo en rango plausible se acepta`() {
        // raw ≤ 1000 no se divide: 42.5 ya está en Celsius
        assertEquals(42.5f, parseThermal("42.5")!!, 0.001f)
    }
}

package com.example.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests JVM puros, mismo patrón que ServiceLauncherTest. */
class MapperDetectionTest {

    // ── validatePackageName ──────────────────────────────────────

    @Test
    fun `accepts valid package names`() {
        listOf(
            "com.zjx.ztezscreenshot",
            "a.b",
            "com.example.app_1",
            "Com.Example.X9"
        ).forEach { assertTrue("debería ser válido: $it", MapperDetection.validatePackageName(it)) }
    }

    @Test
    fun `rejects malformed package names`() {
        listOf(
            "", "com", "com.", ".com", "com..x",
            "1com.x", "com.1x", "com._x", "com.ex-ample"
        ).forEach { assertFalse("debería ser inválido: '$it'", MapperDetection.validatePackageName(it)) }
    }

    @Test
    fun `rejects shell injection attempts`() {
        listOf(
            "com.example; rm -rf /data/local/tmp",
            "com.example rm",
            "com.example\$(id)",
            "com.example`id`",
            "com.example|id",
            "com.example&",
            "com.example'",
            "com.example\"",
            "com.example\n",
            "com.example\nrm -rf /",
            "com.example\u0000"
        ).forEach { assertFalse("debería ser inválido: '$it'", MapperDetection.validatePackageName(it)) }
    }

    @Test
    fun `rejects non-ascii letters`() {
        assertFalse(MapperDetection.validatePackageName("com.exámple.app"))
    }

    @Test
    fun `rejects names over 255 chars`() {
        val long = "com." + "a".repeat(252)
        assertEquals(256, long.length)
        assertFalse(MapperDetection.validatePackageName(long))
        assertTrue(MapperDetection.validatePackageName("com." + "a".repeat(251)))
    }

    // ── buildPgrepCommand ────────────────────────────────────────

    @Test
    fun `builds anchored bracketed escaped command`() {
        assertEquals(
            "pgrep -f '^[c]om\\.zjx\\.ztezscreenshot([: ]|\$)'",
            MapperDetection.buildPgrepCommand("com.zjx.ztezscreenshot")
        )
    }

    @Test
    fun `returns null for invalid input`() {
        assertNull(MapperDetection.buildPgrepCommand(""))
        assertNull(MapperDetection.buildPgrepCommand("com.example; rm -rf /"))
        assertNull(MapperDetection.buildPgrepCommand("com.example\$(id)"))
        assertNull(MapperDetection.buildPgrepCommand("com"))
    }

    @Test
    fun `every dot in the package is escaped`() {
        val cmd = MapperDetection.buildPgrepCommand("a.b.c")!!
        assertEquals("pgrep -f '^[a]\\.b\\.c([: ]|\$)'", cmd)
    }

    @Test
    fun `command only contains safe characters inside quotes`() {
        val cmd = MapperDetection.buildPgrepCommand("com.example.app_1")!!
        val body = cmd.removePrefix("pgrep -f '").removeSuffix("'")
        assertTrue(body.none { it == '\'' || it == ';' || it == '`' || it == '\n' })
    }

    // ── hasPid ───────────────────────────────────────────────────

    @Test
    fun `hasPid detects numeric lines only`() {
        assertTrue(MapperDetection.hasPid("28313\n28422\n"))
        assertTrue(MapperDetection.hasPid("  123  "))
        assertFalse(MapperDetection.hasPid(""))
        assertFalse(MapperDetection.hasPid(null))
        assertFalse(MapperDetection.hasPid("pgrep: bad pattern"))
    }
}

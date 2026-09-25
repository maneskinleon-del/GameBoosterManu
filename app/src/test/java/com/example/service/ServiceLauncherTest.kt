package com.example.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests JVM puros (sin Robolectric) para ServiceLauncher.
 * Usa FakeStarter + pref-lambda para verificar ordering y reverts.
 *
 * Issue D de 5c-b: Robolectric no está configurado en este proyecto
 * (ExampleRobolectricTest es un template sin @RunWith). Tests usan fakes.
 */
class ServiceLauncherTest {

    // -- startBoost -------------------------------------------------

    @Test
    fun `startBoost writes pref true before and succeeds`() {
        val prefs = mutableListOf<Boolean>()
        val starter = FakeStarter(succeedFg = true)
        val launcher = ServiceLauncher(starter, { prefs.add(it) })

        launcher.startBoost()

        assertEquals(listOf(true), prefs)
        assertEquals(1, starter.fgCalls)
        assertEquals("ACTION_START", starter.lastFgAction)
        assertEquals(0, starter.svcCalls)
    }

    @Test
    fun `startBoost reverts pref to false if intent throws`() {
        val prefs = mutableListOf<Boolean>()
        val starter = FakeStarter(succeedFg = false)
        val launcher = ServiceLauncher(starter, { prefs.add(it) })

        assertThrows(SecurityException::class.java) { launcher.startBoost() }

        assertEquals(listOf(true, false), prefs)
        assertEquals(1, starter.fgCalls)
    }

    // -- stopBoost --------------------------------------------------

    @Test
    fun `stopBoost writes pref false before and succeeds`() {
        val prefs = mutableListOf<Boolean>()
        val starter = FakeStarter(succeedSvc = true)
        val launcher = ServiceLauncher(starter, { prefs.add(it) })

        launcher.stopBoost()

        assertEquals(listOf(false), prefs)
        assertEquals(1, starter.svcCalls)
        assertEquals("ACTION_STOP", starter.lastSvcAction)
        assertEquals(0, starter.fgCalls)
    }

    @Test
    fun `stopBoost reverts pref to true if intent throws`() {
        val prefs = mutableListOf<Boolean>()
        val starter = FakeStarter(succeedSvc = false)
        val launcher = ServiceLauncher(starter, { prefs.add(it) })

        assertThrows(SecurityException::class.java) { launcher.stopBoost() }

        assertEquals(listOf(false, true), prefs)
        assertEquals(1, starter.svcCalls)
    }

    // -- markStopped ------------------------------------------------

    @Test
    fun `markStopped writes false without sending intent`() {
        val prefs = mutableListOf<Boolean>()
        val starter = FakeStarter()
        val launcher = ServiceLauncher(starter, { prefs.add(it) })

        launcher.markStopped()

        assertEquals(listOf(false), prefs)
        assertEquals(0, starter.fgCalls + starter.svcCalls)
    }

    // -- startIdle --------------------------------------------------

    @Test
    fun `startIdle sends idle intent without writing pref`() {
        val prefs = mutableListOf<Boolean>()
        val starter = FakeStarter()
        val launcher = ServiceLauncher(starter, { prefs.add(it) })

        launcher.startIdle()

        assertEquals(0, prefs.size)
        assertEquals(1, starter.fgCalls)
        assertNull(starter.lastFgAction)
    }

    // -- Helper -----------------------------------------------------

    private class FakeStarter(
        val succeedFg: Boolean = true,
        val succeedSvc: Boolean = true
    ) : ServiceStarter {
        var fgCalls = 0
        var svcCalls = 0
        var lastFgAction: String? = null
        var lastSvcAction: String? = null

        override fun startForeground(action: String?) {
            fgCalls++
            lastFgAction = action
            if (!succeedFg) throw SecurityException("FGS denied")
        }

        override fun startService(action: String?) {
            svcCalls++
            lastSvcAction = action
            if (!succeedSvc) throw SecurityException("startService denied")
        }
    }
}

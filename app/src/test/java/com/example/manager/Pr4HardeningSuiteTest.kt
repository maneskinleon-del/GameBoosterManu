package com.example.manager

import android.app.Application
import com.example.manager.boostsession.BoostKeys
import com.example.manager.boostsession.BoostSessionManager
import com.example.manager.boostsession.BoostSessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Pr4HardeningSuiteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun A5_cobertura_total_toda_key_tiene_validador() {
        val allKeys = BoostKeys.all.map { it.second }.toSet()
        val missing = allKeys.filter { RestoreValueValidators.forKey(it) == null }
        assertTrue("Keys sin validador: $missing", missing.isEmpty())
    }

    @Test
    fun A5_overlay_display_devices_valida_AOSP_y_rechaza_malformados() {
        assertTrue(RestoreValueValidators.isOverlayDisplayDevices("0"))
        assertTrue(RestoreValueValidators.isOverlayDisplayDevices(""))
        assertTrue(RestoreValueValidators.isOverlayDisplayDevices("1920x1080/320"))
        assertTrue(RestoreValueValidators.isOverlayDisplayDevices("1920x1080"))
        assertTrue(RestoreValueValidators.isOverlayDisplayDevices("1920x1080/320,1280x720/240"))
        assertFalse(RestoreValueValidators.isOverlayDisplayDevices("1920x1080/320; echo hack"))
        assertFalse(RestoreValueValidators.isOverlayDisplayDevices("invalid"))
    }

    @Test
    fun A5_cada_key_de_BoostKeys_valida_al_menos_un_valor_valido_e_invalido() {
        for ((_, key) in BoostKeys.all) {
            val validator = RestoreValueValidators.forKey(key)
            assertNotNull("Validador faltante para $key", validator)
            when (key) {
                "zen_mode" -> {
                    assertTrue(validator!!("2"))
                    assertFalse(validator("99"))
                }
                "private_dns_mode" -> {
                    assertTrue(validator!!("hostname"))
                    assertFalse(validator("invalid_mode"))
                }
                "private_dns_specifier" -> {
                    assertTrue(validator!!("dns.google"))
                    assertFalse(validator("dns..google"))
                }
                "activity_manager_constants" -> {
                    assertTrue(validator!!("max_cached_processes=128"))
                    assertFalse(validator("max_cached_processes=128; rm -rf"))
                }
                "overlay_display_devices" -> {
                    assertTrue(validator!!("0"))
                    assertTrue(validator("1920x1080/320"))
                    assertFalse(validator("1920x1080; id"))
                }
                "debug.hwui.renderer" -> {
                    assertTrue(validator!!("skiavk"))
                    assertFalse(validator("skiavk; bad"))
                }
                "debug.hwui.overdraw", "debug.hwui.show_dirty_regions" -> {
                    assertTrue(validator!!("false"))
                    assertTrue(validator("true"))
                    assertFalse(validator("skiavk"))
                }
                "ble_scan_always_enabled", "wifi_scan_always_enabled", "bluetooth_disabled_profiles",
                "disable_window_blurs", "adaptive_connected_voice_enabled",
                "debug.sf.disable_hwc_vds", "debug.sf.disable_backpressure", "debug.sf.latch_unsignaled",
                "auto_sync" -> {
                    assertTrue(validator!!("1"))
                    assertTrue(validator("0"))
                    assertFalse(validator("true"))
                    assertFalse(validator("1; echo"))
                }
                else -> {
                    assertTrue(validator!!("120.0"))
                    assertFalse(validator("120.0; echo injection"))
                }
            }
        }
    }

    private fun corruptFilesIn(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray()).filter {
            it.name.startsWith("boost_session.json.corrupt.")
        }.toList()

    @Test
    fun A6_load_con_archivo_corrupto_renombra_a_corrupt_ts_y_retorna_null() {
        val sessionFile = File(tmp.root, "boost_session.json")
        sessionFile.writeText("{ json corrupto sin schemaVersion ...")

        val store = BoostSessionStore(sessionFile)
        val session = store.load()
        assertNull("Sesion corrupta no debe cargarse", session)

        val corruptFiles = corruptFilesIn(tmp.root)
        assertNotNull("Debe preservarse como .corrupt.<ts>", corruptFiles)
        assertTrue("Al menos un archivo .corrupt.<ts>", corruptFiles.isNotEmpty())
        assertEquals(
            "{ json corrupto sin schemaVersion ...",
            corruptFiles.first().readText()
        )
        assertFalse("Original no debe existir", sessionFile.exists())
        assertNull(store.load())
    }

    @Test
    fun A6_dos_corrupciones_consecutivas_preservan_dos_archivos() {
        val sessionFile = File(tmp.root, "boost_session.json")
        sessionFile.writeText("{ corrupto1 ...")
        BoostSessionStore(sessionFile).load()
        sessionFile.writeText("{ corrupto2 ...")
        BoostSessionStore(sessionFile).load()

        val corruptFiles = corruptFilesIn(tmp.root)
        assertTrue("Cada corrupcion es un archivo nuevo", corruptFiles.size == 2)
    }

    @Test
    fun C1_isGamePackage_reconoce_juegos_exactos_y_rechaza_substrings() {
        val detector = GameDetector(Application())
        assertTrue(detector.isGamePackage("com.dts.freefireth"))
        assertTrue(detector.isGamePackage("com.tencent.ig"))
        assertTrue(detector.isGamePackage("com.king.candycrushsaga"))
        assertFalse(detector.isGamePackage("com.fake.king.notes"))
        assertFalse(detector.isGamePackage("org.tencent.unrelated.tool"))
        assertFalse(detector.isGamePackage("net.garena.downloader"))
        assertFalse(detector.isGamePackage("com.freefire.wallpaper"))
    }

    @Test
    fun C4_clampRefreshRate_aplica_round_down_o_fallback_60() {
        val supported = listOf(60f, 90f, 144f)
        assertEquals(90f, DisplayRates.clampRefreshRate(120f, supported), 0.01f)
        assertEquals(90f, DisplayRates.clampRefreshRate(90f, supported), 0.01f)
        assertEquals(60f, DisplayRates.clampRefreshRate(60f, supported), 0.01f)
        assertEquals(60f, DisplayRates.clampRefreshRate(50f, supported), 0.01f)
        assertEquals(60f, DisplayRates.clampRefreshRate(120f, emptyList()), 0.01f)
    }

    @Test
    fun Runtime_check_post_hoc_advierte_dinamicas_huerfanas() = runBlocking {
        val storeFile = File(tmp.root, "boost_session.json")
        val store = BoostSessionStore(storeFile)
        val logs = mutableListOf<String>()
        val mgr = BoostSessionManager(
            store,
            runCommand = { Result.success("") },
            log = { level, _, msg -> if (level == "WARN") logs.add(msg) }
        )

        assertTrue(mgr.beginApply())
        mgr.markActive()

        mgr.auditUnrecordedDynamicKeys()
        assertTrue(logs.any { it.contains("Audit:") })
        val initialWarn = logs.last()
        assertTrue(initialWarn.contains("pointer_speed"))

        logs.clear()
        mgr.recordApplied("system", "pointer_speed", "5")
        mgr.recordApplied("system", "peak_refresh_rate", "120.0")
        mgr.recordApplied("system", "min_refresh_rate", "90.0")

        mgr.auditUnrecordedDynamicKeys()
        assertFalse(logs.any { it.contains("pointer_speed") || it.contains("peak_refresh_rate") || it.contains("min_refresh_rate") })
    }

    @Test
    fun recordAppliedCommand_ignora_comandos_no_settings() = runBlocking {
        val storeFile = File(tmp.root, "boost_session.json")
        val store = BoostSessionStore(storeFile)
        val mgr = BoostSessionManager(store, { Result.success("") }, { _, _, _ -> })

        assertTrue(mgr.beginApply())

        mgr.recordAppliedCommand("echo performance > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
        mgr.recordAppliedCommand("cmd power set-fixed-performance-mode-enabled true")
        mgr.recordAppliedCommand("wm density 400")

        val snapshot = mgr.sessionSnapshot()!!
        assertTrue(snapshot.baseline.all { it.appliedValue == null })

        mgr.recordAppliedCommand("settings put system pointer_speed 7")
        val updated = mgr.sessionSnapshot()!!
        assertEquals("7", updated.baseline.first { it.key == "pointer_speed" }.appliedValue)
    }
}

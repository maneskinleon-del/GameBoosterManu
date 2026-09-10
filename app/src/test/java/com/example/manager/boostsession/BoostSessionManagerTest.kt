package com.example.manager.boostsession

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests F4 (F3B + F3B-Fix): baseline persistente, no-sobrescritura, apply
 * interrumpido, recovery, restore fallido, baseline desconocido, RACE 4.D,
 * READ_FAILED vs ABSENT.
 *
 * JVM puro: el CommandRunner es un fake con mapa en memoria que emula
 * `settings get/put/delete <ns> <key>` con la semántica de "null" = key ausente
 * y fallos de lectura/escritura inyectables.
 */
class BoostSessionManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: BoostSessionStore
    private lateinit var device: FakeDevice
    private lateinit var logs: MutableList<String>
    private lateinit var mgr: BoostSessionManager

    /** Emulador mínimo del pipeline settings. */
    class FakeDevice(initial: Map<String, String> = emptyMap()) {
        val settings = initial.toMutableMap()
        var failPutFor: Set<String> = emptySet()      // keys cuyo put/delete fallará
        var failGetFor: Set<String> = emptySet()      // keys cuyo GET fallará (READ_FAILED)
        var delayGetMs: Long = 0                      // simula latencia Shizuku (para reproducir la race)
        val getCalls = java.util.concurrent.atomic.AtomicInteger(0)

        fun get(ns: String, key: String): String =
            settings["$ns:$key"] ?: "null"

        fun run(cmd: String): Result<String> {
            val parts = cmd.split(" ")
            return when {
                parts[0] == "settings" && parts[1] == "get" -> {
                    if (delayGetMs > 0) { getCalls.incrementAndGet(); Thread.sleep(delayGetMs) }
                    val id = "${parts[2]}:${parts[3]}"
                    if (id in failGetFor) Result.failure(RuntimeException("simulated read failure"))
                    else Result.success(get(parts[2], parts[3]))
                }
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
        mgr = BoostSessionManager(store, { cmd -> device.run(cmd) }, log = { l: String, t: String, m: String ->
            logs.add("[$l/$t] $m")
        })
    }

    private fun seedRealisticDevice() {
        device.settings["global:ble_scan_always_enabled"] = "1"
        device.settings["global:auto_sync"] = "1"
        device.settings["global:window_animation_scale"] = "1.0"
        device.settings["global:activity_manager_constants"] = "cached_processes=32" // valor custom del usuario
        // resto de keys ausentes (null) — como un dispositivo real con defaults
    }

    private fun applyBoostToDevice() {
        // Emula lo que los optimizers escriben (subset representativo)
        device.settings["global:ble_scan_always_enabled"] = "0"
        device.settings["global:auto_sync"] = "0"
        device.settings["global:window_animation_scale"] = "0"
        device.settings["global:activity_manager_constants"] = "max_cached_processes=128"
        device.settings["global:wifi_low_latency_mode"] = "1"
    }

    private fun mgr2() = BoostSessionManager(store, { cmd -> device.run(cmd) }, log = { _, _, _ -> })

    // ── T1 — RACE 4.D: sesión vieja NO pisa sesión nueva ──

    @Test
    fun `T1 race 4D old restore cannot overwrite new apply`() = runBlocking {
        seedRealisticDevice()
        // S1: capture + apply + ACTIVE
        assertTrue(mgr.beginApply())
        applyBoostToDevice()
        mgr.markActive()
        val s1Id = store.load()!!.sessionId

        // Interleaving REAL de la race: el restore de S1 corre CONCURRENTEMENTE
        // (como ocurre en producción: scope.launch separado, 34 gets vía Shizuku
        // que tardan 2-4s). Simulamos esa latencia con delayGetMs para garantizar
        // la ventana de interleaving de forma DETERMINISTA.
        device.delayGetMs = 5 // 34 keys × 5ms ≈ 170ms de restore en vuelo
        val restoreScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val restoreJob = restoreScope.launch { mgr.restoreVerified() } // restore de S1 en vuelo
        // Esperar a que el restore tome su snapshot (S1) y pase a RESTORING
        kotlinx.coroutines.withTimeout(5000) {
            while (store.load()?.state != BoostSessionState.RESTORING) kotlinx.coroutines.delay(5)
        }
        val s1RestoringId = store.load()!!.sessionId
        assertEquals("el restore en vuelo opera sobre S1", s1Id, s1RestoringId)

        // DURANTE el restore en vuelo: toggleBoost(ON) → beginApply S2 (REUSE + NUEVO id)
        val mgrNew = mgr2()
        assertTrue(mgrNew.beginApply())
        val s2 = store.load()!!
        assertEquals(BoostSessionState.APPLYING, s2.state)
        assertFalse("S2 debe tener sessionId distinto de S1", s2.sessionId == s1Id)

        // El restore S1 termina → su commit final debe ser RECHAZADO (no stillMine)
        kotlinx.coroutines.withTimeout(10000) { restoreJob.join() }
        device.delayGetMs = 0 // restaurar velocidad normal para las verificaciones posteriores

        // S2 permanece INTACTA (fase crítica de la race)
        val after = store.load()!!
        assertEquals("S2 (APPLYING) debe seguir vigente", BoostSessionState.APPLYING, after.state)
        assertEquals("S2 conserva su sessionId", s2.sessionId, after.sessionId)
        assertFalse("S1 NO escribió RESTORED sobre S2", after.state == BoostSessionState.RESTORED)
        assertEquals("baseline de S2 sigue siendo el ORIGINAL (reuse)", "1",
            after.baseline.first { it.key == "ble_scan_always_enabled" }.originalValue)

        // PROCESS DEATH ahora → arranque ve APPLYING → recovery restaura S2
        val clean = mgrNew.recoverIfNeeded()
        assertTrue("S2 sigue siendo recuperable tras la race", clean)
        assertEquals("dispositivo restaurado al original", "1", device.settings["global:ble_scan_always_enabled"])
        assertEquals(BoostSessionState.IDLE, mgrNew.currentState())
    }

    // ── T2 — RESTORE NORMAL: happy path sigue funcionando ──

    @Test
    fun `T2 normal restore still confirms and clears`() = runBlocking {
        seedRealisticDevice()
        assertTrue(mgr.beginApply())
        applyBoostToDevice()
        mgr.markActive()

        val mgrNew = mgr2()
        val report = mgrNew.recoverIfNeeded()
        assertTrue(report)
        // Tras recover: clear → archivo eliminado → IDLE
        assertEquals(BoostSessionState.IDLE, mgrNew.currentState())
        assertNull(store.load())
        assertEquals("1", device.settings["global:ble_scan_always_enabled"])
        assertEquals("cached_processes=32", device.settings["global:activity_manager_constants"])
    }

    // ── T3 — STARTUP WRITER GATE (a nivel Repository: cubierto por isRecoveryComplete/await; aquí el gate lógico) ──

    @Test
    fun `T3 startup writer waits for recovery gate`() = runBlocking {
        // El gate del Repository (recoveryGate CompletableDeferred) se abre tras el init.
        // El writer (restoreSavedSettings) consulta awaitRecoveryComplete() antes de escribir.
        // Test de la lógica: mientras la sesión está en estado no-IDLE, el recovery
        // debe correr primero; simulamos la secuencia completa:
        seedRealisticDevice()
        assertTrue(mgr.beginApply())
        applyBoostToDevice()
        mgr.markActive()

        // "Arranque": recovery corre ANTES del writer (el gate del repo lo ordena).
        val mgrNew = mgr2()
        val clean = mgrNew.recoverIfNeeded()
        assertTrue(clean)

        // Solo DESPUÉS el startup writer escribe su valor legítimo (pointer_speed propio)
        device.run("settings put system pointer_speed 50")
        assertEquals("50", device.settings["system:pointer_speed"])

        // Y un NUEVO capture posterior a un boost legítimo captura el 50 (voluntad reciente)
        // — sin interferir: esta es la semántica del writer post-gate.
        assertTrue(mgrNew.beginApply())
        val s = store.load()!!
        assertEquals("50", s.baseline.first { it.key == "pointer_speed" }.originalValue)
    }

    // ── T4 — READ_FAILED: sesión queda recuperable, no clear ──

    @Test
    fun `T4 read failed keeps session recoverable`() = runBlocking {
        seedRealisticDevice()
        assertTrue(mgr.beginApply())
        applyBoostToDevice()
        mgr.markActive()

        // Shizuku cae: TODAS las lecturas fallan durante el recovery
        device.failGetFor = device.settings.keys.map { it }.toSet() // fallar todos los gets conocidos
        // (solo hace falta que falle el de las keys del baseline; el fake falla esas)
        val mgrNew = mgr2()
        val clean = mgrNew.recoverIfNeeded()

        assertFalse("recovery NO puede declararse limpio sin poder leer", clean)

        // La sesión SIGUE persistida y recuperable:
        val session = store.load()!!
        assertTrue("no RESTORED sin verificación",
            session.state == BoostSessionState.RECOVERY_REQUIRED ||
            session.state == BoostSessionState.RESTORING)
        assertTrue("baseline permanece", session.baseline.isNotEmpty())
        assertFalse("clear() NO ocurrió", session.state == BoostSessionState.IDLE)

        // Shizuku vuelve → los gets funcionan → el próximo recovery restaura
        device.failGetFor = emptySet()
        val mgr3 = mgr2()
        assertTrue("reintento con Shizuku disponible recupera", mgr3.recoverIfNeeded())
        assertEquals(BoostSessionState.IDLE, mgr3.currentState())
        assertEquals("1", device.settings["global:ble_scan_always_enabled"])
    }

    // ── T4b — READ_FAILED en UNA sola key: no falso allOk ──

    @Test
    fun `T4b single read failure is not false success`() = runBlocking {
        seedRealisticDevice()
        assertTrue(mgr.beginApply())
        applyBoostToDevice()
        mgr.markActive()

        // Una sola key no se puede leer → esa key NO puede ser SKIPPED/CONFLICT
        device.failGetFor = setOf("global:ble_scan_always_enabled")
        val report = mgr.restoreVerified()
        assertFalse("una lectura fallida impide allOk", report.allOk)
        assertEquals(
            com.example.manager.boostsession.RestoreResult.RESTORE_FAILED,
            report.results["global:ble_scan_always_enabled"]
        )
        // El estado persistido NO es RESTORED:
        val state = store.load()!!.state
        assertEquals(BoostSessionState.RECOVERY_REQUIRED, state)
    }

    // ── T5 — ABSENT sigue funcionando con la política existente ──

    @Test
    fun `T5 absent keys use delete policy`() = runBlocking {
        seedRealisticDevice()
        assertTrue(mgr.beginApply())
        // wifi_low_latency_mode estaba AUSENTE; el boost la crea con 1 (NetworkOptimizer)
        device.settings["global:wifi_low_latency_mode"] = "1"
        mgr.markActive()

        val mgrNew = mgr2()
        assertTrue(mgrNew.recoverIfNeeded())
        // ABSENT original → restaurar = delete
        assertNull(device.settings["global:wifi_low_latency_mode"])
        assertEquals(BoostSessionState.IDLE, mgrNew.currentState())
    }

    // ── T6 — HAPPY PATH COMPLETO end-to-end ──

    @Test
    fun `T6 full happy path capture apply active restore verified restored clear`() = runBlocking {
        seedRealisticDevice()
        assertTrue(mgr.beginApply())                        // capture + APPLYING
        assertEquals(BoostSessionState.APPLYING, mgr.currentState())
        applyBoostToDevice()                                 // writers
        mgr.markActive()                                    // ACTIVE
        assertEquals(BoostSessionState.ACTIVE, mgr.currentState())

        val before = device.settings.toMap()

        // "process death" + arranque
        val mgrNew = mgr2()
        val report = mgrNew.restoreVerified()
        assertTrue(report.allOk)
        assertEquals(BoostSessionState.RESTORED, mgrNew.currentState())

        // "clear" (el paso del recoverIfNeeded)
        assertTrue(mgrNew.recoverIfNeeded()) // estado ya RESTORED → clear → IDLE
        assertEquals(BoostSessionState.IDLE, mgrNew.currentState())

        // Dispositivo restaurado
        assertEquals("1", device.settings["global:ble_scan_always_enabled"])
        assertEquals("1", device.settings["global:auto_sync"])
        assertEquals("cached_processes=32", device.settings["global:activity_manager_constants"])
        assertNull(device.settings["global:wifi_low_latency_mode"])
    }

    // ── Tests originales mantenidos (regresión F3B) ──

    @Test
    fun `T1-old baseline survives restart`() = runBlocking {
        seedRealisticDevice()
        assertTrue(mgr.beginApply())
        applyBoostToDevice()
        assertEquals(BoostSessionState.APPLYING, mgr.currentState())
        val report = mgr.restoreVerified()
        assertTrue(report.allOk)
        assertEquals("1", device.settings["global:ble_scan_always_enabled"])
        assertNull(device.settings["global:wifi_low_latency_mode"])
    }

    @Test
    fun `T2-old reapply does not overwrite original baseline`() = runBlocking {
        seedRealisticDevice()
        mgr.beginApply()
        applyBoostToDevice()
        assertTrue(mgr.beginApply()) // re-apply → REUSE
        val session = store.load()!!
        assertEquals("1", session.baseline.first { it.key == "ble_scan_always_enabled" }.originalValue)
        assertEquals("1.0", session.baseline.first { it.key == "window_animation_scale" }.originalValue)
        assertFalse(session.baseline.any { it.originalValue == "max_cached_processes=128" })
    }

    @Test
    fun `T5-old failed postcondition keeps recovery required`() = runBlocking {
        seedRealisticDevice()
        mgr.beginApply()
        applyBoostToDevice()
        device.failPutFor = setOf("global:auto_sync")
        val report = mgr.restoreVerified()
        assertFalse(report.allOk)
        assertEquals(
            com.example.manager.boostsession.RestoreResult.RESTORE_FAILED,
            report.results["global:auto_sync"]
        )
        assertEquals(BoostSessionState.RECOVERY_REQUIRED, store.load()!!.state)
    }

    @Test
    fun `T6-old no baseline invents nothing`() = runBlocking {
        seedRealisticDevice()
        val before = device.settings.toMap()
        assertTrue(mgr.recoverIfNeeded())
        val report = mgr.restoreVerified()
        assertTrue(report.allOk)
        assertEquals(0, report.results.size)
        assertEquals(before, device.settings.toMap())
    }

    // ── Guarda de inventario: las 9 keys placebo muertas + private_dns_spec NO vuelven ──

    @Test
    fun `inventory excludes dead placebo keys and no-op private_dns_spec`() {
        val dead = listOf(
            "system:touch_sensitivity", "system:multi_touch_sensitivity",
            "system:touch_latency_reduction", "system:high_touch_sensitivity_enable",
            "system:high_touch_polling_rate_enable", "system:touch_report_rate",
            "secure:touch_boost_enabled", "secure:swipe_up_to_switch_apps_enabled",
            "secure:edge_prevent_mistouch_enabled", "global:private_dns_spec"
        )
        dead.forEach { id ->
            val (ns, key) = id.split(":")
            assertFalse("key muerta en inventario: $id", BoostKeys.all.contains(ns to key))
            assertNull("appliedValueOf de key muerta debe ser null: $id", BoostKeys.appliedValueOf(ns, key))
        }
        // Claim INTERINO de min_refresh_rate = writer vigente GSM:518 (90.0), no el viejo 120.0
        assertEquals("90.0", BoostKeys.appliedValueOf("system", "min_refresh_rate"))
        // Inventario activo: 44 − 9 placebos − private_dns_spec = 34
        assertEquals(34, BoostKeys.all.size)
    }
}

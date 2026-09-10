package com.example.manager.boostsession

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Inventario de las settings keys que el boost de GameBoost modifica ACTIVAMENTE
 * (writer vigente en producción). 34 entradas.
 *
 * Limpieza 2026-09-10 (auditoría appliedValueOf vs writers reales): se removieron
 * 9 keys placebo muertas — touch_sensitivity, multi_touch_sensitivity,
 * touch_latency_reduction, high_touch_sensitivity_enable,
 * high_touch_polling_rate_enable, touch_report_rate, touch_boost_enabled,
 * swipe_up_to_switch_apps_enabled, edge_prevent_mistouch_enabled (sin writer desde
 * 424c806) — y la línea no-op private_dns_spec (put con valor vacío → usage error
 * EXIT=255, nunca escribe; evidencia en experiments/forensic-phase1/
 * F2F3F5-DEVICE-VERIFICATION-20260910.md).
 *
 * Migración de baselines viejos: el restore itera session.baseline del JSON
 * persistido y NO consulta este inventario — las entradas viejas siguen
 * restaurando por su originalValue; appliedValueOf de keys removidas → null
 * degrada a Caso B (conflicto→conserva), nunca rompe el restore.
 */
object BoostKeys {

    /** namespace → keys. "G"/"S"/"SEC" abrevian global/system/secure. */
    private val globalKeys = listOf(
        // SystemTweaks.apply()
        "ble_scan_always_enabled",
        "wifi_scan_always_enabled",
        "bluetooth_disabled_profiles",
        "overlay_display_devices",
        "debug.hwui.renderer",
        "debug.hwui.overdraw",
        "debug.hwui.show_dirty_regions",
        "debug.sf.disable_backpressure",
        "debug.sf.latch_unsignaled",
        "disable_window_blurs",
        "debug.sf.disable_hwc_vds",
        "auto_sync",
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale",
        "send_action_app_error",
        "activity_manager_constants",
        "low_power_trigger_level",
        "adaptive_connected_voice_enabled",
        "debug.gl.msaa",
        // NetworkOptimizer.apply()
        "private_dns_mode",
        "private_dns_specifier",
        "wifi_watchdog_on",
        "wifi_scan_interval_ms",
        "wifi_power_save",
        "wifi_low_latency_mode",
        "wifi_bt_coexistence",
        // GameSessionManager (Gaming DND)
        "zen_mode"
    )

    private val systemKeys = listOf(
        // TouchOptimizer.applyOptimization() (placebos OEM eliminados en 424c806)
        "pointer_speed",
        // GameSessionManager.applyHighPriorityOptimizations() / ProfileManager
        "peak_refresh_rate",
        "min_refresh_rate"
    )

    private val secureKeys = listOf(
        // TouchOptimizer.applyOptimization() (placebos OEM eliminados en 424c806)
        "long_press_timeout",
        "accessibility_display_magnification_enabled",
        "accessibility_autoclick_enabled"
    )

    val all: List<Pair<String, String>> = // (namespace, key)
        globalKeys.map { "global" to it } +
            systemKeys.map { "system" to it } +
            secureKeys.map { "secure" to it }

    /**
     * Valores que el boost escribe por key (para la política de conflicto del Caso B:
     * distinguir "valor aplicado por nosotros" vs "valor cambiado por el usuario").
     * Solo se listan los valores estáticos; los dinámicos se omiten y se resuelven
     * como conflicto-conservador.
     */
    fun appliedValueOf(namespace: String, key: String): String? = when (key) {
        "ble_scan_always_enabled" -> "0"
        "wifi_scan_always_enabled" -> "0"
        "bluetooth_disabled_profiles" -> "1"
        "overlay_display_devices" -> "0"
        "debug.hwui.renderer" -> "skiavk"
        "debug.hwui.overdraw" -> "false"
        "debug.hwui.show_dirty_regions" -> "false"
        "debug.sf.disable_backpressure" -> "1"
        "debug.sf.latch_unsignaled" -> "1"
        "disable_window_blurs" -> "1"
        "debug.sf.disable_hwc_vds" -> "1"
        "auto_sync" -> "0"
        "window_animation_scale", "transition_animation_scale", "animator_duration_scale" ->
            if (namespace == "global") "0" else null
        "send_action_app_error" -> "0"
        "activity_manager_constants" -> "max_cached_processes=128"
        "low_power_trigger_level" -> "0"
        "adaptive_connected_voice_enabled" -> "0"
        "debug.gl.msaa" -> "4"
        "private_dns_mode" -> "hostname"
        "private_dns_specifier" -> "dns.google"
        "wifi_watchdog_on" -> "0"
        "wifi_scan_interval_ms" -> "300000"
        "wifi_power_save" -> "0"
        "wifi_low_latency_mode" -> "1"
        "wifi_bt_coexistence" -> "0"
        "zen_mode" -> "2"
        // INTERINO (parche, no decisión de diseño): mismo defecto estructural que
        // min_refresh_rate — el writer GSM:517 escribe literal 120.0, pero
        // ProfileManager escribe $safeRefresh.0 dinámico a ambas keys; la tabla
        // estática no puede ser consistentemente correcta. Reemplazo designado: #5
        // (persistir valores realmente aplicados en la sesión y ELIMINAR esta tabla).
        "peak_refresh_rate" -> "120.0"
        // INTERINO (parche, no decisión de diseño): el writer GSM:518 escribe literal 90.0,
        // pero ProfileManager escribe $safeRefresh.0 dinámico — la tabla estática no puede
        // ser consistentemente correcta para esta key. "90.0" cubre la vía que corre hoy;
        // la vía ProfileManager degrada a Caso B (conservador). PENDIENTE #5: persistir los
        // valores realmente aplicados en la sesión y ELIMINAR esta tabla — el issue/PR de #5
        // es el reemplazo designado de este claim.
        "min_refresh_rate" -> "90.0"
        "long_press_timeout" -> "120"
        "accessibility_display_magnification_enabled" -> "0"
        "accessibility_autoclick_enabled" -> "0"
        else -> null // dinámicos: pointer_speed, vía ProfileManager de refresh rates, anims variants
    }
}

/**
 * Núcleo F4: captura persistida de baseline, restore verificado y recovery.
 *
 * No ejecuta comandos directamente: recibe un [CommandRunner] (inyectable para tests).
 */
class BoostSessionManager(
    private val store: BoostSessionStore,
    private val runCommand: suspend (String) -> Result<String>,
    private val log: (level: String, tag: String, message: String) -> Unit,
    private val onRestored: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "BoostSession"
    }

    // ── Lectura/normalización ─────────────────────────────────────────

    /** Resultado de lectura: distinguir PRESENT / ABSENT / READ_FAILED (F3B-Fix Issue 3). */
    sealed class SettingRead {
        data class Present(val value: String) : SettingRead()
        object Absent : SettingRead()
        data class ReadFailed(val reason: String?) : SettingRead()
    }

    /**
     * Lee el valor actual de una key (RAW, sin normalizar — fidelidad al baseline).
     * PRESENT = la key existe con valor; ABSENT = "null" del settings provider;
     * READ_FAILED = el comando falló o el pipeline (Shizuku) no está disponible.
     * "No saber el valor" ≠ "saber que la clave no existe".
     */
    private suspend fun readSetting(ns: String, key: String): SettingRead {
        val res = runCommand("settings get $ns $key")
        return when {
            res.isFailure -> SettingRead.ReadFailed(res.exceptionOrNull()?.message ?: "command failed")
            else -> {
                val raw = res.getOrNull()?.trim()
                when {
                    raw == null || raw.isEmpty() || raw == "null" -> SettingRead.Absent
                    else -> SettingRead.Present(raw)
                }
            }
        }
    }

    /** Normalización para comparación: trim; numérica "1.0"=="1"; "null"→ausencia. */
    private fun normalize(v: String): String {
        val t = v.trim()
        val asDouble = t.toDoubleOrNull()
        return if (asDouble != null) {
            // 1.0 → "1", 0.5 → "0.5": representación canónica numérica
            if (asDouble == asDouble.toLong().toDouble()) asDouble.toLong().toString() else asDouble.toString()
        } else t
    }

    private fun sameValue(a: String?, b: String?): Boolean =
        if (a == null && b == null) true
        else if (a == null || b == null) false
        else normalize(a) == normalize(b)

    // ── Capture (Parte 3: capture-or-reuse) ───────────────────────────

    /**
     * Regla anti-sobrescritura: si ya existe un baseline activo (estado ≠ IDLE/RESTORED),
     * NO se recaptura — se reutiliza (los valores actuales son boosteados).
     */
    suspend fun beginApply(): Boolean = withContext(Dispatchers.IO) {
        val current = store.load()
        if (current == null) {
            log("WARN", TAG, "Sin estado persistido legible — iniciando baseline fresco")
        }
        val active = current != null && current.state in listOf(
            BoostSessionState.BASELINE_CAPTURED,
            BoostSessionState.APPLYING,
            BoostSessionState.ACTIVE,
            BoostSessionState.RESTORING,
            BoostSessionState.RECOVERY_REQUIRED
        )
        val baseline: List<BackupEntry> = if (active && current != null) {
            // REUSE: jamás permitir que un valor boosted se convierta en "original"
            log("INFO", TAG, "Baseline activo (${current.state}) — reutilizando original (${current.baseline.size} keys)")
            current.baseline
        } else {
            // CAPTURE: leer los valores actuales reales antes del primer write.
            // READ_FAILED en capture → abortar el apply: sin baseline fiable no hay
            // recovery posible (fail-closed).
            val sid = "bs_${System.currentTimeMillis()}"
            val now = System.currentTimeMillis()
            val captured = mutableListOf<BackupEntry>()
            var readFailures = 0
            for ((ns, key) in BoostKeys.all) {
                when (val v = readSetting(ns, key)) {
                    is SettingRead.Present -> captured.add(BackupEntry(ns, key, v.value, now, sid))
                    is SettingRead.Absent -> captured.add(BackupEntry(ns, key, null, now, sid))
                    is SettingRead.ReadFailed -> {
                        readFailures++
                        log("ERROR", TAG, "Capture: lectura falló para $ns:$key (${v.reason}) — baseline no fiable")
                    }
                }
            }
            if (readFailures > 0) {
                log("ERROR", TAG, "Capture incompleto ($readFailures lecturas fallidas) — boost NO debe continuar sin baseline fiable")
                return@withContext false
            }
            log("INFO", TAG, "Baseline capturado: ${captured.size} keys (session $sid)")
            captured
        }

        // FIX Race 4.D: cada transición a APPLYING crea una IDENTIDAD de sesión
        // nueva (aunque el baseline se reutilice). Sin esto, un beginApply
        // concurrente con un restore en vuelo compartiría sessionId y el
        // re-check final no podría distinguir "mi commit" del "commit ajeno".
        val sid = "bs_${System.currentTimeMillis()}"
        val ok = store.save(BoostSession(BoostSessionState.APPLYING, baseline, sid, System.currentTimeMillis()))
        if (!ok) log("ERROR", TAG, "No se pudo persistir APPLYING — el boost NO debe continuar sin commit")
        ok
    }

    /** Marca la sesión como ACTIVE (tras completar los applies). */
    fun markActive() {
        val cur = store.load() ?: return
        store.save(cur.copy(state = BoostSessionState.ACTIVE, updatedAt = System.currentTimeMillis()))
    }

    // ── Restore verificado (Partes 6-7) ──────────────────────────────

    data class RestoreReport(
        val results: Map<String, RestoreResult>, // "ns:key" → resultado
        val allOk: Boolean
    )

    /**
     * Restaura el baseline con verificación real por relectura.
     * Caso A: actual==aplicado → restaurar. Caso B: actual≠original≠aplicado → CONFLICT (conservar).
     * Caso C: sin baseline para la key → NO inventar (skip, no comando). Caso D: fallo post → FAILED.
     */
    suspend fun restoreVerified(): RestoreReport = withContext(Dispatchers.IO) {
        val session = store.load()
        if (session == null || session.baseline.isEmpty()) {
            log("WARN", TAG, "restore sin baseline — nada que restaurar (Caso C)")
            return@withContext RestoreReport(emptyMap(), allOk = true)
        }

        // FIX Race 4.D (Issue 1): identidad de la sesión que este restore va a
        // confirmar. El commit final SOLO es válido si el archivo sigue siendo
        // ESTA sesión en RESTORING; si un beginApply nuevo (OFF→ON rápido) creó
        // otra sesión, este restore NO puede pisarla.
        val restoreSessionId = session.sessionId
        store.save(session.copy(state = BoostSessionState.RESTORING, updatedAt = System.currentTimeMillis()))
        val results = mutableMapOf<String, RestoreResult>()
        var failed = 0
        var readFailures = 0

        for (entry in session.baseline) {
            val id = "${entry.namespace}:${entry.key}"
            val current = readSetting(entry.namespace, entry.key)
            val original = entry.originalValue?.let { normalize(it) }

            if (current is SettingRead.ReadFailed) {
                // Issue 3: no sabemos el valor → NO decidir. No es conflicto,
                // no es skip: es un fallo de lectura que deja la sesión recuperable.
                readFailures++
                failed++
                results[id] = RestoreResult.RESTORE_FAILED
                log("ERROR", TAG, "Lectura falló para $id (${current.reason}) — no se decide sin conocer el valor")
                continue
            }
            val currentVal = when (current) {
                is SettingRead.Present -> current.value
                else -> null
            }

            when {
                sameValue(currentVal, original) -> {
                    // Ya está en el valor original (o ambos ausentes) — verificado trivial
                    results[id] = RestoreResult.RESTORE_SKIPPED
                }
                sameValue(currentVal, BoostKeys.appliedValueOf(entry.namespace, entry.key)?.let { normalize(it) }) -> {
                    // Caso A: contiene un valor aplicado por nosotros → restaurar
                    val okCmd = if (original == null) {
                        runCommand("settings delete ${entry.namespace} ${entry.key}")
                    } else {
                        runCommand("settings put ${entry.namespace} ${entry.key} $original")
                    }
                    // El exit code del comando NO basta: releer y comparar
                    val after = readSetting(entry.namespace, entry.key)
                    val afterVal = (after as? SettingRead.Present)?.value
                    results[id] = if (after is SettingRead.ReadFailed) {
                        // No pudimos verificar la postcondición → fallo (no éxito)
                        readFailures++
                        failed++
                        log("ERROR", TAG, "Verificación falló (lectura) para $id — no se marca como restaurado")
                        RestoreResult.RESTORE_FAILED
                    } else if (sameValue(afterVal, original)) {
                        RestoreResult.RESTORE_VERIFIED
                    } else {
                        failed++
                        log("ERROR", TAG, "Restore falló (postcondition): $id → esperado=$original actual=$afterVal")
                        RestoreResult.RESTORE_FAILED
                    }
                    if (okCmd.isFailure) log("WARN", TAG, "Comando de restore reportó error para $id (se decide por relectura)")
                }
                else -> {
                    // Caso B: ni original ni aplicado → el usuario lo cambió durante la ventana
                    results[id] = RestoreResult.RESTORE_CONFLICT
                    log("INFO", TAG, "Conflicto $id: actual=$currentVal no es original($original) ni aplicado — se conserva valor del usuario")
                }
            }
        }

        // FIX Race 4.D (Issue 1): revalidar JUSTO ANTES del commit final.
        // Solo confirmar si el archivo sigue siendo esta sesión en RESTORING.
        val stillMine = store.load()?.let { s ->
            s.sessionId == restoreSessionId && s.state == BoostSessionState.RESTORING
        } == true

        if (!stillMine) {
            log("WARN", TAG, "Sesión cambió durante el restore (era $restoreSessionId) — este restore NO confirma ni limpia; la sesión vigente permanece recuperable")
            return@withContext RestoreReport(results, allOk = false)
        }

        val allOk = failed == 0
        if (allOk) {
            val s = store.load()
            if (s != null) {
                store.save(s.copy(state = BoostSessionState.RESTORED, updatedAt = System.currentTimeMillis()))
            }
            log("INFO", TAG, "Restore verificado: ${results.size} keys (${results.values.count { it == RestoreResult.RESTORE_VERIFIED }} restauradas, ${results.values.count { it == RestoreResult.RESTORE_SKIPPED }} ya-ok, ${results.values.count { it == RestoreResult.RESTORE_CONFLICT }} conflictos conservados)")
        } else {
            val s = store.load()
            if (s != null) {
                store.save(s.copy(state = BoostSessionState.RECOVERY_REQUIRED, updatedAt = System.currentTimeMillis()))
            }
            log("ERROR", TAG, "Restore con $failed fallos ($readFailures de lectura) — RECOVERY_REQUIRED persiste")
        }
        RestoreReport(results, allOk)
    }

    // ── Recovery al arranque (Parte 5) ─────────────────────────────────

    /**
     * Debe ejecutarse en el init del Repository ANTES de cualquier apply/detección.
     * Devuelve true si el sistema quedó limpio (sin residuos pendientes).
     */
    suspend fun recoverIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        val session = store.load()
        if (session == null) {
            return@withContext true // IDLE — nada pendiente
        }
        when (session.state) {
            BoostSessionState.IDLE, BoostSessionState.RESTORED -> {
                // Limpieza de estado residual del archivo
                store.clear()
                true
            }
            BoostSessionState.BASELINE_CAPTURED -> {
                // Capturar no muta nada: baseline huérfano → limpiar
                log("INFO", TAG, "Baseline huérfano sin apply (murió tras capture) — limpiando")
                store.clear()
                true
            }
            BoostSessionState.APPLYING, BoostSessionState.ACTIVE, BoostSessionState.RESTORING, BoostSessionState.RECOVERY_REQUIRED -> {
                log("WARN", TAG, "Recovery requerido (estado al morir: ${session.state}) — restaurando baseline de ${session.baseline.size} keys")
                val report = restoreVerified()
                if (report.allOk) {
                    // FIX Race 4.D: solo limpiar si el archivo sigue siendo la
                    // sesión que este recovery restauró (id + RESTORED). Si cambió
                    // (nuevo apply concurrente), la sesión vigente permanece.
                    val stillMine = store.load()?.let { s ->
                        s.sessionId == session.sessionId && s.state == BoostSessionState.RESTORED
                    } == true
                    if (!stillMine) {
                        log("WARN", TAG, "Sesión cambió durante recovery — NO se limpia; la sesión vigente permanece recuperable")
                        return@withContext false
                    }
                    store.clear() // RESTORED confirmado y sigue siendo esta sesión → IDLE
                    log("INFO", TAG, "Recovery completo — dispositivo restaurado")
                    try { onRestored?.invoke() } catch (_: Exception) {}
                    true
                } else {
                    log("ERROR", TAG, "Recovery INCOMPLETO — la sesión queda RECOVERY_REQUIRED (recuperable cuando Shizuku vuelva a estar disponible)")
                    false
                }
            }
        }
    }

    /** Estado persistido actual (para diagnóstico). */
    fun currentState(): BoostSessionState = store.load()?.state ?: BoostSessionState.IDLE
}

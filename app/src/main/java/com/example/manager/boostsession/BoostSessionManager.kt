package com.example.manager.boostsession

import android.content.Context
import android.util.Log
import com.example.manager.RestoreValueValidators
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

        /**
         * UI (Tab Actividad): último reporte de restore verificado, en slot
         * COMPARTIDO entre instancias. El repositorio y GameSessionManager crean
         * cada uno su propio BoostSessionManager sobre el MISMO store (archivo);
         * un restore de salida de juego se ejecuta en la instancia de GSM, y la
         * UI consulta la del repo — el reporte debe verse desde ambas. Memoria
         * de proceso: se pierde al morir el proceso (la SSOT sigue siendo el
         * archivo persistido).
         */
        @Volatile
        var lastRestoreReport: RestoreReport? = null
            private set

        /** Estados en los que existe un baseline activo (no IDLE/RESTORED). */
        private val ACTIVE_STATES = listOf(
            BoostSessionState.BASELINE_CAPTURED,
            BoostSessionState.APPLYING,
            BoostSessionState.ACTIVE,
            BoostSessionState.RESTORING,
            BoostSessionState.RECOVERY_REQUIRED
        )
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
     *
     * PR2 (auditor, "beginApply con captura dentro de update"): el RMW es ATÓMICO.
     * Antes: store.load() → 34 lecturas de shell (suspend, segundos) → store.save():
     * un recordApplied/markActive/restore concurrente aterrizado en esa ventana se
     * PERDÍA (save pisa el archivo entero). Ahora las lecturas (suspend) corren
     * FUERA — store.update{} es síncrono bajo FILE_LOCK — y la decisión
     * capture-or-reuse + el commit se re-evalúan dentro de update sobre el estado
     * fresco. Además el REUSE previo al commit cierra el caso inverso: un capture
     * lanzado sobre estado inactivo ya no puede pisar una sesión que otro writer
     * activó durante las lecturas. Fail-closed: si el probe activo quedó inactivo
     * al llegar al commit (sin datos frescos que commitear) → aborta con false.
     */
    suspend fun beginApply(): Boolean = withContext(Dispatchers.IO) {
        val probe = store.load()
        if (probe == null) {
            log("WARN", TAG, "Sin estado persistido legible — iniciando baseline fresco")
        }

        // CAPTURE (solo si NO hay baseline activo): lecturas suspend fuera del lock.
        val captured: List<BackupEntry>? = if (probe != null && probe.state in ACTIVE_STATES) {
            log("INFO", TAG, "Baseline activo (${probe.state}) — reutilizando original (${probe.baseline.size} keys)")
            null
        } else {
            val capSid = "bs_${System.currentTimeMillis()}"
            val now = System.currentTimeMillis()
            val entries = mutableListOf<BackupEntry>()
            var readFailures = 0
            for ((ns, key) in BoostKeys.all) {
                when (val v = readSetting(ns, key)) {
                    is SettingRead.Present -> entries.add(BackupEntry(ns, key, v.value, now, capSid))
                    is SettingRead.Absent -> entries.add(BackupEntry(ns, key, null, now, capSid))
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
            log("INFO", TAG, "Baseline capturado: ${entries.size} keys (session $capSid)")
            entries
        }

        // FIX Race 4.D: cada transición a APPLYING crea una IDENTIDAD de sesión
        // nueva (aunque el baseline se reutilice). Sin esto, un beginApply
        // concurrente con un restore en vuelo compartiría sessionId y el
        // re-check final no podría distinguir "mi commit" del "commit ajeno".
        val sid = "bs_${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()

        if (probe == null) {
            // Sin archivo previo no hay RMW que cerrar: save crea la sesión (un
            // recordApplied concurrente es no-op sin sesión — update → load null).
            val ok = store.save(BoostSession(BoostSessionState.APPLYING, captured!!, sid, now))
            if (!ok) log("ERROR", TAG, "No se pudo persistir APPLYING — el boost NO debe continuar sin commit")
            return@withContext ok
        }

        // RMW atómico: la decisión se re-evalúa sobre el ÚLTIMO commit bajo FILE_LOCK.
        var staleProbe = false
        val committed = store.update { cur ->
            when {
                cur.state in ACTIVE_STATES -> {
                    // Otro writer activó la sesión mientras capturábamos (o el probe
                    // sigue activo): REUSE del baseline vigente — jamás un valor
                    // boosted convertido en "original".
                    BoostSession(BoostSessionState.APPLYING, cur.baseline, sid, now)
                }
                captured != null -> BoostSession(BoostSessionState.APPLYING, captured, sid, now)
                else -> {
                    // probe activo al inicio, pero la sesión ya no lo está: no
                    // capturamos y NO vamos a inventar un baseline.
                    staleProbe = true
                    cur // misma instancia → update no reescribe
                }
            }
        }
        if (staleProbe) {
            log("WARN", TAG, "Sesión vira entre probe y commit — beginApply abortado (reintento manual)")
            return@withContext false
        }
        if (committed == null) {
            log("ERROR", TAG, "No se pudo persistir APPLYING — el boost NO debe continuar sin commit")
            return@withContext false
        }
        true
    }

    /**
     * R1 (C4): guarda de estado en la SSOT. Solo APPLYING puede convertirse en ACTIVE.
     *
     * Sin esta guarda, el `markActive()` diferido (Job huérfano del delay(8000) en
     * GSM.toggleBoost) podía revivir una sesión ya restaurada:
     *   restore() → RESTORED → (8 s después) markActive() → ACTIVE zombie
     * Evidencia: OVERLAY-DISAPPEAR-FORENSIC-2026-09-13.md §8b (flip 13:58:12→13:58:14).
     *
     * Estados que SÍ pueden pasar a ACTIVE sin log de alarma: APPLYING (camino normal)
     * y BASELINE_CAPTURED (apply con baseline capturado pero sin commit aún visible —
     * en producción beginApply persiste APPLYING, se tolera por robustez).
     */
    fun markActive() {
        // A3: transición atómica bajo FILE_LOCK (update) — cierra la ventana
        // load→check→save donde un recordApplied concurrente se perdía al ser
        // pisado por el save de esta función (RMW separados).
        store.update { cur ->
            if (cur.state != BoostSessionState.APPLYING && cur.state != BoostSessionState.BASELINE_CAPTURED) {
                log("WARN", TAG, "markActive ignorado: estado=${cur.state} no es APPLYING (R1 C4: evita zombie RESTORED→ACTIVE)")
                return@update cur // no-op (misma instancia): el store no reescribe
            }
            cur.copy(state = BoostSessionState.ACTIVE, updatedAt = System.currentTimeMillis())
        }
    }

    /**
     * R1 (C3+C4): variante explícita para el Job diferido del apply (GSM.applySettleJob).
     * Doble defensa contra el zombie: aunque el Job sobreviva sin cancelarse (C3),
     * la SSOT rechaza la transición inválida (C4). Semánticamente idéntico a
     * markActive() con guarda — se mantiene como API con nombre para que el caller
     * exprese su naturaleza de "job diferido que puede llegar tarde".
     */
    fun markActiveIfApplying() = markActive()

    /**
     * #5 SSOT: los writers graban el valor real que acaban de aplicar a una key.
     * El restore reconoce "aplicado por nosotros" con ESTE valor (verdad de la
     * sesión) y solo cae a BoostKeys.appliedValueOf como fallback. Sin sesión
     * activa o sin entrada de baseline para la key → no-op.
     *
     * Nota de race conocida: recordApplied vs markActive son load→save completos
     * (último-escritor-gana). La degradación es grácil: un registro perdido cae
     * al fallback estático (comportamiento pre-#5), nunca corrompe el baseline.
     */
    fun recordApplied(namespace: String, key: String, value: String?) {
        // A3: RMW atómico (update) — el record de un writer ya no puede perderse
        // por interleave con markActive u otro recordApplied (antes: load→map→save
        // separados, último-escritor-gana a nivel de archivo completo).
        store.update { cur ->
            if (cur.state !in ACTIVE_STATES) return@update cur // no-op
            if (cur.baseline.none { it.namespace == namespace && it.key == key }) return@update cur // no-op
            cur.copy(
                baseline = cur.baseline.map { e ->
                    if (e.namespace == namespace && e.key == key) e.copy(appliedValue = value) else e
                },
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * PR1b (V4): variante BATCH de recordApplied para writers que aplican N keys
     * en un loop (SystemTweaks/NetworkOptimizer). Un solo load→transform→save
     * atómico en lugar de N commits con fd.sync() cada uno: reduce el I/O del
     * apply de ~27 fsyncs a 1, sin perder atomicidad ni mezclar estados.
     * Entradas de keys fuera del baseline → ignoradas (no-op, igual que recordApplied).
     */
    fun recordAppliedBatch(entries: List<Triple<String, String, String?>>) {
        if (entries.isEmpty()) return
        store.update { cur ->
            if (cur.state !in ACTIVE_STATES) return@update cur // no-op
            val requested = entries.mapTo(mutableSetOf()) { "${it.first}:${it.second}" }
            if (cur.baseline.none { "${it.namespace}:${it.key}" in requested }) return@update cur
            val byId = entries.associate { "${it.first}:${it.second}" to it.third }
            cur.copy(
                baseline = cur.baseline.map { e ->
                    byId["${e.namespace}:${e.key}"]?.let { v -> e.copy(appliedValue = v) } ?: e
                },
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * #5 SSOT: variante para writers que disparan comandos sueltos. Parsea
     * "settings put <ns> <key> <value...>" / "settings delete <ns> <key>" y
     * graba el resultado. Otros comandos (cmd power, for/dir, sysctl) se ignoran.
     */
    fun recordAppliedCommand(command: String) {
        val parts = command.trim().split(Regex("\\s+"))
        when {
            parts[0] == "settings" && parts.getOrNull(1) == "put" && parts.size >= 5 ->
                recordApplied(parts[2], parts[3], parts.drop(4).joinToString(" "))
            parts[0] == "settings" && parts.getOrNull(1) == "delete" && parts.size == 4 ->
                recordApplied(parts[2], parts[3], null)
        }
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

            // FIX H6b: namespace y key del baseline persistido NO son confiables
            // (boost_session.json es tamperable vía root/adb; allowBackup=true).
            // H6 valida originalValue, pero estos campos se interpolan también en
            // "settings get/put/delete ${ns} ${key}" que llega a sh -c sin quoting.
            // Validación estructural fail-closed ANTES de cualquier runCommand()
            // (incluye el readSetting de relectura). REGLA: rechazar, nunca mutar.
            if (!RestoreValueValidators.isValidSettingsNamespace(entry.namespace) ||
                !RestoreValueValidators.isValidSettingsKey(entry.key)
            ) {
                failed++
                log(
                    "ERROR", TAG,
                    "FIX H6b: namespace/key inválidos para '$id' — restore NO ejecutado para esta entrada"
                )
                results[id] = RestoreResult.RESTORE_FAILED
                continue
            }
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
                // #5 SSOT: el valor grabado por el writer en esta sesión manda; la
                // tabla estática es SOLO fallback (keys estáticas correctas, p.ej.
                // SystemTweaks). Para writers dinámicos el registro siempre existe
                // (recordApplied) y la tabla ya no se consulta.
                sameValue(currentVal, (entry.appliedValue ?: BoostKeys.appliedValueOf(entry.namespace, entry.key))?.let { normalize(it) }) -> {
                    // Caso A: contiene un valor aplicado por nosotros → restaurar
                    // FIX H6: validar el valor read-back ANTES de interpolarlo en el
                    // comando. Un baseline con metacaracteres shell (corrupción o
                    // provider manipulado) no debe alcanzar runCommand() como segunda
                    // instrucción. REGLA: rechazar, nunca mutar — preferimos declarar
                    // el restore fallido a escribir un valor ≠ baseline.
                    if (original != null &&
                        !RestoreValueValidators.isValidRestoreValue(entry.key, original)
                    ) {
                        failed++
                        log(
                            "ERROR", TAG,
                            "FIX H6: valor del baseline inválido para $id (dominio de '${entry.key}' violado) — restore NO ejecutado para esta key"
                        )
                        results[id] = RestoreResult.RESTORE_FAILED
                        continue
                    }
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

        val allOk = failed == 0
        // Retener el reporte SIEMPRE que el restore recorrió keys (trabajo real
        // ejecutado), incluso si el commit se aborta por cambio de sesión: la UI
        // debe reflejar el intento y su resultado, no silenciarlo.
        lastRestoreReport = RestoreReport(results, allOk)

        if (!stillMine) {
            log("WARN", TAG, "Sesión cambió durante el restore (era $restoreSessionId) — este restore NO confirma ni limpia; la sesión vigente permanece recuperable")
            return@withContext RestoreReport(results, allOk = false)
        }

        if (allOk) {
            // A3: confirmación RESTORED atómica — solo si el archivo sigue siendo
            // esta sesión en RESTORING. Antes: load→save separados permitían que un
            // recordApplied tardío de un writer (entre el load y el save) fuese
            // pisado por el copy(state=RESTORED) con baseline viejo.
            store.update { s ->
                if (s.sessionId == restoreSessionId && s.state == BoostSessionState.RESTORING) {
                    s.copy(state = BoostSessionState.RESTORED, updatedAt = System.currentTimeMillis())
                } else {
                    log("WARN", TAG, "Sesión cambió durante restore-commit — no se marca RESTORED (sesión vigente preservada)")
                    s // no-op (misma instancia): la sesión vigente queda intacta
                }
            }
            log("INFO", TAG, "Restore verificado: ${results.size} keys (${results.values.count { it == RestoreResult.RESTORE_VERIFIED }} restauradas, ${results.values.count { it == RestoreResult.RESTORE_SKIPPED }} ya-ok, ${results.values.count { it == RestoreResult.RESTORE_CONFLICT }} conflictos conservados)")
        } else {
            // Degradación a RECOVERY_REQUIRED: incondicional sobre la sesión vigente
            // (si otra sesión la reemplazó, esa queda en su estado propio — el update
            // solo baja el flag persistente; el estado de la sesión nueva lo gobierna su propio ciclo).
            store.update { s -> s.copy(state = BoostSessionState.RECOVERY_REQUIRED, updatedAt = System.currentTimeMillis()) }
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
                    // FIX Race 4.D + A3: limpieza atómica condicional — el archivo se
                    // borra SOLO si sigue siendo esta sesión en RESTORED, dentro de la
                    // MISMA sección crítica. Antes: load→check→clear separados dejaban
                    // una ventana donde un beginApply concurrente creaba una sesión
                    // nueva que luego era borrada por este clear.
                    val cleared = store.clearIf { s ->
                        s.sessionId == session.sessionId && s.state == BoostSessionState.RESTORED
                    }
                    if (!cleared) {
                        log("WARN", TAG, "Sesión cambió durante recovery — NO se limpia; la sesión vigente permanece recuperable")
                        return@withContext false
                    }
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

    /** Snapshot persistido actual de la sesión (null = sin sesión en el store). Para UI de evidencia. */
    fun sessionSnapshot(): BoostSession? = store.load()
}

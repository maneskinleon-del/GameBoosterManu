package com.example.manager.boostsession

import org.json.JSONArray
import org.json.JSONObject

/**
 * Máquina de estados de la sesión de boost (F4).
 *
 * IDLE                 → sin boost, sin baseline capturado.
 * BASELINE_CAPTURED    → baseline persistido, ningún setting modificado aún.
 * APPLYING             → comandos de apply en vuelo (posible apply parcial si muere el proceso).
 * ACTIVE               → boost aplicado.
 * RESTORING            → comandos de restore en vuelo (posible restore parcial).
 * RECOVERY_REQUIRED    → al arrancar se detectó muerte durante APPLYING/ACTIVE/RESTORING.
 * RESTORED             → baseline restaurado y verificado.
 *
 * La persistencia es un archivo transaccional (write-temp + fsync + rename),
 * de modo que un process death nunca deja un baseline a medias.
 */
enum class BoostSessionState {
    IDLE,
    BASELINE_CAPTURED,
    APPLYING,
    ACTIVE,
    RESTORING,
    RECOVERY_REQUIRED,
    RESTORED
}

/**
 * Registro de backup de un setting individual.
 *
 * `originalValue == null` representa "la key NO existía" (settings get devolvió "null"):
 * restaurar ese caso equivale a `settings delete`.
 *
 * #5 SSOT: `appliedValue` es el valor que NOSOTROS escribimos en esta sesión
 * (grabado por los writers vía BoostSessionManager.recordApplied). El restore
 * lo usa para distinguir "valor aplicado por nosotros" de "valor cambiado por
 * el usuario" SIN depender de la tabla estática BoostKeys.appliedValueOf
 * (que no puede ser correcta para writers dinámicos como refresh rates).
 */
data class BackupEntry(
    val namespace: String,      // "global" | "system" | "secure"
    val key: String,
    val originalValue: String?, // null = key ausente al capturar
    val capturedAt: Long,
    val sessionId: String,
    val appliedValue: String? = null // null = aún no escrito por nosotros en esta sesión
)

data class BoostSession(
    val state: BoostSessionState,
    val baseline: List<BackupEntry>,
    val sessionId: String,
    val updatedAt: Long
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        baseline.forEach { e ->
            arr.put(JSONObject().apply {
                put("ns", e.namespace)
                put("key", e.key)
                put("original", e.originalValue ?: JSONObject.NULL)
                put("capturedAt", e.capturedAt)
                put("sessionId", e.sessionId)
                put("applied", e.appliedValue ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("state", state.name)
            put("sessionId", sessionId)
            put("updatedAt", updatedAt)
            put("baseline", arr)
        }
    }

    companion object {
        /**
         * #5 SSOT (corte limpio, decisión Q2): archivos con schemaVersion ausente
         * o distinto → fromJson devuelve null → BoostSessionStore.load() los
         * manda a .corrupt y borra el original. Es el purge de snapshots legacy:
         * NUNCA se re-aplica un baseline que no entiende esta versión.
         */
        const val SCHEMA_VERSION = 2

        fun idle(): BoostSession =
            BoostSession(BoostSessionState.IDLE, emptyList(), "", 0L)

        fun fromJson(raw: String): BoostSession? {
            return try {
                val obj = JSONObject(raw)
                // Purge legacy (Q2): sin version declarada (v1) o distinta → descartar
                if (obj.optInt("schemaVersion", -1) != SCHEMA_VERSION) return null
                val state = try {
                    BoostSessionState.valueOf(obj.getString("state"))
                } catch (e: Exception) {
                    return null
                }
                val arr = obj.optJSONArray("baseline") ?: JSONArray()
                val baseline = mutableListOf<BackupEntry>()
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    baseline.add(
                        BackupEntry(
                            namespace = e.getString("ns"),
                            key = e.getString("key"),
                            originalValue = if (e.isNull("original")) null else e.getString("original"),
                            capturedAt = e.getLong("capturedAt"),
                            sessionId = e.optString("sessionId", ""),
                            appliedValue = if (e.isNull("applied")) null else e.optString("applied", null)
                        )
                    )
                }
                BoostSession(
                    state = state,
                    baseline = baseline,
                    sessionId = obj.optString("sessionId", ""),
                    updatedAt = obj.optLong("updatedAt", 0L)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Resultado de verificación de un restore individual.
 * RESTORE_VERIFIED   → relectura coincide con el original.
 * RESTORE_FAILED     → relectura NO coincide (estado sigue requiriendo recovery).
 * RESTORE_CONFLICT   → el valor actual no era ni el aplicado ni el original:
 *                      el usuario lo cambió durante la ventana boost; se conserva
 *                      el valor actual y se descarta el baseline de esa key.
 * RESTORE_SKIPPED    → valor actual ya == original (nada que hacer, verificado trivial).
 */
enum class RestoreResult {
    RESTORE_VERIFIED,
    RESTORE_FAILED,
    RESTORE_CONFLICT,
    RESTORE_SKIPPED
}

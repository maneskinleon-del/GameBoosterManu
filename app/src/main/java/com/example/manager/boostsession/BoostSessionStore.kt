package com.example.manager.boostsession

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Persistencia transaccional del estado de sesión de boost.
 *
 * Protocolo de escritura: temp → fsync → rename (atómico en el mismo filesystem).
 * Un process death en cualquier punto deja el archivo completo anterior o el
 * completo nuevo — nunca un baseline a medias.
 *
 * Archivo: filesDir/boost_session.json (disco interno privado, sobrevive LMK/force-stop).
 */
class BoostSessionStore(private val file: File) {

    companion object {
        private const val TAG = "BoostSessionStore"
        private const val FILE_NAME = "boost_session.json"

        /**
         * Lock a nivel de proceso (T4B-A1): todas las instancias que apunten al
         * mismo archivo (boost_session.json / .tmp) serializan su sección crítica
         * entre sí. Un lock por instancia NO es suficiente: existen dos instancias
         * productivas que comparten el mismo par de archivos (499/500 pérdidas en
         * la reproducción controlada).
         */
        private val FILE_LOCK = Any()

        fun create(context: Context): BoostSessionStore =
            BoostSessionStore(File(context.filesDir, FILE_NAME))

        private fun tempOf(file: File): File =
            File(file.parentFile, file.name + ".tmp")
    }

    /** Carga el último commit completo. Null si no existe o no parsea (→ sin baseline: no se inventa). */
    fun load(): BoostSession? {
        synchronized(FILE_LOCK) {
            if (!file.exists()) {
                // Commit previo interrumpido: el temp se descarta (era un estado no confirmado)
                tempOf(file).delete()
                return null
            }
            val raw = try {
                file.readText()
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo leer ${file.name}: ${e.message}")
                return null
            }
            val parsed = BoostSession.fromJson(raw)
            if (parsed == null) {
                // Corrupto: preservar como evidencia y operar sin baseline (Caso C)
                Log.e(TAG, "boost_session.json corrupto — preservando como .corrupt")
                try {
                    file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
                    file.delete()
                } catch (_: Exception) {}
            }
            return parsed
        }
    }

    /** Commit atómico del estado completo. */
    fun save(session: BoostSession): Boolean {
        synchronized(FILE_LOCK) {
            return try {
                val tmp = tempOf(file)
                FileOutputStream(tmp).use { fos ->
                    fos.write(session.toJson().toString().toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync() // durabilidad antes del rename
                }
                if (!tmp.renameTo(file)) {
                    // Fallo real de I/O (T4B-A1): NO se destruye el último commit válido.
                    // El fallback anterior (file.delete() + segundo renameTo) podía borrar
                    // un commit válido si el rename fallaba por una razón transitoria.
                    Log.e(TAG, "rename falló — commit NO realizado, preservando commit previo")
                    tmp.delete()
                    return false
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "save falló: ${e.message}")
                tempOf(file).delete()
                false
            }
        }
    }

    /** Limpia el estado persistido (tras RESTORED verificado, o baseline huérfano). */
    fun clear(): Boolean {
        synchronized(FILE_LOCK) {
            tempOf(file).delete()
            if (!file.exists()) return true
            return try {
                file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "clear falló: ${e.message}")
                false
            }
        }
    }

    /**
     * A3 (SSOT gap): read-modify-write ATÓMICO bajo FILE_LOCK. El transform se
     * aplica sobre el último commit y el resultado se commite en la MISMA sección
     * crítica — ninguna otra operación del store puede interponerse entre el
     * load y el save. Antes, los callers hacían load→transform→save como RMW
     * separados: dos writers concurrentes (recordApplied vs markActive) se
     * perdían updates (último-escritor-gana a nivel de archivo completo).
     *
     * Si el transform devuelve la MISMA instancia (no-op), no se reescribe el
     * archivo (evita commits triviales que solo bump-earían updatedAt).
     * Null = no había sesión legible o el commit falló (estado previo preservado;
     * nunca se destruye el último commit válido — ver save()).
     */
    fun update(transform: (BoostSession) -> BoostSession): BoostSession? {
        synchronized(FILE_LOCK) {
            val cur = load() ?: return null
            val next = transform(cur)
            if (next === cur) return cur
            return if (save(next)) next else null
        }
    }

    /**
     * A3 (SSOT gap): delete condicional ATÓMICO — el predicado se evalúa sobre el
     * último commit dentro de la misma sección crítica que el delete. Cierra la
     * ventana del patrón load→check→clear, donde un beginApply concurrente podía
     * crear una sesión nueva entre el check y el clear (y ser borrada).
     * false = no se borró (no había sesión, el predicado no se cumplió o falló).
     */
    fun clearIf(predicate: (BoostSession) -> Boolean): Boolean {
        synchronized(FILE_LOCK) {
            val cur = load() ?: return false
            return if (predicate(cur)) clear() else false
        }
    }

    /** Solo para tests: ruta del archivo. */
    fun path(): String = file.absolutePath

    /**
     * Solo para tests (A1): lectura cruda SINCRONIZADA bajo FILE_LOCK. El test
     * adversarial necesita leer el commit visible para verificar integridad;
     * sin este lock su read-back es una data race JMM y, en filesystems sin
     * rename atómico (overlayfs/FUSE), puede observar ENOENT entre unlink y
     * link del rename (falso positivo "archivo perdido"). El código de
     * producción siempre lee vía load()/save(), ya bajo el lock.
     */
    internal fun readRawForTest(): String? = synchronized(FILE_LOCK) {
        if (file.exists()) file.readText() else null
    }
}

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

        fun create(context: Context): BoostSessionStore =
            BoostSessionStore(File(context.filesDir, FILE_NAME))

        private fun tempOf(file: File): File =
            File(file.parentFile, file.name + ".tmp")
    }

    private val lock = Any()

    /** Carga el último commit completo. Null si no existe o no parsea (→ sin baseline: no se inventa). */
    fun load(): BoostSession? {
        synchronized(lock) {
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
        synchronized(lock) {
            return try {
                val tmp = tempOf(file)
                FileOutputStream(tmp).use { fos ->
                    fos.write(session.toJson().toString().toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync() // durabilidad antes del rename
                }
                if (!tmp.renameTo(file)) {
                    // Algunos FS: rename falla si destino existe
                    file.delete()
                    if (!tmp.renameTo(file)) {
                        Log.e(TAG, "rename falló — commit NO realizado")
                        tmp.delete()
                        return false
                    }
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
        synchronized(lock) {
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

    /** Solo para tests: ruta del archivo. */
    fun path(): String = file.absolutePath
}

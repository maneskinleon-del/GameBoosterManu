package com.example.manager.boostsession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * T4B-A1: test adversarial de la propiedad de exclusión sobre boost_session.json.
 *
 * Propiedad buscada (plan A1):
 *   "Dos instancias independientes de BoostSessionStore dentro del mismo proceso
 *   nunca ejecutan simultáneamente la sección crítica sobre
 *   boost_session.json / boost_session.json.tmp."
 *
 * Antes del fix (lock por instancia `lock = Any()`), dos save() concurrentes
 * podían intercalar write tmp → fsync → rename: la reproducción controlada
 * obtuvo 499/500 pérdidas del archivo. Este test ejercita EXACTAMENTE esa
 * configuración productiva: dos instancias REALES (no fakes), el MISMO archivo
 * y por consecuencia el mismo .tmp, con arranque sincronizado vía CyclicBarrier.
 *
 * Con el fallback destructivo anterior, la carrera producía además:
 *  - load() == null (archivo perdido),
 *  - truncamiento (JSONException),
 *  - mezcla (baseline con entries de dos sessionId distintos),
 *  - leftover del .tmp (rename de un thread pisa el tmp del otro).
 *
 * El invariante de integridad se verifica en cada read-back: entre el save() de
 * un thread y su lectura, el otro thread puede legítimamente commitear su propia
 * sesión (concurrencia real); el archivo siempre debe contener UNA sesión
 * íntegra de alguno de los dos writers, nunca null, corrupto o mezclado.
 *
 * JVM puro (mismo patrón que GameLifecycleR1Test / BoostSessionManagerTest):
 * usa el BoostSessionStore de producción; Log es no-op seguro en unit tests
 * (isReturnDefaultValues = true en app/build.gradle.kts).
 */
class BoostSessionStoreConcurrencyA1Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var file: File
    private lateinit var sA: BoostSessionStore
    private lateinit var sB: BoostSessionStore

    private val SAVES_PER_THREAD = 250

    /** Sesión distinguible por writer: cada entry lleva el sessionId del writer. */
    private fun sessionFor(writer: String, seq: Int): BoostSession = BoostSession(
        state = BoostSessionState.ACTIVE,
        baseline = listOf(
            BackupEntry("global", "auto_sync", "1", capturedAt = seq.toLong(), sessionId = writer),
            BackupEntry("system", "screen_brightness_mode", "1", capturedAt = seq.toLong(), sessionId = writer)
        ),
        sessionId = "$writer#$seq",
        updatedAt = seq.toLong()
    )

    /**
     * Invariante de integridad: el archivo (si existe) contiene exactamente UNA
     * sesión íntegra de A o de B. Devuelve el sessionId observado.
     * Falla si: JSON corrupto/truncado, mezcla de writers, o file borrado con
     * .tmp leftover (firma de la carrera: rename sobre tmp ajeno).
     */
    private fun assertOneIntactSession(context: String): String {
        assertTrue("$context: el archivo debe existir (sin pérdidas)", file.exists())
        val raw = file.readText()
        val parsed = BoostSession.fromJson(raw)
        assertNotNull("$context: JSON debe parsear como BoostSession íntegra (sin truncamiento): \"$raw\"", parsed)
        val session = parsed!!
        val writer = session.sessionId.substringBefore('#')
        assertTrue(
            "$context: sessionId inesperado: ${session.sessionId}",
            writer == "A" || writer == "B"
        )
        // Sin mezcla: TODAS las entries pertenecen al mismo writer que la sesión
        assertTrue(
            "$context: mezcla detectada — entries de $writer con otros sessionIds: ${session.baseline.map { it.sessionId }}",
            session.baseline.all { it.sessionId == writer }
        )
        return session.sessionId
    }

    @Before
    fun setup() {
        file = File(tmp.root, "boost_session.json")
        sA = BoostSessionStore(file) // misma ruta exacta que sB → mismo .tmp
        sB = BoostSessionStore(file)
    }

    @Test
    fun `two independent store instances saving concurrently never lose or corrupt the file`() {
        val barrier = CyclicBarrier(2)
        val failures = ConcurrentLinkedQueue<String>()
        val threadA = Thread {
            try {
                barrier.await(10, TimeUnit.SECONDS)
                repeat(SAVES_PER_THREAD) { i ->
                    assertTrue("save A#$i devolvió false", sA.save(sessionFor("A", i)))
                    assertOneIntactSession("read-back A#$i")
                }
            } catch (t: Throwable) {
                failures.add("A: ${t.message}")
            }
        }
        val threadB = Thread {
            try {
                barrier.await(10, TimeUnit.SECONDS)
                repeat(SAVES_PER_THREAD) { i ->
                    assertTrue("save B#$i devolvió false", sB.save(sessionFor("B", i)))
                    assertOneIntactSession("read-back B#$i")
                }
            } catch (t: Throwable) {
                failures.add("B: ${t.message}")
            }
        }

        threadA.start()
        threadB.start()
        // Las secciones críticas son rápidas; el deadlock (si el lock se rompiera) no debe colgar el test
        threadA.join(60_000)
        threadB.join(60_000)
        assertTrue(
            "proceso concurrente terminó con errores: $failures",
            failures.isEmpty()
        )

        // 1-4. Estado final: existe, parsea, íntegro, sin mezcla ni truncamiento
        val final = assertOneIntactSession("estado final")
        val loaded = sA.load()
        assertNotNull("load() no debe devolver null tras la carrera", loaded)
        assertEquals(
            "load() debe devolver exactamente el último commit visible",
            final,
            loaded!!.sessionId
        )
        // Sin leftover del .tmp (una carrera de rename habría dejado el tmp del otro writer)
        assertNull(
            "no debe quedar .tmp de una carrera (rename pisado)",
            if (File(file.parentFile, file.name + ".tmp").exists()) "tmp-presente" else null
        )
        // 6. Sin pérdida: NINGÚN save() reportado como falso por ningún writer
        //    (ya asertado por thread en los bucles; el estado final es de A o de B).
        assertTrue(
            "el estado final debe pertenecer íntegramente a una de las sesiones escritas",
            final.startsWith("A#") || final.startsWith("B#")
        )
    }

    /**
     * El fallback destructivo eliminado (file.delete() + segundo renameTo) podía
     * destruir el último commit válido. Este test ejercita el camino REAL de
     * fallo de renameTo (EACCES vía directorio padre r-x — falla determinista
     * POSIX, a diferencia del caso directorio-en-destino, que este JVM/JDK puede
     * resolver como reemplazo exitoso de un directorio vacío) y verifica el
     * contrato del plan A1: registrar error, limpiar el .tmp, retornar false y
     * NO destruir el último commit válido.
     */
    @Test
    fun `failed rename never destroys the previous valid commit`() {
        val original = sessionFor("A", 0)
        assertTrue(sA.save(original))

        // Inyección POSIX determinista de fallo de rename: directorio padre solo-
        // lectura (r-x). Abrir un .tmp EXISTENTE para escritura no requiere permiso
        // de escritura del directorio, pero rename(2) sí → falla con EACCES en
        // cualquier filesystem/JVM. El commit previo queda como archivo regular en
        // el destino, exactamente como en producción.
        val commitBytes = file.readText()
        val tmpFile = File(tmp.root, "boost_session.json.tmp")
        assertTrue(tmpFile.createNewFile()) // .tmp preexistente (como tras un crash)

        assertTrue(tmp.root.setExecutable(true, true))
        assertTrue(tmp.root.setReadable(true, true))
        assertTrue(tmp.root.setWritable(false, true))
        val result: Boolean
        try {
            result = sB.save(sessionFor("B", 1))
        } finally {
            assertTrue(tmp.root.setWritable(true, true))
        }

        // Contrato PART 2: rename fallido → save()==false, SIN destruir el commit
        assertEquals("save con rename fallido debe retornar false", false, result)
        assertEquals(
            "el commit previo debe permanecer byte-idéntico (sin file.delete()+retry)",
            commitBytes,
            file.readText()
        )
        val restored = sA.load()
        assertNotNull("el commit previo debe seguir cargando tras el fallo", restored)
        assertEquals("A#0", restored!!.sessionId)

        // El .tmp conserva lo escrito por el intento (write OK, rename no); el
        // delete() dentro del directorio r-x pudo fallar: tras restaurar permisos,
        // clear() debe poder limpiarlo y dejar el store operativo.
        assertTrue(sA.clear())
        assertFalse("clear() debe eliminar también el .tmp residual", tmpFile.exists())
        assertNull("tras clear(), load() debe devolver null", sA.load())
    }
}

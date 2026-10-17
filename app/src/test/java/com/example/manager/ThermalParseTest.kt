package com.example.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.roundToInt

/**
 * Tests de [parseThermal] (GO del operador, 2026-10-15) y del lector
 * térmico ÚNICO añadido para la validación en POCO M8 5G:
 * [isCpuThermalType], [maxCpuZoneTemp] y [readCpuZoneSamples].
 *
 * Casos cubiertos (spec del operador):
 * 1. Lectura válida de zona CPU.
 * 2. Varias zonas CPU — se toma el máximo.
 * 3. Zona CPU con temperatura 0/negativa/fuera de rango — rechazo del parser.
 * 4. GPU/batería/cargador/aoss y otros subsistemas NO se atribuyen a CPU.
 * 5. Zona desconocida con la temperatura más alta NO desplaza CPU válida.
 * 6. Ninguna zona CPU reconocible → NaN (dato no disponible).
 * 7. Archivos ausentes/vacíos/inaccesibles — fallo controlado, sin excepción
 *    ni 0 °C ficticio (I/O real sobre directorios temporales JVM).
 * 8. Formatos/unidades existentes (miles → Celsius vía parseThermal).
 */
class ThermalParseTest {

    // ─── parseThermal (preexistente del GO, 9 casos) ─────────────────

    @Test
    fun `45000 milidegrees se convierte a 45C`() {
        assertEquals(45.0f, parseThermal("45000")!!, 0.001f)
    }

    @Test
    fun `0 se rechaza como implausivel`() {
        assertNull(parseThermal("0"))
    }

    @Test
    fun `negativo se rechaza como implausivel`() {
        assertNull(parseThermal("-5"))
    }

    @Test
    fun `999999 se rechaza por 999999C fuera de rango`() {
        assertNull(parseThermal("999999"))
    }

    @Test
    fun `450 se rechaza por 450C fuera de rango`() {
        assertNull(parseThermal("450"))
    }

    @Test
    fun `texto no numerico se rechaza`() {
        assertNull(parseThermal("abc"))
    }

    @Test
    fun `cadena vacia se rechaza`() {
        assertNull(parseThermal(""))
    }

    @Test
    fun `milidegrees con espacios se recorta y convierte`() {
        assertEquals(45.0f, parseThermal("  45000  ")!!, 0.001f)
    }

    @Test
    fun `celsius directo en rango plausible se acepta`() {
        // raw ≤ 1000 no se divide: 42.5 ya está en Celsius
        assertEquals(42.5f, parseThermal("42.5")!!, 0.001f)
    }

    // ─── isCpuThermalType (spec §6.4) ────────────────────────────────────

    @Test
    fun `prefijo cpu se revisa cpu0 cpu-0-0 y cpuss`() {
        assertTrue(isCpuThermalType("cpu0"))
        assertTrue(isCpuThermalType("cpu-0-0"))
        assertTrue(isCpuThermalType("cpuss-0-0-usr"))
        assertTrue(isCpuThermalType("CPU0")) // insensible a mayúsculas
        assertTrue(isCpuThermalType("  cpu5  ")) // recorta espacios
    }

    @Test
    fun `subsistemas no-CPU que Device Watch sumaria no se atribuyen`() {
        assertFalse(isCpuThermalType("aoss-0-0"))
        assertFalse(isCpuThermalType("gpuss-0-usr"))
        assertFalse(isCpuThermalType("gpu"))
        assertFalse(isCpuThermalType("battery"))
        assertFalse(isCpuThermalType("batt"))
        assertFalse(isCpuThermalType("charger"))
        assertFalse(isCpuThermalType("mdm_0-0")) // módem
        assertFalse(isCpuThermalType("unknown_zone_name"))
        assertFalse(isCpuThermalType(""))
    }

    // ─── maxCpuZoneTemp (spec §6.1/§6.2/§6.3/§6.5/§6.6/§6.8) ───────

    @Test
    fun `una zona cpu con lectura valida se reporta`() {
        assertEquals(45.0f, maxCpuZoneTemp(listOf("cpu-0-0" to "45000")), 0.001f)
    }

    @Test
    fun `varias zonas cpu se toma la maxima`() {
        val samples = listOf(
            "cpu0" to "40000",
            "cpu5" to "47000",
            "cpu3" to "45000"
        )
        assertEquals(47.0f, maxCpuZoneTemp(samples), 0.001f)
    }

    @Test
    fun `zonas cpu invalidas 0 negativa y fuera de rango se rechazan por el parser`() {
        val samples = listOf(
            "cpu0" to "0",       // implausible (≤0)
            "cpu1" to "-5",      // implausible (≤0)
            "cpu2" to "999999",  // implausible (>150°C)
            "cpu3" to "45000"    // única válida
        )
        assertEquals(45.0f, maxCpuZoneTemp(samples), 0.001f)
    }

    @Test
    fun `una cpu invalida no tapa otra cpu valida`() {
        val samples = listOf(
            "cpu0" to "999999", // inválida
            "cpu1" to "46000"   // válida
        )
        assertEquals(46.0f, maxCpuZoneTemp(samples), 0.001f)
    }

    @Test
    fun `gpu battery aoss loader no se suman al maximo cpu`() {
        val samples = listOf(
            "cpu-0-0" to "45000",
            "gpu-usr" to "90000",
            "battery" to "80000",
            "aoss-0" to "70000",
            "charger" to "65000"
        )
        assertEquals(45.0f, maxCpuZoneTemp(samples), 0.001f)
    }

    @Test
    fun `zona desconocida con la mas alta no desplaza cpu valida`() {
        val samples = listOf(
            "cpu-1-1" to "43000",
            "unknown-hot-thing" to "95000"
        )
        assertEquals(43.0f, maxCpuZoneTemp(samples), 0.001f)
    }

    @Test
    fun `cpuss en miles de grado se convierte`() {
        assertEquals(57.5f, maxCpuZoneTemp(listOf("cpuss-0" to "57500")), 0.001f)
    }

    @Test
    fun `sin zonas cpu devuelve NaN`() {
        assertTrue(maxCpuZoneTemp(listOf("gpu" to "45000")).isNaN())
        assertTrue(maxCpuZoneTemp(emptyList()).isNaN())
    }

    @Test
    fun `sin cpu y sin lectura valida devuelve NaN`() {
        val samples = listOf(
            "cpu0" to "",
            "cpu1" to "texto"
        )
        assertTrue(maxCpuZoneTemp(samples).isNaN())
    }

    // ─── readCpuZoneSamples (spec §6.7 — I/O real con dirs temporales) ──

    private fun makeZone(base: File, index: Int, type: String? = null, temp: String? = null) {
        val zone = File(base, "thermal_zone$index")
        zone.mkdirs()
        type?.let { File(zone, "type").writeText(it) }
        temp?.let { File(zone, "temp").writeText(it) }
    }

    @Test
    fun `enumeracion tolera type o temp ausentes`() {
        val base = Files.createTempDirectory("thermaltest").toFile()
        try {
            makeZone(base, 0, type = "cpu-0-0", temp = "45000") // completa
            makeZone(base, 1, type = "gpu", temp = "90000")     // no-CPU: se lee bien pero se descarta en el máximo
            makeZone(base, 2, type = "cpu-1-1")                 // sin temp: descartada
            makeZone(base, 3, temp = "47000")                   // sin type: descartada
            makeZone(base, 4, type = "", temp = "48000")        // type vacío: descartada
            File(base, "thermal_zone5").writeText("soy un archivo, no un dir") // no-dir: descartada

            val samples = readCpuZoneSamples(base)

            // El orden de listFiles() no está garantizado: comparo como conjunto.
            assertEquals(setOf("cpu-0-0" to "45000", "gpu" to "90000"), samples.toSet())
            assertEquals(45.0f, maxCpuZoneTemp(samples), 0.001f)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `temp vacia o sin leer no inventa 0`() {
        val base = Files.createTempDirectory("thermaltest").toFile()
        try {
            makeZone(base, 0, type = "cpu0", temp = "")   // temp vacío: NO 0 válido
            val samples = readCpuZoneSamples(base)
            assertEquals(1, samples.size)
            assertTrue(maxCpuZoneTemp(samples).isNaN())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `temp que es un directorio no rompe la enumeracion`() {
        val base = Files.createTempDirectory("thermaltest").toFile()
        try {
            // lectura de File(dir).readText() cuando la ruta es dir → IOException: descartada, sin propagar
            makeZone(base, 0, type = "cpu0") // sin temp; "temp" será carpeta
            File(File(base, "thermal_zone0"), "temp").mkdirs()
            makeZone(base, 1, type = "cpu1", temp = "43000")

            val samples = readCpuZoneSamples(base)

            assertEquals(1, samples.size)
            assertEquals(43.0f, maxCpuZoneTemp(samples), 0.001f)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `directorio inexistente devuelve lista vacia sin excepcion`() {
        val missing = File(Files.createTempDirectory("thermaltest").toFile(), "no-existe")
        val samples = readCpuZoneSamples(missing)
        assertTrue(samples.isEmpty())
        assertTrue(maxCpuZoneTemp(samples).isNaN())
    }

    // ─── CpuThermalTickCache: enumeración 1 vez + latch ─────────────

    @Test
    fun `cache enumera solo en el primer tick y relee solo temp`() {
        val base = Files.createTempDirectory("thermalcache").toFile()
        try {
            makeZone(base, 0, type = "cpu0", temp = "45000")
            makeZone(base, 1, type = "gpu", temp = "90000")
            val cache = CpuThermalTickCache(base)

            // Tick 1: enumeración completa (contrato de readCpuZoneSamples).
            assertEquals(setOf("cpu0" to "45000", "gpu" to "90000"), cache.samples().toSet())

            makeZone(base, 2, type = "cpu1", temp = "60000") // nace DESPUÉS del tick 1
            (File(base, "thermal_zone1/temp")).writeText("91000")  // gpu cambió: no interesa
            (File(base, "thermal_zone0/temp")).writeText("47000") // cpu0 cambió: SÍ se relee

            // Tick 2: SOLO las CPU cacheadas; cpu1 no aparece (nació tarde) y la
            // gpu no se relee — pero su presencia tampoco rompe el max CPU.
            val tick2 = cache.samples()
            assertEquals(setOf("cpu0" to "47000"), tick2.toSet())
            assertEquals(47.0f, maxCpuZoneTemp(tick2), 0.001f)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `cache latched nada legible no reintenta ni siquiera cuando cambia sysfs`() {
        val base = Files.createTempDirectory("thermalcache").toFile()
        try {
            // Tick 1: la zona sin `type` no entra en la enumeración → lista
            // vacía y latch inmediato (cero zonas CPU legibles).
            makeZone(base, 0, temp = "45000") // sin type: se descarta
            val cache = CpuThermalTickCache(base)
            assertTrue(cache.samples().isEmpty())

            // Tick 2 en el MISMO estado: tras el latch NI ENUMERA.
            assertTrue(cache.samples().isEmpty())

            // Un cpu0 legible aparece más tarde: NO lo verá este proceso.
            makeZone(base, 3, type = "cpu0", temp = "50000")
            assertTrue(cache.samples().isEmpty())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `cache con cpu valida pero gpu no legible no se latched`() {
        val base = Files.createTempDirectory("thermalcache").toFile()
        try {
            makeZone(base, 0, type = "cpu0", temp = "45000")
            makeZone(base, 1, type = "gpu", temp = "90000")
            val cache = CpuThermalTickCache(base)
            assertEquals(45.0f, maxCpuZoneTemp(cache.samples()), 0.001f)

            // La gpu falla al releerse: no afecta al cache CPU (no se latched).
            (File(base, "thermal_zone1")).deleteRecursively()
            val tick2 = cache.samples()
            assertEquals(setOf("cpu0" to "45000"), tick2.toSet())
            assertEquals(45.0f, maxCpuZoneTemp(tick2), 0.001f)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `fallo puntual y hasta 4 fallos seguidos no latchan y resetea el contador`() {
        val base = Files.createTempDirectory("thermalcache").toFile()
        try {
            makeZone(base, 0, type = "cpu0", temp = "45000")
            val cache = CpuThermalTickCache(base)
            assertEquals(45.0f, maxCpuZoneTemp(cache.samples()), 0.001f) // tick 1

            // Fallo PUNTUAL (deep suspend congela sysfs un instante): sysfs vuelve
            // al tick siguiente — el monitor térmico NO está muerto.
            File(base, "thermal_zone0/temp").delete()
            assertTrue(cache.samples().isEmpty())
            File(base, "thermal_zone0/temp").writeText("52000")
            assertEquals(52.0f, maxCpuZoneTemp(cache.samples()), 0.001f)

            // Hasta 4 fallos CONSECUTIVOS (< Umbral=5) tampoco latchan:
            File(base, "thermal_zone0/temp").delete()
            repeat(4) { assertTrue(cache.samples().isEmpty()) }
            File(base, "thermal_zone0/temp").writeText("51000")
            assertEquals(51.0f, maxCpuZoneTemp(cache.samples()), 0.001f) // resetea contador

            // Otros 4 fallos: de nuevo NO latchan (el contador partió de 0).
            File(base, "thermal_zone0/temp").delete()
            repeat(4) { assertTrue(cache.samples().isEmpty()) }
            File(base, "thermal_zone0/temp").writeText("52000")
            assertEquals(52.0f, maxCpuZoneTemp(cache.samples()), 0.001f)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `cache se latched tras 5 fallos consecutivos de lectura`() {
        val base = Files.createTempDirectory("thermalcache").toFile()
        try {
            makeZone(base, 0, type = "cpu0", temp = "45000")
            val cache = CpuThermalTickCache(base)
            assertEquals(45.0f, maxCpuZoneTemp(cache.samples()), 0.001f) // relee OK

            // Todas las zonas CPU del cache dejan de leerse: fallos 1..4 NO latchan.
            File(base, "thermal_zone0/temp").delete()
            repeat(4) { assertTrue(cache.samples().isEmpty()) }

            // Fallo 5: latch definitivo.
            assertTrue(cache.samples().isEmpty())

            // …y NO reintenta aunque sysfs vuelva a ser legible:
            File(base, "thermal_zone0/temp").writeText("52000")
            assertTrue(cache.samples().isEmpty())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `cache con enumeracion vacia tambien se latched`() {
        val base = Files.createTempDirectory("thermalcache").toFile()
        try {
            // Sin ninguna zona legible: enumeración vacía → latch en tick 1.
            val cache = CpuThermalTickCache(base)
            assertTrue(cache.samples().isEmpty())

            // Nace después una zona CPU válida: no se re-enumerará.
            makeZone(base, 0, type = "cpu0", temp = "45000")
            assertTrue(cache.samples().isEmpty())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `la enumeracion solo alimenta al selector via parseThermal`() {
        // La única puerta de entrada al valor es parseThermal (vía
        // maxCpuZoneTemp). Dashboard (MainActivity 363): NaN → "N/D", valor
        // válido → toInt() (trunca, cosmética); overlay restaurado: NaN →
        // "N/D", valor válido → %.1f.
        val samples = listOf("cpu0" to "44456") // 44.456 °C en miles
        val parsed = maxCpuZoneTemp(samples)
        assertEquals(44.456f, parsed, 0.001f)
        assertEquals(44, parsed.roundToInt()) // redondeo de display, SIEMPRE sobre Celsius válido
    }
}

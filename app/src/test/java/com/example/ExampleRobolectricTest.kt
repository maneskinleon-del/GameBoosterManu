package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test plantilla (era Robolectric leyendo R.string.app_name — la integración
 * Robolectric/resources de este proyecto se repara en la fase de infra de tests;
 * F3B solo garantiza que la suite compile y corra). Se conserva la aserción
 * original de forma verificable en JVM puro leyendo el archivo de recursos.
 */
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        // Equivalente al original: context.getString(R.string.app_name) == "GameBoost Pro"
        val stringsXml = java.io.File("src/main/res/values/strings.xml").readText()
        val appName = Regex("<string name=\"app_name\">(.*?)</string>")
            .find(stringsXml)?.groupValues?.get(1)
        assertEquals("GameBoost Pro", appName)
    }
}

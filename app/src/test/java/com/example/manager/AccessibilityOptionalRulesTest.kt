package com.example.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM puros de [AccessibilityOptionalRules], mismo patrón que MapperDetectionTest.
 * 13 casos: 4 banner + 8 onboarding (tabla completa) + 1 reversibilidad del switch.
 */
class AccessibilityOptionalRulesTest {

    // ── shouldShowAccessibilityBanner (4 casos) ─────────────────────────────

    @Test
    fun `banner hidden when a11y active and not optional`() {
        assertFalse(
            "con a11y activa no hay nada que avisar",
            AccessibilityOptionalRules.shouldShowAccessibilityBanner(isActive = true, isOptional = false)
        )
    }

    @Test
    fun `banner shown when a11y inactive and not optional`() {
        assertTrue(
            "comportamiento actual sin cambios: a11y caída → banner",
            AccessibilityOptionalRules.shouldShowAccessibilityBanner(isActive = false, isOptional = false)
        )
    }

    @Test
    fun `banner hidden when a11y inactive and optional`() {
        assertFalse(
            "modo opcional silencia el banner",
            AccessibilityOptionalRules.shouldShowAccessibilityBanner(isActive = false, isOptional = true)
        )
    }

    @Test
    fun `banner hidden when a11y active and optional`() {
        assertFalse(
            "a11y activa + opcional → sin banner",
            AccessibilityOptionalRules.shouldShowAccessibilityBanner(isActive = true, isOptional = true)
        )
    }

    // ── shouldShowAccessibilityOnboarding: tabla completa 2×2×2 (8 casos) ───
    // Orden de args: (isServiceRunning, onboardingShown, isOptional)
    // Solo (F, F, F) muestra el diálogo.

    @Test
    fun `onboarding T F F does not show`() {
        assertFalse(
            AccessibilityOptionalRules.shouldShowAccessibilityOnboarding(true, false, false)
        )
    }

    @Test
    fun `onboarding T F T does not show`() {
        assertFalse(
            AccessibilityOptionalRules.shouldShowAccessibilityOnboarding(true, false, true)
        )
    }

    @Test
    fun `onboarding T T F does not show`() {
        assertFalse(
            AccessibilityOptionalRules.shouldShowAccessibilityOnboarding(true, true, false)
        )
    }

    @Test
    fun `onboarding T T T does not show`() {
        assertFalse(
            AccessibilityOptionalRules.shouldShowAccessibilityOnboarding(true, true, true)
        )
    }

    @Test
    fun `onboarding F F F shows`() {
        assertTrue(
            "servicio caído + nunca visto + sin modo opcional → diálogo (caso base actual)",
            AccessibilityOptionalRules.shouldShowAccessibilityOnboarding(false, false, false)
        )
    }

    @Test
    fun `onboarding F F T does not show`() {
        assertFalse(
            "modo opcional silencia el onboarding",
            AccessibilityOptionalRules.shouldShowAccessibilityOnboarding(false, false, true)
        )
    }

    @Test
    fun `onboarding F T F does not show`() {
        assertFalse(
            "flag 'ya lo visto' independiente del modo opcional",
            AccessibilityOptionalRules.shouldShowAccessibilityOnboarding(false, true, false)
        )
    }

    @Test
    fun `onboarding F T T does not show`() {
        assertFalse(
            AccessibilityOptionalRules.shouldShowAccessibilityOnboarding(false, true, true)
        )
    }

    // ── Reversibilidad del switch (punto 6) ─────────────────────────────────
    // Contrato explícito: el switch no es one-way — ON silencia, OFF restaura.
    // (Repite las filas F,F,T y F,F,F de la tabla; se mantiene como contrato nombrado.)

    @Test
    fun `switch off re-enables onboarding`() {
        assertFalse(
            "switch ON → onboarding silenciado",
            AccessibilityOptionalRules.shouldShowAccessibilityOnboarding(
                isServiceRunning = false, onboardingShown = false, isOptional = true
            )
        )
        assertTrue(
            "switch OFF → el onboarding vuelve a mostrarse",
            AccessibilityOptionalRules.shouldShowAccessibilityOnboarding(
                isServiceRunning = false, onboardingShown = false, isOptional = false
            )
        )
    }
}

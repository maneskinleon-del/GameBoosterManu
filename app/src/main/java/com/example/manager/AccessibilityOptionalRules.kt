package com.example.manager

/**
 * Reglas puras (JVM, sin Android) de visibilidad de los avisos de accesibilidad.
 *
 * Contexto (R1): UnifiedAccessibilityService solo acelera la detección (fast-path);
 * GameDetector (UsageStats + dumpsys window vía Shizuku) es el árbitro real. Este modo
 * existe para usuarios que no quieren habilitar accesibilidad por privacidad: silencia
 * los avisos de UI sin tocar el modelo de detección.
 *
 * Nota: DiagnosticScreen sigue mostrando "INACTIVO" para accesibilidad.
 * Es información de estado (no nudge) y queda fuera del alcance de este modo.
 */
object AccessibilityOptionalRules {

    /**
     * Banner naranja "Servicio inactivo — toque la tarjeta para activar" del Dashboard.
     * Solo se silencia con el modo opcional activo (si la a11y está activa no hay
     * nada que avisar). Con isOptional=false reproduce exactamente el condition previo.
     */
    fun shouldShowAccessibilityBanner(isActive: Boolean, isOptional: Boolean): Boolean =
        !isActive && !isOptional

    /**
     * Diálogo de onboarding de accesibilidad del Dashboard.
     * Se muestra solo si el servicio no corre, el usuario nunca lo vio y no activó
     * el modo opcional. Los dos flags son independientes: onboardingShown ("ya lo vi")
     * se mantiene aunque se active el modo opcional.
     *
     * Caveat: el diálogo es modal — un usuario nuevo lo ve una vez antes de poder
     * llegar al switch, así que en la práctica el switch silencia sobre todo el banner.
     */
    fun shouldShowAccessibilityOnboarding(
        isServiceRunning: Boolean,
        onboardingShown: Boolean,
        isOptional: Boolean
    ): Boolean = !isServiceRunning && !onboardingShown && !isOptional
}

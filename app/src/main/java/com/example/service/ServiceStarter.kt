package com.example.service

/**
 * Seam de test para [ServiceLauncher].
 *
 * Diseño (Issue 3 de 5c-b): recibe `action: String?` en lugar de `Intent` completo.
 * Esto evita que ServiceLauncher dependa de `Context` para crear Intents — el Context
 * se inyecta solo en la implementación de producción ([AndroidServiceStarter]).
 *
 * Tradeoff documentado: los tests no pueden verificar el `component` del Intent construido
 * (que apunta a GameBoostService::class.java). La creación es una única línea en
 * AndroidServiceStarter — bajo riesgo — y se valida indirectamente en instrumented tests
 * si fuera necesario.
 */
interface ServiceStarter {
    /** Lanza un foreground service (idle o boost). `action` = null para idle. */
    fun startForeground(action: String?)

    /** Lanza un service estándar (para ACTION_STOP). */
    fun startService(action: String?)
}
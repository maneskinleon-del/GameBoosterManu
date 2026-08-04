# AGENTS.md — GameBoost Pro

## Project Overview
Android app (Kotlin/Compose) que optimiza rendimiento para juegos móviles vía Shizuku + AccessibilityService.
- **minSdk:** 24, **targetSdk:** 34, **compileSdk:** 34
- **Arquitectura:** managers (lógica de sistema) → repository (facade singleton) → ViewModel → UI (Compose)
- **Clave:** Shizuku para comandos privilegiados, foreground service anti-LMK, overlay flotante para métricas

## Estructura del Proyecto
```
com.example/
├── data/
│   ├── database/       # Room: GameEntity, ProfileEntity, LogEntity + DAOs
│   ├── repository/     # GameBoostRepository (singleton facade)
│   └── PreferenceManager.kt, SettingToggle.kt
├── manager/            # Lógica de sistema (ShizukuExecutor, ProfileManager, GameSessionManager, etc.)
├── service/            # GameBoostService, UnifiedAccessibilityService, BootReceiver, Watchdog
├── ui/
│   ├── FloatingPanelManager.kt   # Overlay flotante (vía WindowManager)
│   ├── theme/          # MyApplicationTheme, Colors, Typography
│   └── viewmodel/      # GameBoostViewModel
├── touch/              # HysteresisFilter
└── MainActivity.kt
```

## Kotlin Standards

### Corrutinas
- UI → `Dispatchers.Main` | Red/archivos/shell → `Dispatchers.IO` | Cómputo → `Dispatchers.Default`
- ViewModel usa `viewModelScope.launch` para operaciones async
- Flujos de datos: `StateFlow` en managers, `collectAsStateWithLifecycle()` en UI
- Repository tiene su propio `repositoryScope` con `SupervisorJob`
- Siempre usar `withContext(Dispatchers.IO)` para comandos shell

### Null Safety
- Los campos de data class para respuestas de server: **nullable con default null**
- Usar `?.` y `?:`, evitar `!!`
- `lateinit` solo cuando es 100% seguro (inyección en onCreate/serviceConnected)

### Excepciones
- Comandos shell: usar `Result<T>` con `runCatching`, no try-catch silenciosos
- En flujos de collect: try-catch con re-lanzamiento de `CancellationException`
- Loggear errores con `Log.w(TAG, "contexto: ${e.message}")` antes de fallback

### Logging
- `Log.d(TAG, "...")` para debug
- `Log.i(TAG, "...")` para eventos importantes (start/stop/detección)
- `Log.w(TAG, "...")` para recuperaciones y fallbacks
- `Log.e(TAG, "...")` para errores no recuperables
- Tag constante por clase: `private const val TAG = "ClassName"`

## Compose & UI

### Colores del tema
```kotlin
// Theme personalizado oscuro (fondo #0B1326, superficie #111827)
// Colores definidos en ui/theme/Color.kt
val WarningOrange = Color(0xFFFF9800)  // Advertencias
val ErrorRed = Color(0xFFEF5350)       // Errores
```

### Cards
```kotlin
// Patrón estándar: surfaceVariant.copy(alpha = 0.2f) + borde tenue
Card(
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
    ),
    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
    shape = RoundedCornerShape(16.dp)
)
```

### Prefijos de recursos
- Colors: `app_` (ej: `app_background`)
- Icons: `ic_` (ej: `ic_extreme`)
- Profile colors: `profile_extreme`, `profile_ads`, `profile_gaming`, `profile_balanced`, `profile_power_save`

## Managers Clave

### ProfileManager (object)
```kotlin
enum class ProfileType(
    val displayName: String, val icon: String, val iconRes: Int,
    val priority: Int, val governor: String, val refreshRate: String,
    val pointerSpeed: Int, val animationScale: Int
) {
    EXTREME("🔥 EXTREME", "🔥", ..., 5, "performance", "120", 10, 0),
    ADS("⌨️ FF MOUSE", "⌨️", ..., 4, "performance", "120", 10, 0),
    GAMING("🎮 GAMING", "🎮", ..., 3, "schedutil", "90", 6, 0),
    BALANCED("⚖️ BALANCED", "⚖️", ..., 2, "schedutil", "60", 5, 1),
    POWER_SAVE("🔋 POWER SAVE", "🔋", ..., 1, "powersave", "60", 3, 1)
}
```

### GameBoostRepository (singleton)
- `getInstance(context)` — punto de entrada único
- StateFlows públicos: `isBoostActive`, `shizukuConnected`, `simulatedGame`, `systemMetrics`
- Métodos: `toggleBoost()`, `setActiveProfile(id)`, `setDpi()`, `setPointerSpeed()`

### GameBoostService (foreground)
- `ACTION_START` / `ACTION_STOP` para ciclo de vida
- `START_REDELIVER_INTENT` para autoreinicio
- Notification channel `gameboost_channel` con IMPORTANCE_HIGH
- `isRunning` flag static para verificar estado

### FloatingPanelManager
- Singleton vía `getInstance(context)`
- Overlay flotante con `TYPE_APPLICATION_OVERLAY` (API 26+) / `TYPE_PHONE` (<26)
- Flags: `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_WATCH_OUTSIDE_TOUCH`
- WindowManager obtenida fresh cada vez (nunca cachear)
- `show()` / `hide()` posteado a `mainHandler`

### ShizukuExecutor
- Comandos shell privilegiados: `runCommand(cmd): Result<String>`
- `sanitizeShellArg()`: filtrar `[a-zA-Z0-9._-/]`
- Fallback vía Settings API cuando Shizuku no conectado

## Reglas Anti-Hallucination

1. **No inventar APIs de Shizuku.** Verificar en ShizukuExecutor antes de asumir métodos.
2. **No asumir imports.** Cada import debe existir en build.gradle.kts o libs.versions.toml.
3. **No inventar nombres de recursos.** Verificar en res/ antes de referenciar R.id.* o R.drawable.*
4. **No asumir métodos de Android.** Si la API es > targetSdk 34, preguntar antes de usar.
5. **Overlay requiere `SYSTEM_ALERT_WINDOW`.** Verificar con `Settings.canDrawOverlays()`.
6. **Servicio foreground requiere notificación.** Siempre crear NotificationChannel en API 26+.
7. **No usar `!!` en código de producción.** Preferir `?.` + `?:` + manejo de null seguro.

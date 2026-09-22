# Inventario y propuesta de reorganización — GameBoosterManu

**Fecha:** 2026-09-13 · **Base:** main @ `f5ba334` · **Tipo:** documento de diseño (sin cambios de código)
**Insumos:** forense F1–F5, diagnóstico overlay (`OVERLAY-DISAPPEAR-FORENSIC-2026-09-13.md`), spec #5 implementada, ciclo ON/OFF validado en device.

**Pregunta guía:** ¿qué arquitectura y qué partes de esta funcionalidad realmente deben existir?

---

## 1. Inventario (43 archivos Kotlin, 8.721 líneas)

### Núcleo de ejecución privilegiada

| Archivo | LOC | Rol | Estado |
|--|--|--|--|
| `manager/ShizukuExecutor.kt` | 256 | Canal único de comandos privilegiados (Shizuku + fallback rish interno) | ✅ **KEEP** — 16 archivos lo referencian; taxonomía de errores honesta (F2); es el choke-point del registro de valores (#5) |
| `manager/RishExecutor.kt` | 205 | Shell uid 2000 vía `rish` | ✅ KEEP como capa interna de ShizukuExecutor (única referencia real) |
| `manager/exec/ExecutorExceptions.kt` | — | Tipos de error compartidos | ✅ KEEP |

### Persistencia de sesión (SSOT #5)

| Archivo | LOC | Rol | Estado |
|--|--|--|--|
| `manager/boostsession/BoostSessionStore.kt` | 104 | Escritura atómica (tmp→fsync→rename), lectura tri-state, `.corrupt` | ✅ **KEEP** — probado en device (purge v1→v2, recovery 34 keys) |
| `manager/boostsession/BoostSessionManager.kt` | 466 | Ciclo APPLYING→ACTIVE→RESTORED, restore verificado, recordApplied | ✅ KEEP |
| `manager/boostsession/BoostSessionState.kt` | 142 | Modelo schema v2 | ✅ KEEP |

### FSM / ciclo de vida del boost

| Archivo | LOC | Rol | Estado |
|--|--|--|--|
| `manager/GameSessionManager.kt` | **949** | FSM de juego + selección de perfil + toggleBoost + funnel `executePrivilegedCommands` + Mobilador + perfiles + restore orchestration | ⚠️ **SPLIT** — dios-componente; al menos 5 responsabilidades; el race de `markActive()` (bug registrado #2) vive aquí |
| `data/repository/GameBoostRepository.kt` | 489 | Fachada + init/recovery + wiring de callbacks + watchdog wiring | ⚠️ REVISAR — init de 250 líneas hace todo el wiring implícito |
| `service/GameBoostService.kt` | 364 | FGS + notificación + observers (boost, perfil, juego) + handleStart/Stop | ⚠️ REVISAR — handleStop ya saneado (PR #7); observers duplican owners del overlay |

### Detección de foreground — **DUPLICADA**

| Archivo | LOC | Rol | Estado |
|--|--|--|--|
| `manager/GameDetector.kt` | 296 | Polling UsageStats (3 s fg / 30 s bg) + fallback shell; lista de ignorados **larga** (incluye `com.android.vending`) | ⚠️ **FUSIONAR** — ver §2.1 |
| `service/UnifiedAccessibilityService.kt` | 232 | Eventos a11y (entrada instantánea) + botón ADS + lista de ignorados **corta** (causa del bug #1) | ⚠️ **FUSIONAR** |

### Resiliencia del servicio — **DUPLICADA**

| Archivo | LOC | Rol | Estado |
|--|--|--|--|
| `manager/WatchdogManager.kt` | 292 | Heartbeat in-proceso (15 s/60 s, backoff exponencial, health flow) | ⚠️ **FUSIONAR** |
| `service/ServiceWatchdogReceiver.kt` | 123 | Alarma AlarmManager (sobrevive muerte del proceso) | ✅ KEEP como mecanismo primario |

### Optimizers (writers de settings)

| Archivo | LOC | Rol | Estado |
|--|--|--|--|
| `manager/SystemTweaks.kt` | 336 | ~19 keys globales con verificación | ✅ KEEP |
| `manager/NetworkOptimizer.kt` | 164 | DNS/wifi low-latency | ✅ KEEP |
| `manager/TouchOptimizer.kt` | 139 | pointer/long_press/accessibility | ✅ KEEP |
| `manager/PowerOptimizer.kt` | 210 | cached apps suspend, power modes | ✅ KEEP |
| `manager/RamManager.kt` | 129 | Limpieza RAM | ✅ KEEP |
| `manager/ProfileManager.kt` | 102 | Perfiles → comandos (sin Room; Room es la autoridad) | ✅ KEEP |
| `manager/ThermalController.kt` | 191 | Lectura térmica → callbacks | ⚠️ REVISAR — en ZTE **inerte por construcción** (sysfs denegado, F5 guard); útil solo en devices con lectura |
| `manager/ResourceGovernor.kt` | 231 | Escala de recursos según boost | ⚠️ REVISAR — superpuesto con SystemMonitor |
| `manager/AdsPointerManager.kt` | 71 | Pointer del botón ADS (a11y) | ✅ KEEP (feature niche real) |

### Monitoreo

| Archivo | LOC | Rol | Estado |
|--|--|--|--|
| `manager/SystemMonitor.kt` | 370 | Métricas (temp, batería, RAM, ping) para overlay/dashboard | ✅ KEEP |
| `data/repository/DependencyState.kt` | 295 | Estado unificado de dependencias (Shizuku/a11y/batería) | ✅ KEEP |

### UI

| Archivo | LOC | Rol | Estado |
|--|--|--|--|
| `MainActivity.kt` | **1.309** | Toda la UI Compose + permisos + lifecycle + lógica de boost | ⚠️ **SPLIT** — dios-activity |
| `ui/FloatingPanelManager.kt` | 352 | Overlay TYPE_APPLICATION_OVERLAY | ✅ KEEP — show()/hide() idempotentes; el bug no era suyo |
| `ui/viewmodel/GameBoostViewModel.kt` | 139 | ViewModel del dashboard | ✅ KEEP — extender al dividir MainActivity |
| `ui/theme/*` | ~150 | Tema | ✅ KEEP |

### Servicios/Receivers

| Archivo | LOC | Rol | Estado |
|--|--|--|--|
| `service/ShizukuServiceConnection.kt` | 87 | Conexión binder Shizuku | ✅ KEEP |
| `service/BootReceiver.kt` | — | Arranque en boot | ✅ KEEP |
| `data/PreferenceManager.kt` | 185 | Prefs (ya saneado de override global en #5) | ✅ KEEP |

**Torta:** ~60% del código es keep directo; el 40% restante concentra 5 duplicaciones/cadenas problemáticas.

---

## 2. Cadenas duplicadas (evidencia de complejidad innecesaria)

### 2.1 Detección de foreground ×2 — **causa del bug registrado #1**

```
GameDetector (polling 3s/30s)  ──┐
                                 ├──> GameBoostRepository.onForegroundAppChanged()
UnifiedAccessibilityService ─────┘         ├─ isGamePackage → setForegroundApp (entrada)
                                           └─ !isGamePackage → onForegroundAppLost (SALIDA)
```

- **Dos listas de ignorados distintas** (`GameDetector`: 15+ paquetes; `UnifiedA11y`: 8). Las ventanas transitorias del OEM (`com.zjx.ztezscreenshot`, `cn.nubia.gameassist`) pasan el filtro del a11y y disparan salidas falsas → `isBoostActive=false` → `hide()`.
- El a11y es el **único** que da entrada instantánea; el polling es el único que puede **confirmar** salida (re-lectura del estado real).
- **Decisión de diseño:** a11y = señal de entrada; polling = árbitro de salida. Salida solo si el poll confirma no-juego N ciclos consecutivos. Esto absorbe el fix del bug #1 sin parche aislado.

### 2.2 Watchdogs ×2

- `WatchdogManager` (heartbeat in-proceso): útil para **salud de dependencias** (reconexión Shizuku), inútil si el proceso muere.
- `ServiceWatchdogReceiver` (alarma): sobrevive muerte del proceso; es el que realmente resucita el servicio.
- **Decisión:** un solo dueño de "mantener el servicio vivo" (alarma); WatchdogManager se reduce a guardian de dependencias (o se fusiona con DependencyState).

### 2.3 Restore ×4 orquestaciones

- Exit de juego (`triggerExitWithHysteresis`): `restoreVerified()` + `networkOptimizer.restore()` + `systemTweaks.restore()` + `toggleMobilador()` + `touchOptimizer.restore()`.
- `toggleBoost(false)` → `restoreSettings()` (misma secuencia, otro path).
- `GameBoostService.handleStart` → `restoreSavedSettings()` (DPI/pointer, tercera variante).
- Recovery de arranque (cuarta).
- **Decisión:** una sola operación `BoostSession.restoreAll(reason)` que invoque a los writers en orden fijo; los 4 paths la llaman. Elimina divergencias (el race del bug #2 se detectó justamente por esta dispersión).

### 2.4 Overlay ×4 owners

- Service observer (show/hide por isBoostActive), MainActivity.onResume (re-show), MainActivity switch (hide manual), Repository.onGameDetected (re-show).
- `show()/hide()` son idempotentes, así que funciona — pero el estado visible depende de 4 writers + `FloatingPanelManager.isVisible` + `PreferenceManager.serviceRunning`.
- **Decisión:** el service observer es el **único escritor**; los demás declaran *intención* (`requestOverlayVisible(bool)` en el repo). Bajo costo, elimina ambigüedad.

### 2.5 Boost lifecycle partida en 3

- `toggleBoost()` (GameSessionManager): session.beginApply + ensureBoostServiceRunning + applyBoostSettings + `delay(8000){markActive()}` ← **el race del bug #2**.
- `GameBoostService.handleStart`: re-aplica perfil + restoreSavedSettings.
- Recovery en `GameBoostRepository.init`.
- **Decisión:** el estado persistido manda; `markActive()` debe ser un evento del ciclo (cancelable por exit/restore), no un timer huérfano. Se resuelve dentro de la unificación de restore (2.3).

---

## 3. Arquitectura objetivo

```
┌──────────────────────────── UI ────────────────────────────┐
│ MainActivity (split: DashboardScreen / SettingsScreen /    │
│ DiagnosticsScreen) · GameBoostViewModel · FloatingPanel    │
│ (overlay: show/hide SOLO desde BoostCoordinator)           │
└──────────────▲─────────────────────────────────────────────┘
               │ StateFlows
┌──────────────┴────────────── APP CORE ─────────────────────┐
│ GameBoostRepository (fachada delgada)                      │
│   ├── ForegroundMonitor  ← A11yService(entrada) + Poll     │
│   │                          (confirmación de salida)      │
│   │      · UNA lista de ignorados compartida               │
│   ├── BoostCoordinator (= GameSessionManager slim)         │
│   │      · FSM READY/GAME_ACTIVE · selección de perfil     │
│   │      · toggleBoost: beginApply→apply→markActive        │
│   │        (markActive cancelable por exit)                │
│   ├── BoostSession (+Store)  ← SSOT persistida (schema v2) │
│   │      · restoreAll(reason): único entry-point de restore│
│   ├── Optimizers: SystemTweaks · Network · Touch · Power   │
│   │      · Ram · ProfileManager (todos vía funnel)         │
│   └── DependencyHealth (Shizuku/a11y/battery) + AlarmWd    │
└──────────────▲─────────────────────────────────────────────┘
               │
┌──────────────┴──────────── EXECUTION ──────────────────────┐
│ ShizukuExecutor (+RishExecutor interno) · recordApplied()  │
└─────────────────────────────────────────────────────────────┘
```

Principios: **una** detección con dos modos, **un** restore, **un** owner del overlay, **un** executor privilegiado, **una** fuente de verdad persistida. El FGS queda como shell de notificación/lifecycle, sin lógica de negocio.

---

## 4. Disposición por componente

| Acción | Componentes |
|--|--|
| **KEEP** | ShizukuExecutor(+Rish), BoostSession/Store, SystemTweaks, NetworkOptimizer, TouchOptimizer, PowerOptimizer, RamManager, ProfileManager, AdsPointerManager, SystemMonitor, DependencyState, FloatingPanelManager, PreferenceManager, ShizukuServiceConnection, BootReceiver, theme, ViewModel |
| **FUSIONAR** | GameDetector + UnifiedA11y → `ForegroundMonitor` (un ignore-list, a11y=entrada, poll=árbitro de salida) · WatchdogManager + ServiceWatchdogReceiver → alarma primaria + health check |
| **SPLIT** | `GameSessionManager` → `BoostCoordinator` (FSM+lifecycle) / `ProfileSelector` / `PrivilegedFunnel` (ya existe como método; hacerlo tipo) · `MainActivity` → screens + ViewModels |
| **SIMPLIFICAR** | GameBoostService (sin observers de negocio; solo notification+coordinación) · GameBoostRepository (fachada delgada, init explícito) |
| **REVISAR (device-dependent)** | ThermalController (inerte en ZTE; conservar tras feature-flag) · ResourceGovernor (solapa con SystemMonitor; evaluar fusión) |

---

## 5. Plan de reorganización (secuencia sin parches aislados)

1. **R1 — ForegroundMonitor** (absorbe bug #1): un ignore-list, entrada por a11y, salida confirmada por poll. *Verificación: repro del forense — entrar FF con ventanas transitorias y seguir boost+overlay.*
2. **R2 — restoreAll unificado** (absorbe bug #2): markActive cancelable, un solo entry-point. *Verificación: ciclo ON/OFF + muerte mid-ACTIVE (ya automatizable con el harness FakeDevice).* 
3. **R3 — Overlay single-writer**: requestOverlayVisible() + service observer como único ejecutor.
4. **R4 — Split de GameSessionManager y MainActivity** (refactor puro, tests existentes + nuevos).
5. **R5 — Limpieza device-dependent**: flags para Thermal/ResourceGovernor.

Cada paso mergeable independiente; los dos bugs registrados quedan cerrados por R1 y R2, no por hotfixes.

---

## 6. Preguntas abiertas (decidir antes de R1)

- **Q1:** ¿Conservar el modo Mobilador y el botón ADS como features de primera clase, o moverlos a fase 2 del split? (afecta alcance de R1/R4)
- **Q2:** ¿El FGS `GameBoostService` mantiene su rol actual (notificación + shell) o se degrada a notificación pura con el coordinator en el proceso principal?
- **Q3:** ¿Se acepta perder la entrada instantánea por a11y en devices sin el servicio habilitado (queda el polling 3 s como fallback), o se exige paridad?

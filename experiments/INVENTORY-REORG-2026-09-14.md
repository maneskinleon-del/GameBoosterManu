# Inventario actualizado — GameBoosterManu (post-R1)

**Fecha:** 2026-09-14 · **Base:** `main @ feaf42bd70dbc56c2c6378e9612ee331efb70220` · **Tipo:** observación + análisis. **Cero cambios de código.**
**Antecedente:** `INVENTORY-REORG-2026-09-13.md` (base `f5ba334`, pre-R1). Este documento lo actualiza; NO lo reemplaza como registro histórico.
**Objetivo:** fotografía estructural para decidir qué hacer con **Tab 3 (Logs)** y **Tab 4 (Diagnóstico)** y las responsabilidades repartidas tras R1.

**Convención de evidencia:**
- **CONFIRMADO** → demostrado con código/commit/CodeGraph/referencia cruzada verificación.
- **INFERIDO** → conclusión razonable, no demostrada al 100%.
- **NO DETERMINADO** → falta evidencia.

**Metodología:** CodeGraph (`.codegraph/` presente; `query`, `explore`, `callers`) como mapa + lectura directa de código + `grep` de referencias cruzadas sobre `app/src/main` y `app/src/test`. CodeGraph se usó para ubicación, no como sustituto de lectura. **Dos lecciones de fiabilidad de CodeGraph detectadas durante este inventario** (ver §2.3): colisiones por nombre (`clearLogs` ×3 en clases distintas; `LogsScreen` reportado en línea 232 = el `when` de navegación, no una llamada directa).

---

## 1. Estado del repositorio — CONFIRMADO

| Ítem | Valor |
|--|--|
| Branch | `main` |
| HEAD | `feaf42bd70dbc56c2c6378e9612ee331efb70220` (merge R1) |
| Working tree | Limpio; solo untracked preexistentes: `.codegraph/` + 4 docs de experimentos + `experiments/forensic-*` |
| `origin/main` | `feaf42b…` — **sincronizado** (verificado con `git ls-remote`) |
| Commits R1 en main | `bc30384` (C1–C6+tests) → `0a1fc4f` (2 fixes de device) → `ec07246` (hardening review) → `feaf42b` (merge `--no-ff`) |

---

## 2. Estructura actual

### 2.1 Módulos y tamaño — CONFIRMADO (`wc -l` sobre HEAD)

Un solo módulo Gradle (`app/`). **43 archivos Kotlin, 8,907 LOC** (pre-R1: 8,721 → Δ +186 producción, +159 test). Sin nuevos archivos de producción en R1; 1 test nuevo (`GameLifecycleR1Test.kt`, 159 LOC).

| Capa | Archivo | LOC | Δ vs pre-R1 |
|--|--|--|--|
| UI | `MainActivity.kt` | 1,314 | +5 (C5: request en vez de hide directo) |
| FSM/lifecycle | `manager/GameSessionManager.kt` | 978 | +29 (C3 applySettleJob + C6 performRestore) |
| Fachada | `data/repository/GameBoostRepository.kt` | 523 | +34 (C2 exit-hint + C5 overlayRequest + cache-sync hook) |
| SSOT sesión | `manager/boostsession/BoostSessionManager.kt` | 490 | +24 (C4 guard) |
| Servicio | `service/GameBoostService.kt` | 382 | +18 (C5 observador combinado + hide explícito handleStop) |
| Detección | `manager/GameDetector.kt` | 360 | +64 (árbitro: launcher-rule, splash cross-check, notifyForegroundGame) |
| A11y | `service/UnifiedAccessibilityService.kt` | 244 | +12 (C1 propone, no ejecuta; +3 paquetes ignorados) |
| Resto | optimizers, monitor, watchdogs, theme, DB… | ~4,946 | sin cambios |

### 2.2 Paquetes — CONFIRMADO

```
com.example
├── MainActivity.kt               (toda la UI Compose + 4 tabs)
├── data/  PreferenceManager · SettingToggle · database/ (Room: AppDatabase, GameDao, ProfileDao, LogDao, LogEntity, GameEntity, ProfileEntity)
│         repository/ (GameBoostRepository, DependencyState)
├── manager/  GameSessionManager · GameDetector · WatchdogManager · ShizukuExecutor · RishExecutor
│             exec/ (PrivilegedResult, ExecutorExceptions) · boostsession/ (Manager, Store, State)
│             SystemTweaks · NetworkOptimizer · TouchOptimizer · PowerOptimizer · RamManager
│             ProfileManager · ThermalController · ResourceGovernor · AdsPointerManager
├── service/  GameBoostService · UnifiedAccessibilityService · ServiceWatchdogReceiver · ShizukuServiceConnection · BootReceiver
├── touch/    HysteresisFilter        ← ver §8: sin referencias en todo el repo
└── ui/       FloatingPanelManager · viewmodel/GameBoostViewModel · theme/
```

### 2.3 CodeGraph — notas de uso (para futuras sesiones)

- `codegraph explore "MainActivity tabs UI structure"` y `callers <símbolo>` fueron útiles para blast-radius (MainActivity→GameBoostService, LogsScreen/DiagnosticScreen→GameBoostApp).
- **Colisión por nombre (CONFIRMADO):** `callers clearLogs` mezcló tres funciones distintas: `GameBoostRepository.clearLogs()` (Room), `GameSessionManager.quickClean()` que llama `systemTweaks.clearLogs()` (logcat -c vía Shizuku), y `SystemTweaks.clearLogs()`. Son cadenas independientes.
- **Coincidencia por texto (CONFIRMADO):** `LogsScreen` apareció como "caller" en `GameBoostApp:232` — es el `when` de navegación, correcto pero fácil de malinterpretar.
- Conclusión: CodeGraph vale para orientación; toda clasificación de este inventario se apoyó además en grep de referencia cruzada y lectura.

---

## 3. UI actual — CONFIRMADO (lectura de `MainActivity.kt`)

Navegación: `enum NavigationTab { TABLERO, OPTIMIZAR, LOGS, DIAGNOSTICO }` (línea 228), bottom bar, `AnimatedContent` (311–316). TopBar: botón toggle de visibilidad del overlay (`FloatingPanelManager.toggleVisibility`) + otro IconButton (258).

### Tab 1 — TABLERO (`DashboardScreen`, 324–726) — mixta usuario/diagnóstico
| Sección | Contenido | Acciones |
|--|--|--|
| DEPENDENCIAS DEL SISTEMA | `DependencyState` (Shizuku/Accesibilidad/Batería/GameBoostService) + reinicios automáticos (`WatchdogManager.healthStatus`) | ninguna directa |
| CONFIGURACIÓN ACTUAL | perfil activo, DPI, puntero, animaciones, refresco, governor, detección Mobilador | ninguna (solo lectura) |
| Auto-Boost Engine | switch `testTag("boost_switch")` + Modo Mobilador + botones "Clear Cache"/"Optimize RAM" (`quickClean`) + estado térmico/batería | toggle boost (start/stop FGS + `setOverlayRequested`), toggle Mobilador, quickClean |
| StatusCards SHIZUKU / ACCESIBILIDAD | estado + RECONECTAR SHIZUKU (`toggleShizukuState`) + onboarding a11y | activar permisos |
| DETECTED GAMEPLAY | texto estado FSM | ninguna |
| AJUSTE DE PANTALLA | slider DPI (280–600), puntero | setDpi/setPointerSpeed (vía Shizuku) |

### Tab 2 — OPTIMIZAR (`BoostScreen`, 826–911) — usuario
Perfiles (Room) con activación, "Perfil Personalizado" (`CreateProfileDialog`: governor, refresco, toggles *decorativos* — "Restricciones de Datos", "Master Filter (AI)", "Afinidad de CPU" tienen `onCheckedChange = {}` — **INFERIDO: placebo UI, sin efecto backend**), y 5 AdvancedToggles (`isAggressiveOptimization`, `isThermalWatchdog`, `isAutoDetectGames`, `isDeepSleep`, `isMsaa`) persistidos en prefs — consumo real de cada toggle en el core **NO DETERMINADO en detalle** (no se auditó el consumo de cada flag; solo que se persisten).

### Tab 3 — LOGS (`LogsScreen`, 1068–1090) → ver §4
### Tab 4 — DIAGNÓSTICO (`DiagnosticScreen`, 1092–1129) → ver §5

Otras piezas de UI fuera de tabs: overlay flotante (`FloatingPanelManager`, escritor único = observador del servicio desde R1), `AccessibilityOnboardingDialog`.

---

## 4. Tab 3 — Logs

### Cadena completa — CONFIRMADO

```
Productores (fire-and-forget, doble canal Room + Log.d):
  GameBoostRepository.addLog()  (:479)  ← lambdas log= de WatchdogManager/BoostSessionManager/ResourceGovernor (:98)
                                        ← ~12 llamadas propias (recovery, DPI, init, seed…)
  GameSessionManager.addLog()   (:952)  ← lambda log= de su BoostSessionManager interno (:108)
                                        ← ~20 llamadas propias (boost ON/OFF, baseline, restore, Mobilador, DND, FSM_DIAG…)
        ↓ insertLog
  Room tabla `logs` (LogDao: SELECT * ORDER BY id DESC LIMIT 200 — autotrim por lectura)
        ↓ getRecentLogsFlow
  GameBoostRepository.logsFlow (:172) → GameBoostViewModel.logs → LogsScreen (solo lectura)
```

### Respuestas

- **¿Quién produce?** Dos funciones `addLog` gemelas (repo y GSM), cada una con su propia instancia Room-DAO-wrapped; las lambdas `log=` inyectan el mismo canal en `BoostSessionManager` (2 instancias: la del repo y la de GSM), watchdog y ResourceGovernor.
- **¿Quién consume?** Únicamente `LogsScreen`. El `viewModel.logs` es el único lector de `logsFlow` (CONFIRMADO por grep; ningún otro consumidor en app/src).
- **¿Utilidad operacional para el usuario?** **INFERIDO: moderada.** Registra la historia real del ciclo (boost ON/OFF, baseline 34 keys, restore verificado, errores de recovery, fallos de Shizuku). Es la única visibilidad de "qué hizo la app" que no requiere adb. En las investigaciones forenses y en la validación de R1 la DB de logs se usó como canal de evidencia en device (CONFIRMADO por uso en `R1-IMPLEMENTATION-2026-09-13.md`).
- **¿Necesaria para funcionamiento interno?** **No como dependencia.** El write path es lateral (fire-and-forget); el core no lee la tabla para decidir nada (CONFIRMADO: `logsFlow` solo alimenta UI).
- **Dependencias de la UI:** `viewModel.logs` exclusivamente. `viewModel.clearLogs()` existe y llama `repository.clearLogs()` (Room) — **sin ningún botón que lo invoque** (CONFIRMADO por grep en MainActivity/ui: 0 llamadas) → cadena muerta para la UI.
- **¿Qué se rompería si desapareciera solo la UI?** Nada del core. Quedarían: la tabla Room y los dos `addLog` escribiendo (logs acumulándose sin lector), `clearLogs()` y `logsFlow` huérfanos. Costo real = espacio de la tabla (LIMIT 200 mitiga). **CONFIRMADO por estructura de dependencias.**

---

## 5. Tab 4 — Diagnóstico

### Cadena completa — CONFIRMADO

```
DiagnosticScreen (1092)
  ├─ report      = viewModel.getDiagnosticReport()
  │                └→ repository.getDiagnosticReport() (:467): "FSM: … Shizuku: … Boost: …"  (3 líneas, StateFlows vivos)
  ├─ shizukuReport = viewModel.getShizukuDiagnosis(context)
  │                └→ ShizukuExecutor.diagnose(context) (:248): checkState() + API version  (2 líneas)
  └─ Botón "ACTUALIZAR DIAGNÓSTICO" → re-ejecuta ambos
```

### Respuestas

- **¿Qué muestra?** Dos monospace-boxes negros (reporte FSM/boost y estado Shizuku) + botón refresh.
- **¿De dónde proviene?** StateFlows ya existentes + `ShizukuExecutor.checkState()`. Cero consultas nuevas, cero comandos privilegiados.
- **¿Qué acciones ejecuta?** Ninguna de mutación. Es 100% observabilidad de bajo costo.
- **¿Qué es útil al usuario?** **INFERIDO:** solo la línea "Shizuku connected/State" (diagnóstico del permiso más frecuente). El resto (FSM, Boost active) es lenguaje de desarrollador.
- **¿Qué es herramienta de diagnóstico/desarrollo?** Practicamente todo el tab; los textos (`FSM: READY`) no están pensados para un usuario final.
- **Dependencias:** `getDiagnosticReport`, `getShizukuDiagnosis`. Nota: `GameBoostRepository.getShizukuDiagnosis()` (:495, 11 LOC con thermal/mobilador/active-game) **no tiene ningún llamador** — la ViewModel salta directo a `ShizukuExecutor.diagnose` (CONFIRMADO por grep).
- **¿Qué se rompería si desapareciera solo la UI?** Nada. Quedarían 3 funciones huérfanas (~20 LOC) y `ShizukuExecutor.diagnose` sin consumidores directos (aunque es utilidad genérica del executor). **CONFIRMADO.**

---

## 6. Núcleo funcional (post-R1) — propietarios de estado y escritura

```
UI (MainActivity + GameBoostViewModel)            ← solo acciones y proyección
   ↓ (llamadas directas + StateFlows)
GameBoostRepository (fachada, singleton)          ← init/recovery, wiring, setOverlayRequested
   ↓
GameSessionManager (FSM + lifecycle boost)        ← fsmState, _simulatedGame, isBoostActive
   │      ↑ entrada: GameDetector.set模拟... (ver abajo)     ↑ toggleBoost manual (Tab 1)
   ↓
BoostSessionManager + BoostSessionStore           ← SSOT persistida (files/boost_session.json, schema v2)
   ↓                guard C4: markActiveIfApplying()
Optimizers (SystemTweaks/Network/Touch/Power/Ram) ← comandos settings
   ↓
ShizukuExecutor (+RishExecutor)                   ← ÚNICO canal privilegiado; recordApplied() aquí
   ↓ (resultado observado)
FloatingPanelManager                              ← show/hide SOLO desde GameBoostService (C5, CONFIRMADO por diff)

Detección:  UnifiedAccessibilityService ──propone──> onForegroundGameExitHint → pokePoll
            GameDetector (poll) ──ÁRBITRO──> decide entradas/salidas reales → GSM
```

**Puntos de escritura de estado (CONFIRMADO):**
- Estado de sesión persistente: solo `BoostSessionStore` vía `BoostSessionManager` (guard de estado desde R1).
- `isBoostActive`/FSM: `GameSessionManager` (autoridad única desde R1; a11y ya no escribe salidas).
- Overlay: solo el observador del servicio (+ hide explícito en `handleStop`, mismo archivo — `ec07246`).
- Prefs: `PreferenceManager` (toggles, serviceRunning, dpi/pointer) — escrituras distribuidas en repo/GSM/UI (INFERIDO: sigue siendo el punto más disperso).
- Room: profiles (UI CRUD), games (solo seed), logs (2 productores).

---

## 7. Qué cambió estructuralmente con R1 (impacto en el inventario anterior)

| Cambio | Antes (inventario 09-13) | Ahora |
|--|--|--|
| Detección ×2 | dos dueños de salida; a11y ejecutaba salidas | **CONFIRMADO:** a11y propone, polling árbitro; exit-hint + `pokePoll`; cache-sync (`notifyForegroundGame`) y splash cross-check |
| Restore ×4 | 4 orquestaciones | **PARCIAL:** `triggerExitWithHysteresis` + `restoreSettings` convergen en `performRestore(reason)`; recovery de arranque y `handleStart` mantienen sus propios flujos (queda para R2, fuera de R1) |
| markActive zombi | timer huérfano 8 s | **CONFIRMADO:** `applySettleJob` cancelable (3 puntos) + guard en SSOT (`markActiveIfApplying`) — doble defensa |
| Overlay ×4 owners | 4 escritores | **CONFIRMADO:** escritor único (servicio); UI/repos declaran `setOverlayRequested` |
| Watchdogs ×2 / Split GSM-MainActivity | pendientes | **sin cambios** (R2–R5 pendientes) |

Efecto neto en tamaño: +186 LOC producción (especialmente GameDetector +64 y repo +34). La complejidad de *decisión* bajó (una autoridad de salida), aunque el LOC subió por guardas y defensas.

---

## 8. Código posiblemente prescindible (clasificación SIN eliminar)

### 8.1 Muerto — CONFIRMADO (0 referencias en app/src, verificado por grep + CodeGraph callers)

| Elemento | Ubicación | Nota |
|--|--|--|
| `HysteresisFilter.kt` (36 LOC, archivo entero) | `touch/` | Cero referencias en main y tests |
| `repository.runBootOptimizer()` / `runDexOptimize()` | repo:462-463 | 0 llamadores. Ojo: `PowerOptimizer.bootOptimizer()` podría tener llamador interno — **NO DETERMINADO** a nivel de método; el wrapper repo está muerto |
| `repository.forceReconnect` (`ShizukuExecutor`) | ShizukuExecutor:255 | 0 llamadores |
| `repository.getShizukuDiagnosis()` (11 LOC) | repo:495 | La VM usa `ShizukuExecutor.diagnose` directo; esta versión más completa quedó huérfana |
| Cadena UI-muerta: `VM.clearLogs`→`repo.clearLogs` | VM:82, repo:475 | sin botón que la invoque |
| `VM.refreshMetrics()` | VM:113 | 0 llamadores UI |
| `VM.simulateGameLaunch` / `VM.simulatedGame` | VM:76,29 | 0 llamadores UI (la detección real viene de detector/servicio) |
| `repo.addGame`/`clearGames` + `GameDao.addGame/clearAll` | repo:425-443 | solo seed escribe la tabla; sin llamadores externos |

### 8.2 Vivo pero de diagnóstico/desarrollo (candidato a reubicar si se quitan Tabs 3/4)

- `LogsScreen` + `logsFlow` + tabla `logs` (producir sí; exponer en UI, decisión pendiente) — CONFIRMADO productores, INFERIDO valor de usuario moderado.
- `DiagnosticScreen` + `getDiagnosticReport` + `ShizukuExecutor.diagnose` — observabilidad pura.
- `FSM_DIAG` addLog DEBUG en GSM (:432) — ruido de desarrollo dentro del canal de logs del usuario.

### 8.3 Infraestructura necesaria (NO tocar)

ShizukuExecutor(+Rish), BoostSession{Manager,Store,State}, GameSessionManager, GameDetector, UnifiedAccessibilityService, optimizers ×5, ProfileManager, SystemMonitor, DependencyState, FloatingPanelManager, GameBoostService, ServiceWatchdogReceiver, WatchdogManager (salud/healthStatus que la Tab 1 muestra), PreferenceManager, BootReceiver, ShizukuServiceConnection, Room profiles/games.

### 8.4 Requieren investigación antes de decidir (NO DETERMINADO)

1. Consumo real de cada AdvancedToggle de Tab 2 (persistidos pero impacto core sin auditar).
2. Toggles decorativos del `CreateProfileDialog` (`onCheckedChange = {}`) — ¿placebo a limpiar o paridad con ProfileManager? (INFERIDO: placebo).
3. ¿Se llama internamente a `PowerOptimizer.bootOptimizer()`? (el wrapper repo está muerto; el método quizá no).
4. Volumen/frecuencia real de escritura de logs en una sesión larga (impacto Room/IO) — medible en device.
5. Relación `ResourceGovernor` ↔ pref `isDeepSleepEnabled` (¿el toggle de Tab 2 gobierna su receiver de screen on/off?).

---

## DECISIÓN PENDIENTE

1. **¿Qué valor aporta actualmente Tab 3?**
   Historial operativo legible (boost, restores, errores) — la única "memoria" visible sin adb. **INFERIDO:** valor usuario moderado-bajo, valor de desarrollo/forense alto (fue canal de evidencia en R1). Infraestructura de producción (2 `addLog`) es independiente de la UI y no debería desaparecer con ella.

2. **¿Qué valor aporta actualmente Tab 4?**
   Bajo para usuario (texto de desarrollador), útil solo la línea de estado Shizuku. Cero acciones de mutación. Es diagnosticable de 10 líneas sobre StateFlows ya existentes.

3. **¿Qué debería conservarse aunque eliminemos esas Tabs?**
   Productores de log (repo.addLog/GSM.addLog + lambdas `log=`) — son el registro interno y alimentan Log.d; `ShizukuExecutor.diagnose` (utilidad del executor); `DependencyState`/`healthStatus` (ya visibles en Tab 1); toda la infraestructura de §8.3.

4. **¿Qué debería eliminarse de la UI? (candidatos, sin implementar)**
   Tab 3 y Tab 4 como pantallas + sus entradas de navegación + miembros VM muertos o huérfanos (`logs`, `clearLogs`, `refreshMetrics`, `simulateGameLaunch`, `simulatedGame`, `getDiagnosticReport`, `getShizukuDiagnosis`) + wrappers muertos del repo (`clearLogs`, `runBootOptimizer`, `runDexOptimize`, `getShizukuDiagnosis`, `forceReconnect`) + `HysteresisFilter.kt`. Alternativa a eliminar: fusionar una versión mínima (últimos N eventos + estado Shizuku) dentro de Tab 1.

5. **¿Qué NO debería tocarse?**
   Todo §8.3; el guard C4 y `applySettleJob` (R1 recién validado en device); el escritor único del overlay; el pipeline de detección a11y-propone/poll-árbitro; Room profiles/games.

6. **¿Qué información falta antes de implementar la reorganización?**
   (a) Consumo real de los AdvancedToggles (§8.4-1); (b) verificación de `PowerOptimizer.bootOptimizer` interno; (c) volumen real de logs en sesión larga; (d) decidir el destino del historial de eventos si muere Tab 3 (¿nada? ¿mini-feed en Tab 1?); (e) confirmar si algo externo (tests de instrumentación planeados, `testTag`) depende de las pantallas a remover; (f) aceptación de producto: ¿los logs son feature de usuario o solo herramienta de desarrollo?

---
*Fin del inventario. Sin cambios de código, sin commits. La decisión se toma con esta evidencia sobre la mesa.*

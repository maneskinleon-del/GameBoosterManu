# 🏛️ AUDITORÍA TÉCNICA — GameBoost Pro

> **Fecha:** Agosto 2026 (actualizado)
> **App:** Android/Kotlin — Optimizador de rendimiento para juegos móviles (Shizuku + AccessibilityService)

---

## 📋 Changelog de Fixes Aplicados (Julio — Agosto 2026)

| # | Bug | Severidad | Estado | Archivos modificados |
|---|-----|-----------|--------|---------------------|
| — | Fixes previos (Julio 2026) | — | ✅ Ver abajo | — |
| **11** | Perfil FF MOUSE: pointer speed inconsistente (level 7→10) | 🟡 Medio | ✅ CORREGIDO | `ProfileManager.kt` |
| **12** | Perfil FF MOUSE: doble aplicación para mappers | 🟡 Medio | ✅ CORREGIDO | `GameBoostRepository.kt` |
| **13** | Perfil FF MOUSE: toggleMobilador() redundante | 🟢 Bajo | ✅ CORREGIDO | `GameBoostRepository.kt` |
| **14** | `GAMES_LIST` hardcodeada → JSON + Room DB | 🔴 Crítico | ✅ CORREGIDO | `GameEntity.kt`, `GameDao.kt`, `default_games.json`, `AppDatabase.kt`, `GameBoostRepository.kt` |
| **15** | CPU usage simulado (Random) → real vía /proc/stat | 🟢 Bajo | ✅ CORREGIDO | `GameBoostRepository.kt` |
| **16** | Random en GPU y temperatura | 🟢 Bajo | ✅ CORREGIDO | `GameBoostRepository.kt` |
| **17** | Sin fallback cuando Shizuku no está conectado | 🟡 Medio | ✅ CORREGIDO | `GameBoostRepository.kt` |
| **18** | App se cierra al abrir Free Fire (LMK mata servicio) | 🔴 Crítico | ✅ CORREGIDO | `RamManager.kt`, `GameBoostRepository.kt`, `GameBoostService.kt` |
| **19** | `echo 1 > /proc/sys/vm/drop_caches` causa lags y kills del LMK | 🔴 Crítico | ✅ CORREGIDO | `RamManager.kt`, `FloatingPanelManager.kt` |
| **20** | Choreographer FrameCallback innecesario en Main Thread (cada frame) | 🟡 Medio | ✅ CORREGIDO | `GameBoostRepository.kt` |
| **21** | `cmd activity set-process-limit -1` sin fallback (llenaba logs) | 🟢 Bajo | ✅ CORREGIDO | `GameBoostRepository.kt` |
| **22** | Watchdog térmico demasiado agresivo (45°C cambiaba perfil) | 🟡 Medio | ✅ CORREGIDO | `WatchdogManager.kt` |
| **23** | Governor path solo probaba un layout de sysfs | 🟡 Medio | ✅ CORREGIDO | `GameBoostRepository.kt`, `ProfileManager.kt` |
| **24** | Servicio no se reiniciaba automáticamente al ser matado | 🔴 Crítico | ✅ CORREGIDO | `GameBoostService.kt`, `ServiceWatchdogReceiver.kt` (nuevo), `BootReceiver.kt` (nuevo) |
| **25** | Prioridad de notificación IMPORTANCE_LOW insuficiente anti-LMK | 🟡 Medio | ✅ CORREGIDO | `GameBoostService.kt` |
| **26** | DNS no se aplicaba (escaping bug en RESTORE_COMMANDS) | 🟡 Medio | ✅ CORREGIDO | `NetworkOptimizer.kt` |
| **27** | Optimizaciones de red no se aplicaban al cambiar de perfil | 🟡 Medio | ✅ CORREGIDO | `GameSessionManager.kt` |
| **28** | Indicador de perfil activo poco distintivo | 🟢 Bajo | ✅ CORREGIDO | `MainActivity.kt` |
| **29** | Boost queda activo al cerrar juego con perfil manual | 🔴 Crítico | ✅ CORREGIDO | `GameSessionManager.kt` |
| **30** | Perfil manual no se reaplica al reabrir juego | 🔴 Crítico | ✅ CORREGIDO | `GameSessionManager.kt`, `PreferenceManager.kt` |
| **31** | Notificación sin contexto al ocultar overlay | 🟡 Medio | ✅ CORREGIDO | `GameBoostService.kt` |
| **32** | Optimización de batería mata servicio en Xiaomi | 🔴 Crítico | ✅ CORREGIDO | `MainActivity.kt`, `AndroidManifest.xml` |
| **33** | Init timeout insuficiente en dispositivos lentos (GameDetector nunca arranca) | 🔴 Crítico | ✅ CORREGIDO | `GameBoostRepository.kt` |

### Fixes previos (Julio 2026)

| # | Bug | Severidad | Estado |
|---|-----|-----------|--------|
| **1** | `hysteresisJob` sin protección de concurrencia | 🔴 Crítico | ✅ CORREGIDO |
| **2** | GAME_ACTIVE sin verificar Shizuku | 🔴 Crítico | ✅ CORREGIDO |
| **3** | Reflection insegura en ShizukuExecutor | 🔴 Crítico | ✅ CORREGIDO |
| **4** | Dos servicios de accesibilidad duplicados | 🔴 Crítico | ✅ CORREGIDO |
| **5** | `manualOverrideActive` sin `@Volatile` | 🔴 Crítico | ✅ CORREGIDO |
| **6** | `hasBackup` sin protección de concurrencia | 🟡 Medio | ✅ CORREGIDO |
| **7** | `checkExternalDevices()` cada 2s (polling) | 🟡 Medio | ✅ CORREGIDO |
| **8** | RECOVERING sin timeout absoluto | 🟡 Medio | ✅ CORREGIDO |
| **9** | `ignoredPackages` duplicado e incompleto | 🟡 Medio | ✅ CORREGIDO |
| **10** | restoreMobiladorSettings() con defaults hardcodeados | 🟢 Bajo | ✅ CORREGIDO |

---

## 1. Race Conditions y Concurrencia

### 🔴 `hysteresisJob` sin protección de acceso concurrente ✅ CORREGIDO

**Fix:** `AtomicReference<Job?>` con `getAndSet()` garantiza cancelación + limpieza atómica.

### 🔴 `manualOverrideActive` sin `@Volatile` ✅ CORREGIDO

### 🟡 `_externalDevicesConnected` modificado desde dos corrutinas

**Estado: 🟡 Mitigado** — Solo se escribe desde su propio loop de 20s y desde `toggleForceScrcpyMode()`.

---

## 2. Máquina de Estados (FSM)

### 🔴 Transición `DEGRADED` / `RECOVERING` → `GAME_ACTIVE` sin verificar Shizuku ✅ CORREGIDO

### 🟡 `RECOVERING` sin timeout absoluto ✅ CORREGIDO

**Fix:** `ABSOLUTE_RECOVERY_TIMEOUT = 300000L` (5 min) en `WatchdogManager.kt`.

### 🟢 BAJO — `INITIALIZING` sin timeout de salida ✅ CORREGIDO

**Fix:** `withTimeout(10s)` con catch de `TimeoutCancellationException` y fallback a `FsmState.READY`. Los monitores lanzados desde `startMonitoringLoop()` sobreviven al timeout gracias a `SupervisorJob`.

---

## 3. Lógica de Detección (Accessibility + Repository)

### 🟡 MEDIO — `contains("tencent")` → `knownTencentGames.startsWith()`

**Estado: ✅ MITIGADO** — El `contains("tencent")` original fue reemplazado por `knownTencentGames.any { packageName.startsWith(it) }` con 5 packages específicos. Solo queda `contains("freefire")` que es intencional para capturar variantes regionales.

### 🟢 BAJO — `contains("freefire")` podría capturar apps no-juego

**Estado: ❌ PENDIENTE** — `packageName.contains("freefire")` también detectaría apps como "com.example.freefirewallpaper". Bajo riesgo pero podría refinarse.

---

## 4. Manejo de Errores

### 🟡 MEDIO — Comandos shell con sintaxis peligrosa ✅ CORREGIDO

**Fix:** Se agregaron funciones `sanitizeShellArg()` (GameBoostRepository) y `sanitizeForShell()` (ProfileManager) que filtran caracteres peligrosos (`; & | ` $ ( ) { } [ ] < > ! # ~ % * ? ' " \`). Se aplican en:
- `getProcessPid()` — sanitiza packageName antes de `pidof` (vector crítico del AccessibilityService)
- `applyHighPriorityOptimizations()` — sanitiza pid y mask
- `ProfileManager.applyProfile()` — sanitiza governor, refreshRate, animationScale

Solo permitidos: `[a-zA-Z0-9._-/]` (GameBoostRepository) y `[a-zA-Z0-9._-]` (ProfileManager).

### 🟢 BAJO — TouchOptimizer.restore() no verifica Shizuku

**Estado: ❌ PENDIENTE** — Si Shizuku no está conectado, `restore()` falla silenciosamente.

### 🟢 BAJO — RamManager con delays secuenciales

**Estado: ❌ PENDIENTE** — Revisar si los delays en cascada son necesarios.

---

## 5. Persistencia y Consistencia de Estado

### 🟡 MEDIO — StateFlow vs SharedPreferences sin resincronización

**Estado: ❌ PENDIENTE** — Aunque los toggles guardan en ambos lados y se cargan desde SharedPrefs en init, no hay un patrón unificado. Con el tiempo, los valores pueden desincronizarse si se añaden nuevos settings sin seguir el patrón.

---

## 6. Rendimiento y Batería

### 🟡 checkExternalDevices() cada 2 segundos ✅ CORREGIDO

**Fix:** Separado en loop de 20s.

### 🟡 monitorAimButton() recrea corrutina en cada evento ✅ CORREGIDO

---

## 7. Mantenibilidad

### 🔴 `GAMES_LIST` hardcodeada → Base de datos + JSON ✅ CORREGIDO

**Fix:** Migrado a sistema externo:
- `res/raw/default_games.json` — lista por defecto (17 juegos)
- `GameEntity` / `GameDao` — entidad Room con packageName (PK) y displayName
- `_gamesCache` — `ConcurrentHashMap` en memoria para O(1)
- API pública: `addGame()`, `removeGame()`, `restoreDefaultGames()`

### 🟢 Nombres de juegos en inglés

**Estado: ❌ PENDIENTE** — Los displayNames están en inglés. Para usuarios hispanohablantes, podría traducirse.

---

## 8. Ranking Actualizado

| # | Problema | Severidad | Estado |
|---|----------|-----------|--------|
| **1** | `hysteresisJob` sin protección de concurrencia | 🔴 | ✅ CORREGIDO |
| **2** | GAME_ACTIVE sin Shizuku check | 🔴 | ✅ CORREGIDO |
| **3** | Reflection insegura en ShizukuExecutor | 🔴 | ✅ CORREGIDO |
| **4** | Dos servicios duplicados | 🔴 | ✅ CORREGIDO |
| **5** | `manualOverrideActive` sin `@Volatile` | 🔴 | ✅ CORREGIDO |
| **6** | `hasBackup` sin protección de concurrencia | 🟡 | ✅ CORREGIDO |
| **7** | `checkExternalDevices()` cada 2s | 🟡 | ✅ CORREGIDO |
| **8** | RECOVERING sin timeout absoluto | 🟡 | ✅ CORREGIDO |
| **9** | `ignoredPackages` duplicado e incompleto | 🟡 | ✅ CORREGIDO |
| **10** | restoreMobiladorSettings() defaults | 🟢 | ✅ CORREGIDO |
| **11** | Pointer speed perfil FF MOUSE (7→10) | 🟡 | ✅ CORREGIDO |
| **12** | Doble aplicación perfil FF MOUSE para mappers | 🟡 | ✅ CORREGIDO |
| **13** | toggleMobilador redundante en simulateGameLaunch | 🟢 | ✅ CORREGIDO |
| **14** | GAMES_LIST hardcodeada | 🔴 | ✅ CORREGIDO |
| **15** | CPU usage simulado (Random) | 🟢 | ✅ CORREGIDO |
| **16** | Random en GPU y temp | 🟢 | ✅ CORREGIDO |
| **17** | Sin fallback cuando Shizuku no conectado | 🟡 | ✅ CORREGIDO |
| **18** | App se cierra al abrir Free Fire (LMK) | 🔴 | ✅ CORREGIDO |
| **19** | drop_causes causa lags y kills | 🔴 | ✅ CORREGIDO |
| **20** | Choreographer FrameCallback en MainThread | 🟡 | ✅ CORREGIDO |
| **21** | cmd activity set-process-limit-1 | 🟢 | ✅ CORREGIDO |
| **22** | Watchdog térmico agresivo | 🟡 | ✅ CORREGIDO |
| **23** | Governor path incompleto | 🟡 | ✅ CORREGIDO |
| **24** | Sin autoreinicio al ser matado | 🔴 | ✅ CORREGIDO |
| **25** | Prioridad notificación baja | 🟡 | ✅ CORREGIDO |
| **26** | DNS no se aplicaba (escaping bug) | 🟡 | ✅ CORREGIDO |
| **27** | Optimizaciones de red no en cambio de perfil | 🟡 | ✅ CORREGIDO |
| **28** | Indicador perfil activo poco distintivo | 🟢 | ✅ CORREGIDO |

### Pendientes más urgentes ahora:

| # | Problema | Archivo | Severidad | Esfuerzo |
|---|----------|---------|-----------|----------|
| **1** | `INITIALIZING` sin timeout de salida | `GameBoostRepository.kt` | 🟢 Bajo | ✅ CORREGIDO |
| **2** | Comandos shell con sintaxis peligrosa | `GameBoostRepository.kt`, `ProfileManager.kt` | 🟡 Medio | ✅ CORREGIDO |
| **3** | StateFlow vs SharedPreferences sin resincronización | `GameBoostRepository.kt` | 🟡 Medio | ⏱ 2-3 hrs |
| **4** | TouchOptimizer.restore() no verifica Shizuku | `TouchOptimizer.kt` | 🟢 Bajo | ⏱ 30 min |
| **5** | RamManager con delays secuenciales | `RamManager.kt` | 🟢 Bajo | ⏱ 30 min |
| **6** | Nombres de juegos en inglés | `default_games.json` | 🟢 Bajo | ⏱ 15 min |
| **7** | Watchdog batching de Android 13 (7 min en vez de 30s) | `ServiceWatchdogReceiver.kt` | 🟡 Medio | ⏱ 1 hr |
| **8** | Governor no escribible en kernel ZTE (Permission denied) | — | 🟡 Medio | ✅ Mitigado (skip + mensaje) |
| **9** | Temperatura CPU no legible (permisos kernel) | — | 🟢 Bajo | ❌ Bloqueado por kernel |

---

## 9. Verificación de Fixes Previos

### Punto 4: `_simulatedGame` contaminado con apps no-juego ✅ CORREGIDO

### Punto 5: `setActiveProfile()` no cancela `hysteresisJob` ✅ CORREGIDO

### Punto 6: Sistema "pegado" en modo juego al entrar a WhatsApp ✅ CORREGIDO

Múltiples fixes aplicados:
- Duplicación de servicios eliminada (unificación)
- Concurrencia de `hysteresisJob` resuelta (`AtomicReference`)
- Shizuku check antes de GAME_ACTIVE
- `manualOverrideActive` con `@Volatile`
- Logging de diagnóstico activo en 6 checkpoints (tag `FSM_DIAG`)
- Perfil FF MOUSE con pointer speed correcto (100% = raw 7)
- Fallback vía Settings API cuando Shizuku no conectado

---

*Fin del documento de auditoría.*

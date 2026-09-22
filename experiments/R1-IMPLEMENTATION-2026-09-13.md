# R1 Implementation Report — Boost lifecycle authority

**Date:** 2026-09-14 (implementation session) · **Branch:** `r1/lifecycle-authority` (off `main @ f5ba334`)
**Plan:** `experiments/R1-IMPLEMENTATION-PLAN-2026-09-13.md`
**Inputs:** `OVERLAY-DISAPPEAR-FORENSIC-2026-09-13.md`, `INVENTORY-REORG-2026-09-13.md`

| | |
|--|--|
| **Commit inicial (C1–C6)** | `bc30384` — "R1: boost lifecycle authority — a11y proposes, polling disposes" |
| **Commit final (fixes de device)** | `0a1fc4f` — "R1 device validation fixes: arbiter cache-sync + splash cross-check" |
| **Diffstat total** | 8 files, **+395 / −59** (7 prod + 1 test file de 159 LOC) |
| **Working tree** | limpio (solo untracked: experiment docs previos) |

---

## 1. Implementación por cambio

| Cambio | Archivos | Contenido real |
|--|--|--|
| **C1** | `UnifiedAccessibilityService.kt`, `GameBoostRepository.kt` | La rama no-juego de `onForegroundAppChanged` ya NO llama `onForegroundAppLost()`; ahora llama `onForegroundGameExitHint(pkg)` (log + `pokePoll`). Los 3 paquetes transitorios evidenciados (`com.zjx.ztezscreenshot`, `com.android.vending`, `cn.nubia.gameassist`) añadidos al ignore list del a11y. Doc-comment de arquitectura en `handleWindowStateChanged`. |
| **C2** | `GameDetector.kt`, `GameBoostRepository.kt` | `pokePoll()`: sondeo inmediato único (correra `pollForegroundApp` fuera de ciclo). Regla launcher-exit: si `lastForegroundApp` era juego y el poll lee launcher → salida real. 2 paquetes transitorios añadidos al ignore list del detector (vending ya estaba). **+2 fixes de device (commit `0a1fc4f`):** `notifyForegroundGame()` (sync de caché del árbitro en entradas vía a11y) y cross-check de focus shell antes de declarar salida por launcher (UsageStats es eventualmente consistente durante el splash). |
| **C3** | `GameSessionManager.kt` | `applySettleJob: Job?` retiene el `delay(8000){markActive}`. Cancelación en: `toggleBoost(false)`, `triggerExitWithHysteresis()` (primera línea), rollback por baseline fallido. El Job usa `markActiveIfApplying()` (doble defensa). |
| **C4** | `BoostSessionManager.kt` | `markActive()` con guarda: solo `APPLYING`/`BASELINE_CAPTURED` → `ACTIVE`; otherwise WARN + reject. `markActiveIfApplying()` = API con nombre para el Job diferido. El zombie `RESTORED→ACTIVE` queda imposible en la SSOT. |
| **C5** | `GameBoostRepository.kt`, `GameBoostService.kt`, `MainActivity.kt` | `_overlayRequest: MutableStateFlow<Boolean?>` (null = seguir boost; false = usuario ocultó). El observador del servicio combina `isBoostActive` + `overlayRequest` (`request ?: boost`) y es el ÚNICO caller de `FPM.show()/hide()`. MainActivity: switch-ON/OFF y `onResume` usan `setOverlayRequested()`; el show directo de `onGameDetected` se eliminó (el reset a null dispara la reevaluación del observador). |
| **C6** | `GameSessionManager.kt` | `performRestore(reason)` converge las secuencias de OFF manual (`restoreSettings`) y exit de juego. Los extras exclusivos del exit (thermalservice reset, disableGameMode, Mobilador, ram clean) quedan en el call-site. Boot-recovery y `restoreSavedSettings` del service NO tocados (R2). |

## 2. Discrepancias plan↔código y decisiones tomadas

1. **Tests T1–T7 no eran implementables como JVM units** (GSM/Detector/Repo acoplados a Android: Room, SharedPreferences, `startForegroundService`, singletons; sin lib de mocks; Robolectric roto según el propio proyecto). **Decisión del usuario: "adapted split"** → JVM tests para el núcleo de C4 (criterio #5); el resto se valida en device con el repro forense. Se creó `GameLifecycleR1Test.kt` (5 tests) usando el patrón `FakeDevice` de `BoostSessionManagerTest`.
2. **Launcher-exit era necesario**: el ignore list del poll suprime el launcher → al quitarle autoridad de salida al a11y (C1), la salida más común (HOME) quedaba sin árbitro. Se añadió la regla launcher-tras-juego en el poll (dentro del mandato Q3 "polling = árbitro de salida").
3. **Dos gaps hallados SOLO en device** (commit `0a1fc4f`): (a) caché de dedup del árbitro desincronizada cuando la entrada llega por a11y y el juego dura <1 ciclo de polling → boost pegado en ON; (b) UsageStats stale durante el splash de arranque frío → falsa salida por launcher justo tras entrar. Ambos corregidos con ~30 LOC en el árbitro.

## 3. Tests y build

| Verificación | Resultado |
|--|--|
| `GameLifecycleR1Test` (5 tests: zombie RESTORED→ACTIVE imposible, APPLYING→ACTIVE normal, RECOVERY_REQUIRED ignorado, sin sesión no-op, BASELINE_CAPTURED tolerado) | **5/5 PASS** |
| Suite completa (`testDebugUnitTest`) | **39/39 PASS** (18 BoostSession + 5 R1 + 16 resto) |
| `compileDebugKotlin` | ✅ |
| `assembleDebug` | ✅ (instalado en ZTE, `lastUpdateTime` 2026-09-13 21:31/22:0x) |

## 4. Validación en dispositivo (ZTE Z2352N, Shizuku server vivo, APK `bc30384`+`0a1fc4f`)

Cronología local del device: 21:31–22:05. Evidencia: JSON del store (`run-as com.example cat files/boost_session.json`), Room DB (databases/gameboost_database), `dumpsys window`, `settings get`.

| # | Criterio / escenario | Resultado | Evidencia |
|--|--|--|--|
| 1 | **Entrada FF correcta** (a11y + rep auto) | ✅ PASS | DB 21:34:07 "Game detected: Free Fire" → 21:34:08 "Boost mode: ON" → baseline 34 keys (21:34:10) → settle a ACTIVE 21:34:18 (store `"state":"ACTIVE"`). |
| 2 | **Ventana OEM transitoria ≠ salida** | ✅ PASS | Entrada 21:34:07 con transitorias activas (gameassist visible en WMS durante FF); **71 s en FF sin ninguna salida falsa** (ayer: 4 s). Store ACTIVE durante todo el período; solo un exit real posterior. |
| 3 | **Polling-only sin Accessibility (Q3)** | ✅ PASS | Ciclo 21:40:37–21:41:13 completo (entrada → ON → baseline → salida real → restore) con el servicio a11y NO ligado (`Bound services` sin com.example; sin fila "connected" en ese proceso). Latencia mayor (poll 30 s bg + histeresis 5 s) = diseño. |
| 4 | **Salida real → restore** | ✅ PASS | HOME 21:35:14 → "Salida confirmada" 21:35:18 → "Restore verificado: 34 keys (3 restauradas, 30 ya-ok, 1 conflicto conservado)" 21:35:20 → `RESTORED`, `auto_sync=1`. Segundo ciclo 22:01:12→22:01:19 (~7 s), overlay removido (0 ventanas). |
| 5 | **Sin markActive huérfano (zombie)** | ✅ PASS (JVM + device) | JVM: test T5 núcleo (secuencia forense exacta). Device: ciclos con exit a t<8s (21:55:41→RESTORED 21:55:52 con solape settle/restore real; 21:40 exit a t+8s con restore completo y sin revival). La guarda C4 registró transiciones legítimas únicamente. |
| 6 | **Autoridad única de salida** | ✅ PASS | Todos los exits observados (21:35:18, 21:41:12, 21:55:5x, 22:01:12) provienen del path del detector (`Salida de juego confirmada` vía `onGameExited`). Cero `onForegroundAppLost` directos desde a11y (la ruta fue eliminada del código). |
| 7 | **Overlay = proyección** | ✅ PASS | Con boost ACTIVE en FF (22:00:34): nuestra ventana `APPLICATION_OVERLAY` registrada (`mViewVisibility=0x0`, `mObscured=false`). Tras salida real: 0 ventanas overlay. Único writer = observador del servicio (verificable por grep: FPM.show/hide solo en `GameBoostService.kt`). |

**Bonus validado:** recovery post-install (store ACTIVE huérfano de un kill → limpiado a IDLE al relanzar; luego ciclo nuevo limpio) — interacción R1+recovery correcta.

## 5. Antes / después (síntoma original)

| | Antes (forense 2026-09-13, main `f5ba334`) | Después (R1, `0a1fc4f`) |
|--|--|--|
| Entrada FF | Boost ON 13:58:04 | Boost ON (igual) |
| Ventana transitoria OEM | `onForegroundAppLost` inmediato | **hint log-only** + pokePoll |
| 4 s después | Falsa "Salida confirmada" | **Nada** (poll no confirma: FF sigue) |
| 8 s después | `hide()` + restore (overlay desaparece) | Overlay visible, boost ACTIVE, restore verificado solo por settle legítimo |
| Exit real (HOME) | (no llegaba a probarse) | ~5–7 s: poll confirma → restore → overlay removida → RESTORED |
| Zombie RESTORED→ACTIVE | Observado 13:58:14 | **Imposible** (guarda SSOT C4 + cancelación C3) |

## 6. Hallazgos colaterales (fuera de R1, para la cola)

1. **Config de a11y inestable en el device**: MacroDroid re-gestiona `enabled_accessibility_services` y re-añade componentes; el clear vía `settings put` no es definitivo. Esto afecta testabilidad (flapping de binds) pero no es bug de GameBoost.
2. **Repositorio singleton puede duplicarse tras process death** (fila "Init timed out" 21:41:01 mientras el repo original seguía vivo; comportamiento preexistente observado también en sesiones anteriores). Candidato a R2+.
3. **Falso positivo del test C**: con app en background, el poll de 30 s + lookback de 2 s de UsageStats pueden no ver una visita de <18 s a un juego. Diseño existente (no R1); el producto real usa la señal a11y como path rápido.
4. `.corrupt` residue del purge v1 sigue en `files/` (preexistente, inofensivo).

## 7. Conclusión

**R1 COMPLETO según la regla final:** código implementado (2 commits, solo R1) + tests 39/39 + build ✅ + checkpoint limpio (tree sin modificaciones) + validación de dispositivo de los 7 criterios con el repro forense. Los dos fixes adicionales (`0a1fc4f`) surgieron de la validación en device y permanecen estrictamente dentro del alcance C2 (robustez del árbitro).

**No se inició R2** ni ningún otro ítem de la cola. El dispositivo quedó en su estado original (a11y deshabilitado, store limpio, sin boost activo).

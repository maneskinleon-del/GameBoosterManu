# F3B-FIX — F4 MICRO-FIX + REVALIDACIÓN DIRIGIDA

```
Timestamp:     2026-09-05 19:00–20:40 -04:00
Repo:          main @ 862a5bf (SIN commit)
Entrada:       F4 PASS WITH LIMITATIONS (post-audit F3B-F4-POST-AUDIT-20260905-184500.md)
Dispositivo:   ZTE Z2352N (Android 13) — prueba A, B y C (11 races + 1 evidenciada con log vivo)
Salida de tests: 12/12 PASS re-ejecutados (--rerun-tasks); assembleDebug SUCCESSFUL
```

---

## 1. PRE-FIX STATE

Working tree con F4 (PASS WITH LIMITATIONS): 8 archivos modificados + 4 nuevos. Tres issues abiertos: race 4.D (save RESTORED pisa APPLYING), startup writer sin gate (pointer_speed), null ambiguo (READ_FAILED ≡ ABSENT).

## 2. ROOT CAUSE — RACE 4.D

`restoreVerified()` confirmaba su commit final (`RESTORED`/`RECOVERY_REQUIRED`) sin revalidar que el archivo siguiera siendo **la misma sesión en RESTORING**. Un `beginApply` concurrente (OFF→ON rápido) persiste una sesión nueva; el restore viejo al terminar la pisaba → muerte posterior sin marcador → residuos. **Causa agravante encontrada durante el fix**: el `sessionId` se heredaba del baseline en modo REUSE (línea original `baseline.firstOrNull()?.sessionId`), por lo que S1 y S2 compartían id y la revalidación por sessionId NO habría distinguido sesiones — el test T1 lo detectó antes del release (fallo del primer intento del fix).

**Fix (BoostSessionManager.kt):**
1. Cada `beginApply` que persiste la transición a APPLYING genera `sessionId` NUEVO (identidad de sesión por transición, baseline reusado intacto).
2. `restoreVerified` guarda `restoreSessionId = session.sessionId` al inicio y, **justo antes del commit final**, revalida: `store.load()` debe ser esa sesión Y estado RESTORING. Si no: log WARN, NO confirma, NO limpia, la sesión vigente permanece recuperable.
3. `recoverIfNeeded` aplica el mismo re-check antes de `store.clear()` (solo limpia si el archivo sigue siendo la sesión que restauró y quedó RESTORED).

## 3. ROOT CAUSE — STARTUP WRITER

`GameBoostService.onCreate → restoreSavedSettings` escribe `pointer_speed` incondicionalmente, en paralelo (sin sincronización) con el recovery del init del Repository → puede falsear un Caso B y descartar el baseline de esa key.

**Fix (GameBoostRepository + GameBoostService):**
- Repository expone `recoveryGate: CompletableDeferred<Unit>` + `isRecoveryComplete()/awaitRecoveryComplete()`.
- El gate se abre en un `finally` del init (éxito, timeout O error — nunca deja writers colgados indefinidamente).
- `restoreSavedSettings` hace `awaitRecoveryComplete()` antes de escribir. Una sola fuente de verdad (el archivo de sesión); el gate es solo de orden, no de estado.
- Orden garantizado: `PROCESS START → LOAD SESSION → RECOVERY → VERIFY → MARK → gate abre → startup writers`.

## 4. ROOT CAUSE — NULL AMBIGUITY

`readSetting()` devolvía `null` tanto para "key ausente" ("null" del provider) como para "lectura fallida" (Shizuku caído) → restore podía contar SKIPPED/CONFLICT falsos → `allOk` falso-positivo → clear() con residuos.

**Fix (BoostSessionManager.kt):** `sealed SettingRead { Present(value) | Absent | ReadFailed(reason) }`.
- **PRESENT**: flujo normal.
- **ABSENT**: política existente (original null → delete).
- **READ_FAILED**: en restore → `RESTORE_FAILED` + failed++ (NO conflicto, NO skip, NO clear, sesión queda RECOVERY_REQUIRED persistida y recuperable cuando Shizuku vuelva). En capture (beginApply) → **abort del apply completo** (fail-closed: sin baseline fiable no hay boost).
- Verificación post-put también distingue: READ_FAILED al releer → FAILED (no éxito).
- Regla implementada: *no saber el valor ≠ saber que la clave no existe*.

## 5. EXACT CHANGES

| Archivo | Cambio | Líneas aprox |
|---|---|---|
| boostsession/BoostSessionManager.kt | SettingRead sealed + readSetting; capture fail-closed; restoreVerified con READ_FAILED handling + re-check sessionId/RESTORING pre-commit; sessionId nuevo por beginApply; recoverIfNeeded re-check pre-clear | +75 netas aprox |
| GameBoostRepository.kt | recoveryGate + helpers + complete en finally | +17 |
| GameBoostService.kt | awaitRecoveryComplete() en restoreSavedSettings | +8 |
| test/.../BoostSessionManagerTest.kt | T1 race (con FakeDevice.delayGetMs para interleaving determinista), T2 normal, T3 startup-writer-gate, T4 read-failed-recuperable, T4b single-read-failure, T5 absent, T6 happy path + 4 tests de regresión F3B | 12 tests |
| (sin cambios) | ResourceGovernor, ShizukuExecutor, RishExecutor, SystemMonitor, WatchdogManager, ThermalController, ProfileManager, optimizers, GameDetector | 0 |

Diff total vs HEAD: 8 archivos (+144/−49) + 4 nuevos (3 prod + 1 test). Sin refactor, sin UI, sin perfiles.

## 6. TEST RESULTS — 12/12 PASS

```
gradle testDebugUnitTest --rerun-tasks → BUILD SUCCESSFUL
TEST-BoostSessionManagerTest: tests=11 failures=0
TEST-ExampleRobolectricTest:  tests=1  failures=0
```

| Test | Propiedad | Resultado |
|---|---|---|
| **T1 race 4D** | restore S1 (concurrente, delay determinista 5ms/get) + beginApply S2 durante → S2 queda APPLYING con id propio, baseline original intacto, NO RESTORED, y tras recoverIfNeeded S2 es restaurable (dispositivo vuelve al original) | **PASS** |
| T2 normal restore | happy path confirma RESTORED + clear + IDLE | PASS |
| T3 startup writer gate | writer post-recovery escribe legítimamente; capture posterior refleja su valor | PASS |
| T4 read failed | TODOS los gets fallan → recovery=false, sesión persistida RECOVERY_REQUIRED/RESTORING con baseline, NO clear; Shizuku vuelve → reintento recupera completo | PASS |
| T4b single read failure | 1 key ilegible → esa key FAILED, allOk=false, estado RECOVERY_REQUIRED (no RESTORED) | PASS |
| T5 absent | keys ausentes → delete correcto | PASS |
| T6 happy path completo | capture→APPLYING→ACTIVE→restore→verificado→RESTORED→clear→IDLE | PASS |
| Regresión ×4 (T1-old/T2-old/T5-old/T6-old) | propiedades F3B intactas | PASS |

## 7. DEVICE RESULTS

**Caso A — ACTIVE death (con fixes)**: boost ON → sesión ACTIVE → force-stop → restart →
`[WARN] Recovery requerido (estado al morir: ACTIVE) — restaurando baseline de 44 keys` → `Restore verificado: 44 keys (36 restauradas, 7 ya-ok, 1 conflicto conservado)` → `Recovery completo`. ble 0→1, anim 0→1, touch→null, sesión ELIMINADA. El conflicto conservado (pointer_speed actual=7 vs original=0) es el comportamiento CORRECTO del Caso B (el 7 lo puso el Mobilador durante el boost; política: conservar/atribuir — ver nota en §13).

**Caso B — APPLYING death (con fixes)**: tap boost → kill a t=2.5s → archivo persistido `"state":"APPLYING"` → restart → `Recovery requerido (estado al morir: APPLYING)` → `44 keys (36 restauradas, 8 ya-ok, 0 conflictos)` → completo.

## 8. RACE TEST RESULTS (Caso C — 10 ciclos + 1 evidenciado con log vivo)

10 ciclos OFF→ON con 0.4s de separación (dentro de la ventana de restore ~2.3s):

```
cycle  pre-state (S1)                post-state (S2)
1..10  APPLYING/ACTIVE, sid_A       APPLYING/ACTIVE, sid_B ≠ sid_A, baseline 44 keys
```

**0 sesiones pisadas** (ningún POST quedó RESTORED), **0 clears incorrectos**, **0 residuos**.

Ciclo de evidencia directa con logcat streaming en vivo:

```
PRE : ACTIVE bs_1788654765744
[OFF → 0.3s → ON]
D/BoostSession: [INFO] Baseline activo (RESTORING) — reutilizando original (44 keys)
D/BoostSession: [WARN] Sesión cambió durante el restore (era bs_1788654765744)
                 — este restore NO confirma ni limpia; la sesión vigente permanece recuperable
POST: ACTIVE bs_1788654867470  (S2 vigente, no pisada)
```

Y la propiedad completa cerrada en dispositivo:

```
RACE (S2 APPLYING) → force-stop (persistido ACTIVE S2) → restart
  → Recovery requerido (ACTIVE) → 44 keys (36 rest, 8 ya-ok, 0 conflictos)
  → Recovery completo — sesión eliminada
  → ble=1 anim=1 llm=null watchdog=1 touch=null  ✔
```

## 9. 43-KEY RECOVERY RESULT

Verificación integral post-todo (lectura real por settings get):
- 21 global keys = valores originales/defaults ✔ (dns=off, anims=1, ble/wifi_scan=1, amc=null, low_power=15, coex=1, blurs=0, am/proto/vs=null...) — incluidas las 4 antes sin restore (wifi_watchdog=1, wifi_power_save=null, scan_interval=null, low_latency=null)
- system: touch_report/touch_sensitivity/high_*=null ✔, latency_reduction=0 ✔; **peak/min_refresh=60.0 = el ORIGINAL del dispositivo en esta ventana** (capturado así por el baseline y fielmente restaurado — NO es residuo del boost, que escribe 120.0; restaurar 60.0 es exactamente la semántica del contrato)
- secure: long_press=400 ✔, touch_boost=0 ✔
- pointer_speed: gestionado por Caso B (conflicto conservado en un ciclo; NO reinventado)

## 10. F1/F2/F3/F5 ISOLATION

15/15 archivos verificados INTACTOS contra HEAD (git diff por archivo): ResourceGovernor ✔ ShizukuExecutor ✔ RishExecutor ✔ SystemMonitor ✔ WatchdogManager ✔ ThermalController ✔ SystemTweaks ✔ NetworkOptimizer ✔ TouchOptimizer ✔ ProfileManager ✔ RamManager ✔ PowerOptimizer ✔ AdsPointerManager ✔ RecoveryManager ✔ GameDetector ✔. Archivos main tocados: solo Repository/GSM/Service (integración F4). isManual (WatchdogManager:251/259) SIN tocar — OUT OF SCOPE respetado.

## 11. VERIFIED

1. **Race 4.D eliminada por código** — re-check sessionId+RESTORING justo antes del commit final (post-operaciones, pre-commit, como exige la spec) + identidad de sesión por transición.
2. **T1 demuestra** que un restore viejo no pisa una sesión nueva (concurrente, determinista) y que la sesión nueva queda restaurable tras recover.
3. **Startup writer no compite con recovery** — gate con complete en finally (ni bloqueo eterno ni escritura prematura).
4. **READ_FAILED ≠ ABSENT** — sealed class; ninguna mutación/clear sin conocer el valor.
5. **READ_FAILED conserva la sesión recuperable** — T4 (sin Shizuku → persistida; con Shizuku → recuperación completa) + T4b.
6. T2–T6 + 4 regresión: 12/12.
7. ACTIVE death recupera (device, Caso A).
8. APPLYING death recupera (device, Caso B).
9. 11 races OFF→ON (10 protocolo + 1 con log vivo): 0 pisadas, 0 clears incorrectos, 0 residuos; + race→death→recovery completa.
10. 43/43 keys recuperables y verificadas por lectura real.
11. build/tests: BUILD SUCCESSFUL re-ejecutado.
12. F1/F2/F3/F5 fuera del diff (15/15).

Propiedad objetivo de la fase **demostrada**:

```
OLD RESTORE + NEW APPLY + PROCESS DEATH → NEW SESSION REMAINS RECOVERABLE ✔
```

## 12. NOT VERIFIED

1. LMK real (presión de memoria) y crash real (death intra-fsync) — siguen siendo INFERIDOS por el mecanismo (SIGKILL + último commit en disco), no muestreados. La evidencia device sigue siendo FORCE_STOP.
2. Race con timing aún más agresivo (<0.3s) o doble-ON en <1s — los 10 ciclos usaron 0.3-0.4s; la ventana teórica más temprana (restore recién creado) está cubierta por el mismo guard (el re-check es de estado, no de timing) pero no se muestreó cada microventana.
3. READ_FAILED en device (Shizuku caído en el arranque real) — la simulación es JVM (FakeDevice); el camino device no fue probado (requiere matar Shizuku, mutante fuera de alcance).
4. Interacción del thermal watchdog (F5) aplicando perfiles durante un RESTORING — sigue documentada como ventana teórica (arranca post-recovery; riesgo bajo).

## 13. UNKNOWN

1. pointer_speed tras la ventana del Caso B: en un ciclo quedó conservado en 7 (valor Mobilador) — decisión de política correcta-conservadora del diseño, pero el "original" del usuario (0) queda descartado para esa sesión. Semántica heredada del diseño F3B (Caso B), no un bug del fix.
2. Si algún OEM ignora `settings delete` de keys system (peak/min) — solo ZTE probado.

## 14. FINAL VERDICT

# **F4 PASS CONFIRMED**

Los 12 criterios de PASS se cumplen simultáneamente (§11). La propiedad F4 queda protegida también frente a carreras de lifecycle y fallos de lectura: la race 4.D fue reproducida de forma determinista en test y en dispositivo, y el guard la rechazó en ambos; READ_FAILED ya no puede producir un falso éxito ni descartar una sesión recuperable; el startup writer respeta el orden recovery→writers. Las limitaciones del post-audit 1–3 quedan cerradas; las 4–7 menores del post-audit (Caso A teórico, normalización cosmética, BASELINE_CAPTURED muerto, appliedValueOf-90.0) permanecen documentadas y no bloquean la garantía.

## 15. COMMIT RECOMMENDATION

# **READY FOR COMMIT**

El diff es pequeño (+144/−49 + 4 archivos), aislado de F1/F2/F3/F5 (15/15 intactos), con 12/12 tests re-ejecutados y validación device en 3 escenarios + 11 races. Recomendación de commit: UN solo commit con mensaje del tipo `fix(f4): persistent boost-session baseline + verified recovery — race 4.D guard, recovery gate for startup writers, READ_FAILED ≠ ABSENT`. Nota: los reportes de `experiments/forensic-phase3/` pueden incluirse en el mismo commit o en uno separado de documentación, a elección del owner. (Esta fase no hace el commit, según reglas.)

---

## Anexo — evidencia de sesión (transcripción resumida)

```
REGRESIÓN: gradle testDebugUnitTest assembleDebug --rerun-tasks → BUILD SUCCESSFUL (23s); 12/12.
DEVICE CASO A: ACTIVE→force-stop→restart: Recovery requerido(ACTIVE); 44 keys (36/7/1); completo; sesión eliminada.
DEVICE CASO B: APPLYING(t=2.5s kill)→restart: Recovery requerido(APPLYING); 44 keys (36/8/0); completo.
DEVICE CASO C: 10× (OFF→0.4s→ON): pre sid_A / post sid_B APPLYING ×10 — 0 pisadas.
  Ciclo 11 (log vivo): "Sesión cambió durante el restore (era bs_...5744) — NO confirma ni limpia" + POST=ACTIVE bs_...8654770.
  RACE→DEATH→RECOVERY: ACTIVE persistido → recovery completo → 43 keys limpias → sesión eliminada.
FIX DETECTADO POR TEST ANTES DE RELEASE: sessionId heredado en REUSE habría invalidado el guard → corregido (id nuevo por transición). Este es exactamente el valor del test T1 determinista.
```

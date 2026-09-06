# F3B/F4 — DISEÑO TÉCNICO (Partes 1–7)
# Persistencia de baseline + recuperación tras process death

Baseline repo: 862a5bf · main · pre-implementación

---

## PARTE 1 — AUDITORÍA DE MUTACIONES (43 recursos)

Clasificación por reversibilidad/verificabilidad (V=verificable con `settings get`):

### Settings.System/Global/Secure escritos por la app (39 keys)

| # | Key | NS | Escritor(es) | Valor aplicado | Backup RAM hoy | Persist | Restore hoy | V | Clasificación |
|---|---|---|---|---|---|---|---|---|---|
| 1 | ble_scan_always_enabled | G | SystemTweaks:60 | 0 | sí | NO | default 1 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 2 | wifi_scan_always_enabled | G | ST:61 | 0 | sí | NO | default 1 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 3 | bluetooth_disabled_profiles | G | ST:62 | 1 | sí | NO | default 0 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 4 | overlay_display_devices | G | ST:65 | 0 | sí | NO | default 1 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 5 | debug.hwui.renderer | G | ST:66 | skiavk | sí | NO | delete | ✔ | REVERSIBLE_AND_VERIFIABLE (null = delete) |
| 6 | debug.hwui.overdraw | G | ST:67 | false | sí | NO | false | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 7 | debug.hwui.show_dirty_regions | G | ST:68 | false | sí | NO | false | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 8 | debug.sf.disable_backpressure | G | ST:69 | 1 | sí | NO | 0 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 9 | debug.sf.latch_unsignaled | G | ST:70 | 1 | sí | NO | 0 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 10 | disable_window_blurs | G | ST:71 | 1 | sí | NO | 0 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 11 | debug.sf.disable_hwc_vds | G | ST:72 | 1 | sí | NO | 0/delete | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 12 | auto_sync | G | ST:75 | 0 | sí | NO | 1 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 13 | window_animation_scale | G | ST:76 (+otros) | 0 | sí | NO | 1.0 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 14 | transition_animation_scale | G | ST:77 | 0 | sí | NO | 1.0 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 15 | animator_duration_scale | G | ST:78 | 0 | sí | NO | 1.0 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 16 | send_action_app_error | G | ST:79 | 0 | sí | NO | 1 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 17 | activity_manager_constants | G | ST:84 | max_cached_processes=128 | sí | NO | valor original O delete | ✔ | REVERSIBLE_AND_VERIFIABLE (delete = null) |
| 18 | low_power_trigger_level | G | ST:86 | 0 | sí | NO | 15 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 19 | adaptive_connected_voice_enabled | G | ST:88 | 0 | sí | NO | 1 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 20 | debug.gl.msaa | G | ST:92 (cond.) | 4 | sí | NO | 0 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 21 | private_dns_mode | G | NetOpt:51 | hostname | sí | NO | original?:off | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 22 | private_dns_specifier | G | NetOpt:52 | dns.google | sí | NO | original?:"" | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 23 | private_dns_spec | G | NetOpt:55 | "" (legacy) | NO | NO | "" | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 24 | wifi_watchdog_on | G | NetOpt:57 | 0 | NO | NO | **NINGUNO** | ✔ | REVERSIBLE_AND_VERIFIABLE (gap de restore) |
| 25 | wifi_scan_interval_ms | G | NetOpt:58 | 300000 | NO | NO | **NINGUNO** | ✔ | REVERSIBLE_AND_VERIFIABLE (gap) |
| 26 | wifi_power_save | G | NetOpt:61 | 0 | NO | NO | **NINGUNO** | ✔ | REVERSIBLE_AND_VERIFIABLE (gap) |
| 27 | wifi_low_latency_mode | G | NetOpt:62 | 1 | NO | NO | **NINGUNO** | ✔ | REVERSIBLE_AND_VERIFIABLE (gap) |
| 28 | wifi_bt_coexistence | G | NetOpt:63 | 0 | sí | NO | original?:1 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 29 | zen_mode | G | GSM:230 | 2 | sí | NO | original?:0 | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 30 | pointer_speed | S | TouchOpt:33/ProfileMgr/AdsPointer/mobilador | dinámico | sí | NO | original (si backup ok) | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 31 | touch_sensitivity | S | TouchOpt:37 | 100 | NO | NO | **NINGUNO** | ✔ | REVERSIBLE_AND_VERIFIABLE (gap) |
| 32 | multi_touch_sensitivity | S | TouchOpt:38 | 100 | NO | NO | **NINGUNO** | ✔ | REVERSIBLE_AND_VERIFIABLE (gap) |
| 33 | long_press_timeout | SEC | TouchOpt:42 | 120/300 | sí | NO | original | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 34 | accessibility_display_magnification_enabled | SEC | TouchOpt:45 | 0 | sí | NO | original | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 35 | accessibility_autoclick_enabled | SEC | TouchOpt:46 | 0 | sí | NO | original | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 36 | touch_latency_reduction | S | TouchOpt:50 | 1 | NO | NO | 0 hardcode | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 37 | touch_boost_enabled | SEC | TouchOpt:51 | 1 | NO | NO | 0 hardcode | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 38 | high_touch_sensitivity_enable | S | TouchOpt:52 | 1 | NO | NO | **NINGUNO** | ✔ | REVERSIBLE_AND_VERIFIABLE (gap) |
| 39 | high_touch_polling_rate_enable | S | TouchOpt:53 | 1 | NO | NO | **NINGUNO** | ✔ | REVERSIBLE_AND_VERIFIABLE (gap) |
| 40 | swipe_up_to_switch_apps_enabled | SEC | TouchOpt:55 | 0 | sí* | NO | *backup parcial | ✔ | REVERSIBLE_AND_VERIFIABLE |
| 41 | edge_prevent_mistouch_enabled | SEC | TouchOpt:56 | 0 | NO | NO | **NINGUNO** | ✔ | REVERSIBLE_AND_VERIFIABLE (gap) |
| 42 | touch_report_rate | S | TouchOpt:60 | 240 | NO | NO | **NINGUNO** | ✔ | REVERSIBLE_AND_VERIFIABLE (gap) |
| 43 | peak_refresh_rate / min_refresh_rate | S | GSM:414-415, ProfileMgr:76-77 | 120.0/90.0 | NO | NO | **NINGUNO** | ✔ | REVERSIBLE_AND_VERIFIABLE (gap) |

### Recursos NO-settings (fuera del contrato V=verificable por settings get)

| Recurso | Escritor | Comentario | Clasificación |
|---|---|---|---|
| wm density | GameBoostRepository.setDpi / GameBoostService.restoreSavedSettings | La app YA persiste su valor en prefs (`custom_dpi`) y lo re-aplica — comportamiento auto-consistente | APP_ONLY (ya gestionado) |
| cmd power set-fixed-performance-mode | GSM apply/restore | No persiste en settings (runtime); se restaura con comando off | REVERSIBLE_BUT_NOT_VERIFIABLE (get no soportado en ZTE — DEVICE OBSERVED) |
| cmd power set-adaptive-power-saver-enabled | GSM | idem | REVERSIBLE_BUT_NOT_VERIFIABLE |
| cmd thermalservice reset | GSM exit | idem | REVERSIBLE_BUT_NOT_VERIFIABLE |
| dumpsys deviceidle force-idle | ResourceGov/PowerOpt | F1 (fuera de alcance) — runtime, no persistente | OUT OF SCOPE (F1) |
| am force-stop <pkg> | PowerOpt/RamManager | irreversible (no se puede "restaurar" una app muerta) | NOT_REVERSIBLE (por naturaleza; no requiere backup) |
| renice/taskset/governor sysfs | (ya no se ejecutan / skip kernel) | — | OUT OF SCOPE |
| logcat -c / dmesg -c | SystemTweaks.clearLogs | borra evidencia del sistema | NOT_REVERSIBLE (aceptado por diseño; documentado) |
| Game Mode API (setGameState) | GSM:424-454 | runtime binder | REVERSIBLE_BUT_NOT_VERIFIABLE |

**Decisión de alcance**: el mecanismo F4 cubre las **43 settings keys** (incluidas las 12 con gap de restore — esto además cierra los gaps: wifi_watchdog_on, wifi_scan_interval_ms, wifi_power_save, wifi_low_latency_mode, touch_sensitivity, multi_touch_sensitivity, high_touch_*, edge_prevent, touch_report_rate, refresh rates). Los recursos runtime (cmd power, game mode) se restauran por comando al igual que hoy (comportamiento existente conservado); NO se les inventa verificación (get no soportado en ZTE). am force-stop/logcat-c son irreversibles y quedan explícitamente excluidos del contrato.

---

## PARTE 2 — MÁQUINA DE ESTADOS DE SESIÓN

Persistida (archivo transaccional, ver Parte 4). Estados:

```
IDLE                 → sin boost, sin baseline capturado. Estado inicial/limpio.
BASELINE_CAPTURED    → baseline persistido, NINGÚN setting modificado aún.
APPLYING             → comandos de apply en vuelo (algunos settings ya modificados).
ACTIVE               → boost aplicado completamente.
RESTORING            → comandos de restore en vuelo.
RECOVERY_REQUIRED    → detectado al arrancar: estado era APPLYING/ACTIVE/RESTORING al morir el proceso.
RESTORED             → baseline restaurado y verificado. Equivalente operativo a IDLE (se limpia a IDLE tras confirmar).
```

Transiciones y muerte del proceso:

| Estado al morir | Efecto al restart | Razón |
|---|---|---|
| IDLE | Nada | nada modificado |
| BASELINE_CAPTURED | Nada que restaurar (no se tocó nada) → limpiar baseline → IDLE | capture pura no muta |
| APPLYING | **RECOVERY_REQUIRED** → restaurar TODO el baseline | puede haber apply parcial |
| ACTIVE | **RECOVERY_REQUIRED** → restaurar TODO | todos modificados |
| RESTORING | **RECOVERY_REQUIRED** → reintentar restore desde baseline | restore parcial (los no restaurados siguen boosteados) |
| RESTORED | → IDLE (limpiar) | ya verificado antes de marcar |

Justificación de decisiones:
- No hay estado "DEGRADED" del boost: ese es el FSM de sesión de juego (FsmState) — ortogonal. El BoostSessionState vive su propio ciclo.
- BASELINE_CAPTURED se trata como no-peligroso: capturar no muta el dispositivo. Se limpia porque un baseline huérfano sin apply no tiene valor y evitaría un futuro capture fresco (regla Parte 3).
- RESTORING es peligroso (igual que APPLYING): si muere a mitad, parte restaurada y parte no → RECOVERY_REQUIRED re-ejecuta desde el baseline persistido (idempotente: restaurar dos veces un valor original es seguro).

## PARTE 3 — CONTRATO DEL BACKUP

Registro por setting (unidad de backup):

```json
{
  "namespace": "global" | "system" | "secure",
  "key": "ble_scan_always_enabled",
  "originalValue": "1",            // valor leído ANTES del primer apply; null = la key NO EXISTÍA (→ restore = delete)
  "capturedAt": 1693920000000,     // epoch ms
  "sessionId": "bs_1693920000000", // id de sesión de boost
  "state": "..."                   // estado de sesión al capturar
}
```

Campos adicionales justificados:
- `originalValue: null` **representa "key ausente"** (settings get devuelve "null") — crítico: en F3A vimos keys cuya ausencia es el baseline (debug.*, refresh). Restaurar "null" = `settings delete`. Esto captura el 100% de la semántica sin campo extra.
- No se guarda "valor aplicado": el restore solo necesita el original. (La verificación post-restore compara contra originalValue.)

**Regla anti-sobrescritura (crítica):**

```
captureBaseline():
    if (persistedState in [BASELINE_CAPTURED, APPLYING, ACTIVE, RESTORING, RECOVERY_REQUIRED]) {
        // ya existe baseline activo → REUSE (no recapturar: los valores actuales son boosted)
        return persistedBaseline
    }
    // IDLE o RESTORED/limpio → capturar valores actuales reales
    readAllOriginals() → persist
```

Esto implementa exactamente `NO ACTIVE BASELINE → capture; ACTIVE BASELINE → reuse` y hace imposible que un valor boosted se convierta en "original" (el bug de reaplicar perfiles que F3A/F2 detectaron en SystemTweaks.backupOriginalValues, que pisaba originales en cada setActiveProfile→apply).

## PARTE 4 — PERSISTENCIA: ELECCIÓN

Opciones evaluadas:

| Opción | Atomicidad | Sobrevive process death | Escritura | Recuperación | Multi-setting consistencia |
|---|---|---|---|---|---|
| SharedPreferences | apply() es atómico por-escritura en memoria + escritura async a disco; **no transaccional multi-key**; riesgo de escritura parcial si el proceso muere a mitad de un batch | sí | trivial | determinista | **no garantizada** |
| DataStore (Preferences) | transaccional por edit; corrige el problema de escrituras parciales | sí | corrutinas | determinista | sí (edit atómico) pero añade dependencia nueva |
| Room | transaccional (lo que buscamos) | sí | más boilerplate (entity+dao+migration) | determinista | sí |
| **Archivo JSON transaccional (write-to-temp + rename)** | **sí: rename() es atómico en POSIX/ext4/f2fs; o es el archivo completo viejo o el nuevo completo** | sí | simple | determinista | sí (un solo archivo = un commit) |

**Elección: archivo JSON transaccional** en `filesDir/boost_session.json` con protocolo write-temp→fsync→rename.

Justificación objetiva:
1. **Atomicidad**: la operación crítica es el commit del baseline completo. Con un solo archivo + `rename` atómico, un process death en cualquier punto deja el estado anterior íntegro o el nuevo íntegro — nunca un baseline a medias (que sería peor que no tener backup: restauraría valores incompletos).
2. **Sobrevivencia**: disco interno privado (`Context.getFilesDir`), sobrevive LMK/crash/force-stop (no `cacheDir`, que el sistema puede purgar).
3. **Esritura/lectura simple**: ~43 registros, un objeto. Sin esquema, sin migraciones, sin DAO.
4. **Recuperación determinista**: un JSON parseable o falla entero (→ sin baseline → Caso C de Parte 6 → no inventar).
5. Room sería adecuado si el backup creciera o necesitara queries; para una máquina de estados de un solo documento es infra innecesaria — **Room es innecesario para este alcance** (queda dicho explícitamente).
6. SharedPreferences sería suficiente SOLO si el estado cupiera en escrituras individuales atómicas; el requisito de consistencia multi-setting (estado + baseline en un commit) lo descarta: SharedPreferences.commit() por-clave puede dejar estado="ACTIVE" con baseline a medio escribir si muere entre puts. (Si alguien argumenta "aplicar en una sola putString gigante" — es exactamente el archivo transaccional, pero sin control de fsync/rename. Preferimos el mecanismo explícito.)

Detalle: el JSON se escribe con `write.temp` en el mismo directorio (mismo filesystem → rename atómico) + `fsync` antes de rename (`FileDescriptor.sync()`), y `File.renameTo`. Al leer: si `boost_session.json` no existe pero sí `boost_session.temp` → el commit previo falló a mitad → se ignora el temp (estado = el último commit completo) y el temp se borra.

## PARTE 5 — RECOVERY (arranque)

```
Application/Repository init (BEFORE cualquier apply/detección de juego)
   ↓
load boost_session.json (si no parsea → tratar como sin baseline, log, mover a .corrupt)
   ↓
state ∈ {APPLYING, ACTIVE, RESTORING, RECOVERY_REQUIRED}?
   ↓ SÍ                                    ↓ NO
RECOVERY_REQUIRED                          BASELINE_CAPTURED → limpiar → IDLE
   ↓                                       IDLE/RESTORED → continuar normal
por cada registro del baseline:
   restoreWithVerification()  (Parte 7)
   ↓
¿todos VERIFIED o UNVERIFIABLE-aceptado?
   ↓ SÍ → state=RESTORED → persist → luego limpiar → IDLE
   ↓ NO → state=RECOVERY_REQUIRED persist + log ERROR (reintenta en próximo arranque)
   ↓
SOLO DESPUÉS: inicialización normal (monitores, detectores, boost)
```

**Punto de integración**: el recovery corre en el `init` de `GameBoostRepository` ANTES de `sessionManager.initialize()`/`gameDetector.start()` y antes de que cualquier toggle/apply pueda ejecutarse. Con esto se garantiza "recovery antes de aplicar de nuevo": el primer comando settings que la app puede lanzar tras un arranque con residuos es un restore, no un apply. (Los monitores solo LEEN settings; el único writer temprano era el propio boost.)

`is_running` (prefs) deja de ser la única fuente de verdad: la fuente es el estado persistido del archivo. `is_running` se mantiene por compatibilidad (watchdog) pero su semántica queda subordinada: tras RESTORED exitoso también se pone `is_running=false` (coherencia con F3A).

## PARTE 6 — SAFETY RESTORE (política por caso)

### Caso A — baseline existe Y valor actual coincide con valor aplicado conocido (o está en el set de valores boosteables)
→ **restaurar** (la mutación es nuestra).

### Caso B — baseline existe PERO el valor actual no coincide NI con el valor aplicado NI con el original
→ el usuario (u otra app) lo cambió durante la ventana boost. **Política de conflicto: NO sobrescribir; conservar el valor actual; registrar RESTORE_CONFLICT en el log y en el resultado (no cuenta como fallo bloqueante, se reporta)**. Justificación: el valor actual es la voluntad más reciente del usuario; restaurar pisaría un cambio humano deliberado. El baseline de esa key queda descartado (se elimina con el resto al terminar).

Matiz aplicado: si actual == originalValue → nada que hacer (RESTORE_VERIFIED trivial).

### Caso C — no existe baseline para una key (nunca capturada)
→ **NO inventar**. No se ejecuta ningún comando para esa key. (Cobertura: el capture cubre las 43 keys del inventario ANTES del primer apply, así que este caso solo ocurre ante writes nuevos no inventariados o baseline corrupto.)

### Caso D — restore ejecutado pero postcondition (relectura) falla
→ la key queda `RESTORE_FAILED`; el estado de sesión NO pasa a RESTORED; permanece RECOVERY_REQUIRED (se reintenta en próximo arranque o siguiente restore). Nunca se marca exitoso un restore no verificado.

### Extra — RECOVERY_UNVERIFIABLE
Para keys donde la relectura devuelva un valor que no coincida con el original PERO tampoco podamos distinguir (p.ej. OEM normaliza "1.0"→"1"): normalización de comparación (trim, "1.0"=="1" numérico cuando parseable, "null"→null). Si tras normalizar sigue sin coincidir → FAILED (conservador).

## PARTE 7 — VERIFICACIÓN DE RESTORE

Por cada key restaurada:

```
restoreCommand (put o delete)
      ↓
releer: settings get <ns> <key>   [vía el MISMO pipeline ShizukuExecutor]
      ↓
normalizar y comparar con originalValue (null == "null"/ausencia)
      ↓
RESTORE_VERIFIED | RESTORE_FAILED | (RESTORE_CONFLICT si Caso B detectado pre-comando)
```

Exit code 0 solo NO basta — explícito en el código: el resultado del comando se ignora para el veredicto; manda la relectura.

---

## NOTAS DE IMPLEMENTACIÓN (Parte 8, preview)

Archivos NUEVOS (sin tocar existentes en su lógica):
1. `manager/boostsession/BoostSessionState.kt` — enum + data classes + (de)serialización JSON manual (org.json ya usada en el proyecto).
2. `manager/boostsession/BoostSessionStore.kt` — archivo transaccional (temp+fsync+rename), load/save/clear.
3. `manager/boostsession/BoostSessionManager.kt` — captura/apply-gate/restore/verify/recovery (habla con ShizukuExecutor solo a través de comandos settings get/put/delete).

Cambios MÍNIMOS en existentes (puntos de integración):
- `GameBoostRepository.init`: llamar a `BoostSessionManager.recoverIfNeeded()` antes de sessionManager.initialize(); exponer instancia.
- `GameSessionManager.applyBoostSettings`: `boostSession.beginApply()` (capture-or-reuse + state=APPLYING) ANTES de tocar optimizers; al completar los applies → `state=ACTIVE`.
- `GameSessionManager.restoreSettings` + `triggerExitWithHysteresis` (bloque A): `boostSession.beginRestore()` → restore verificado propio (el nuevo mecanismo REEMPLAZA la escritura de anims/dns del viejo camino para las 43 keys, pero dejo los restores existentes de recursos runtime: cmd power/zen ya cubiertos) → `markRestored()`.
- SystemTweaks/NetworkOptimizer/TouchOptimizer **sin cambios de lógica**: sus apply() siguen escribiendo igual (los writers no cambian); el baseline lo captura BoostSessionManager leyendo ANTES del primer apply de la sesión. Sus restore() RAM internos quedan como capa 2 inofensiva (el nuevo mecanismo es la fuente de verdad). — Esto mantiene el diff mínimo y evita el refactor prohibido.

El "antes del primer apply" se implementa así: `beginApply()` captura (si IDLE) las 43 keys vía settings get, persiste state=APPLYING+baseline, y los optimizers continúan. Como applyBoostSettings llama touch/network/tweaks.apply() justo tras beginApply(), el orden es: capture → persist → writers corren. Si el proceso muere entre writers: estado=APPLYING+baseline completo → RECOVERY al arrancar.

`wm density`/pointer_speed de usuario: EXCLUÍDOS del contrato (ya tienen persistencia propia de la app — APP_ONLY, Parte 1). `zen_mode` INCLUIDO (43 keys) — su backup RAM actual se conserva; el persistente es aditivo.

T7 (isManual poisoning de watchdog térmico): PERTENECE a F5 — NO se implementa aquí. **Dependencia documentada**: el fix real es `setActiveProfile(id, isManual=false)` en WatchdogManager:251/259; se registra como pendiente F5.

Tests (Parte 9): infra mínima = añadir `testImplementation(libs...)` faltantes SOLO los que necesita un test puro-JVM (junit ya está; para BoostSessionStore/State hace falta solo junit + temporales; para el Manager con ShizukuExecutor → NO testeable JVM sin mocks → diseño permite inyectar un `CommandRunner` función → tests T1-T6 usan un runner fake con mapa en memoria; CERO Robolectric necesario → NO se arregla la suite rota de Robolectric aquí, solo se añade lo mínimo para compilar Y correr los tests nuevos. Los tests viejos rotos (ExampleRobolectricTest/GreetingScreenshotTest) se dejan como están (fuera de alcance; su fix completo es tarea de infra aparte) PERO bloquean compileDebugUnitTest... → decisión: excluirlos temporalmente NO es posible sin tocarlos; MÍNIMO invasivo = añadir las deps robolectric/roborazzi que faltan para que compile (es exactamente "infraestructura mínima para ejecutar tests relacionados con F4" — sin reconstruir la suite). Se evalúa costo: 2 deps al version catalog + 4 líneas build.gradle.

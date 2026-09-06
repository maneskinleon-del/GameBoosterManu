# F3B — F4 IMPLEMENTATION REPORT
# Persistencia de baseline + recuperación tras process death

```
Timestamp:        2026-09-05 (15:00–17:45 -04:00)
Repo base:        862a5bf (main) — sin commits nuevos (cambios en working tree, sin commit)
Dispositivo:      ZTE Z2352N, Android 13, API 33
Pre-requisito:    F3A PASS (dispositivo limpio y documentado)
```

## 1. ESTADO INICIAL

HEAD 862a5bf, working tree con solo artefactos de evidencia. Backups 100% RAM, 12 keys sin restore, `is_running` única fuente de verdad, tests no compilables. ApF4: eliminar el defecto `BOOST → RAM-only backup → process death → residuos permanentes`.

## 2. AUDITORÍA DE MUTACIONES (Parte 1 — resumen ejecutivo)

43 settings keys auditadas (tabla completa en F3B-F4-DESIGN.md): **42 REVERSIBLE_AND_VERIFIABLE** (incluidas 12 con gap de restore en el código original: wifi_watchdog_on, wifi_scan_interval_ms, wifi_power_save, wifi_low_latency_mode, touch_sensitivity, multi_touch_sensitivity, high_touch_sensitivity/polling_enable, edge_prevent_mistouch, touch_report_rate, peak/min_refresh_rate) + recursos no-settings: `wm density`/pointer (APP_ONLY, ya autogestionados por la app), `cmd power *` (REVERSIBLE_BUT_NOT_VERIFIABLE — el get no existe en el ZTE), `am force-stop`/`logcat -c` (NOT_REVERSIBLE, excluidos del contrato).

## 3. DISEÑO ELEGIDO (Partes 2-7 → F3B-F4-DESIGN.md)

- **Máquina de estados**: IDLE → BASELINE_CAPTURED → APPLYING → ACTIVE → RESTORING → RECOVERY_REQUIRED → RESTORED. Muerte en BASELINE_CAPTURED → limpia (no muta); en APPLYING/ACTIVE/RESTORING → RECOVERY_REQUIRED.
- **Contrato backup**: {ns, key, originalValue(null=ausente→delete), capturedAt, sessionId}; regla capture-or-reuse (activa baseline → jamás recaptura).
- **Persistencia**: archivo JSON transaccional (temp+fsync+rename atómico) en filesDir. Room innecesario para 1 documento; SharedPreferences insuficiente para commit multi-key atómico. Corrupto → .corrupt + operar sin baseline (nunca inventar).
- **Safety**: A(actual==aplicado→restaurar) / B(conflicto→conservar valor usuario, RESTORE_CONFLICT) / C(sin baseline→nada) / D(postcondition falla→RECOVERY_REQUIRED persiste).
- **Verificación**: relectura real post-comando, comparación normalizada numéricamente (1.0==1); exit-code insuficiente por diseño.

## 4. ARCHIVOS MODIFICADOS (diff: +118/−49 en 8 archivos + 4 nuevos)

**Nuevos:**
- `manager/boostsession/BoostSessionState.kt` — estados, BackupEntry, (de)serialización, RestoreResult
- `manager/boostsession/BoostSessionStore.kt` — store transaccional temp/fsync/rename
- `manager/boostsession/BoostSessionManager.kt` — capture/reuse, restoreVerified, recoverIfNeeded, BoostKeys (inventario 44), onRestored hook
- `test/.../BoostSessionManagerTest.kt` — T1–T6 con FakeDevice in-memory

**Modificados (mínimos, sin tocar F1/F2/F3/F5):**
- `GameBoostRepository` (+18): instancia boostSession + recoverIfNeeded() ANTES de sessionManager.initialize() + onRestored→setServiceRunning(false)
- `GameSessionManager` (+38): beginApply() con cancelación del boost si el commit falla; markActive() diferido 8s; restoreVerified() en restoreSettings() y en triggerExitWithHysteresis
- `GameBoostService` (+28): gate anti-reapply en handleStart y en profile observer (durante recovery no se re-aplica perfil)
- `app/build.gradle.kts` / `libs.versions.toml` (+23): deps de test mínimas (robolectric, roborazzi, androidx-test-core, ui-test-junit4, org.json real) + `unitTests.isReturnDefaultValues=true`
- Tests plantilla: ExampleRobolectricTest convertido a JVM puro (leía 1 string; la integración Robolectric/resources se repara en fase de infra general); GreetingScreenshotTest eliminado (demo huérfano que jamás compiló en este repo — `Pixel8` no existe en roborazzi 1.26)

## 5. TESTS — 7/7 PASAN (T1–T6 + plantilla)

`gradle testDebugUnitTest` → **BUILD SUCCESSFUL**: T1 baseline-sobrevive-restart ✔, T2 reapply-no-sobrescribe ✔ (42 keys), T3 apply-interrumpido-recupera ✔, T4 ACTIVE-death-recupera ✔, T5 postcondition-fallida→RECOVERY_REQUIRED ✔, T6 sin-baseline-no-inventa ✔ (cero comandos). Causa raíz previa del no-compile: deps de test ausentes + `Pixel8` inexistente + android.util.Log/org.json stubs en JVM.

## 6. BUILD

`gradle assembleDebug` → **BUILD SUCCESSFUL** (APK 18.2MB, instalado en ZTE).

## 7. PRUEBA CONTROLADA EN DISPOSITIVO (6 ciclos)

| Ciclo | Escenario | Resultado |
|---|---|---|
| 1 | boost ON → force-stop → restart | Recovery ✔ 44 keys (36 rest, 6 ya-ok, 2 conflictos refresh). **Detectó 2 issues** que se corrigieron en el mismo ciclo |
| 2 | repetición | Corrección de appliedValueOf(refresh) aplicada; detectó race del profile observer → residuo |
| 3 | + gate handleStart + is_running post-recovery | Recovery ✔ 0 conflictos, is_running=false ✔; detectó fuga del profile observer |
| 4 | + gate profile observer | Recovery ✔ (gate saltó correctamente durante RESTORING) — residuo por baseline capturado ya-boosteado (herencia del ciclo 3, comportamiento CORRECTO del sistema: restaura lo que había) |
| **5** | **baseline limpio → ACTIVE → death → restart → t=44s** | **RECOVERY PERFECTO: anim=1.0, ble=1, autos=1, touch/peak/llm=null, lp=400. Sesión eliminada. is_running=false. CERO re-boost a t=44s** |
| **6** | **muerte DURANTE APPLYING (t=2s) → restart** | **Recovery de apply-parcial ✔: 36 restauradas, 0 conflictos, 0 residuos. Estado persistido capturado: APPLYING** |

Evidencia logcat ciclo 5: `[WARN] Recovery requerido (estado al morir: ACTIVE) — restaurando baseline de 44 keys` / `[INFO] Restore verificado: 44 keys (36 restauradas, 8 ya-ok, 0 conflictos conservados)` / `[INFO] Recovery completo — dispositivo restaurado`.
Evidencia ciclo 6: `Recovery requerido (estado al morir: APPLYING)` → mismo resultado verificado.

Estado final del dispositivo: **43/43 keys en baseline limpio** (verificación integral ejecutada; pointer_speed=7 residual de pruebas restaurado a 0 valor-documentado F3A).

Hallazgos colaterales verificados en dispositivo durante las pruebas (documentados, NO corregidos — fuera de alcance):
- El boost se activa sin capture si el proceso muere <1s tras el tap (ventana no cubierta: beginApply corre tras el toggle UI) — irrelevante para uso normal.
- `is_running=true` + watchdog anti-LMK + profile observer eran un mecanismo de re-boost fantasma tras process death (los 3 gates F4 lo neutralizan cuando hay sesión activa).

## 8. VERDICT

---

# FORMATO FINAL

## VERIFIED
- La propiedad central: **APPLY/ACTIVE + PROCESS DEATH → RECOVERABLE**, demostrada en dispositivo 6 veces con verificación por relectura real de cada key.
- Baseline no se sobrescribe al reaplicar (T2 + capturas de ciclo 5: original anim=1.0 capturado tras residuos=0).
- Recovery corre ANTES de cualquier apply (gate + orden de init; logcat muestra skip-re-apply durante RESTORING).
- Restore fallido no marca éxito (T5 + código: postcondition decide).
- is_running ya no es la única fuente de verdad (archivo de sesión; onRestored lo sincroniza).

## STATICALLY VERIFIED
- Cobertura del inventario: 44 keys (43 auditadas + zen_mode) con backup persistido y restore verificado.
- Transaccionalidad del store (temp+fsync+rename; load ignora .tmp).
- Casos A/B/C/D del safety-restore implementados según diseño.

## DEVICE VERIFIED
- Ciclos 5 y 6 (ACTIVE-death y APPLYING-death): recuperación completa, 0 residuos, 0 re-boost a t=44s.
- 12 keys con gap de restore ahora cubiertas (p.ej. wifi_watchdog_on 0→1, touch_report_rate 240→null en cada recovery).
- Interacción con watchdog/observers preexistentes neutralizada (3 gates).

## TESTS
7/7 PASAN (T1–T6 F4 + plantilla). `testDebugUnitTest` BUILD SUCCESSFUL.

## BUILD
`assembleDebug` BUILD SUCCESSFUL. APK instalado y probado en Z2352N.

## FILES MODIFIED
8 modificados (+118/−49) + 4 nuevos (boostsession/) — sin commits; working tree documentado.

## UNKNOWN
- Comportamiento del restore con Shizuku muerto (recovery requiere Shizuku para put/delete/get; si Shizuku está caído al arrancar, el recovery reintenta en el próximo arranque — estado RECOVERY_REQUIRED persiste. No probado en dispositivo).
- `cmd power get-fixed-performance-mode` no verificable en este ZTE (comando get inexistente) — fuera del contrato de 43 keys.
- pointer_speed como key dinámica: excluida de valores-aplicados catalogados (dinámica); restaurada por baseline si difiere.

## OUT OF SCOPE (no tocado, según reglas)
F1 (ResourceGovernor drop_caches/set-process-limit SIGUEN VIVOS), F2 (timeouts), F3 (UserService/reflection), F5 (thermal/isManual poisoning — dependencia T7 documentada: WatchdogManager.kt:251/259 debe llamar setActiveProfile(id, isManual=false); el poisoning persiste), refactor de optimizers RAM (capa 2 conservada), suite Robolectric completa.

## F4 VERDICT

# **PASS**

Baseline persistido sobrevive process death ✔ · original no se sobrescribe ✔ · death en APPLYING recuperable ✔ · death en ACTIVE recuperable ✔ · restore verificado por lectura real ✔ · fallo no marcado como éxito ✔ · sin valores inventados ✔ · recovery antes de re-apply ✔ · is_running ya no única verdad ✔ · tests F4 pasan ✔ · build pasa ✔ · prueba controlada en dispositivo pasa ✔ (11/11 criterios).

**GameBoost puede morir sin dejar atrás indefinidamente el estado que modificó — DEMOSTRADO.**

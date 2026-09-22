# F2/F3/F5 — DEVICE VERIFICATION & FIX ROUND (2026-09-10)

Rama:        fix/forensic-f2-f3-f5-leaks
Publicada:   424c806..90fd2d8 (fast-forward, verificado con ls-remote)
Device:      ZTE Nubia Z2352N (320344802623), Android 13, Shizuku 13.5 (adb server)
APK final:   18,462,505 bytes — sha256 a18796136a912b030e71b27646a9ecda9fb77dcd0d84adc3d3fbcccb61144025
             (Nota: sha de la APK del fix de typealiases; la del fix final incluye +6 l de DeadObject)

## Historial publicado en el PR

    424c806  PR publicado (base — INTACTA, commit ya en remoto)
    22b7871  fix: remove invalid nested typealiases (necesario: 424c806 solo NO compila)
    60a6a8f  fix(f2): sondear exitValue() cuando waitFor(timeout) no está soportado
    90fd2d8  fix(f2): fallo rápido del sondeo si el binder de Shizuku está muerto

Diff del PR verificado: solo ShizukuExecutor.kt + RishExecutor.kt (+64/−10).
Wrapper/gradlew/.gitignore verificados AUSENTES del diff (no solo de palabra).
Commit del wrapper (1f40e79, orig) quedó FUERA del PR — local only, jar ignorado.

## Las 3 regresiones cazadas en la ronda (patrón: confirmar con device, no con diff)

1. waitFor() infinito reintroducido en el catch (detectado en auditoría previa al device-run).
2. Captura parcial de Mobilador (ronda anterior).
3. **Falso-timeout Shizuku 13.5** (esta ronda): RemoteProcess lanza en CADA llamada a
   waitFor(timeout,unit) ("process hasn't exited") → drainProcess devolvía false → 118
   TIMEOUTs falsos con comandos SÍ aplicados (wifi_scan_interval_ms=300000 verificado en
   settings ground truth). Rish nunca entraba (solo cubre fallos de launch, no de wait).
   Fix: waitFor → si lanza, sondeo exitValue() cada 25ms hasta deadline → recién ahí
   timeout real + destroyForcibly. NUNCA waitFor() sin límite.
   Sub-fix: exitValue() del proxy Binder lanza IllegalArgumentException (no
   IllegalThreadStateException) → sondeo atrapa cualquier excepción como "sigue vivo";
   DeadObjectException → fast-fail con log "Binder muerto" (no confundir con timeout).

## Evidencia device

| Métrica            | Antes (424c806) | Después (90fd2d8) |
|--------------------|-----------------|-------------------|
| Shizuku OK         | 0               | 105/105           |
| TIMEOUT 8000ms     | 118             | 0                 |
| Crashes            | 0               | 0 (2 pre-fix en crash-buffer, pids viejos) |

Ejercicio real: restoreSavedSettings completa (settings get/put, dumpsys, ps).
F5: sin disparo térmico (device ~31°C board — no-op correcto); lógica verificada en código
(isNaN→return, isManual=false ×3, captura única por episodio).
F3-negativo y ciclo boost UI: NO ejecutados deliberadamente (reiniciar servidor Shizuku en
A13 requiere re-pairing manual; Switch Compose = bounds cero en accessibility → tap no fiable).

## Pendientes explícitos (fuera de este PR)

1. `Modifier.testTag("boost_switch")` en el Switch de Compose — habilita automatización UI.
2. Wrapper de Gradle para clones nuevos: *.jar en .gitignore excluyó el jar de todo el
   historial → PR propio con negación `!gradle/wrapper/gradle-wrapper.jar`.
   Restauración local del jar: `git show 1f40e79:gradle/wrapper/gradle-wrapper.jar > gradle/wrapper/gradle-wrapper.jar`
   (o regenerar con Gradle 9.3.0 cacheado en ~/.gradle/wrapper/dists/).
3. `RecoveryManager` sin caller.
4. `BoostKeys` con placebo obsoleto.
5. Validar `long_press_timeout=400` contra el ZTE real.

## VALIDACIÓN long_press_timeout (2026-09-10, post-push) — VEREDICTO: NO es placebo

Método: gestos `input swipe x y x y <D>` (presión sostenida) sobre icono Chrome del
launcher (bitpit), D controlado. Resultado binario inequívoco: D<threshold → tap (abre
Chrome); D≥threshold → menú contextual (no abre). Launcher reiniciado entre fases
(force-stop) para que ViewConfiguration re-lea el setting.

| Fase | T (setting) | Hold | Resultado | Conclusión |
|------|-------------|------|-----------|------------|
| A1   | 120         | 100ms | abre Chrome | mecánica OK (100<120) |
| A2   | 120         | 400ms | menú contextual | mecánica OK (400≥120) |
| B1   | **400**     | 300ms | **abre Chrome** | **T=400 HONRADO** (con 120 vigente habría menú) |
| B2   | 400         | 500ms | menú contextual | 500>400 ✓ |
| C1   | key AUSENTE | 300ms | abre Chrome | factory ∈ (300, …) |
| C2   | key AUSENTE | 450ms | menú contextual | factory ∈ (…, 450] |

Conclusiones:
1. El setting `secure long_press_timeout` es EFECTIVO en el ZTE Nubia Z2352N (A13):
   el framework lo honra (ViewConfiguration.getLongPressTimeout() alimenta todos los Views).
2. Factory default del ZTE ∈ (300, 450] ms — consistente con AOSP DEFAULT_LONG_PRESS_TIMEOUT=400.
3. El fallback 400 del app (GameSessionManager restore) es CORRECTO: coincide con el
   comportamiento de fábrica. Restaurar a 400 ≈ restaurar a comportamiento de fábrica.
4. El valor 120 hallado en el device era RESIDUO del app (boost), no valor de fábrica.
   OJO: toda la evidencia previa de "original=120/300" en docs F3A/F3B fue capturada
   con boost activo (contaminada). El original real es "key ausente" (=400 efectivo).
5. ~~Hallazgo para pendiente #4 (BoostKeys): :124 placeholder~~ CORREGIDO: BoostKeys:124
   es `appliedValueOf` (lo que el boost AFIRMA escribir, política Caso A/B), no un
   placeholder de key-ausente. El restore F4 siempre hace delete para ausencia (ver
   AUDIT abajo). El riesgo real de "120" es otro: ver sección AUDIT.

Estado final del device: long_press_timeout=120 (tal como se encontró). Chrome quedó
como estaba (ya corría en background antes del test).

---

## AUDIT BoostKeys/appliedValueOf (2026-09-10) — claims estáticos vs writers reales

Método: writer-sweep de las 44 keys (`grep "settings put <ns> <key>"` sin boostsession)
cruzado con appliedValueOf (BoostKeys.kt) y la política de restore (BoostSessionManager:280-354).
Semántica de restore: Caso A = current==appliedValueOf → restaurar; Caso B = ni original
ni applied → CONFLICT (se preserva). Un claim stale ⇒ el valor escrito por el boost cae
en Caso B y NO se restaura jamás (residuo garantizado).

### 1. Claim INCORRECTO con impacto funcional (prioridad ALTA)

| Key | claim | writer real | Efecto del claim stale |
|-----|-------|-------------|------------------------|
| min_refresh_rate | "120.0" | **90.0** (GameSessionManager:518) | El 90.0 escrito por el boost cae en Caso B → **min_refresh_rate NUNCA se restaura** → residuo 90.0 tras cada boost. Además el comentario cita "GSM:414-415" — líneas movidas y valor ya no coincide. |

(peak_refresh_rate claim "120.0" coincide con GSM:517 ✓; la vía ProfileManager:76-77
escribe $safeRefresh.0 dinámico — claim parcialmente stale, mitigado por ser el mismo
valor frecuente. touch/pointer_speed ya están documentados como dinámicos en else.)

### 2. Keys OBLETAS en el inventario: claim sin writer (desde limpieza placebos 424c806)

9 keys listadas en BoostKeys.all + appliedValueOf pero SIN ningún writer en producción:
touch_sensitivity, multi_touch_sensitivity, touch_latency_reduction,
high_touch_sensitivity_enable, high_touch_polling_rate_enable, touch_report_rate,
touch_boost_enabled, swipe_up_to_switch_apps_enabled, edge_prevent_mistouch_enabled.
(Las menciones en TouchOptimizer.kt:19-20 son el comentario "PLACEBO eliminados".)

Residuos reales en el ZTE de builds viejos: touch_report_rate=240, touch_latency_reduction=0,
touch_boost_enabled=0 (presentes hoy), resto ausentes. Con el código actual, esos residuos
se CAPTURAN como "original" en cada baseline nuevo → se petrifican como "original" para
siempre. F4 no puede limpiarlos porque el boost ya no los toca.

Limpieza propuesta: remover las 9 de BoostKeys.all + sus branches en appliedValueOf.
Migración segura: los baselines persistidos viejos igual restauran por originalValue
guardado; appliedValueOf→null solo degrada a Caso B (conservador).

### 3. Writer PLACEBO / no-op real

NetworkOptimizer:55: `settings put global private_dns_spec ` (valor vacío) → error de
usage del comando, NO escribe nada (device: 'null' tras múltiples sesiones). Placebo de
escritura. Propuesta: eliminar el comando (o `settings delete` explícito si la intención
era limpiar la key legacy). La key también está en BoostKeys.all sin branch en
appliedValueOf (else null, "dns_spec legacy") — coherente con remove.

### 4. Claim condicional no modelado

debug.gl.msaa: claim "4" incondicional; writer condicional (SystemTweaks:91 enableMsaa).
El propio comentario del else dice "msaa condicional" — contradicción en el código.
Misifire posible de Caso A si current==4 sin que el boost lo haya escrito. Frecuencia baja.

### 5. Root cause / mejora de diseño

appliedValueOf es una TABLA ESTÁTICA que duplica lo que los writers hacen — se pudre
cuando los writers cambian (ya pasó: min_refresh_rate, placebos). Root fix: persistir en
la sesión los valores REALMENTE aplicados (map "applied" escrito al confirmar APPLY)
y eliminar la tabla. Elimina la clase completa de bugs "tabla-vs-writer".

### Nota de interacción

TouchOptimizer escribe long_press 120 (gaming) / 300 (no-gaming) y tiene su PROPIO
backup/restore en memoria (TouchOptimizer:95). El claim "120" de appliedValueOf cubre
solo la vía gaming; la vía 300 caería en Caso B si solo restaurara la sesión F4
(mitigado por el restore propio de TouchOptimizer si se invoca).

### LISTA DE LIMPIEZA (propuesta, sin ejecutar)

1. [ALTA] appliedValueOf: min_refresh_rate → "90.0" (o mejor: alinear writer GSM a 120.0
   y claim 120.0 — decidir cuál es el valor de diseño correcto; hoy GSM escribe 90.0).
2. [ALTA] BoostKeys.all + appliedValueOf: remover las 9 keys placebo muertas.
3. [MED]  NetworkOptimizer:55: eliminar el put vacío de private_dns_spec (+ su entrada
   en BoostKeys.all) o convertirlo en delete explícito.
4. [MED]  debug.gl.msaa: remover claim "4" o plumar enableMsaa en la sesión.
5. [DESIGN] Persistir valores aplicados reales en la sesión; deprecar appliedValueOf.
6. [INFO] touch_report_rate=240 / touch_latency_reduction=0 / touch_boost_enabled=0 son
   residuos viejos en el ZTE; tras la limpieza #2 pueden borrarse a mano
   (settings delete) porque factory = ausencia (verificado en device).

### VERIFICACIONES SOLICITADAS (2026-09-10, cierre del gate de análisis)

#### #1 min_refresh_rate: el 90.0 es CONSTANTE, no derivado
GameSessionManager:517-518 (applyHighPriorityOptimizations, FASE 2 con delay fijo 500ms):
    "settings put system peak_refresh_rate 120.0",   ← literal
    "settings put system min_refresh_rate 90.0"      ← literal
Sin lógica condicional de panel ni capability-check. PERO existe el segundo writer
(ProfileManager:76-77) que escribe `$safeRefresh.0` DINÁMICO a ambas keys. Conclusión:
la tabla estática NO puede ser consistentemente correcta para min/peak_refresh_rate
(dos writers, uno fijo y uno dinámico). Fix interino: claim→"90.0" (cubre la vía GSM,
la que ejercita el boost de alta prioridad); la vía ProfileManager degrada a Caso B
(conservador). Fix real: punto 5 (persistir aplicados).

#### #2 pureza del restore: VERIFICADO — restore NO consulta BoostKeys.all
Usos de `BoostKeys.` fuera del objeto (grep exhaustivo): exactamente 2 —
  a) BoostKeys.all → SOLO en beginApply() (loop de captura)
  b) appliedValueOf → Caso A del restore
El restore itera session.baseline parseado 100% del JSON persistido
(BoostSessionState.kt:89: parse por-entry de originalValue; data class pura, sin
referencia al inventario). Remover las 9 keys del inventario NO puede perder entradas
de baselines viejos: siguen en el JSON y en el loop del restore.
EFECTO documentado de la migración: para baselines previos con esas keys, appliedValueOf
→ null ⇒ sameValue(current, null)=false ⇒ Caso B (conflicto→preserva) en lugar de Caso A
(restaura). Degradación conservadora: el residuo persiste, la sesión sigue siendo
recuperable (RESTORE_CONFLICT no incrementa `failed` ⇒ allOk no se ve afectado).
Aceptable + limpieza manual única de los 3 residuos (item 6).

#### #3 write vacío private_dns_spec: REPRODUCIDO en device con output exacto
Comando idéntico al de la app (sh -c, como hace ShizukuExecutor):
    adb shell sh -c "settings put global private_dns_spec "
Output: usage de settings provider (help: get/put/delete/reset/list) + EXIT=255.
Post-write: settings get global private_dns_spec → null (no escribió nada).
Confirmado: no-op garantizado que además genera exit≠0 en cada apply → ruido de
"⚠️ exit≠0"/CommandFailedException en el pipeline (y posible conteo de fallos en
políticas stderr). Proceda: eliminar la línea.

#### #4 msaa: decisión = modelar, no borrar (aceptada con matiz)
Origen del condicional: GameSessionManager:228 systemTweaks.apply(enableMsaa = msaaEnabled)
— flag runtime (prefs), la tabla estática no puede saberlo. Interino: MANTENER claim "4"
(optimista: si current==4, restaura — riesgo bajo porque solo boost escribe 4; el caso
"usuario activó MSAA manual en la ventana" es raro y el efecto es restaurar a original).
Definitivo: #5. Queda registrado como condicional-no-modelado, no como bug activo.

#### #3-bis CIERRE: ¿espacio-final o argumento-ausente? → AMBOS convergen en 255
Matiz planteado: `"...private_dns_spec "` (arg vacío) vs `"...private_dns_spec"`
(arg ausente) son errores de usage distintos. Verificado en código y device:
1. Construcción: `cat -A` NetworkOptimizer:55 → literal HARDCODEADO con espacio final,
   sin interpolación: `"settings put global private_dns_spec ",$`. La forma es fija.
2. Pass-through: los 3 backends usan `sh -c <command>` como string único
   (ShizukuExecutor:229 arrayOf("sh","-c",command); RishExecutor:79 ProcessBuilder;
   RishExecutor:87 Runtime.exec). El word-splitting del shell ELIMINA el espacio
   final → `settings` recibe 3 args (put/global/key) → la forma operativa real es
   argumento-ausente, no arg-vacío.
3. Device: `settings put global private_dns_spec` → `Bad arguments`, EXIT=255, sin
   write. Control: put con valor → EXIT=0; delete → EXIT=0 (estado restaurado).
Conclusión: ambas formas malformadas convergen en el mismo resultado (255 + no-op).
La limpieza (c) cubre todos los casos — no existe tercera variante porque el literal
es constante en compile-time. Line: safe to delete.

#### #3-ter CIERRE: alcance real de (c) — eran TRES writes defectuosos, no uno
Corrección de alcance surgida al implementar: la frase "la línea de NetworkOptimizer:55"
subestimaba el daño. Lectura completa del archivo + grep exhaustivo (`grep -rn
private_dns_spec --include=*.kt app/src/`) revela:

| # | Ubicación | Forma | Disparo |
|---|---|---|---|
| 1 | `apply()` ~:55 | literal `"settings put global private_dns_spec "` | CADA boost → `failCount++` + WARN en cada apply |
| 2 | `restore()` ~:109 | literal idéntico (`// limpiar legacy`) | Cada restore → 255 silencioso |
| 3 | `restore()` ~:108 | `"...private_dns_specifier $specifier"` con fallback `specifier=""` | Condicional: si el usuario no tenía DNS privado original → interpolación vacía → mismo missing-arg 255 |

El caso 3 es una variante NUEVA no vista en la auditoría. Reproducción exacta en
device del patrón de interpolación vacía (`sh -c "settings put global
private_dns_specifier "`): `Bad arguments`, EXIT=255; control con valor válido:
EXIT=0. Corrección aplicada en commit `0467ba0` (rama fix/boostkeys-inventory-cleanup,
2º commit del PR): ambos literales eliminados + restore condicional — put solo si el
usuario tenía specifier; si no, `settings delete global private_dns_specifier`
(usa la misma semántica absent-key que el restore F4). Enseñanza registrada: grep de
inventario ≠ grep de writers — la auditoría que cruzó claims vs writers capturó el
inventario, pero no barrió TODO el código por el nombre de la key; el barrí exhaustivo
de writers (que esta vez sí se hizo antes de editar) fue el que destapó :109 y :108.

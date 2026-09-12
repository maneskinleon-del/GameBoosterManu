# SPEC #5 — Single Source of Truth para valores aplicados

Fecha: 2026-09-12 · Estado: **DECIDIDO (Q1–Q3 cerradas 2026-09-12, ver §6)** · Base de evidencia: main@de9fc58 → daa733c + device ZTE Z2352N

## 1. Problema (verificado, no hipotético)

Hoy el "estado aplicado" vive en **5 lugares que pueden disentir entre sí**:

| # | Store | Contenido | Riesgo verificado |
|---|-------|-----------|-------------------|
| 1 | Room DB (`profiles.isActive`) | perfil activo para la UI | Puede quedar desincronizado de lo realmente aplicado |
| 2 | Prefs `last_manual_profile_<pkg>` + `last_manual_profile_` (global) | perfil manual recordado | **Global `""` overridea todo, para siempre, con `isManual=true`** — envenenamiento demostrado hoy (purge manual requerido) |
| 3 | `filesDir/boost_session.json` (BoostSessionStore) | baseline F4 + restore verificado | Snapshot legacy re-aplica valores viejos al morir el proceso; sin versión de schema |
| 4 | RAM (`manualOverrideActive`, `currentProfileId`, caps Mobilador, mapa TouchOptimizer) | estado de sesión | Muere con el proceso; TouchOptimizer RAM-only se pierde (BoostSession cubre parte) |
| 5 | `settings put` (system/secure) | **la verdad real del device** | Nadie la reconcilia centralizadamente |

**Caso real de hoy:** UI mostraba Balanced sin causa → DB decía `ff_mouse|1`, prefs decían `ff_mouse`, watchdog imposibilitado (NaN guard) → la única explicación era el snapshot legacy + override global re-armándose. Purge manual en device como workaround; el fix estructural es #5.

## 2. Objetivos

- G1: **Un solo writer por key.** Cada `settings put/delete` restaura desde una tabla única de fallbacks.
- G2: **Un solo store persistido del estado aplicado**, versionado por schema.
- G3: La UI lee el perfil activo de **una sola fuente observable** (hoy: Room + prefs + RAM).
- G4: Snapshot corrupto/legajo/desconocido → **descartar y baseline fresco** (nunca re-aplicar a ciegas).
- G5: Fallbacks **medidos por device** (ZTE: `long_press_timeout=120`, no AOSP 400 — ver PR #3).

## 3. Diseño propuesto

### 3.1 `AppliedStateStore` (reemplaza a boost_session.json como store único)

```json
{
  "schemaVersion": 2,
  "deviceFingerprint": "Z2352N-ums9620-33",
  "profile": { "id": "ff_mouse", "isManual": true, "source": "user|watchdog|gameDetect", "at": 0 },
  "settings": {
    "secure.long_press_timeout": { "value": "120", "absent": false, "capturedAt": 0, "writer": "mobilador" },
    "system.pointer_speed":     { "value": null, "absent": true,  "capturedAt": 0, "writer": "mobilador" }
  }
}
```

- Atomic write (ya existe el patrón en BoostSessionStore: `.tmp` + rename).
- `schemaVersion` distinto o JSON corrupto → renombrar a `.corrupt` y **arrancar vacío** (G4).
- `absent: true` → restore = `settings delete` (semántica F4 ya implementada).

### 3.2 Tabla única de fallbacks (G1, G5)

```kotlin
object AppliedDefaults {
    // Defaults MEDIDOS por device (no AOSP doc). PR #3 cerró el caso long_press.
    fun fallback(fingerprint: String, ns: String, key: String): String?
}
```

- Un solo lugar; `buildRestore` del Mobilador, `BoostSessionManager` y `TouchOptimizer` consultan esto.
- Si no hay medición para el device → fallback AOSP documentado + `addLog WARN` explícito (como hoy hace Mobilador).

### 3.3 Autoridad de perfil (G3)

- **Room `profiles.isActive` = única verdad persistida del perfil activo.** UI la observa; todo write pasa por `GameSessionManager.setActiveProfile` (ya es así).
- Prefs dejan de tener la key global `last_manual_profile_` (empty pkg): la preferencia manual es **por juego**; si no hay juego activo, no se persiste override global.
- `manualOverrideActive` queda RAM-only (gate de sesión, no persistencia).
- Migración one-shot: borrar `last_manual_profile_` (global) en el arranque si existe (lo que hoy hicimos a mano).

### 3.4 Writers y restore

- `restoreVerified()` (BoostSessionManager) sigue siendo el mecanismo, pero lee/escribe `AppliedStateStore` y reporta outcome per-key al mismo store.
- TouchOptimizer deja de mantener mapa RAM paralelo: captura → store; restore → store.

## 4. Criterios de aceptación

1. `grep -r "long_press_timeout\|pointer_speed"` → todo restore deriva de `AppliedDefaults` o de valor capturado en store (cero literales sueltos).
2. Force-stop mid-ON → relaunch → restore exacto de lo capturado, o fallback medido; **nunca** 400 en ZTE.
3. Ningún path puede dejar la UI en "Balanced" sin un write explícito (watchdog NaN-guard o usuario).
4. Key global `last_manual_profile_` eliminada + migración de purge en el arranque.
5. `schemaVersion` mismatch / corrupt → descarte + log WARN + baseline fresco (verificable con test unitario del store).

## 5. Fuera de alcance (esta spec)

- UserService migration de Shizuku (TODO ya existente).
- Wrapper PR.
- `testTag("boost_switch")` (viene después; se beneficiará de G1 para asertar sobre el store).

## 6. Decisiones cerradas (operador, 2026-09-12)

- **Q1 → Room DB.** Room es la única autoridad del perfil activo; el JSON store solo guarda baseline de settings aplicados. La key global `last_manual_profile_` (empty pkg) **no existe** en el diseño nuevo.
- **Q2 → Corte limpio.** Sin migración fiel: en el arranque, cualquier snapshot con `schemaVersion != 2` (o corrupto) se **borra** (no se importa). Baseline fresco desde el device real. Ya verificado en device que el purge manual es seguro (2026-09-12).
- **Q3 → Orden:** wrapper PR primero (o en paralelo) → después #5 → `testTag("boost_switch")` cuando convenga.

### Live test post-purge (evidencia que valida el diseño)

FF táctil en ZTE tras purge: detección aplicó `free_fire_touch` (`isManual=false`), DB `free_fire_touch|1`, **0 keys `last_manual_profile*`** tras detección+boost, settings stock intactos (`long_press_timeout=120`, `pointer_speed=0`), baseline F4 fresco (`bs_…279700`). El mecanismo de envenenamiento quedó muerto con el purge; #5 lo mantiene muerto estructuralmente.

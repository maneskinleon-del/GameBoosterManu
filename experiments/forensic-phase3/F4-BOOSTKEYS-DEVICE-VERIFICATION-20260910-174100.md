# F4-BOOSTKEYS — DEVICE VERIFICATION DEL INVENTARIO LIMPIO (2026-09-10)

Rama probada:   fix/boostkeys-inventory-cleanup (publicada → origin, repo GameBoosterManu)
HEAD probado:   b599172e5a326873467a21d5565ef6c885bd9bd2
  parent 36b651f (fix(f4): purgar inventario de keys placebo muertas y línea no-op private_dns_spec)
  HEAD   b599172 (fix(network): remove no-op private_dns_spec writes and guard empty specifier restore)
Device:         ZTE Nubia Z2352N (serial 320344802623), Android 13 (API 33), ums9620
APK instalada:  app/build/outputs/apk/debug/app-debug.apk (debug, assembleDebug BUILD SUCCESSFUL)
                18,462,510 bytes — sha256 f0e0992c1e43b09baf5a4905952db9e350d4fec4b560f2e4c2060d9d2690922d

---

## 1. Estado probado

HEAD = b599172, con su commit padre 36b651f. Ambos publicados en
`origin/fix/boostkeys-inventory-cleanup` (esta ronda: push + borrado de la rama
backup `pre-peak-refresh-fix`). Working tree limpio salvo untracked propios
(`.codegraph/`, `experiments/`).

## 2. Condiciones de prueba

- Instalación limpia previa al ciclo: `adb install -r` del APK de b599172; sin
  `files/boost_session.json` previo (install reciente, baseline desde cero).
- Permisos concedidos por ADB: Shizuku API (`pm grant moe.shizuku.manager.permission.API_V23`),
  `SYSTEM_ALERT_WINDOW`, `GET_USAGE_STATS`, `RUN_IN_BACKGROUND`, `POST_NOTIFICATION`,
  batería sin restricciones (deviceidle whitelist). Shizuku server activo (uid 10483,
  bucket=5/ACTIVE). Accesibilidad: UnifiedAccessibilityService ACTIVE.
- Display del ZTE con overrides: `cur=720x1440` app=595x1440, density override 280.
  Coordenadas de taps en px físicos del uiautomator.
- El boost se dispara por UI (Switch "Auto-Boost Engine" en Tablero), porque
  `GameBoostService` es `exported=false` y `ACTION_START` solo sale de `toggleBoost()`.
- Nota técnica registrada: el Switch Compose reporta `bounds=[0,0][0,0]` en
  uiautomator (semantics vacías); el target real es el touch target de 48dp pegado
  al borde derecho del header row. Tap efectivo: x=650, y=534 (header en y≈513-553,
  scrolled). Costó hablar los first taps (575/588/500 no dispararon). Respalda el
  pendiente #1 de F2F3F5: `Modifier.testTag("boost_switch")`.

## 3. Boost real — observado

- Header subtitle: "Optimización inactiva" → "Optimización activa".
- `files/boost_session.json` post-captura: `state="ACTIVE"`,
  `sessionId="bs_1789072557617"`, baseline de **34 entradas, 0 duplicados**.

## 4. Inventario purgado — verificado en el baseline nuevo

Las 10 keys removidas en esta ronda NO aparecen en el baseline nuevo:

- 9 placebos muertas (sin writer desde 424c806): `touch_sensitivity`,
  `multi_touch_sensitivity`, `touch_latency_reduction`, `high_touch_sensitivity_enable`,
  `high_touch_polling_rate_enable`, `touch_report_rate`, `touch_boost_enabled`,
  `swipe_up_to_switch_apps_enabled`, `edge_prevent_mistouch_enabled` → **AUSENTES**.
- `private_dns_spec` (la key del no-op con put vacío → usage error) → **AUSENTE**.
- `private_dns_specifier` → **PRESENTE** con `original="dns.google"`: es key activa
  real (writer legítimo con valor); no es la línea no-op eliminada. Correcto que
  siga capturándose y restaurándose.

Nota de migración (no verificado en device, ya documentado en F2F3F5 §2): los
baselines VIEJOS que contengan esas keys siguen restaurando por `originalValue`
guardado; `appliedValueOf`→null sobre key removida degrada a Caso B (conservador).

## 5. Logcat durante el ciclo

- `EXIT=255` durante boost real y restore: **0 ocurrencias** (grep sobre buffer completo).
- Comandos shell no-op `private_dns_spec` durante el apply: **0**.
- Errores de ShizukuExecutor (fail/usage/255): **0**.

## 6. Restore observado

- Header subtitle vuelve a "Optimización inactiva"; JSON `state="RESTORED"` (34
  entradas de baseline intactas).
- Log del restore: `Restore verificado: 34 keys (6 restauradas, 26 ya-ok, 2 conflictos conservados)`.
- Spot-check de settings post-restore: `global auto_sync=1` (original 1),
  `global debug.gl.msaa=0` (original 0) — restauraron bien. `system peak_refresh_rate`
  quedó en 60.0, ver §7.

## 7. Hallazgo #5 — evidencia de device del defecto estructural

Secuencia exacta observada en logcat (17:37:01-02, pid app 18243):

```
17:37:01.599  ShizukuExecutor: settings put system peak_refresh_rate 60.0
17:37:01.659  ShizukuExecutor: settings put system min_refresh_rate 60.0
17:37:02.709  ShizukuExecutor: settings get system peak_refresh_rate
17:37:02.713  BoostSession [INFO] Conflicto system:peak_refresh_rate: actual=60.0
              no es original(120) ni aplicado — se conserva valor del usuario
17:37:02.743  BoostSession [INFO] Conflicto system:min_refresh_rate: actual=60.0
              no es original(120) ni aplicado — se conserva valor del usuario
17:37:02.978  BoostSession [INFO] Restore verificado: 34 keys (6 restauradas,
              26 ya-ok, 2 conflictos conservados)
```

El baseline capturó `original="120.0"` para `peak_refresh_rate` y `min_refresh_rate`
(el device estaba en perfil FF Mouse, 120 Hz). El restore escribió `put ... 60.0`,
y el mecanismo de protección del Caso B detectó que 60.0 no coincide ni con el
original (120) ni con el valor estático considerado aplicado → **conservó el 60.0**.
Resultado neto del ciclo Boost→Restore: las refresh rates quedaron en 60.0, no en el
estado original 120.0 observado al capturar.

## 8. Conclusión (acotada a lo observado)

La tabla estática `appliedValueOf` no representa de manera fiable el valor realmente
aplicado para `peak/min_refresh_rate`: el propio restore escribió un valor (60.0)
que no era el aplicado por el boost ni el original, y solo el mecanismo conservador
del Caso B evitó que eso fuera peor. El dato `120.0 → 60.0` convierte en **fallo
observable del ciclo Boost→Restore** lo que hasta ahora era una limitación inferida
de la tabla. **#5 (persistir en la sesión los valores REALMENTE aplicados y eliminar
`appliedValueOf`) queda JUSTIFICADO como reemplazo — NO está implementado todavía.**

## 9. Regresión de esta ronda vs. defecto estructural

- **Regresión de esta ronda: NINGUNA.** Las 9 placebos + `private_dns_spec` ausentes
  de los baselines nuevos, `EXIT=255` eliminado, restore 34 keys sin errores. Los
  "2 conflictos conservados" son comportamiento esperado del Caso B, no regresión.
- **Defecto estructural descubierto: #5, PREEXISTENTE.** El write `60.0` y el
  conflicto conservado provienen del writer/restore de refresh rates, que NO fue
  tocado por los 2 commits de esta rama. Esta ronda solo lo hizo observable en device.

---

## Hash (para verificación/immutabilidad)

- Commit probado:  `b599172e5a326873467a21d5565ef6c885bd9bd2`
- Commit padre:    `36b651f` (purga de inventario)
- APK:             18,462,510 bytes, sha256 `f0e0992c1e43b09baf5a4905952db9e350d4fec4b560f2e4c2060d9d2690922d`
<!-- HASH-FOOTER -->
- Documento (bloque previo al marcador `<!-- HASH-FOOTER -->`):
  sha256 `b410784ce3b16eafdcf0027ddff057afcfab09cd3dec9add251abf8b597efbce`
  (verificar: `csplit -s -f /tmp/docpart <archivo> '/<!-- HASH-FOOTER -->/' && sha256sum /tmp/docpart00`)
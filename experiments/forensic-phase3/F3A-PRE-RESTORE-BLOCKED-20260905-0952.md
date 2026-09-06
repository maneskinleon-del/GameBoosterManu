# F3A — PRE-RESTORE FORENSIC SNAPSHOT
## ESTADO: **BLOCKED — DEVICE DISCONNECTED**

```
Timestamp:        2026-09-05T09:52:42-04:00 (inicio de F3A)
Repo HEAD:        862a5bf507abfae0b7ab81a1bc854d97d541cb08
Branch:           main
Working tree:      ?? .codegraph/ (artefacto de análisis) + experiments/forensic-phase3/ (este reporte)
Gradle local:      ~/.gradle/wrapper/dists/gradle-9.3.0-bin/79n14ral3mx1ozqr3csh2u872/ (wrapper jar del repo ausente)
App (repo):       versionCode=2, versionName="1.1" (commit HEAD)
App (device):     versionCode=2, versionName=1.1 — OBSERVADO en FORENSIC PHASE 2 (2026-09-04 18:56) cuando el dispositivo estaba conectado
```

## BLOCKER

Durante la PARTE 2 (snapshot de dispositivo), el ZTE Z2352N **se desconectó físicamente del USB**:

```
adb devices          → List of devices attached (vacío)
adb kill-server/start-server + 6 reintentos espaciados 10s → sin dispositivo
lsusb                 → sin dispositivo Android visible
/dev/bus/usb escaneo (udevadm) → sin vendor ZTE/Nubia
```

**Regla 10 aplicada**: me detengo. NO se ejecutó ningún comando mutante sobre el dispositivo en esta fase (ningún `settings put`, ningún kill, ningún force-stop). Cero cambios de dispositivo.

**Última evidencia de dispositivo válida** = FORENSIC PHASE 2 (2026-09-04 18:56-04:00). Es la base de la correlación PARTE 3, marcada como OBSERVED (pre-disconnect).

---

## PARTE 3 — CORRELACIÓN CÓDIGO ↔ DISPOSITIVO (estática, completa)

Metodología: cada key escrita por el código (grep exhaustivo: 29 keys global + 13 system/secure) correlacionada con (a) el valor observado el 2026-09-04 en el ZTE, (b) el string literal del código que lo escribe, (c) el backup que existiría, (d) el restore.

### 3.1 Keys observadas residuales el 2026-09-04 (13)

| Key | Valor observado (OBSERVED 09-04) | Valor escrito por código (CODE) | Evidencia de atribución | Backup (RAM) | Persistido | Restore | Confianza atribución |
|---|---|---|---|---|---|---|---|
| private_dns_mode | hostname | `"settings put global private_dns_mode hostname"` (NetworkOptimizer.kt:51, APPLY_COMMANDS) | Valor literal exacto; `hostname` no es default AOSP (default: off/opportunistic según estado); co-ocurrencia con specifier | originalDnsMode (NetworkOptimizer:40) | NO | restore() usa original ?: "off" | HIGH |
| private_dns_specifier | dns.google | `"settings put global private_dns_specifier dns.google"` (NetworkOptimizer.kt:52) | String exacto; además la key legacy private_dns_spec= vacía solo la limpia GameBoost | originalDnsSpecifier | NO | original ?: "" | HIGH |
| window_animation_scale | 0 | SystemTweaks:76 (`... 0`), ProfileManager (animScale 0), GameSessionManager:209, ResourceGovernor:64 (0.5) | Valor literal; múltiples writers todos con 0/0.5 durante boost | originalWindowAnim | NO | original ?: "1.0" | HIGH |
| ble_scan_always_enabled | 0 | SystemTweaks:60 `0` | Valor literal exacto; default AOSP es 1 | originalBleScan | NO | original ?: "1" | HIGH |
| wifi_scan_always_enabled | 0 | SystemTweaks:61 `0` | Ídem | originalWifiScanAlways | NO | original ?: "1" | HIGH |
| disable_window_blurs | 1 | SystemTweaks:71 `1` | Valor literal; default AOSP 0 | originalWindowBlurs | NO | original ?: "0" | HIGH |
| auto_sync | 0 | SystemTweaks:75 `0` | Valor literal; default AOSP 1 | originalAutoSync | NO | original ?: "1" | HIGH |
| debug.sf.disable_hwc_vds | 1 | SystemTweaks:72 `1` | Valor literal; default AOSP null/0 | originalVsync | NO | original ?: "0" | HIGH |
| send_action_app_error | 0 | SystemTweaks:79 `0` | Valor literal | originalSendActionAppError | NO | original ?: "1" | HIGH |
| activity_manager_constants | max_cached_processes=128 | SystemTweaks:84 `max_cached_processes=128` | Firma EXACTA del string literal — atribución prácticamente única (ningún sw del sistema escribe ese blob) | originalActivityManagerConstants | NO | original ?: **settings delete** (única key con delete) | VERY HIGH |
| low_power_trigger_level | 0 | SystemTweaks:86 `0` | Valor literal; default AOSP 15 | originalLowPowerTrigger | NO | original ?: "15" | HIGH |
| wifi_bt_coexistence | 0 | NetworkOptimizer:63 `0` | Valor literal | originalWifiBtCoex | NO | original ?: "1" | HIGH |
| wifi_low_latency_mode | 1 | NetworkOptimizer:62 `1` | Valor literal; default null | (sin backup propio) | NO | NO se restaura en restore() (bug del restore — NetworkOptimizer.restore solo restaura mode/specifier/coex) | HIGH |

### 3.2 Keys que el código escribe PERO no fueron observadas el 09-04 (verificar al reconectar)

Estas keys DEBEN capturarse en el snapshot real para completar el baseline (la lista de 13 NO era exhaustiva — hay al menos 16 más):

**SystemTweaks.apply() (todas con backup RAM, restore con default-inventado):**
- bluetooth_disabled_profiles=1 (restore default: 0)
- overlay_display_devices=0 (restore default: 1)
- debug.hwui.renderer=skiavk (restore: settings delete si null)
- debug.hwui.overdraw=false (restore default: "false")
- debug.hwui.show_dirty_regions=false (restore default: "false")
- debug.sf.disable_backpressure=1 (restore default: 0)
- debug.sf.latch_unsignaled=1 (restore default: 0)
- adaptive_connected_voice_enabled=0 (restore default: 1)
- debug.gl.msaa=4 (SOLO si toggle MSAA ON; restore default: 0)
- animator_duration_scale, transition_animation_scale (0 durante boost; restore default "1.0")

**NetworkOptimizer.apply() (sin restore en 3 casos):**
- wifi_watchdog_on=0 — **NO se restaura nunca** (restore() solo toca dns_mode/specifier/spec legacy/bt_coex)
- wifi_scan_interval_ms=300000 — **NO se restaura nunca**
- wifi_power_save=0 — **NO se restaura nunca**

**TouchOptimizer.applyOptimization() (backup parcial de 5 keys; las demás SIN restore):**
- system pointer_speed (dinámico) — restore solo si backup ok
- system touch_sensitivity=<n>, multi_touch_sensitivity=<n> — **NO se restauran nunca**
- secure long_press_timeout=120 (gaming) — backup sí
- secure accessibility_display_magnification_enabled=0, accessibility_autoclick_enabled=0 — backup sí
- system touch_latency_reduction=1, secure touch_boost_enabled=1, system high_touch_sensitivity_enable=1, high_touch_polling_rate_enable=1, secure swipe_up_to_switch_apps_enabled=0, edge_prevent_mistouch_enabled=0 — **NO se restauran (solo 2 con "default 0" hardcoded en restore)**
- system touch_report_rate=240 — **NO se restaura nunca**

**GameSessionManager / PowerOptimizer / ResourceGovernor (no-settings):** zen_mode=2 (backup RAM), refresh peak/min 120/90, `cmd power set-fixed-performance-mode-enabled true`, `cmd power set-adaptive-power-saver-enabled false`, `wm density`.

### 3.3 Estructura del fallo (DERIVED)

- Backups: 100% RAM (`original<Key>` @Volatile / map). Persistidos: 0.
- Restore tras process death: NO EXISTE. GameBoostService.onCreate/handleStart solo restauran DPI/pointer_speed propios.
- El 09-04: proceso muerto + `is_running=true` + 13 keys en estado boost → patrón `APPLY → PROCESS DEATH → NO RESTORE` **DEVICE OBSERVED** (FORENSIC PHASE 2).

---

## PARTE 4 — CLASIFICACIÓN DE BASELINE (preliminar; se confirma con el snapshot real)

Criterio: RESTORE_SAFE solo si (a) atribución a GameBoost con confianza HIGH+, Y (b) el valor de restauración tiene evidencia legítima local (default AOSP verificable en el código del restore, o valor conocido del dispositivo), Y (c) el cambio es reversible/verificable.

| Key | Valor actual (09-04) | Clasificación baseline | Valor de restauración | Justificación |
|---|---|---|---|---|
| private_dns_mode | hostname | **RESTORE_WITH_CAUTION** | `off` | El restore propio de la app usaría original?:"off" — pero "off" podría no ser el estado pre-GameBoost del usuario (pudo ser opportunistic). El usuario debe elegir. NO auto-restaurar. |
| private_dns_specifier | dns.google | **RESTORE_WITH_CAUTION** | `` (delete) | Ídem: depende del modo elegido. |
| window/transition/animator_animation_scale | 0 | **RESTORE_WITH_CAUTION** | `1.0`? | Default AOSP = 1.0 y el restore del código usa 1.0 — PERO el usuario pudo tener 0.5/0 custom. ZTE Settings no permite valores custom no-enteros; riesgo bajo pero existe. Preguntar al usuario. |
| ble_scan_always_enabled | 0 | **RESTORE_SAFE** | `1` | Default AOSP/fábrica = 1 (el propio código lo usa como restore default). Atribución HIGH. |
| wifi_scan_always_enabled | 0 | **RESTORE_SAFE** | `1` | Ídem. |
| disable_window_blurs | 1 | **RESTORE_SAFE** | `0` | Default AOSP 0; restore default del código = 0. |
| auto_sync | 0 | **RESTORE_SAFE (condicional)** | `1` | Default AOSP 1 — PERO el usuario puede haber desactivado auto-sync deliberadamente (ahorro batería). Verificar con usuario; atribución del 0 a GameBoost es HIGH (co-ocurrencia), la restauración a 1 requiere confirmación de que el usuario lo tenía en 1. → reclasificar a CAUTION si el usuario confirma que no lo apagó manualmente. |
| debug.sf.disable_hwc_vds | 1 | **RESTORE_SAFE** | `0` | Key debug AOSP, default 0/null; solo GameBoost escribe 1 (SESIÓN-07-21 lista esta key como aplicada por la app). |
| send_action_app_error | 0 | **RESTORE_SAFE** | `1` | Default AOSP 1; solo la app escribe 0 (MEJORAS §8). |
| activity_manager_constants | max_cached_processes=128 | **RESTORE_SAFE** | `settings delete global activity_manager_constants` | El PROPIO código de restore usa delete cuando no había original — delete es el baseline correcto (ausencia de la key); default del sistema se aplica al no estar seteada. Atribución VERY HIGH (firma única). |
| low_power_trigger_level | 0 | **RESTORE_WITH_CAUTION** | `15` | Default AOSP = 15 y el código usa 15 como restore default. PERO ¿el ZTE usa 15 de fábrica? HIGH confidence pero no VERIFIED para este OEM. Confirmar con usuario (puede consultar su otra ROM/expectativa) o dejar el valor del código (15). |
| wifi_bt_coexistence | 0 | **RESTORE_SAFE** | `1` | Valor "normal" del restore propio = 1 (default del dispositivo). |
| wifi_low_latency_mode | 1 | **RESTORE_SAFE** | `settings delete global wifi_low_latency_mode` | Key no-AOSP-estándar de algunos OEM; default = ausencia. La app no la restaura nunca. DELETE es lo más cercano a "nunca existió". CAUTION: si el OEM la usa con default propio... atribución HIGH, restauración por delete razonable. |
| zen_mode | 0 (no residual esta vez) | — | — | No requiere acción (ya en 0). |
| wifi_watchdog_on=0, wifi_scan_interval_ms=300000, wifi_power_save=0 | por capturar | **probable UNKNOWN/CAUTION** | — | La app nunca los restaura; baseline original desconocido. Capturar en snapshot; restaurar solo si hay evidencia. |
| touch_* / multi_touch / report_rate / latency keys | por capturar | **UNKNOWN** | — | Keys experimentales no estándar; el baseline es ausencia → candidates a `settings delete`. Decidir por key al capturar. |
| bluetooth_disabled_profiles, overlay_display_devices, hwui.*, sf.backpressure/latch, adaptive_connected_voice | por capturar | **probable SAFE (restore-default del código)** | ver tabla 3.1 | Defaults del propio restore; alta confianza pero confirmar valores actuales primero. |
| peak/min_refresh_rate | por capturar | **CAUTION** | default del dispositivo (60?) | Depende del panel (Z2352N podría ser 90/120Hz nativo). NO inventar. |
| pointer_speed | por capturar | **CAUTION** | pref de la app NO lo tenía guardado (Phase 2 prefs: sin pointer_speed key) → el valor pre-app es desconocido; default Android = 0 | |
| DPI / wm density | por capturar | **CAUTION** | pref sin custom_dpi → probablemente density nativa | `wm density reset` sería el candidato PERO verificar `wm density` actual vs `getprop ro.sf.lcd_density` antes. |

**VALORES UNKNOWN que NO se restaurarán bajo ninguna circunstancia** hasta tener evidencia: private_dns_*, animation scales (hasta confirmar con usuario), low_power_trigger_level (hasta confirmar), refresh rates, cualquier key touch experimental sin default claro.

**NOTA**: ninguna restauración se ejecutará con `settings put` inventado. Las RESTORE_SAFE usan exclusivamente: (a) el default que el PROPIO código de la app define como restore (evidencia local legítima), o (b) `settings delete` para keys cuya ausencia es el estado de fábrica.

---

## PARTE 5 — SNAPSHOT PRE-RESTORE: **PENDIENTE DE DISPOSITIVO**

No se puede completar hasta que el ZTE reconecte. Este documento ES el reporte de bloqueo + preparación estática completa.

Al reconectar, ejecutar (script ya preparado, en orden):
1. `adb devices -l` + `getprop ro.product.model` + `ro.build.version.release/sdk`
2. `pidof com.example` (debe seguir muerto)
3. `ps -A | grep -i shizuku`
4. `run-as com.example cat shared_prefs/gameboost_prefs.xml`
5. Snapshot de ~50 keys (las 13 de Phase 2 + 16 adicionales del inventario §3.2 + zen/anim/dpi/refresh/pointer):
   `adb shell settings get global <key>` × cada una (lista en §3.1/§3.2)
6. `adb shell wm density` + `getprop ro.sf.lcd_density` (correlación DPI)
7. Guardar TODO en `experiments/forensic-phase3/F3A-PRE-RESTORE-<ts>.md` + copia a `~/storage/downloads/`

## PARTE 6 — RESTAURACIÓN CONTROLADA: **NO EJECUTADA** (bloqueada por desconexión)

## PARTE 7 — SNAPSHOT POST-RESTORE: **NO EJECUTADA**

## PARTE 8 — VEREDICTO (estado actual)

### VERIFIED (estático)
- Inventario completo de keys escritas: 29 global + 13 system/secure + zen/dpi/refresh/pointer (grep exhaustivo, sort -u).
- Valores literales exactos de apply y de restore-default para cada key (SystemTweaks.kt:58-94/203-266, NetworkOptimizer.kt:48-121, TouchOptimizer.kt:22-108).
- Backups 100% RAM, 0 persistidos, 0 restore post-mortem (Phase 2 + re-verificado).

### DEVICE OBSERVED
- 13 settings residuales + prefs is_running=true + proceso muerto (FORENSIC PHASE 2, 2026-09-04 18:56 — última ventana de conexión).

### DERIVED
- El baseline restaurable con evidencia local legítima: 6 keys RESTORE_SAFE (ble_scan, wifi_scan, window_blurs, hwc_vds, send_action, activity_manager_constants-delete, wifi_bt_coexistence) + resto CAUTION/UNKNOWN según tabla PARTE 4.
- 4 keys del boost NO tienen restore en el código (wifi_watchdog_on, wifi_scan_interval_ms, wifi_power_save, wifi_low_latency_mode) — leak adicional del restore.

### UNKNOWN
- Estado ACTUAL de las keys del dispositivo (desconectado desde ~09:52 del 09-05): podría haber cambiado (reinicio del teléfono, otra sesión de boost, intervención del usuario).
- Valores pre-GameBoost de private_dns, animations, low_power_trigger, refresh, touch experimentales.

### CHANGES MADE TO DEVICE
**NONE** (cero comandos mutantes; ni siquiera read-only se pudieron ejecutar hoy).

### CHANGES MADE TO REPOSITORY
- Creado `experiments/forensic-phase3/` + este documento `F3A-PRE-RESTORE-BLOCKED-20260905-0952.md` (artefacto de evidencia; código NO modificado).
- Copia en `~/storage/downloads/F3A-PRE-RESTORE-BLOCKED-20260905-0952.md`.

### NEXT STEP
1. **RECONECTAR el ZTE por USB** (verificar `adb devices`).
2. Ejecutar el snapshot PRE-RESTORE (script §PARTE 5) y regenerar el reporte con datos reales.
3. Confirmar con el usuario los 3 puntos CAUTION que requieren su input: private_dns (¿tenía DNS privado configurado antes?), animation scales (¿usaba 1x?), auto_sync (¿lo tenía activado?).
4. Restaurar SOLO las RESTORE_SAFE con verificación antes/después por key.
5. POST-RESTORE snapshot + comparación.
6. Solo tras PASS de F3A → **FASE 3B (implementación F4: persistencia + safety restore) está JUSTIFICADA por la evidencia ya recolectada** (la decisión GO de Phase 2 no cambia por la desconexión).

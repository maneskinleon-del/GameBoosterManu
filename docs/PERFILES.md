# PERFILES.md — Matriz real de perfiles (medida, no aspiracional)

Fuente de verdad: `app/src/main/java/com/example/manager/ProfileManager.kt`
(`applyProfile`), verificada en el ZTE Z2352N vía `adb shell` con uid 2000
(shell) — el mismo uid que el executor Shizuku-ADB usa dentro de la app.
Medición: 2026-10-17, rama `docs/profiles-readme` (HEAD `066e369`).

## 1. Qué aplica cada perfil (desde el código)

`ProfileType` (valores del enum) → comandos que `applyProfile` ejecuta:

| Perfil | governor | refresh | animaciones | perf mode fijo | pointer_speed | mobilador |
|--------|----------|---------|-------------|----------------|---------------|-----------|
| 🔥 EXTREME | performance | 120 | 0 | sí (priority≥4) | 70% → raw 2 | no |
| ⌨️ FF MOUSE (ADS) | performance | 120 | 0 | sí (priority≥4) | 50% → raw 0 | on |
| 🎮 GAMING | performance | 120 | 0 | no | — | no |
| 🎯 FREE FIRE (touch) | performance | 120 | 0 | no | — | no |
| ⚖️ BALANCED | schedutil | 60 | 1 | no | — | no |
| 🔋 POWER SAVE | powersave | 60 | 1 | no | — | no |

Mecanismos exactos (línea `ProfileManager.kt:73-90`):

1. **Governor CPU**: `echo <gov> > /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_governor`
   y `/sys/devices/system/cpu/cpufreq/policy*/scaling_governor` (bucle por directorio).
2. **Refresh rate**: `settings put system peak_refresh_rate <R>.0` + `min_refresh_rate`.
3. **Animaciones**: `settings put global {window,transition,animator}_animation_scale <A>`.
4. **Modo performance fijo**: `cmd power set-fixed-performance-mode-enabled <true|false>`
   (true solo en EXTREME y ADS).
5. **Puntero** (solo ADS/EXTREME): funnel SSOT → `settings put system pointer_speed <raw>`
   (mapa: `raw = int(pct*0.14 - 7)`, `SystemMonitor.kt:612`).
6. **Mobilador** (solo ADS): toggle app-side; no es comando shell.

## 2. Verificación en el ZTE (uid 2000 = mismo privilegio que Shizuku-ADB)

Resultado por comando, con lectura de vuelta tras aplicar cada perfil:

| Comando | Resultado en ZTE | Evidencia cruda |
|---------|------------------|-----------------|
| governor CPU (sysfs) | **DENEGADO** | `can't create .../scaling_governor: Permission denied` ×11 nodos (cpu0-7 + policy0/4/7); el governor quedó inmutable `schedutil` en las 6 aplicaciones de perfil |
| `peak/min_refresh_rate` | **CAMBIA** | readback literal: 120.0/120.0 (EXTREME/ADS/GAMING), 60.0/60.0 (BALANCED/POWER_SAVE), exit=0 |
| 3× animation_scale | **CAMBIA** | readback literal: 0/0/0 vs 1/1/1, exit=0 |
| `set-fixed-performance-mode-enabled` | **SIN EFECTO observable** | exit=0 pero `dumpsys power` no muestra ninguna línea "fixed performance" ni antes ni después |
| `pointer_speed` (ADS/EXTREME) | **CAMBIA** | readback literal: 7 (snapshot) → 2 (EXTREME) → 0 (ADS), exit=0 |
| DPI (`wm density`) | **NO aplica por perfil** | `applyProfile` no lo llama; solo el ajuste manual del usuario (`GameBoostRepository.setDpi`, requiere Shizuku) |

**Conclusiones operativas del ZTE:**
- El governor CPU no se puede cambiar sin root en el Z2352N: la única escritura
  privilegiada que este kernel permite a uid 2000 falla. El perfil "performance"
  no existe aquí — lo único que varía el perfil es: refresh rate, animaciones,
  pointer_speed (ADS/EXTREME) y el intento de modo performance fijo.
- El modo performance fijo devuelve exit=0 pero no hay estado observable en
  `dumpsys power`: no hay forma de confirmar que haga nada en este firmware.
- Con Shizuku rish (uid 2000) los resultados son equivalentes; sin Shizuku la
  app degrada a Settings API y el bloque de governor falla igual.

## 3. Qué NO hace la app (y por qué)

| Ajuste prometido por el ecosistema "boost" | Estado real | Razón (medida) |
|---|---|---|
| DPI por perfil | No se aplica al cambiar de perfil | `applyProfile` no construye comando de densidad; `setDpi` es acción manual del usuario |
| Touch sampling 240Hz | No existe en el ZTE | `/sys/class/input/input*/touch_sampling_rate`: el kernel no expone el nodo (C1 de la prueba de candidatos) |
| GPU governor | No implementado | No hay comando ni nodo tocado por la app; el ZTE no publica governor GPU por sysfs accesible a uid 2000 (no hallado en `/sys/class/`) |
| Cambio de governor CPU | Escribe pero el kernel lo deniega | `scaling_governor` es root-only en el Z2352N (Permission denied medido) |
| Renice taskset del juego (-20) | No está en `applyProfile` | Solo existe como candidato externo (ver §4); renice sobre procesos de otros uid requeriría root |

## 4. Candidatos externos probados (estilo AutoJS6, uid 2000)

Producidos y medidos uno por uno con exit code + lectura de vuelta + restore:

| Candidato | exit | Lectura de vuelta | Clasificación | ¿Reincorporar? |
|-----------|------|-------------------|---------------|----------------|
| `echo 240 > touch_sampling_rate` (todos los input*) | n/a | nodo no existe | **NO-EXISTE** en Z2352N | No |
| `renice -n -20 -p <pid propio>` | 0 | nice 0 → **-20** | **CAMBIA** | Sí (solo para procesos del propio uid; procesos de otra app requerirían root) |
| `taskset -p <mask> <pid>` (máscara sin `0x`) | 0 | affinity ff → **f** | **CAMBIA** | Sí — la versión con `0x0F` falla (`bad mask`): toybox exige hex sin prefijo |
| `settings put global v_sync_behavior 1` | 0 | 0 → **1** | **CAMBIA** | Marginal — cambia el setting, efecto real en gameplay no demostrado |
| `settings put global wifi_sleep_policy 2` | 0 | 2 → 2 (ya estaba) | **SIN EFECTO** (ya al objetivo) | No |
| `settings put global wifi_scan_throttle_enabled 0` | 0 | 0 → 0 (ya estaba) | **SIN-DEMOSTRACIÓN** (original = target) | No |
| `cmd power set-fixed-performance-mode-enabled true` | 0 | sin cambio observable en `dumpsys power` | **SIN EFECTO observable** | No |
| `cmd thermalservice override-status 0` | OMITIDO | — | **PROHIBIDO por política** (desactiva mitigación térmica) | No |
| `cmd thermalservice override-status reset` | 255 | `NumberFormatException: "reset"` | **NO-SOPORTADO** en ZTE | No |
| escritura `scaling_governor` por policy | 1 | Permission denied (11 nodos) | **DENEGADO** | No |

Valores restaurados al final y verificados por lectura: refresh 120.0/120.0,
animaciones 0/0/0, pointer_speed=7 (estado previo del dispositivo),
wifi_scan_throttle_enabled=0 (original), v_sync_behavior=0 (original).

## 5. Migraciones pendientes vs doc antigua (discrepancias detectadas)

- **DPI por perfil**: la documentación anterior lo prometía por perfil (480/417);
  el código solo lo aplica por ajuste manual. Corregido en esta matriz.
- **Watchdog térmico**: `PreferenceManager.kt:129` instala
  `getBoolean(KEY_THERMAL_WATCHDOG, true)` y `GameBoostRepository.kt:190`
  `SettingToggle(context, "thermal_watchdog", true)` → **el default del código es
  ACTIVADO**, no apagado. Si la decisión del operador es "apagado por defecto",
  hay que cambiar el default en código (tarea separada, no en esta rama de docs).
- **Telemetría debug**: `SystemMonitor` emite CSV `ThermalSession` por tick
  (~2 s) solo en builds `BuildConfig.DEBUG` (ver README, sección Telemetría).

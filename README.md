<div align="center">

# 🎮 GameBoost Pro

**Optimización de rendimiento para Android con Shizuku**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Compose-Material3-blue)](https://developer.android.com/jetpack/compose)
[![API](https://img.shields.io/badge/API-24%2B-green)](https://developer.android.com/about/versions/lollipop)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

</div>

---

## ✨ Qué hace (trazable a la matriz de [docs/PERFILES.md](docs/PERFILES.md))

Cada afirmación de esta lista tiene su contrapartida en la matriz medida
(§1-§4 de [PERFILES.md](docs/PERFILES.md)):

- **6 perfiles de hardware**: EXTREME, FF MOUSE (ADS), GAMING, FREE FIRE
  (touch), BALANCED, POWER SAVE con combinación distinta de
  governor CPU + refresh rate + animaciones + modo performance fijo +
  pointer_speed (solo ADS/EXTREME).
- **Ejecución privilegiada vía Shizuku-ADB** (uid 2000) — sin root.
- **Refresh rate por `settings put system peak/min_refresh_rate`** —
  verificado CAMBIA en el ZTE (§2 del matriz).
- **Animaciones por `settings put global *_animation_scale`** —
  verificado CAMBIA (§2).
- **Velocidad del puntero por funnel SSOT** (`settings put system
  pointer_speed`, mapeo 0-100% → raw -7..7) — verificado CAMBIA (§2).
- **Watchdog anti-LMK** — el servicio foreground se reanuda
  automáticamente (`START_REDELIVER_INTENT`).
- **Telemetría de sesión térmica en builds debug** (CSV `ThermalSession`
  por tick ~2 s, ver §Telemetría abajo).
- **Detección automática de juegos** — UsageStatsManager polling
  (`GameDetector`); `UnifiedAccessibilityService` es suplementario y
  **no capta juegos nativos** como Free Fire (su motor de render no
  genera `TYPE_WINDOW_STATE_CHANGED`).

## ❌ Qué NO hace la app (verificado, no prometido)

| Ajuste | Estado | Por qué |
|--------|--------|---------|
| DPI por perfil | No se aplica al cambiar perfil | `applyProfile` no construye comando de densidad; solo ajuste manual del usuario |
| Touch sampling 240 Hz | No disponible en ZTE | El kernel no expone `touch_sampling_rate` (§4) |
| GPU governor | No implementado | Sin nodo/permiso usable por uid 2000 en el ZTE |
| Governor CPU en ZTE | Comando existe, kernel lo deniega | `scaling_governor` es root-only en Z2352N: `Permission denied` medido en 11 nodos |
| Modo performance fijo en ZTE | Comando aceptado (exit 0), sin estado observable en `dumpsys power` | No hay confirmación de que el firmware lo implemente |

→ Detalle por comando con evidencia cruda: [docs/PERFILES.md §2](docs/PERFILES.md).

## 👤 Watchdog térmico

La app incluye el toggle **"Watchdog Térmico"** (pantalla avanzada):
monitorea la temperatura y previene el sobrecalentamiento.

- **Estado por defecto (código vigente): DESACTIVADO**
  (`GameBoostRepository.kt:190` → `SettingToggle(..., false)` y
  `PreferenceManager.kt` → `getBoolean(..., false)`). El default anterior
  era ACTIVADO y se cambió a apagado hasta que la FASE B recalibre el
  governor: un pico instantáneo NO debe mandar a power save a usuarios
  nuevos.
- Si lo activas manualmente y la temperatura dispara un nivel, el
  Watchdog puede llevar la app a POWER SAVE incluso por un pico
  sensorial puntual. Para apagarlo: pantalla avanzada →
  "Watchdog Térmico" off. El governor de señal (`ThermalGovernor`)
  espera mediana-5 + dwell 30 s + histéresis 60 s para evitarlo
  (ThermalGovernorTest, 7 tests en verde), pero no está cableado al
  efecto hasta FASE B.
- ¿Cuándo activar el watchdog?: si tu juego se cierra por
  sobrecalentamiento (LMK térmico) Y notas el teléfono caliente.
  Recomendado: apagado durante sesiones de calibración y pruebas.

## 📱 Requisitos

| Requisito | Versión |
|-----------|---------|
| Android | 7.0+ (API 24) |
| Shizuku | Instalado y activo |

### ⚠️ Usar el Shizuku CLÁSICO (RikkaApps), NO el fork Shizuku+

| | Shizuku clásico (RikkaApps) | Fork Shizuku+ |
|---|---|---|
| Permiso API | `moe.shizuku.manager.permission.API_V23` | `af.shizuku.plus.permission.API_V23` |
| Compatibilidad | ✅ **Funciona** | ❌ **NO funciona** — la app pide el permiso clásico y el fork no lo concede |
| Verificación | `logcat \| grep ShizukuExecutor` → `✅ Shizuku OK` | Usa `SUBridge` (mecanismo distinto) |

**Solución**: instalar el clásico desde
[RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku/releases) y
activar por ADB:

```bash
# Fix de batería (capas chinas: Xiaomi/HyperOS, ZTE, Vivo, Huawei)
adb shell 'dumpsys deviceidle whitelist +moe.shizuku.privileged.api'
adb shell 'cmd appops set moe.shizuku.privileged.api RUN_ANY_IN_BACKGROUND allow'
adb shell 'am set-standby-bucket moe.shizuku.privileged.api active'

# Activar servidor (método oficial 13.6.0+)
APK_DIR=$(adb shell 'pm path moe.shizuku.privileged.api' | sed 's/package://; s|/base.apk||')
adb shell "cp $APK_DIR/lib/arm64/libshizuku.so /data/local/tmp/shizuku && chmod 755 /data/local/tmp/shizuku"
adb shell '/data/local/tmp/shizuku'

# Conceder permiso a la app
adb shell pm grant com.manuel.gameboostpro moe.shizuku.manager.permission.API_V23
```

> ⚠️ El servidor Shizuku **muere al reiniciar el teléfono** — hay que
> re-ejecutar `/data/local/tmp/shizuku` tras cada reinicio.

## 📲 Descargar APK (release estable)

**[⬇️ GameBoostPro-debug-b190221.apk — release estable `checkpoint-b190221`](https://github.com/maneskinleon-del/GameBoosterManu/releases/tag/checkpoint-b190221)**

| | |
|--|--|
| **Branch/commit** | `main` HEAD `b190221` |
| **Asset** | `GameBoostPro-debug-b190221.apk` |
| **SHA-256 (verificado: descargado == compilado)** | `ec34c42bd7ada4f254816bd82af153e6f60c3af37d1d92bbde404f8f4df1c73e` |
| **Incluye** | Telemetría `ThermalSession` (debug), `ThermalGovernor` + 7 tests, watchdog térmico **apagado por defecto**, verificación UI→executor→sistema documentada en [PERFILES.md §6](docs/PERFILES.md) |

## 📊 Telemetría de sesión (debug)

`SystemMonitor` emite una línea CSV por tick (~2 s) con el tag
`ThermalSession` **solo cuando `BuildConfig.DEBUG=true`** (el bloque se
elimina en release por R8, flag de compilación):

```
ts_ms,batteryLevel,batteryTemp,cpuMax,perfil,cpu-0-0=val,cpu-1-3=val,...
```

Captura de una sesión de juego:

```bash
adb logcat -c
# ... jugar la partida ...
adb logcat -v time -s ThermalSession > ~/session.log
python3 tools/summarize_session.py ~/session.log
```

`tools/summarize_session.py` calcula min/p50/median/p95/max por columna,
saltos >10 °C por zona cpu-* y los que sobreviven a un filtro mediana-5
(verificado sobre `tools/fixture_session.log`: 3 saltos crudos, 0 tras
el filtro, zona más volátil `cpu-0-0`).

## 🏗️ Arquitectura

```
com.example/
├── data/
│   ├── database/       # Room: GameEntity, ProfileEntity, LogEntity
│   ├── repository/     # GameBoostRepository (singleton facade)
│   └── PreferenceManager.kt
├── manager/            # Lógica de sistema
│   ├── ShizukuExecutor    # Comandos privilegiados (uid 2000)
│   ├── ProfileManager     # Perfiles de hardware (matriz §1)
│   ├── ThermalController  # Control de temperatura
│   ├── ThermalGovernor    # Governors de señal térmica (puro, 7 tests)
│   ├── SystemMonitor       # Métricas + telemetría ThermalSession
│   ├── GameDetector       # Detección de juegos (UsageStats)
│   └── ...
├── service/
│   ├── GameBoostService     # Foreground service anti-LMK
│   ├── UnifiedAccessibilityService
│   └── BootReceiver         # Reinicio post-boot
├── ui/
│   ├── FloatingPanelManager # Overlay flotante
│   └── ...
└── MainActivity.kt
```

## 🔒 Limitaciones conocidas

> **Alcance de la medición:** toda la matriz de perfiles y candidatos de
> [docs/PERFILES.md](docs/PERFILES.md) fue medida en un **ZTE Z2352N**
> (uid 2000). En otros equipos el resultado puede diferir — por ejemplo
> el Poco M8 sí reporta cpuMax (el ZTE devuelve `NaN`). Los candidatos
> `touch_sampling_rate` y `scaling_governor` deben repetirse en el M8
> cuando esté conectado por adb.

- **Governor CPU inaccesible sin root en kernels ZTE** (medido:
  `Permission denied` en cpu0-7 + policy0/4/7) — el cambio de perfil
  solo altera refresh/animaciones/pointer/perf-mode en esos equipos.
- **Modo performance fijo sin confirmación observable** en `dumpsys
  power` del firmware ZTE (exit=0 pero sin estado expuesto).
- **Lectura `thermal_zone` a veces falla sin permisos** — en el ZTE el
  cpuMax reporta `NaN` (esperable, latch térmico del OEM); el
  ThermalGovernor trata NaN como señal inválida y no actúa.
- Android 13+ hace batching de AlarmManager (~7 min en Doze) para el
  watchdog del servicio.
- **`renice`/`taskset` solo verificados sobre procesos del propio uid**
  (C2 de [PERFILES.md §4](docs/PERFILES.md)); su efecto sobre procesos
  de otras apps (el juego) está **no verificado** — probablemente
  requiera root, pero no se ha medido.

## 🔑 Shizuku clásico: symptoms del fork y fix

Ver sección de **Requisitos** ya arriba. Resumen técnico del fallo del
fork: `af.shizuku.plus.permission.API_V23` no coincide con el permiso
que la app declara (`moe.shizuku.manager.permission.API_V23`) →
`granted=false` → los comandos privilegiados fallan silenciosamente y
el logcat de `ShizukuExecutor` queda vacío.

## 🖱️ Keymapper recomendado: GG Mouse Pro 2

Para jugar Free Fire con mouse/teclado desde PC con scrcpy, el keymapper
evaluado que mejor funciona con este setup es **GG Mouse Pro 2**:

- Paquete: `com.zjx.ztezscreenshot`
- Activación: Shizuku clásico (ver Requisitos)
- Riesgo de ban: bajo (no clona el APK del juego)

```bash
adb shell appops set com.zjx.ztezscreenshot SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +com.zjx.ztezscreenshot
adb shell pm grant com.zjx.ztezscreenshot moe.shizuku.manager.permission.API_V23
```

**Alternativas evaluadas** (no recomendadas aquí): Mantis Gamepad Pro
(mapper gamepad, no teclado/mouse), Octopus (clona APK → riesgo de ban
alto), Panda Mouse Pro (compatible pero con drift).

## 📄 Licencia

MIT License

---

<div align="center">

**Desarrollado por Manu** 🇨🇱

</div>

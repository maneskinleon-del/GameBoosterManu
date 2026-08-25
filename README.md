<div align="center">

# 🎮 GameBoost Pro

**Optimización de rendimiento gaming para Android**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Compose-Material3-blue)](https://developer.android.com/jetpack/compose)
[![API](https://img.shields.io/badge/API-24%2B-green)](https://developer.android.com/about/versions/lollipop)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

*Perfiles de hardware, monitoreo en tiempo real, y optimización automática para Free Fire y otros juegos.*

</div>

---

## ✨ Características

- **5 perfiles de rendimiento:** EXTREME, FF MOUSE, GAMING, BALANCED, POWER SAVE
- **Detección automática** de juegos en ejecución vía AccessibilityService
- **Optimización por Shizuku/rish** — comandos privilegiados sin root
- **Panel flotante** con métricas en tiempo real (CPU, RAM, FPS, temperatura)
- **Watchdog anti-LMK** — el servicio se reinicia automáticamente si Android lo mata
- **Control de:** governor CPU, refresh rate, DPI, velocidad del puntero, animaciones
- **DNS automático** — Google DNS (dns.google) se aplica al cambiar de perfil
- **WiFi optimizado** — baja latencia + prioridad WiFi sobre Bluetooth
- **Indicador visual naranja** — perfil activo destacado con badge "ACTIVO"

## 📱 Requisitos

| Requisito | Versión |
|-----------|---------|
| Android | 7.0+ (API 24) |
| Shizuku | Instalado y activo |


## 📲 Descargar APK

**[⬇️ Descargar GameBoost Pro v1.2.3](https://github.com/maneskinleon-del/GameBoosterManu/releases/download/v1.2.3/app-debug.apk)**

> ⚠️ Al instalar, permite "Fuentes desconocidas" en la configuración de Android.

## 🚀 Instalación

### Opción 1: Compilar desde código fuente

1. Clona el repo:
```bash
git clone git@github.com:maneskinleon-del/GameBoosterManu.git
cd GameBoosterManu
```

2. Abre en Android Studio

3. Build & Run

4. En `app/build.gradle.kts`, elimina:
```kotlin
signingConfig = signingConfigs.getByName("debugConfig")
```

5. Build & Run

### Opción 2: AI Studio

[Ver en AI Studio](https://ai.studio/apps/0f4c4b23-5708-4c64-be11-2a547261544e)

## 🏗️ Arquitectura

```
com.example/
├── data/
│   ├── database/       # Room: GameEntity, ProfileEntity, LogEntity
│   ├── repository/     # GameBoostRepository (singleton facade)
│   └── PreferenceManager.kt
├── manager/            # Lógica de sistema
│   ├── ShizukuExecutor    # Comandos privilegiados
│   ├── ProfileManager     # Perfiles de hardware
│   ├── RamManager         # Limpieza de memoria
│   ├── ThermalController  # Control de temperatura
│   ├── GameDetector       # Detección de juegos
│   └── ...
├── service/
│   ├── GameBoostService        # Foreground service anti-LMK
│   ├── UnifiedAccessibilityService
│   ├── ServiceWatchdogReceiver # Watchdog AlarmManager
│   └── BootReceiver            # Reinicio post-boot
├── ui/
│   ├── FloatingPanelManager   # Overlay flotante
│   ├── theme/
│   └── viewmodel/
└── MainActivity.kt
```

## 🎯 Perfiles

| Perfil | Governor | Refresh | DPI | Uso |
|--------|----------|---------|-----|-----|
| 🔥 EXTREME | performance | 120Hz | 480 | Máximo rendimiento |
| ⌨️ FF MOUSE | performance | 120Hz | 480 | Free Fire + mouse |
| 🎮 GAMING | schedutil | 90Hz | 480 | Gaming general |
| ⚖️ BALANCED | schedutil | 60Hz | 417 | Uso diario |
| 🔋 POWER SAVE | powersave | 60Hz | 417 | Ahorro de batería |

## 📊 Scripts de diagnóstico

```bash
# Capturar logs durante una partida
bash capturar_logs.sh

# Medir FPS/RAM/CPU durante 60s
bash medir_rendimiento.sh
```

## 🔧 Dependencias principales

| Librería | Uso |
|----------|-----|
| Jetpack Compose | UI declarativa |
| Room | Base de datos local |
| Shizuku | Comandos privilegiados |
| Lifecycle | StateFlow + ViewModel |
| Coroutines | Operaciones async |

## 📝 Documentación

- [AGENTS.md](AGENTS.md) — Guía para AI agents
- [MEMORIA.md](MEMORIA.md) — Sesión de estabilidad y fixes
- [PERFILES.md](PERFILES.md) — Detalle de perfiles de hardware
- [SISTEMA_DE_MONITOREO.md](SISTEMA_DE_MONITOREO.md) — Sistema de métricas
- [bugs.md](bugs.md) — Bug tracker (28 bugs documentados)
- [SESION-2026-08-04.md](SESION-2026-08-04.md) — Sesión DNS + UI improvements
- [SESION-2026-08-24.md](SESION-2026-08-24.md) — Sesión perfil manual, reapply, exit fix, batería

## ⚠️ Limitaciones conocidas

- Escritura de `scaling_governor` bloqueada en algunos kernels (ZTE, Xiaomi) — **mitigado** con skip automático
- Lectura de `thermal_zone` puede no funcionar sin permisos
- `renice` y `taskset` requieren Shizuku activo
- Android 13+ hace batching de AlarmManager (~7 min para Doze)

## 🔑 Shizuku: situación y requisito crítico

**GameBoost Pro necesita Shizuku activo** para ejecutar comandos privilegiados (governor, refresh rate, DPI, pointer speed) sin root.

### ⚠️ Usar el Shizuku CLÁSICO (RikkaApps), NO el fork Shizuku+

| | Shizuku clásico (RikkaApps) | Fork Shizuku+ |
|---|---|---|
| Permiso API | `moe.shizuku.manager.permission.API_V23` | `af.shizuku.plus.permission.API_V23` |
| Compatibilidad | ✅ **Funciona con GameBoost Pro** | ❌ **NO funciona** — la app pide el permiso clásico y el fork no lo concede |
| Servidor | `shizuku_server` | `shizuku_plus_server` |
| Verificación | `logcat \| grep ShizukuExecutor` → `✅ Shizuku OK` | Usa `SUBridge` (mecanismo distinto) |

**Síntoma del fork**: la app se abre pero los comandos privilegiados fallan silenciosamente (el logcat de `ShizukuExecutor` queda vacío). El fork usa `af.shizuku.plus.permission.API_V23` mientras que GameBoost Pro (y GG Mouse Pro 2) esperan `moe.shizuku.manager.permission.API_V23` → `granted=false`.

**Solución**: desinstalar el fork e instalar el clásico desde [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku/releases), luego activar por ADB:

```bash
# Fix de batería (capas chinas: Xiaomi/HyperOS, ZTE, Vivo, Huawei)
adb shell 'dumpsys deviceidle whitelist +moe.shizuku.privileged.api'
adb shell 'cmd appops set moe.shizuku.privileged.api RUN_ANY_IN_BACKGROUND allow'
adb shell 'am set-standby-bucket moe.shizuku.privileged.api active'

# Activar (método oficial 13.6.0+)
APK_DIR=$(adb shell 'pm path moe.shizuku.privileged.api' | sed 's/package://; s|/base.apk||')
adb shell "cp $APK_DIR/lib/arm64/libshizuku.so /data/local/tmp/shizuku && chmod 755 /data/local/tmp/shizuku"
adb shell '/data/local/tmp/shizuku'

# Conceder permiso a la app
adb shell pm grant com.manuel.gameboostpro moe.shizuku.manager.permission.API_V23
```

> ⚠️ El servidor Shizuku **muere al reiniciar el teléfono** — hay que re-ejecutar `/data/local/tmp/shizuku` tras cada reinicio (o usar depuración inalámbrica).

## 🖱️ Keymapper recomendado: GG Mouse Pro 2

Para jugar Free Fire con mouse/teclado desde PC (scrcpy), **GG Mouse Pro 2** es el keymapper que mejor funciona con este setup:

- **Paquete**: `com.zjx.ztezscreenshot`
- **Activación**: Shizuku (requiere el clásico, ver arriba)
- **Compatibilidad Free Fire**: ✅ Excelente
- **Riesgo de ban**: Muy bajo (no clona el APK del juego)

```bash
# Permisos necesarios
adb shell appops set com.zjx.ztezscreenshot SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +com.zjx.ztezscreenshot
adb shell pm grant com.zjx.ztezscreenshot moe.shizuku.manager.permission.API_V23
```

> ⚠️ **No usar el fork Shizuku+ con GG Mouse Pro 2** — mismo problema que con GameBoost Pro: el fork no concede el permiso clásico y el keymapper no puede inyectar comandos.

**Alternativas evaluadas** (no recomendadas para este caso):
- **Mantis Gamepad Pro** — mapper de *gamepad* físico, NO de teclado/mouse. No sirve para este setup.
- **Octopus** — clona el APK (virtual sandbox) → **alto riesgo de ban** en Free Fire. Evitar.
- **Panda Mouse Pro** — compatible pero con más drift y problemas ocasionales con anti-cheat.

## 📄 Licencia

MIT License

---

<div align="center">

**Desarrollado por Manu** 🇨🇱

*Free Fire + Android + Optimización + IA*

</div>

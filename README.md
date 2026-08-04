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
- **Gemini AI** — análisis inteligente de rendimiento (opcional)

## 📱 Requisitos

| Requisito | Versión |
|-----------|---------|
| Android | 7.0+ (API 24) |
| Shizuku | Instalado y activo |
| Opcional | Gemini API key (para análisis AI) |

## 🚀 Instalación

### Opción 1: Compilar desde código fuente

1. Clona el repo:
```bash
git clone git@github.com:maneskinleon-del/GameBoosterManu.git
cd GameBoosterManu
```

2. Abre en Android Studio

3. Crea `.env` con tu API key:
```
GEMINI_API_KEY=tu_api_key_aqui
```

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
- [bugs.md](bugs.md) — Bug tracker

## ⚠️ Limitaciones conocidas

- Escritura de `scaling_governor` bloqueada en algunos kernels (ZTE, Xiaomi)
- Lectura de `thermal_zone` puede no funcionar sin permisos
- `renice` y `taskset` requieren Shizuku activo
- Android 13+ hace batching de AlarmManager (~7 min para Doze)

## 📄 Licencia

MIT License

---

<div align="center">

**Desarrollado por Manu** 🇨🇱

*Free Fire + Android + Optimización + IA*

</div>

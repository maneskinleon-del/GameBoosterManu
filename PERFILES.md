# Perfiles de GameBoost Pro — Guía Comparativa

> **Última actualización:** Agosto 2026
> Basado en investigación de configuraciones competitivas de Free Fire (XENKZONE, Cashify, BlueStacks, NoPing)

---

## Parámetros base del sistema

| Parámetro | Rango Android | Valor competitivo | Explicación |
|-----------|--------------|-------------------|-------------|
| **Governor** | `powersave` / `schedutil` / `performance` | `performance` | Frecuencia CPU máxima estable, mínima latencia |
| **Refresh Rate** | 60–144 Hz | **120 Hz** | Movimiento suave, ventaja en tracking |
| **Pointer Speed** | raw -7 a +7 | **raw 1–3** (50–75%) | Estabilidad sobre velocidad bruta; raw 7 causa overshoot |
| **Animation Scale** | 0–10 | **0** | Sin animaciones = respuesta instantánea |
| **DPI** | 320–960 | **440–480** | Moderado, evita pixel skipping |

---

## Tabla comparativa de perfiles

| Perfil | DPI | Governor | Refresco | Pointer (raw) | Anim. | Uso recomendado | Veredicto |
|--------|-----|----------|----------|---------------|-------|-----------------|-----------|
| 🔥 **EXTREME** | **480** | `performance` | 120 Hz | 2 (70%) | 0x | Competitivo máximo, torneos | ✅ Competitivo |
| ⌨️ **FF MOUSE** | **460** | `performance` | 120 Hz | 0 (50%) | 0x | Free Fire + ggmouse/teclado | ✅ Competitivo |
| 🎮 **GAMING** | **440** | `performance` | 120 Hz | 1 (60%) | 0x | Juegos táctiles en general | ✅ Competitivo |
| 🎯 **FREE FIRE** | **460** | `performance` | 120 Hz | 1 (60%) | 0x | Free Fire táctil (sin mouse) | ✅ Competitivo |
| ⚖️ **BALANCED** | **360** | `schedutil` | 60 Hz | 0 (50%) | 1x | Uso diario, redes sociales | ✅ Default sistema |
| 🔋 **POWER SAVE** | **320** | `powersave` | 60 Hz | -2 (30%) | 1x | Batería baja, sesiones largas | ✅ Ahorro energía |

### Mapeo pointer speed

| Pointer | % del slider | Raw | Efecto |
|---------|-------------|-----|--------|
| 10 | 100% | +7 | Máxima — overshoot |
| 7 | 70% | +2 | Rápida controlable |
| 6 | 60% | +1 | Ligeramente rápida |
| 5 | 50% | 0 | Default del sistema |
| 3 | 30% | -2 | Lenta |

---

## Recomendaciones por escenario

| Escenario | Perfil ideal | Por qué |
|-----------|-------------|---------|
| Free Fire + mouse/teclado (ggmouse) | ⌨️ **FF MOUSE** | `performance` governor, raw 0 (el mouse maneja sensibilidad) |
| Free Fire táctil (sin mouse) | 🎯 **FREE FIRE** | `performance` + 120 Hz, raw 1 controlable |
| Competitivo máximo (torneo) | 🔥 **EXTREME** | `performance` + 120 Hz + raw 2 + anim 0 |
| Otros juegos táctiles | 🎮 **GAMING** | `performance` + 120 Hz, máximo rendimiento |
| Uso diario / redes sociales | ⚖️ **BALANCED** | Valores default del sistema |
| Batería baja / larga sesión | 🔋 **POWER SAVE** | `powersave` + anim 1 + raw lento |

---

## Detección automática de perfiles

Actualmente implementada en `GameSessionManager.simulateGameLaunchInternal()`:

| App detectada | Dispositivos externos | Perfil que se aplica |
|---------------|----------------------|---------------------|
| Free Fire / Garena | Sí (ggmouse, teclado, etc.) | ⌨️ **FF MOUSE** |
| Free Fire / Garena | No | 🎯 **FREE FIRE** |
| Cualquier mapper (gg.mouse, scrcpy, etc.) | — | ⌨️ **FF MOUSE** |
| Otros juegos | No | Perfil activo actual |

---

## Notas técnicas

### Pointer speed mapping

El perfil usa `pointerSpeed` en escala 0–10. Al aplicarse se multiplica por 10 y se mapea a raw mediante:

```kotlin
fun mapPercentToRawSpeed(percent: Int): Int {
    return ((percent / 100f) * 14 - 7).toInt().coerceIn(-7, 7)
}
```

Donde `percent = profile.pointerSpeed * 10`.

### Governor compatible

El comando de governor soporta ambos layouts de kernel que se encuentran en dispositivos Android:

```bash
for dir in /sys/devices/system/cpu/cpu[0-9]*/cpufreq \
            /sys/devices/system/cpu/cpufreq/policy*; do
    [ -f "$dir/scaling_governor" ] && echo performance > "$dir/scaling_governor"
done
```

### Thermal override

El perfil EXTREME tiene `thermalOverride = true`, lo que significa que el watchdog térmico no lo desactivará automáticamente.

---

## Fuentes de la investigación

- [XENKZONE — Sensibilidad Prohibida Free Fire](https://xenkzone.com/la-supuesta-sensibilidad-prohibidade-free-fire/)
- [Cashify — Best Free Fire Max Sensitivity Settings](https://www.cashify.in/best-free-fire-max-sensitivity-settings)
- [NoPing — Best Free Fire Sensitivity](https://noping.com/blog/best-free-fire-sensitivity)
- [BlueStacks — FF Best Settings for Headshots](https://www.bluestacks.com/blog/game-guides/free-fire-battlegrounds/ff-best-settings-for-headshots-en.html)

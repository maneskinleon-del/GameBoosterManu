# MEMORIA — Sesión de estabilidad GameBoost Pro

**Fecha:** 17 Julio 2026
**Problema:** GameBoost Pro se cierra al abrir Free Fire (com.dts.freefireth)
**Dispositivo:** 1080x2400, densidad 480/417, Android con kernel bloqueado

---

## 1. Análisis inicial — ¿Por qué se cierra GameBoost?

### Causas identificadas (por probabilidad)

| # | Causa | Explicación |
|---|-------|-------------|
| 1 | **LMK (Low Memory Killer)** | Free Fire consume mucha RAM. Android mata servicios en segundo plano (incluso foreground services) cuando hay presión de memoria |
| 2 | **`RamManager.clean(force=true)` inmediato** | Al detectar el juego, se ejecutaba `ramManager.clean(force=true)` que mata apps y hace `drop_caches`, justo cuando Free Fire está cargando → el sistema entra en pánico |
| 3 | **`echo 1 > /proc/sys/vm/drop_caches`** | Borra toda la caché del kernel de golpe, causando que el sistema tenga que recargar todo desde cero → LMK se activa |
| 4 | **Choreographer FrameCallback en Main Thread** | Se registraba un callback que se ejecuta CADA FRAME (60-120fps) en el Main Thread cuando hay un juego activo → contribuye a la presión de memoria |
| 5 | **`renice -20` + `taskset`** | Intentaba dar máxima prioridad a Free Fire, pero en dispositivos sin root esto falla y puede saturar Shizuku |
| 6 | **Watchdog térmico agresivo** | Si la CPU subía de 45°C, cambiaba el perfil abruptamente, causando cambios de estado inconsistentes |
| 7 | **Reflection en ShizukuExecutor** | Usaba `Shizuku.newProcess()` via reflection, que puede crashear si la API fue eliminada |
| 8 | **`cmd activity set-process-limit -1`** | No funciona en todos los dispositivos y no tiene fallback |

---

## 2. Cambios realizados

### 2.1 `app/src/main/java/com/example/manager/RamManager.kt`

**Objetivo:** Suavizar la limpieza de RAM para no estresar al sistema cuando Free Fire está cargando.

```diff
- fun clean(force: Boolean = false) {
+ fun clean(force: Boolean = false) {  // force=true ya no es tan agresivo
```

- ❌ Eliminado `getBackgroundPackages()` + `am force-stop` masivo en modo forzado
- ❌ Eliminado `echo 1 > /proc/sys/vm/drop_caches` del `cleanCache()` (causaba kills del LMK)
- ✅ En modo forzado ahora solo mata la `killList` (Facebook, Instagram, etc.) y solo si el modo agresivo está activo
- ✅ `cleanCache()` ahora solo ejecuta `pm trim-caches 128M` (suave)

### 2.2 `app/src/main/java/com/example/manager/ShizukuExecutor.kt`

**Objetivo:** Evitar que la reflection sobre `Shizuku.newProcess()` crashee la app.

```diff
- catch (e: NoSuchMethodException) {
-     throw RuntimeException("Shizuku.newProcess() no encontrado...", e)  // ¡CRASH!
- }
+ catch (e: Exception) {
+     return Runtime.getRuntime().exec(cmdArray)  // Fallback seguro
+ }
```

- ✅ Captura `NoSuchMethodException`, `IllegalAccessException` y cualquier `Exception`
- ✅ Fallback a `Runtime.exec()` (sin privilegios Shizuku, pero no crashea)

### 2.3 `app/src/main/java/com/example/data/repository/GameBoostRepository.kt`

**Objetivo:** Espaciar las optimizaciones y reducir presión de memoria al detectar juego.

```diff
// applyBoostSettings() - antes era inmediato y agresivo
- ramManager.clean(force = true)  // Inmediato
+ delay(5000)  // Espera 5s a que el juego cargue
+ ramManager.clean(force = false)  // Sin force
```

```diff
// applyHighPriorityOptimizations() - antes todo en paralelo
- 4 comandos juntos + renice -20 + taskset
+ FASE 1: governor + perf mode (inmediato)
+ FASE 2: refresh rate (delay 500ms)
+ FASE 3: renice -10 + taskset (delay 1s)
```

- ❌ Eliminado `Choreographer` FrameCallback (corría en Main Thread cada frame)
- ❌ Eliminado `cmd activity set-process-limit -1` (no tiene fallback, llena logs de errores)
- ✅ `applyBoostSettings()` ahora espera 5s antes de limpiar RAM y sin `force=true`
- ✅ `applyHighPriorityOptimizations()` dividido en 3 fases con delays
- ✅ `renice` bajado de -20 a -10 (menos agresivo)
- ✅ Comando del governor ahora prueba ambos layouts de sysfs (`cpu[0-9]*/cpufreq` y `cpufreq/policy*`)

### 2.4 `app/src/main/java/com/example/service/GameBoostService.kt`

**Objetivo:** Evitar que Android LMK mate el servicio.

```diff
- return START_STICKY
+ return START_REDELIVER_INTENT  // Se reinicia automáticamente si LMK lo mata
```

```diff
- NotificationManager.IMPORTANCE_LOW
+ NotificationManager.IMPORTANCE_HIGH  // Alta prioridad anti-LMK
```

```diff
- NotificationCompat.Builder(...)
+   .setOngoing(true)  // No descartable
+   .setPriority(NotificationCompat.PRIORITY_MAX)  // Prioridad máxima
```

### 2.5 `app/src/main/java/com/example/manager/WatchdogManager.kt`

**Objetivo:** Evitar cambios bruscos de perfil por temperatura.

```diff
- cpuTemp >= 50f -> baterry_saver (cambio inmediato)
- cpuTemp >= 45f -> balanced (si estaba en extreme)
+ cpuTemp >= 55f -> battery_saver (con cooldown de 2 min)
+ cpuTemp >= 50f -> balanced (solo si estaba en extreme)
+ cpuTemp <= 40f && lastProfile != null -> restaurar perfil anterior
```

- ✅ Umbral crítico subido de 50°C → 55°C
- ✅ Cooldown de 2 minutos entre acciones térmicas
- ✅ Restauración automática del perfil anterior cuando la temperatura baja

### 2.6 `app/src/main/java/com/example/ui/FloatingPanelManager.kt`

- ❌ Cambiado `echo 3 > /proc/sys/vm/drop_caches` → `pm trim-caches 128M`

### 2.7 `app/src/main/java/com/example/manager/ProfileManager.kt`

- ✅ Comando del governor actualizado para probar ambos layouts de sysfs
- ✅ `[ -f "$dir/scaling_governor" ]` antes de escribir (evita errores si no existe)
- ✅ `2>/dev/null` silencia errores de permisos

### 2.8 `app/src/main/java/com/example/service/ServiceWatchdogReceiver.kt` (NUEVO)

**Objetivo:** Watchdog anti-LMK basado en AlarmManager que reinicia automáticamente GameBoostService si es matado.

```kotlin
class ServiceWatchdogReceiver : BroadcastReceiver() {
    // Se activa periódicamente vía AlarmManager
    // Si GameBoostService.isRunning == false y debería estarlo
    //   → Lo reinicia con startForegroundService()
}
```

- ✅ Usa `AlarmManager.RTC_WAKEUP` — persiste incluso si el proceso de la app es matado
- ✅ Intervalo de 30s (pero Android 13 lo batcha a ~7 min por Doze mode)
- ✅ Se cancela automáticamente cuando el usuario detiene el servicio
- ✅ Verifica `PreferenceManager.isServiceRunning()` para no reiniciar si el usuario lo apagó

### 2.9 `app/src/main/java/com/example/service/BootReceiver.kt` (NUEVO)

**Objetivo:** Reprogramar el watchdog después de un reinicio del dispositivo.

- ✅ Escucha `ACTION_BOOT_COMPLETED`
- ✅ Si el servicio estaba activo antes del reinicio, lo reinicia automáticamente
- ✅ Reprograma la alarma del watchdog

### 2.10 `AndroidManifest.xml`

- ✅ Agregado permiso `RECEIVE_BOOT_COMPLETED`
- ✅ Registrados `ServiceWatchdogReceiver` y `BootReceiver`

### 2.11 Scripts de diagnóstico (NUEVOS en raíz del proyecto)

| Script | Propósito |
|--------|-----------|
| `capturar_logs.sh` | Captura logs de sistema + GameBoost durante una partida de Free Fire. Busca LMK kills, crashes, watchdog events |
| `medir_rendimiento.sh` | Mide FPS, CPU, RAM y temperatura durante 60s de juego. Genera resumen con promedios |

---

## 3. Diagnóstico del dispositivo (via ADB)

### Especificaciones detectadas

| Característica | Valor |
|---------------|-------|
| Resolución | 1080x2400 |
| Densidad | 480 (override: 417) |
| CPU cores | 8 (policies: 0, 4, 7) |
| Governors disponibles | userspace, conservative, powersave, performance, schedutil |
| Governor actual | schedutil |
| Shizuku | Instalado (moe.shizuku.privileged.api) |
| Batería | 81-82%, 36.0°C |
| Free Fire | Detected (com.dts.freefireth) |

### Restricciones del kernel detectadas

| Operación | Resultado | Detalle |
|-----------|-----------|---------|
| Escribir scaling_governor | ❌ **Permission denied** | Incluso via ADB shell. SELinux/Kernel bloquea cambios de governor |
| Leer thermal_zone*/temp | ❌ **Permisos ?????????** | Los archivos existen pero no son legibles por el user shell |
| Leer /proc/stat | ✅ OK | CPU usage funciona |
| Leer BatteryManager | ✅ OK | Nivel y temperatura de batería funcionan |
| Dumpsys gfxinfo Free Fire | ✅ OK | Frame timing disponible para medir FPS |
| Dumpsys gfxinfo GameBoost | ✅ Sin datos | Esperado — la app no tiene UI propia |

---

### Watchdog — verificación en AlarmManager

| Métrica | Resultado |
|---------|-----------|
| Watchdog registrado | ✅ `com.example.action.WATCHDOG_CHECK` activo en AlarmManager |
| Tipo de alarma | `RTC_WAKEUP` (despierta el dispositivo) |
| Intervalo configurado | 30 segundos |
| Intervalo real (Android 13) | ~7 minutos (batching de Doze mode) |
| Próxima ejecución | 2026-07-17 18:18:36 |

---

## 4. Resultado de las pruebas

### ✅ Lo que funciona
- Instalación y ejecución de la app
- Servicio foreground con notificación (IMPORTANCE_HIGH)
- Detección de Free Fire via accesibilidad
- Aplicación de optimizaciones (las que no requieren kernel)
- Modo Mobilador (3 comandos OK)
- Monitoreo de métricas (CPU usage, RAM, batería)
- Watchdog térmico (aunque no pueda leer temp real, usa fallback)
- El proceso de la app NO muere inmediatamente

### ❌ Lo que aún falla / limitaciones
- Escritura de governor (bloqueado por kernel del dispositivo ZTE)
- Lectura de temperatura CPU (bloqueado por kernel)
- `cmd activity set-process-limit -1` (eliminado del código)
- Comandos `renice` y `taskset` probablemente también fallen por permisos

### ⏱️ Pendiente de probar
- Si el watchdog (AlarmManager) reinicia el servicio cuando es matado por LMK
- Si START_REDELIVER_INTENT reinicia el servicio rápido
- Si los scripts `capturar_logs.sh` y `medir_rendimiento.sh` funcionan correctamente

---

## 5. Próximos pasos sugeridos

### Para mejorar la estabilidad:

1. **Probar el watchdog** — Forzar detención del servicio y ver si el watchdog lo reinicia en <30s (o <7min por batching de Android 13)
2. **Ajustar watchdog a setExact()** — Para evitar el batching de Android 13 y que responda en segundos
3. **Desactivar optimizaciones de batería del fabricante (ZTE)** — Ir a Ajustes → Apps → GameBoost Pro → Batería → Sin restricciones
4. **Bloquear la app en RAM** — Desde apps recientes, bloquear GameBoost Pro para que no sea limpiado

### Para diagnosticar:

1. **`bash capturar_logs.sh`** — Captura logs durante una partida para ver si LMK mata el servicio
2. **`bash medir_rendimiento.sh`** — Mide FPS/RAM/CPU durante 60s de juego

---

## 6. Archivos modificados (resumen)

| Archivo | Cambios |
|---------|---------|
| `RamManager.kt` | Quitado drop_caches, suavizada limpieza forzada |
| `ShizukuExecutor.kt` | Fallback en reflection para evitar crash |
| `GameBoostRepository.kt` | Optimizaciones espaciadas, quitado Choreographer, quitado cmd activity |
| `GameBoostService.kt` | START_REDELIVER_INTENT, IMPORTANCE_HIGH, PRIORITY_MAX, ongoing |
| `WatchdogManager.kt` | Hysteresis térmica, cooldown, restauración automática |
| `FloatingPanelManager.kt` | drop_caches → trim-caches |
| `ProfileManager.kt` | Governor con ambos layouts de sysfs |
| `ServiceWatchdogReceiver.kt` | **NUEVO** — Watchdog anti-LMK via AlarmManager |
| `BootReceiver.kt` | **NUEVO** — Reinicio post-boot |
| `AndroidManifest.xml` | RECEIVE_BOOT_COMPLETED, receivers registrados |
| `capturar_logs.sh` | **NUEVO** — Script de captura de logs |
| `medir_rendimiento.sh` | **NUEVO** — Script de medición de rendimiento |
| `MEMORIA.md` | Este documento |
| `bugs.md` | Actualizado con bugs y fixes de esta sesión |

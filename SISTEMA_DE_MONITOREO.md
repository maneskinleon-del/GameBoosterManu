# Sistema de Monitoreo de GameBoost Pro

Documentación detallada de cómo la app **GameBoost Pro** lee las métricas del sistema: temperatura, CPU, batería, RAM, ping, y más.

---

## 1. Temperatura de CPU (`cpuTemp`)

**Archivo:** `app/src/main/java/com/example/data/repository/GameBoostRepository.kt`
**Método:** `getRealCpuTemp()`

Lee archivos del sistema de thermal zones de Linux en `/sys/class/thermal/`:

```kotlin
private fun getRealCpuTemp(): Float {
    val thermalFiles = listOf(
        "/sys/class/thermal/thermal_zone0/temp",       // Ruta común en mayoría de ROMs
        "/sys/class/thermal/thermal_zone1/temp",       // Zona alternativa
        "/sys/devices/virtual/thermal/thermal_zone0/temp" // Ruta virtual adicional
    )
    for (file in thermalFiles) {
        try {
            val temp = File(file).readText().trim().toFloat()
            return if (temp > 1000) temp / 1000 else temp  // Algunos kernels reportan milligrados
        } catch (e: Exception) {}
    }
    return 38.0f  // Default si no puede leer
}
```

**Detalles:**
- Intenta 3 rutas diferentes del kernel (por compatibilidad con distintas ROMs/kernels).
- Si el valor es > 1000, divide entre 1000 (pasa de milligrados a grados Celsius).
- Si todas las rutas fallan, retorna 38.0°C como valor por defecto.
- Se ejecuta cada **2 segundos** en el bucle principal de monitoreo (`startMonitoringLoop`).

---

## 2. Temperatura de Batería (`batteryTemp`)

**Archivo:** `app/src/main/java/com/example/data/repository/GameBoostRepository.kt`
**Método:** `getRealBatteryInfo()`

Usa el `BatteryManager` de Android vía un `BroadcastReceiver`:

```kotlin
private fun getRealBatteryInfo(): Pair<Int, Float> {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val pct = ... // Nivel de batería
    val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    return Pair(pct, temp)
}
```

**Detalles:**
- `BatteryManager.EXTRA_TEMPERATURE` devuelve décimas de grado Celsius (e.g., 365 = 36.5°C).
- La app divide entre 10 para obtener grados Celsius.
- Valor por defecto si falla: 36.5°C.
- Se ejecuta cada **2 segundos**.

---

## 3. Uso de CPU (`cpuUsage`)

**Archivo:** `app/src/main/java/com/example/data/repository/GameBoostRepository.kt`
**Método:** `getRealCpuUsage()`

Lee `/proc/stat` y calcula el delta de tiempo idle vs total (sistema Linux estándar):

```kotlin
private fun getRealCpuUsage(): Int {
    val statLines = File("/proc/stat").readLines()
    val cpuLine = statLines.firstOrNull { it.startsWith("cpu ") } ?: return 0
    val parts = cpuLine.split("\\s+".toRegex()).drop(1)
    val user = parts[0].toLong()
    val nice = parts[1].toLong()
    val system = parts[2].toLong()
    val idle = parts[3].toLong()
    val total = user + nice + system + idle

    val totalDelta = total - lastCpuTotalTicks
    val idleDelta = idle - lastCpuIdleTicks

    if (totalDelta <= 0) return 0
    return ((totalDelta - idleDelta) * 100 / totalDelta).toInt().coerceIn(0, 100)
}
```

**Detalles:**
- Técnica clásica de Linux: comparar dos lecturas de `/proc/stat` con el tiempo transcurrido.
- **Idle ticks** = ocioso; **Total ticks** = user + nice + system + idle.
- Fórmula: `(totalDelta - idleDelta) * 100 / totalDelta`.
- La primera lectura retorna 0 (porque no hay delta anterior).
- Se ejecuta cada **2 segundos**.

---

## 4. Nivel de Batería (`batteryLevel`)

**Archivo:** `app/src/main/java/com/example/data/repository/GameBoostRepository.kt`
**Método:** `getRealBatteryInfo()`

```kotlin
val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
val pct = (level * 100 / scale.toFloat()).toInt()
```

**Detalles:**
- Usa el `Intent.ACTION_BATTERY_CHANGED` con `null` receiver (lectura del último estado conocido, no requiere registro persistente).
- `EXTRA_LEVEL` = nivel actual, `EXTRA_SCALE` = escala máxima (normalmente 100).
- Fórmula: `(level / scale) * 100`.
- Valor por defecto: 80%.
- Se ejecuta cada **2 segundos**.

---

## 5. Uso de RAM (`ramUsed`, `ramTotal`)

**Archivo:** `app/src/main/java/com/example/data/repository/GameBoostRepository.kt`
**Método:** `getRealRamInfo()`

Usa `ActivityManager.MemoryInfo`:

```kotlin
private fun getRealRamInfo(): Pair<Long, Long> {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    val totalRam = memoryInfo.totalMem / (1024 * 1024)  // bytes a MB
    val availRam = memoryInfo.availMem / (1024 * 1024)  // bytes a MB
    return Pair(totalRam - availRam, totalRam)  // (usada MB, total MB)
}
```

**Detalles:**
- `totalMem` = RAM física total del dispositivo (en bytes).
- `availMem` = RAM disponible actualmente (en bytes).
- Se devuelve en **megabytes (MB)**.
- También hay un `RamManager` (en `app/src/main/java/com/example/manager/RamManager.kt`) que hace lo mismo y además calcula el porcentaje de uso.
- Valor por defecto si falla: (2048, 4096).
- Se ejecuta cada **2 segundos**.

---

## 6. Ping / Latencia de Red (`ping`)

**Archivo:** `app/src/main/java/com/example/data/repository/GameBoostRepository.kt`
**Método:** `getRealPing()`

Ejecuta `ping` vía `Runtime.exec()`:

```kotlin
private suspend fun getRealPing(): Int {
    val process = Runtime.getRuntime().exec("ping -c 1 -w 1 8.8.8.8")
    val start = System.currentTimeMillis()
    val exitValue = process.waitFor()
    if (exitValue == 0) {
        val ping = (System.currentTimeMillis() - start).toInt()
        lastMeasuredPing = ping
        return ping
    }
    return lastMeasuredPing  // Retorna el último valor conocido si falla
}
```

**Detalles:**
- Ejecuta `ping -c 1 -w 1 8.8.8.8` (1 paquete, timeout de 1 segundo).
- Mide el tiempo de ida y vuelta (RTT).
- Si falla el ping, retorna el último valor medido exitosamente.
- Se ejecuta cada **2 segundos**.

---

## 7. Velocidad del Puntero (`pointerSpeed`)

**Archivo:** `app/src/main/java/com/example/data/repository/GameBoostRepository.kt`
**Método:** `getRawPointerSpeed()` y `mapRawSpeedToPercent()`

Lee la configuración del sistema Android via Shizuku:

```kotlin
private suspend fun getRawPointerSpeed(): Int {
    val result = ShizukuExecutor.runCommand("settings get system pointer_speed")
    return result.getOrNull()?.trim()?.toInt() ?: 0
}

fun mapRawSpeedToPercent(raw: Int): Int {
    return (((raw + 7).toFloat() / 14f) * 100).toInt().coerceIn(0, 100)
}
```

**Detalles:**
- El valor raw de Android va de -7 a +7.
- La app lo mapea a porcentaje (0%–100%) para la UI.
- Se ejecuta cada **2 segundos**.

---

## 8. Monitoreo Térmico del Sistema (API de Android)

**Archivo:** `app/src/main/java/com/example/data/repository/GameBoostRepository.kt`
**Método:** `setupThermalMonitoring()`

Registra un listener térmico usando la API oficial de Android (API 29+):

```kotlin
@RequiresApi(Build.VERSION_CODES.Q)
private fun setupThermalMonitoring() {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(context)) { status ->
        when (status) {
            PowerManager.THERMAL_STATUS_SEVERE -> {
                logAsync("WARN", "Thermal", "Severe heat detected! Throttling optimizations.")
            }
            PowerManager.THERMAL_STATUS_CRITICAL -> {
                logAsync("ERROR", "Thermal", "Critical heat! Disabling boost.")
                if (_isBoostActive.value) toggleBoost()
            }
        }
    }
}
```

**Detalles:**
- Solo disponible en **Android 10+** (API 29).
- `THERMAL_STATUS_SEVERE`: registra advertencia.
- `THERMAL_STATUS_CRITICAL`: **desactiva el boost automáticamente**.

---

## 9. Watchdog Térmico (`WatchdogManager`)

**Archivo:** `app/src/main/java/com/example/manager/WatchdogManager.kt`
**Método:** `checkThermalHealth()`

El WatchdogManager monitorea la temperatura **cada 15 segundos** y toma acciones:

```kotlin
private suspend fun checkThermalHealth() {
    val cpuTemp = repository.systemMetrics.value.cpuTemp
    when {
        cpuTemp >= 50f -> {
            // CRÍTICO: Cambia automáticamente al perfil "Battery Saver"
            repository.setActiveProfile("battery_saver")
        }
        cpuTemp >= 45f -> {
            // ELEVADO: Si el perfil actual es "Extreme", baja a "Balanced"
            repository.setActiveProfile("balanced")
        }
    }
}
```

**Detalles:**
- **≥ 50°C:** Cambia a perfil **Battery Saver** (ahorro de energía forzoso).
- **≥ 45°C:** Si el perfil actual es **Extreme**, baja a **Balanced**.
- Solo actúa si el toggle `thermalWatchdog` está habilitado (por defecto: `true`).

---

## 10. CPU Governor / Frecuencias

**Archivo:** `app/src/main/java/com/example/data/repository/GameBoostRepository.kt`
**Método:** `fetchAvailableGovernors()` y `getBigCoresMask()`

### Governors disponibles:
```kotlin
val result = ShizukuExecutor.runCommand("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors")
```
Lee los governors disponibles del kernel vía Shizuku.

### Máscara de cores grandes (big cores):
```kotlin
for (i in 0 until cores) {
    val result = ShizukuExecutor.runCommand("cat /sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
    maxFreqs.add(result.getOrNull()?.trim()?.toLongOrNull() ?: 0L)
}
```
Identifica los cores con mayor frecuencia máxima y genera una máscara hexadecimal para `taskset`.

---

## 11. GPU (`gpuUsage` - Estimado)

**Archivo:** `app/src/main/java/com/example/data/repository/GameBoostRepository.kt`

```kotlin
val estimatedGpuUsage = (realCpuUsage * 0.8).toInt().coerceIn(0, 100)
```

**Detalles:**
- ⚠️ No se lee GPU real. Es una **estimación** basada en el uso de CPU × 0.8.
- Android no expone el uso de GPU de manera sencilla sin acceso root a drivers específicos.

---

## 12. Refresh Rate y Escala de Animaciones

**Archivo:** `app/src/main/java/com/example/data/repository/GameBoostRepository.kt`

```kotlin
refreshRate = activeProfile?.refreshRate ?: "60 Hz"
animationScale = if (_isBoostActive.value) "0x" else "1x"
```

Se lee del perfil activo (en base de datos) y del estado de boost actual, no de sensores del sistema directamente.

---

## Resumen de Frecuencias de Monitoreo

| Métrica | Frecuencia | Método | Fuente |
|---------|-----------|--------|--------|
| CPU Usage | Cada 2s | `/proc/stat` (delta) | Archivo Linux |
| CPU Temp | Cada 2s | `/sys/class/thermal/*/temp` | Archivo Linux |
| RAM | Cada 2s | `ActivityManager.MemoryInfo` | API Android |
| Battery Level | Cada 2s | `BatteryManager` (Broadcast) | API Android |
| Battery Temp | Cada 2s | `BatteryManager.EXTRA_TEMPERATURE` | API Android |
| Ping | Cada 2s | `ping -c 1 8.8.8.8` | Shell |
| Pointer Speed | Cada 2s | `settings get system pointer_speed` (Shizuku) | Shell |
| Thermal Status | Tiempo real | `PowerManager.addThermalStatusListener` (API 29+) | API Android |
| Watchdog Térmico | Cada 15s | `cpuTemp` de métricas + lógica de perfiles | Interno |

---

## Stack Tecnológico

- **Shizuku API** (`rikka.shizuku`): Ejecución de comandos shell privilegiados (sin root).
- **Android SDK** (`ActivityManager`, `BatteryManager`, `PowerManager`): APIs estándar.
- **Linux sysfs** (`/proc/stat`, `/sys/class/thermal/`): Archivos del kernel.
- **Shell commands** (`ping`, `settings`, `pidof`, `taskset`, `renice`): Comandos Unix.
- **Room Database** (`ProfileEntity`, `LogEntity`): Persistencia local de perfiles y logs.
- **Kotlin Coroutines & StateFlow**: Monitoreo reactivo en tiempo real.

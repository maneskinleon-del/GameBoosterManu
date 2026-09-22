# T1/T2 — Shizuku OFF vs salida prematura del Auto Boost Engine

**Fecha:** 2026-09-16
**Objetivo:** cerrar la pregunta causal — ¿con Shizuku OFF, Auto Boost sale porque `GameDetector` declara falsamente `game-exit`, o la salida observada tuvo otra causa?
**Restricción:** solo evidencia. Cero cambios de lógica, cero commits de código.

## Instrumentación (creada, sin tocar lógica)

- `verify_device.sh` — checklist pre-prueba (estado real del dispositivo, solo lectura)
- `capture_t1.sh` — captura logcat filtrada T1 → `logcat-T1-<timestamp>.log`
- `capture_t2.sh` — captura logcat filtrada T2 → `logcat-T2-<timestamp>.log`

Filtro de captura: `com\.example( |$)|GameDetector|GameSession|BoostSession|DependencyState|Watchdog|Heartbeat|Shizuku|game-exit|restore|RECOVERY_REQUIRED|DEGRADED|RECOVERING` — coincide con los TAGs reales verificados en HEAD: `GameDetector`, `GameSession`, `BoostSession`, `DependencyState`, `Watchdog`/`WatchdogReceiver`, `Heartbeat`, `ShizukuExecutor`, `ShizukuServiceConnection` (+ mensajes con `game-exit`, `restore`, `DEGRADED`, `RECOVERING`, `Launcher en foreground`).

## T1 — Reproducción controlada

1. `bash experiments/shizuku-off-t1/verify_device.sh` — confirmar todo OK.
2. `bash experiments/shizuku-off-t1/capture_t1.sh` (dejar corriendo).
3. En el teléfono: abrir **Shizuku** y confirmar que está **funcionando** (running).
4. En GameBoost: confirmar **Auto Boost habilitado** y **Shizuku conectado** (UI).
5. Entrar a **Free Fire**. Esperar a que la sesión esté ACTIVE/APPLYING (logs o UI).
6. Sin salir del juego: **apagar el servidor Shizuku** (botón STOP en la app Shizuku, o cerrar la app desde recientes). No usar HOME ni minimizar Free Fire.
7. Cronometrar hasta 2 minutos. Anotar hora exacta del apagado de Shizuku.
8. Si ocurre una salida (overlay desaparece / logs dicen salida) → anotar hora y qué se veía en pantalla.
9. Ctrl-C en la captura. Renombrar el log si hace falta: `mv logcat-T1-*.log logcat-T1-<desc>.log`.

**Verificación post-T1 (solo lectura):**
```bash
# Últimos eventos de Room log de la app (si expone los logs en UI, capturar pantalla también)
adb shell "dumpsys activity services com.example | head -40"
```

## T2 — Control con Shizuku ON (solo si T1 muestra salida sospechosa)

1. Reinit Shizuku (ON). Si el estado quedó raro: cerrar y reabrir GameBoost; si el overlay no desapareció, tocar el botón de boost en la UI.
2. `bash experiments/shizuku-off-t1/capture_t2.sh`.
3. Entrar a Free Fire, activar Auto Boost, confirmar ACTIVE.
4. Mantener Shizuku ON todo el tiempo.
5. Salir legítimamente: HOME (o gesture).
6. Esperar salida normal confirmada en logs (game-exit + restore).
7. Ctrl-C. Comparar T1 vs T2.

## Resultado

**T1 ejecutada el 2026-09-16 (T0 = 19:50:00, kill de shizuku_server con Free Fire en foreground): NO hubo salida prematura.** FSM a DEGRADED, 3 intentos de recuperación fallidos, sesión viva y estable en degradación. Cero eventos de GameDetector/game-exit/restore. Ver informe completo: `INFORME-T1-2026-09-16.md`.

Evidencia: `logcat-T1-20260916-194123.log` · `room-logs-T1-preserved.db*`.

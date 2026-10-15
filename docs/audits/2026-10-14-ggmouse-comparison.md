# Auditoría comparativa: GG Mouse Pro 3 vs. GameBoosterManu

- **Fecha del análisis:** 2026-10-14
- **HEAD del repo al momento del análisis:** `54a691defbb0fd9d86dcbd30a28006f1d38e2acf` (rama `main`, 2026-10-09, "feat(ui): botón 'Continuar sin accesibilidad'…"), árbol de trabajo limpio (0 modificaciones).
- **Alcance:** 100% estático, solo lectura — ningún comportamiento ejecutado, ningún archivo de producción modificado (`git status` limpio antes y después), GG Mouse nunca instalado ni ejecutado.
- **APK analizado:** `~/Descargas/GG Mouse Pro 3_3.1.13.apk` (`com.zjx.ztezscreenshot`, vcode 434, minSdk 29, targetSdk 35).
- **Herramientas:** `jadx-headless-mcp-0.7.1-all.jar` (Java 17, server HTTP local terminado al cerrar), `unzip` de libs a `/tmp`, desencriptador de strings reimplementado en Python.
- **Procedencia:** transcripción fiel del informe original emitido en el ciclo (2026-10-14), sin evidencia nueva. La tabla se convirtió de su formato de ancho fijo original a markdown con contenido idéntico; rutas home abreviadas con `~`.

Investigación estática, solo lectura. No se modificó ningún archivo de producción (GBM: `git status` limpio antes y después; árbol sin cambios). No se instaló ni ejecutó GG Mouse.

## 1. Estado del repositorio auditado

- Repo: `~/GameBoosterManu/` — rama `main`, HEAD `54a691defbb0fd9d86dcbd30a28006f1d38e2acf` (2026-10-09, "feat(ui): botón 'Continuar sin accesibilidad'…"), árbol de trabajo limpio (0 modificaciones).
- GG Mouse: APK `~/Descargas/GG Mouse Pro 3_3.1.13.apk` (`com.zjx.ztezscreenshot`, vcode 434, minSdk 29, targetSdk 35), analizado con `jadx-headless-mcp-0.7.1-all.jar` (Java 17, server HTTP local ya terminado).

## 2. Archivos y métodos inspeccionados

**GBM (HEAD real):** `ShizukuExecutor.kt` (`checkState`, `runCommand`, `runOnProcess`, `createShizukuProcess`, `drainProcess`/`pollUntilExited`), `RishExecutor.kt`, `exec/ExecutorExceptions.kt` (`DEFAULT_TIMEOUT_MS=8000`), `WatchdogManager.kt` (`doubleCheckShizuku`, `triggerShizukuRecovery`), `GameSessionManager.kt` (`onShizukuBinderReceived`, `onShizukuReconnected`), `PermissionManager.kt:60` (`addBinderReceivedListener`), `MainActivity.kt`, manifest (`ShizukuProvider`), `exec/PrivilegedResult.kt` + tests de read-back.

**GG Mouse (JADX):** `PrivilegedMain` (+`main`/`h`/`j`/`n`/`x`), `activation.ShizukuUserServiceImpl`, `activation.AdbPairingService` (solo localizado; no central), `defpackage.va` (gestor de activación), `defpackage.zr9`/`es9` (envoltorio Shizuku), `rpc.binder.privileged.ContentProvider`, `contentprovider.BinderContentProvider`, `defpackage.ei0`, `defpackage.fi0`, `j68` (Stub AIDL lado privilegiado), `ProcessObserver`, `defpackage.qr9` (`ShellCommand.kt`), `defpackage.d73`, `InputNative`, `TouchPerformerSendEvent`, `w63`/`kua`/`cf5`, `ae2`/`zd2`/`dm8`/`XorDecode` (descifrado de strings reimplementado en Python y validado: `newProcess`, `su`, el script de arranque, etc.), y el asset `lib/arm64-v8a/libadb_cmd.so`.

## 3. Comparación técnica

| Capacidad | GG Mouse (demostrado) | GameBoosterManu (demostrado) | Diferencia técnica | Beneficio posible | Riesgo/coste de cambio | Evidencia | Estado |
|---|---|---|---|---|---|---|---|
| Inicio de proceso privilegiado | Daemon `app_process` … `--nice-name=gb_native_service com.zjx.gamebox.adb.PrivilegedMain $a` lanzado por script shell (190 B) embebido como `libadb_cmd.so`; 4 vías: `su`, Shizuku UserService, `Shizuku.newProcess` por reflexión, ADB TCP | Proceso efímero por comando: `Shizuku.newProcess` por reflexión → fallback Rish → Runtime | Daemon persistente vs. proceso por comando; GG carga `.so` nativos (`libevent_bridge.so`, `libjyandroid.so`) en el proceso shell | Ninguno para GBM (no necesita sesión de entrada persistente) | Alto: re-arquitectura, ciclo de vida nuevo | `libadb_cmd.so` (cat), `va.e/f/G/I`, `va.E` (decrypt → `"newProcess"`), `PrivilegedMain.main` | Confirmado |
| Comunicación entre procesos | Dos canales: Binder AIDL intercambiado por rendezvous vía `getContentProviderExternal("…ShellProcessContentProvider")` como `com.android.shell` + stream de sockets (`w63`) para eventos de alta frecuencia | Llamada síncrona por comando (stdin/stdout del proceso) + Binder de Shizuku subyacente | GBM no mantiene canal abierto; GG invierte un canal permanente | Bajo: comandos GBM son puntuales | Medio-alto | `PrivilegedMain.h`, `ContentProvider.initProvider`, `BinderContentProvider.call`, `ei0.r` | Confirmado |
| Detección de pérdida | `ei0 implements IBinder.DeathRecipient`: `linkToDeath` sobre el binder del daemon + broadcast `"connected"`; heartbeat app→daemon (`ei0.j2→K2()`) con timeout inverso en `PrivilegedMain.a`; el script supervisor bloquea mientras viva `gb_native_service` | Solo `addBinderReceivedListener` + polling (`pingBinder` en Watchdog + `echo 1` real). Sin dead listener registrado | Event-driven vs. poll-based | Detección más inmediata al matar el server Shizuku | Bajo si es incremental | `ei0.r/binderDied/v3`, `WatchdogManager:150-200`, `rg` sin `DeadListener` en GBM | Confirmado (ambos) |
| Reconexión/recuperación | Broadcasts de estado → relanzar por el método de activación elegido (`va.F`); UserService: `onServiceDisconnected`/`onBindingDied` → `X=null` | Backoff exponencial 2s·2ⁿ (cap 30s), `MAX_RECOVERY_ATTEMPTS`, FSM `RECOVERING`/`DEGRADED`, `onShizukuBinderReceived` reactiva sesión pendiente | GBM es más disciplinado: backoff acotado + estados; GG relanza sin política de intentos evidente | Ninguno | — | `WatchdogManager.triggerShizukuRecovery`, `GameSessionManager:170-183` | Confirmado (GBM); inferido (política GG) |
| Ejecución de comandos y errores | Por comando: `Runtime.exec(sh -c)` dentro del daemon, leyendo stdout o stderr por separado (`qr9.a`=stderr, `qr9.b`=stdout); errores por texto de stderr (p. ej. detecta `"android.permission.WRITE_SECURE_SETTINGS"` en `j68.H0`) | Por comando: drain paralelo stdout+stderr, `waitFor(timeout)` con poll de respaldo, `DeadObjectException` → fallo inmediato, `CommandFailedException` honesto, `PRIVILEGE_UNAVAILABLE` solo si todos los backends fallan | GBM trata timeout/bloqueo de pipe y distingue tipos de fallo; GG devuelve strings y confía en parsing de stderr | GBM ya es superior aquí | — | `qr9`, `j68.H0/G0`, `ShizukuExecutor.runOnProcess`/`drainProcess` | Confirmado |
| Verificación de efectos | No hay read-back sistemático verificado en los métodos inspeccionados | `ExecOutcome.EXECUTED` ≠ verificado; solo "fully verified" con read-back (con tests) | GBM verificación explícita; GG fire-and-forget salvo parsing de stderr | Ninguno (GBM ya lo tiene) | — | `PrivilegedResult.kt`, `PrivilegedResultContractTest.kt` | Confirmado (ausencia en GG: límite de lo inspeccionado) |
| Permisos/compatibilidad | Permiso Shizuku API_V23; ejecuta como shell (uid 2000) para `INJECT_EVENTS`; fuerza `settings put global block_untrusted_touches 0`; reflexiones por rango de SDK (≤33 vs >33) en `d73` | Permiso Shizuku estándar; sin escritura de settings globales peligrosas; sin dependencia de uid shell | GG depende de uid shell y de tocar una global de seguridad; GBM no | Ninguno | Alto si se copiara | Manifest GG, `ShizukuUserServiceImpl.e2`, `d73.<init>` | Confirmado |
| Inyección de eventos vs comandos | Inyección in-process: `InputManager.injectInputEvent` por reflexión (`d73`) + nativo `/dev/input` (`TouchPerformerSendEvent.open/write/closeTouchDeviceNative`, `InputNative` JNI) mantenido vivo por el daemon | No aplica: GBM no inyecta eventos; solo comandos `settings put`/sysfs | Mecanismo que GBM no necesita | — | — | summaries JNI de ambas clases | Confirmado / D. No aplicable |

## 4. Hallazgos clave

- **`libadb_cmd.so`:** script shell de **190 B** embebido como asset (`lib/arm64-v8a/libadb_cmd.so`) — es el lanzador del daemon privilegiado de GG Mouse. Leído directamente del APK (`cat`).
- **Cadenas descifradas:** el desencriptador (`XorDecode` contra el array `zd2.a`) se reimplementó en Python y se validó con cadenas de control coherentes: `newProcess`, `su`, `killall -9 gb_native_service` y el contenido literal del propio script `libadb_cmd.so`.
- **`ShellProcessContentProvider`:** el rendezvous de GG Mouse pasa por `getContentProviderExternal("…ShellProcessContentProvider")` como `com.android.shell` — es la pieza central de su comunicación entre procesos (evidencia: `PrivilegedMain.h`, `ContentProvider.initProvider`, `BinderContentProvider.call`).
- **Código muerto detectado:** `ShizukuServiceConnection.kt` + `IShizukuService.aidl` — cero referencias en Kotlin/Java de `app/src` en el HEAD auditado; además `ShizukuServiceConnection.executeCommand` usaba `Runtime.getRuntime().exec()` sin privilegio (su nombre sugiere una función que el código no cumplía). **Posteriormente eliminado en `835e373`** (dead code removal, parte del checkpoint `checkpoint-835e373`).

## 5. Hallazgos confirmados y limitaciones

**Confirmados:** (1) GG Mouse usa un daemon `app_process` persistente con handshake Binder por ContentProvider y `linkToDeath`; (2) su UserService (`ShizukuUserServiceImpl`) es solo una de 4 vías de activación y ejecuta comandos shell, no su IPC principal; (3) GBM supera a GG Mouse en tratamiento de errores, timeouts y verificación de efectos; (4) código muerto `ShizukuServiceConnection` / `IShizukuService.aidl` en GBM; (5) GBM no registra ningún listener de muerte de Shizuku (solo binder-received + polling).

**Limitaciones:** análisis 100% estático (ningún comportamiento ejecutado); el desencriptador de strings de GG Mouse fue validado por coherencia, no contra ejecución; no se decompilaron los `.so` nativos (`libevent_bridge.so`, `libjyandroid.so`); el protocolo socket de `w63` no se trazó hasta su par app; la búsqueda "code scope" del MCP devolvió 0 resultados, así que la navegación fue por xrefs y descifrado.

## 6. Recomendaciones (máx. 3)

1. **[C — Problema confirmado]** Retirar `ShizukuServiceConnection.kt` + `IShizukuService.aidl` (código muerto engañoso). Beneficio medio-bajo, riesgo muy bajo. Prueba mínima: compilar `assembleDebug` + tests unit existentes tras la eliminación, y `rg 'IShizukuService|ShizukuServiceConnection' app/src` que devuelva 0.
2. **[B — Mejora potencial]** Evaluar detección de pérdida por evento (`addUidProcessDeadListener` / `OnShizukuDeadListener`) en paralelo al polling del Watchdog. Beneficio: latencia de detección de Shizuku caído menor que el intervalo de heartbeat. Riesgo bajo si es aditivo. Prueba mínima: en rama aislada, registrar el listener, matar el server Shizuku y medir la latencia de detección vs. la del heartbeat actual antes de tocar FSM/Watchdog.
3. **[A/D — No requiere cambios / no aplicable]** No migrar a UserService ni adoptar el daemon de GG Mouse, y no portar inyección nativa. GG usa UserService solo como canal de activación y su complejidad (daemon, socket, uid shell, `block_untrusted_touches`) sirve a funciones que GBM no necesita; el executor actual de GBM (fallbacks + timeouts + read-back) ya es más robusto que el lado de comandos de GG Mouse. Prueba mínima: ninguna — es una decisión de no acción documentada.

Siguiente prueba mínima global (si se quiere cerrar el único gap verificable): el paso 2, instrumentado fuera de la FSM, como se indicó.

## 7. Integridad de la evidencia y verificación

- Repositorio GBM sin modificar: `git status --porcelain` = 0 líneas tras toda la auditoría (re-verificado después de terminar).
- Sin instalación/ejecución de GG Mouse: solo lectura estática del APK (`unzip` de libs a `/tmp`, JADX headless, `cat` de un asset).
- Sin commits, PRs, dependencias ni cambios de rama: todo el material de análisis vivió en `/tmp` (`ggmcp.py`, `ggmcp_http.py`, `decode_ggm.py`, fuentes decompiled).
- Server MCP de JADX terminado (PID 606726 confirmado `stopped`).
- El desencriptador de strings de GG Mouse se reimplementó en Python contra el array `zd2.a` y se validó con cadenas de control coherentes (`newProcess`, `su`, `killall -9 gb_native_service`, el contenido literal del script `libadb_cmd.so` leído directamente del APK). El hallazgo de código muerto (`ShizukuServiceConnection.kt` / `IShizukuService.aidl` sin referencias en `app/src`) se verificó con `rg` en el HEAD real.
- **Checks que no pudieron correrse (por diseño de la tarea):** ninguna ejecución dinámica — todo el comportamiento estático de GG Mouse (incluida la inferencia de que `package:<apk>` funciona como classpath por el separador `:`) queda marcado como inferido, no demostrado en ejecución; es la limitación principal ya declarada. No había tests de GBM que ejecutar: la tarea fue de solo lectura y no se tocó código.

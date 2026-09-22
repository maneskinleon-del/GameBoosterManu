# Reporte forense — Overlay flotante desaparece al entrar a Free Fire

**Fecha:** 2026-09-13 · **Tipo:** Diagnóstico puro (sin cambios de código)
**Pregunta:** ¿qué evento hace que el overlay deje de estar visible?

---

## 1. HEAD / commit auditado

- **HEAD = `f5ba334`** — "Merge pull request #8 from maneskinleon-del/chore/remove-dead-code" (main, 2026-09-13 12:34:06 -0300).
- Working tree limpio en `app/src` (solo untracked: `experiments/`, `.codegraph/`).

## 2. APK probado

| Campo | Valor |
|--|--|
| package | `com.example` |
| versionName / versionCode | `1.1` / `2` |
| lastUpdateTime | **2026-09-13 12:35:14** |
| Origen | `adb install -r` desde `assembleDebug` del propio `f5ba334` (misma sesión) |

## 3. Secuencia exacta de reproducción (repro v3, limpia)

Estado previo: GameBoost en primer plano con boost OFF, FF instalado, Shizuku activo, overlay-permission concedida (`appop=SYSTEM_ALERT_WINDOW`).

1. Llevar FF al frente (`am start com.dts.freefireth/.FFMainActivity`) — **T_ENTER 13:58:02**.
2. La auto-detección (servicio de accesibilidad) detecta FF y el perfil manual recordado `ff_mouse` dispara el boost automático.
3. Boost ON **13:58:04.680** → `show()` exitoso **13:58:04.767** (burbuja 57×57 añadida al WindowManager en x=553,y=465).
4. **~8 s después**, la FSM confirma "salida de juego" (13:58:08.719) y el boost se apaga solo.
5. `hide()` **13:58:12.470** → "Floating panel removed successfully" **13:58:12.486**.
6. Resultado: usuario en FF, boost auto-aplicado y auto-cancelado, overlay fuera. Reproducción 2/2 intentos (v1: 13:08:37 exit → restore 13:08:40).

## 4. Observaciones

- El proceso **no muere**: pid `7110` constante durante toda la ventana crítica y después.
- `GameBoostService` vivo (START_REDELIVER_INTENT, watchdog programado); sin ANR (`lastanr: <no ANR since boot>`).
- **0 excepciones** en la ventana 13:58:04–13:58:19 (grep FATAL/DeadObject/BadToken/Security = 0).
- El overlay **sí se creó** (`✅ Floating panel shown successfully`) y **sí fue removido por la propia app** ("Floating panel removed successfully" desde `FloatingPanelManager.hide()`).
- Display override activo en el device: `Override size: 1440x720`, density 280, rotación 3 (modo juego ZTE). Irrelevante para la visibilidad del overlay (la burbuja renderizaba correctamente con el mismo override a las 13:05, verificada por screencap + píxeles cian en su frame), pero sí rompe el dispatch de `input tap` de ADB (lección metodológica del repro v2).

## 5. Evidencia (logcat pid 7110, captura host continua /tmp/overlay-repro2.log)

```
13:58:03.042 FSM_DIAG UnifiedA11y.WINDOW_STATE_CHANGED: pkg=com.dts.freefireth   ← entra FF
13:58:03.045 GameBoostService 🎮 Simulated Game Flow emission: com.dts.freefireth (Boost=false)
13:58:03.704 FSM_DIAG UnifiedA11y.setForegroundApp(com.zjx.ztezscreenshot)        ← ventana transitoria ZTE (screenshot)
13:58:04.051 FSM_DIAG UnifiedA11y.setForegroundApp(com.android.vending)           ← Play Store (overlay de install)
13:58:04.355 FSM_DIAG UnifiedA11y.setForegroundApp(cn.nubia.gameassist)           ← game assist OEM
13:58:04.562 FSM_DIAG UnifiedA11y.setForegroundApp(com.zjx.ztezscreenshot)        ← otra vez ZTE screenshot
13:58:04.180 Monitor [INFO] Perfil: mapper=false, ext=false, boostActivo=false
13:58:04.198 Monitor [INFO] ▶️ Reaplicando perfil manual recordado: ff_mouse
13:58:04.680 GameSession toggleBoost() called. Old state: false, New state: true
13:58:04.680 GameBoostService 🚀 Boost Active Flow emission: true
13:58:04.681 FloatingPanelManager show() requested. isVisible=false
13:58:04.705 FloatingPanelManager Adding view to WindowManager at x=553, y=465
13:58:04.767 FloatingPanelManager ✅ Floating panel shown successfully           ← overlay ARRIBA
13:58:06.620 BoostSession [INFO] Baseline capturado: 34 keys (session bs_1789318684683)
13:58:06.635 GameBoostService Service started
13:58:08.709 Monitor [INFO] Salida de juego confirmada (com.dts.freefireth)      ← SALIDA FALSA
13:58:12.467 GameBoostService 🚀 Boost Active Flow emission: false
13:58:12.470 FloatingPanelManager hide() requested. isVisible=true
13:58:12.483 BoostSession [INFO] Restore verificado: 34 keys (16 restauradas, 18 ya-ok, 0 conflictos)
13:58:12.486 FloatingPanelManager Floating panel removed successfully            ← overlay FUERA
```

Evidencia complementaria:

- **dumpsys WMS pre-transición (13:05):** ventana overlay presente — `ty=APPLICATION_OVERLAY`, `mViewVisibility=0x0 mObscured=false mHasSurface=true isOnScreen()=true`, frame `[608,584][665,641]`; screencap con píxeles cian de la burbuja en ese frame.
- **dumpsys WMS post-exit (13:59):** la ventana `Window{... u0 com.example}` (overlay sin nombre) **ya no existe**; solo quedan las ventanas de la Activity (inactiva, `mViewVisibility=0x8`).
- **Room DB (`gameboost_database.logs`):** `13:08:37 Salida de juego confirmada` → `13:08:40 Restore verificado` → `Boost en-app apagado (overlay oculto)` → `Perfil manual 'ff_mouse' recordado` (repro v1). Ningún evento de usuario entre medio.
- **Store (`files/boost_session.json`):** `ACTIVE` (13:58:06) → `RESTORED` (13:58:12) → `ACTIVE` de nuevo (13:58:14, ver hallazgo secundario §8b).

## 6. Flujo de código relevante (HEAD f5ba334)

```
UnifiedAccessibilityService.onAccessibilityEvent
  └─ TYPE_WINDOW_STATE_CHANGED → handleWindowStateChanged(pkg)
       ├─ filtro: pkg propio + ignoredPackages (lista CORTA: systemui, teclados,
       │   android, settings, gms, permissioncontroller — NO incluye
       │   com.zjx.ztezscreenshot / cn.nubia.gameassist / com.android.vending)
       └─ dedup por lastPackage → repository.onForegroundAppChanged(pkg)
GameBoostRepository.onForegroundAppChanged(pkg)
  ├─ isGamePackage(pkg)  → sessionManager.setForegroundApp(pkg)   [entrada de juego]
  └─ !isGamePackage(pkg) → sessionManager.onForegroundAppLost()   [SALIDA]
GameSessionManager.simulateGameLaunch(null)
  └─ hysteresisJob = delay(HYSTERESIS_DELAY_MS = 5000)
       └─ triggerExitWithHysteresis():
            restoreVerified() + networkOptimizer/systemTweaks/Mobilador restore
            _isBoostActive.value = false            ← GameSessionManager.kt:606
            PreferenceManager.setServiceRunning(false)
            (recordar perfil manual; manualOverrideActive = false)
GameBoostService boost-state observer (serviceScope, onStartCommand→startMonitoring)
  └─ repository.isBoostActive.collect { active ->
         if (active) FPM.show() else FPM.hide() }   ← GameBoostService.kt:270-272
FloatingPanelManager.hide()
  └─ wm.removeView(floatingView) → "Floating panel removed successfully"
```

En paralelo, la entrada de juego que dispara el boost: `onGameDetected` (a11y o GameDetector por UsageStats) → `setForegroundApp` → perfil recordado `ff_mouse` → `toggleBoost(true)` (auto) → observador → `show()`.

## 7. Hipótesis descartadas

| Hipótesis | Evidencia que la descarta |
|--|--|
| **B — Muerte/reinicio del proceso o servicio** | pid 7110 constante; 0 excepciones/FATAL; sin ANR; servicio vivo con watchdog; la remoción la hace el propio proceso (`hide()` con log de éxito). |
| **C — Android/OEM quitó u ocultó la ventana** | La ventana fue removida por `FloatingPanelManager.hide()` del propio app ("removed successfully"), no por WMS; no hay BadTokenException ni `mForceHide`/permiso revocado (`appop=SYSTEM_ALERT_WINDOW` estable; `show()` re-creó la ventana a las 13:58:04 sin error). La ventana de sistema `com.zjx.ztezscreenshot` existe pero es `FLAG_NOT_TOUCHABLE` con alpha clamped a 0.80 — no oculta nuestra ventana. |
| **Pérdida de overlay-permission al entrar al juego** | `show()` de 13:58:04.767 tuvo éxito 4 s antes del hide; appop estable durante toda la ventana. |
| **Hipótesis perfil→Balanced (§2 del pedido)** | Sin ruta causal demostrable, y además falsada empíricamente: en toda la ventana crítica el perfil aplicado/activo fue `ff_mouse` (FF Mouse Duo); `applyProfile()` no toca `isBoostActive`, no llama `show()/hide()`, no reinicia el servicio ni destruye el overlay. El `hide()` fue causado por el **observer de isBoostActive=false**, no por ningún cambio de perfil. Cambiar Balanced→FF Mouse Duo no puede apagar el boost por ninguna ruta estática encontrada. |
| **Muerte por LMK / pressure** | `boost_session.json` y DB demuestran transiciones ordenadas APPLYING→ACTIVE→RESTORED ejecutadas por el propio proceso; proceso vivo después. |
| **Input/coordinates (repro v2)** | Afecta solo a las pruebas ADB (`input tap` bajo override 1440x720), no al síntoma. Documentado como aprendizaje metodológico. |

## 8. Causa más probable

### 8a. Causa principal — Categoría **A: la propia app apagó el boost** (CONFIRMADO)

La secuencia completa fue capturada con logs del propio proceso:

1. Al entrar FF, durante el arranque aparecen **ventanas transitorias del sistema ZTE / OEM** (screenshot overlay `com.zjx.ztezscreenshot`, Game Assist `cn.nubia.gameassist`, instalador de Play `com.android.vending`).
2. El servicio de accesibilidad las reporta como "app en foreground" porque **no están en su `ignoredPackages`** (la lista del servicio es más corta que la de `GameDetector`, que sí filtra `com.android.vending`).
3. `onForegroundAppChanged(pkg)` clasifica no-juego → `onForegroundAppLost()` → hysteresis de 5 s → **"Salida de juego confirmada"** a las 13:58:08.719, con FF todavía en primer plano.
4. La salida confirmada ejecuta restore y pone `isBoostActive=false` → el observer del servicio ejecuta `hide()` a las 13:58:12.47 → overlay removido.

**Confianza: CONFIRMADO** para el mecanismo `salida-falsa → isBoostActive=false → hide()` (evidencia directa, sin especulación). La atribución del disparador a las ventanas transitorias listadas es **FUERTEMENTE SOPORTADO** (timestamps exactos + hueco de filtro demostrado en el código; son todos los eventos no-juego de la ventana).

Nota: la misma causa explica que el boost completo (no solo el overlay) se apaga: restore verificado 34 keys, DNS/wifi/Mobilador revertidos ~4 s después de aplicarse. El overlay es solo el síntoma más visible.

### 8b. Hallazgo secundario (nuevo, requiere seguimiento propio)

El store terminó `ACTIVE` de nuevo (13:58:14) **sin nuevo `toggleBoost`**: la salida falsa (restore 13:58:12.483) cayó dentro de la ventana del `delay(8000)` → `markActive()` diferido de `toggleBoost` (13:58:04.680+8s≈13:58:12.68), que re-armó el estado después del restore. Además coexisten dos sessionIds en 2 s (`bs_…684683` baseline vs `bs_…686614` en el archivo) cuyo autor exacto no quedó en los logs. Riesgo: boost "zombie" con settings de boost parcialmente aplicados y sin overlay/observabilidad. Evidencia: transición RESTORED→ACTIVE en `boost_session.json` + ausencia de toggleBoost en logcat 13:58:12–14.

## 9. Nivel de confianza

- **Categoría causal: A — CONFIRMADO** (la app ejecutó `hide()` por transición a false de `isBoostActive`, con logs del propio proceso).
- **Disparador específico (ventanas transitorias OEM vía a11y): FUERTEMENTE SOPORTADO.**
- **Hallazgo secundario (carrera markActive vs exit): OBSERVADO** (transición registrada; autor exacto del segundo sessionId abierto).

## 10. Cambio mínimo propuesto (NO implementado)

1. **Principal (1 línea):** extender `ignoredPackages` de `UnifiedAccessibilityService` con los paquetes transitorios observados: `com.zjx.ztezscreenshot`, `cn.nubia.gameassist`, `com.android.vending` (y valorar unificar la lista con la de `GameDetector`, que ya filtra más).
2. **Robustez (opcional, siguiente iteración):** tratar "no-juego en foreground" como salida solo si se confirma con un segundo evento/poll (delegar la salida al polling de `GameDetector` y usar a11y solo para entrada), evitando que cualquier ventana transitoria futura provoque salidas falsas.
3. **Carrera del hallazgo 8b:** cancelar el job del `markActive()` diferido en `triggerExitWithHysteresis()` (y en `toggleBoost(false)`), para que un restore posterior al apply no pueda re-armar ACTIVE.

Si la evidencia hubiera apuntado a OEM/permisos/fullscreen, no habría cambio de código que proponer; no es el caso: el trigger está en el pipeline de detección de la propia app.

---

### Metodología (lecciones de las repeticiones)

- **v1 (13:05–13:08):** repro natural válido, pero la captura host de logcat murió a las 12:59:14 (proceso host efímero) y perdió la ventana crítica; la cronología se reconstruyó con la DB Room + dumpsys.
- **v2 (13:32–13:55):** los taps ADB no despacharon al switch (mismatch de coordenadas por override 1440x720 + rotación 3); los swipes sí funcionaron. No tocar estado del boost: sus taps no llegaron.
- **v3 (13:58):** captura host continua (`setsid`) + ring buffer en device; reproducción limpia y evidencia completa. Capturas: `/tmp/overlay-repro2.log` (host), `/data/local/tmp/gb-repro2.log` (device, rotativo).

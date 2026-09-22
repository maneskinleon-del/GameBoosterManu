#!/usr/bin/env bash
# capture_t4b.sh — T4-B: GAME_ACTIVE → kill shizuku_server → abrir Shizuku SIN iniciar
# server → exit legítimo → restore con Shizuku muerto → RECOVERY_REQUIRED → revival
# oficial → recuperación → S3 (re-entrada FF) → exit final a HOME.
#
# REGLA DEL EXPERIMENTO: NO ejecuta `logcat -c` (preservar buffer).
# Uso: bash experiments/shizuku-off-t1/capture_t4b.sh
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/logcat-T4B-$(date +%Y%m%d-%H%M%S).log"
PKG='com\.example( |$)|GameDetector|GameSession|BoostSession|DependencyState|Watchdog|Heartbeat|Shizuku|game-exit|restore|RESTOR|RECOVERY_REQUIRED|DEGRADED|RECOVERING|Launcher en foreground|FSM_DIAG|GAME_ACTIVE|OnBinderReceived|UnifiedA11y|cr_A11yState|bs_|Salida|salida|FREE_FIRE|free_fire|extreme|GamingDND|Mobilador|SettingsRestore|RestoreThermal'

echo ">> Captura T4-B → $OUT  (buffer NO se limpia)"
adb logcat -v threadtime | grep --line-buffered -E "$PKG" | tee "$OUT"

#!/usr/bin/env bash
# capture_t4.sh — T4 variante B: GAME_ACTIVE → kill shizuku_server → exit legítimo
# (apertura UI Shizuku) → restore con Shizuku muerto → revival → OnBinderReceived
# → hot-reload/reclaim → re-entrada FF → nueva sesión → exit final → restore.
#
# REGLA DEL EXPERIMENTO: NO ejecuta `logcat -c` (preservar buffer).
# Uso: bash experiments/shizuku-off-t1/capture_t4.sh
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/logcat-T4-$(date +%Y%m%d-%H%M%S).log"
PKG='com\.example( |$)|GameDetector|GameSession|BoostSession|DependencyState|Watchdog|Heartbeat|Shizuku|game-exit|restore|RESTOR|RECOVERY_REQUIRED|DEGRADED|RECOVERING|Launcher en foreground|FSM_DIAG|GAME_ACTIVE|OnBinderReceived|UnifiedA11y|cr_A11yState|bs_|Salida|salida'

echo ">> Captura T4 → $OUT  (Ctrl-C para terminar; buffer NO se limpia)"
adb logcat -v threadtime | grep --line-buffered -E "$PKG" | tee "$OUT"

#!/usr/bin/env bash
# capture_t1.sh — T1: Shizuku OFF durante Auto Boost activo (solo captura, no modifica código)
# Uso: bash experiments/shizuku-off-t1/capture_t1.sh
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/logcat-T1-$(date +%Y%m%d-%H%M%S).log"
PKG='com\.example( |$)|GameDetector|GameSession|BoostSession|DependencyState|Watchdog|Heartbeat|Shizuku|game-exit|restore|RECOVERY_REQUIRED|DEGRADED|RECOVERING|Launcher en foreground'

echo ">> Captura → $OUT  (Ctrl-C para terminar)"
adb logcat -c
adb logcat -v threadtime | grep --line-buffered -E "$PKG" | tee "$OUT"

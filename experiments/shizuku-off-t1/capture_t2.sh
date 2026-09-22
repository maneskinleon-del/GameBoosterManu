#!/usr/bin/env bash
# capture_t2.sh — T2: control con Shizuku ON (salida legítima a HOME)
# Uso: bash experiments/shizuku-off-t1/capture_t2.sh
set -u
DIR="$(cd "$(dirname "$0")" )"
OUT="$DIR/logcat-T2-$(date +%Y%m%d-%H%M%S).log"
PKG='com\.example( |$)|GameDetector|GameSession|BoostSession|DependencyState|Watchdog|Heartbeat|Shizuku|game-exit|restore|RECOVERY_REQUIRED|DEGRADED|RECOVERING|Launcher en foreground'

echo ">> Captura → $OUT  (Ctrl-C para terminar)"
adb logcat -c
adb logcat -v threadtime | grep --line-buffered -E "$PKG" | tee "$OUT"

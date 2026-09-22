#!/usr/bin/env bash
# capture_t3.sh — T3: entrada en frío a Free Fire con Shizuku OFF (variante H1b)
# Uso: bash experiments/shizuku-off-t1/capture_t3.sh
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/logcat-T3-$(date +%Y%m%d-%H%M%S).log"
PKG='com\.example( |$)|GameDetector|GameSession|BoostSession|DependencyState|Watchdog|Heartbeat|Shizuku|game-exit|restore|RECOVERY_REQUIRED|DEGRADED|RECOVERING|Launcher en foreground|salida'

echo ">> Captura → $OUT  (Ctrl-C para terminar)"
adb logcat -c
adb logcat -v threadtime | grep --line-buffered -E "$PKG" | tee "$OUT"

#!/usr/bin/env bash
# snapshot_t4.sh — volcado de estado en un momento del experimento T4 (solo lectura)
# Uso: bash experiments/shizuku-off-t1/snapshot_t4.sh <etiqueta>
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
TAG="${1:-fase}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$DIR/t4-snapshot-$TAG-$TS.txt"
{
echo "== T4 snapshot [$TAG] — PC $TS | device $(adb shell date '+%F %T' | tr -d '\r') =="
echo
echo "-- foreground --"
adb shell "dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity' | head -3"
echo
echo "-- shizuku_server --"
adb shell "ps -A -o PID,USER,NAME | grep shizuku_server || echo '(sin proceso shizuku_server)'"
echo
echo "-- boost_session.json (SSOT) --"
adb shell "run-as com.example cat files/boost_session.json 2>/dev/null || echo '(no existe)'"
echo
echo "-- prefs is_running / auto_boost --"
adb shell "run-as com.example cat shared_prefs/gameboost_prefs.xml 2>/dev/null" | grep -E 'is_running|auto_boost' || echo '(prefs no disponibles)'
echo
echo "-- settings clave --"
echo "window_animation_scale = $(adb shell settings get system window_animation_scale | tr -d '\r')"
echo "ble_scan_always_enabled = $(adb shell settings get global ble_scan_always_enabled | tr -d '\r')"
echo "pointer_speed = $(adb shell settings get system pointer_speed | tr -d '\r')"
echo
echo "-- servicio app --"
adb shell "dumpsys activity services com.example | grep -E 'ServiceRecord|app=' | head -6"
} | tee "$OUT"
echo ">> snapshot guardado: $OUT"

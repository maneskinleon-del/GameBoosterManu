#!/usr/bin/env bash
# verify_device.sh — verificación pre-prueba T1 (solo lectura, no modifica nada)
set -u
echo "== 1. Dispositivo =="
adb devices -l

echo; echo "== 2. App GameBoost instalada y versión =="
adb shell "dumpsys package com.example | grep -E 'versionName|lastUpdateTime' | head -2"

echo; echo "== 3. Shizuku (server + paquete) =="
adb shell "ps -A | grep -i shizuku || echo '(sin proceso shizuku)'"
adb shell "dumpsys package moe.shizuku.privileged.api | grep versionName | head -1"

echo; echo "== 4. Gating crítico T1 =="
echo "-- deviceidle whitelist (Shizuku):"
adb shell "dumpsys deviceidle whitelist | grep shizuku || echo '  NO ESTÁ EN WHITELIST'"
echo "-- standby bucket Shizuku:"
adb shell "am get-standby-bucket moe.shizuku.privileged.api"
echo "-- SYSTEM_ALERT_WINDOW de la app:"
adb shell "appops get com.example SYSTEM_ALERT_WINDOW"

echo; echo "== 5. Servicio de la app activo =="
adb shell "dumpsys activity services com.example | grep -E 'ServiceRecord|app=' | head -10"

echo; echo "== 6. Free Fire instalado =="
adb shell "pm list packages | grep -i 'dtgames\|freefire' || echo '(no encontrado)'"

echo; echo "== 7. Foreground actual =="
adb shell "dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity' | head -3"

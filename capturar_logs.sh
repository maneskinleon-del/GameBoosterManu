#!/bin/bash
# ============================================================
# Captura de logs — GameBoost Pro + Free Fire
# Uso: bash capturar_logs.sh
# ============================================================
OUTPUT_DIR="logs_gameboost_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUTPUT_DIR"

echo "=============================================="
echo "📱 Captura de logs de GameBoost + Free Fire"
echo "=============================================="
echo ""
echo "1. Se limpiarán los logs actuales"
echo "2. Se iniciará la captura en segundo plano"
echo "3. ABRE FREE FIRE y juega hasta que GameBoost se cierre"
echo "4. Cuando notes que se cerró, presiona ENTER para detener"
echo ""

# --- 1. Timestamp inicial ---
echo "=== INICIO CAPTURA: $(date) ===" > "$OUTPUT_DIR/timestamps.txt"

# --- 2. Limpiar logs viejos ---
echo "🧹 Limpiando buffer de logs..."
adb logcat -c 2>/dev/null
echo "✅ Buffer limpio"
echo ""

# --- 3. Iniciar captura de logs en segundo plano ---

# Log #1: Eventos del sistema (ActivityManager, LMK, kills)
echo "📝 Capturando eventos del sistema (ActivityManager, LMK)..."
adb logcat -b events -v threadtime -T 1 2>/dev/null | grep -iE "kill|lowmem|lmk|proc|crash|anr|death" > "$OUTPUT_DIR/eventos_sistema.log" &
PID_EVENTS=$!

# Log #2: Logs de la app (GameBoostService, WatchdogReceiver, etc)
echo "📝 Capturando logs de GameBoost..."
adb logcat -v threadtime -T 1 2>/dev/null \
  | grep -iE "GameBoost|Watchdog|FSM_DIAG|BoostApply|HighPriority|RamManager|TouchOpt|Shizuku|Heartbeat|AdsPointer|ResourceGov|Recovery|Thermal|System\." \
  > "$OUTPUT_DIR/gameboost.log" &
PID_GAMEBOOST=$!

# Log #3: Errores fatales y crashes
echo "📝 Capturando crashes y errores fatales..."
adb logcat -v threadtime -T 1 2>/dev/null \
  | grep -iE "FATAL EXCEPTION|AndroidRuntime|Caused by|uncaught|CRASH|Native crash|DEBUG" \
  > "$OUTPUT_DIR/crashes.log" &
PID_CRASH=$!

# Log #4: Dumpsys periódico del servicio
echo "📝 Capturando estado del servicio (cada 10s)..."
(
  while true; do
    echo "=== $(date +%H:%M:%S) ===" >> "$OUTPUT_DIR/servicio_status.log"
    adb shell "dumpsys activity services com.example/.service.GameBoostService 2>/dev/null | grep -E 'app=ProcessRecord|pid=|foreground|active=true'" >> "$OUTPUT_DIR/servicio_status.log" 2>/dev/null
    adb shell "ps -A 2>/dev/null | grep com.example | awk '{print \$2, \$NF}'" >> "$OUTPUT_DIR/servicio_status.log" 2>/dev/null
    sleep 10
  done
) &
PID_STATUS=$!

# Log #5: Kernel (OOM, LMK)
echo "📝 Capturando eventos del kernel (OOM, LMK)..."
adb logcat -v threadtime -T 1 -b kernel 2>/dev/null \
  | grep -iE "oom|kill|lmk|alloc|memory|lowmem" \
  > "$OUTPUT_DIR/kernel.log" 2>/dev/null &
PID_KERNEL=$!

echo ""
echo "=============================================="
echo "🔴 CAPTURA ACTIVA — Abre Free Fire ahora"
echo "=============================================="
echo "PID de procesos de captura:"
echo "  Eventos sistema: $PID_EVENTS"
echo "  GameBoost:       $PID_GAMEBOOST"
echo "  Crashes:         $PID_CRASH"
echo "  Kernel:          $PID_KERNEL"
echo "  Estado servicio: $PID_STATUS"
echo ""

# --- 4. Esperar a que el usuario presione ENTER ---
read -p "⏸️  Presiona ENTER cuando GameBoost se haya cerrado..." input

echo ""
echo "🛑 Deteniendo capturas..."

# --- 5. Detener capturas ---
kill $PID_EVENTS 2>/dev/null
kill $PID_GAMEBOOST 2>/dev/null
kill $PID_CRASH 2>/dev/null
kill $PID_KERNEL 2>/dev/null
kill $PID_STATUS 2>/dev/null
wait 2>/dev/null

echo "=== FIN CAPTURA: $(date) ===" >> "$OUTPUT_DIR/timestamps.txt"

# --- 6. Resumen ---
echo ""
echo "=============================================="
echo "📊 RESULTADOS"
echo "=============================================="
echo ""
echo "Archivos generados en: $OUTPUT_DIR/"
echo ""

for f in "$OUTPUT_DIR"/*.log; do
  LINES=$(wc -l < "$f" 2>/dev/null)
  SIZE=$(du -h "$f" 2>/dev/null | cut -f1)
  echo "  📄 $(basename $f): $LINES líneas ($SIZE)"
done

echo ""
echo "=== RESUMEN DE EVENTOS ==="

# Buscar kills de la app
echo "🔍 Buscando kills de com.example..."
grep -i "com.example" "$OUTPUT_DIR/eventos_sistema.log" 2>/dev/null | tail -20

echo ""
echo "🔍 Buscando LMK/OOM..."
grep -iE "kill|lmk|oom|lowmem" "$OUTPUT_DIR/eventos_sistema.log" 2>/dev/null | tail -20

echo ""
echo "🔍 Buscando crashes..."
cat "$OUTPUT_DIR/crashes.log" 2>/dev/null

echo ""
echo "🔍 Estado del servicio en el momento del cierre:"
tail -20 "$OUTPUT_DIR/servicio_status.log" 2>/dev/null

echo ""
echo "=============================================="
echo "✅ Captura completada"
echo "Revisa los archivos en: $OUTPUT_DIR/"
echo "=============================================="

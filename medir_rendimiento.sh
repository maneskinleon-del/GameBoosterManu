#!/bin/bash
# ============================================================
# Medición de rendimiento — Free Fire + GameBoost Pro
# Uso: bash medir_rendimiento.sh
# ============================================================
OUTPUT_DIR="rendimiento_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUTPUT_DIR"

echo "=============================================="
echo "📊 Medición de rendimiento en vivo"
echo "=============================================="
echo ""
echo "Este script capturará durante 60 segundos:"
echo "  • FPS de Free Fire (via gfxinfo)"
echo "  • Uso de CPU de Free Fire"
echo "  • Uso de CPU de GameBoost"
echo "  • Memoria RAM de ambos procesos"
echo "  • Temperatura del dispositivo"
echo "  • Frecuencia de la CPU"
echo ""
echo "1. Abre Free Fire y ponte en una partida"
echo "2. Presiona ENTER cuando la partida esté iniciada"
echo ""

read -p "⏸️  Presiona ENTER para comenzar la medición (60s)..."

# Timestamp inicial
START_TIME=$(date +%s)
END_TIME=$((START_TIME + 60))
SAMPLE=0

echo ""
echo "🔴 MIDIENDO... (60 segundos)"
echo ""

# Loop de muestreo cada 2 segundos (30 muestras en 60s)
while [ $(date +%s) -lt $END_TIME ]; do
    SAMPLE=$((SAMPLE + 1))
    TIMESTAMP=$(date +%H:%M:%S.%3N)
    
    {
        echo "=== MUESTRA $SAMPLE — $TIMESTAMP ==="
        
        # --- FPS de Free Fire (gfxinfo) ---
        echo "--- GFXINFO FREE FIRE ---"
        dumpsys gfxinfo com.dts.freefireth 2>/dev/null | grep -A100 "Profile data in ms" | head -20
        
        # --- CPU de Free Fire ---
        FF_PID=$(pidof com.dts.freefireth 2>/dev/null | cut -d' ' -f1)
        if [ -n "$FF_PID" ]; then
            echo "--- CPU FREE FIRE (PID $FF_PID) ---"
            cat /proc/$FF_PID/stat 2>/dev/null | awk '{print "utime="$14 " stime="$15 " cutime="$16 " cstime="$17 " threads="$20}'
            echo "CPU cores: $(cat /proc/$FF_PID/status 2>/dev/null | grep Cpus_allowed | head -1)"
        fi
        
        # --- CPU de GameBoost ---
        GB_PID=$(pidof com.example 2>/dev/null | cut -d' ' -f1)
        if [ -n "$GB_PID" ]; then
            echo "--- CPU GAMEBOOST (PID $GB_PID) ---"
            cat /proc/$GB_PID/stat 2>/dev/null | awk '{print "utime="$14 " stime="$15 " threads="$20}'
        fi
        
        # --- RAM de ambos ---
        echo "--- RAM ---"
        if [ -n "$FF_PID" ]; then
            echo "Free Fire RSS: $(awk '/RssAnon/ {sum+=$2} END {print sum/1024 \" MB\"}' /proc/$FF_PID/status 2>/dev/null)"
        fi
        if [ -n "$GB_PID" ]; then
            echo "GameBoost RSS: $(awk '/RssAnon/ {sum+=$2} END {print sum/1024 \" MB\"}' /proc/$GB_PID/status 2>/dev/null)"
        fi
        
        # --- Temperatura ---
        echo "--- TEMPERATURA ---"
        for t in /sys/class/thermal/thermal_zone*/temp 2>/dev/null; do
            [ -r "$t" ] && echo "$(basename $(dirname $t)): $(cat $t 2>/dev/null | awk '{print $1/1000 \"°C\"}')"
        done | head -5
        
        # --- Frecuencia CPU ---
        echo "--- FRECUENCIA CPU ---"
        for cpu in /sys/devices/system/cpu/cpu[0-9]*; do
            freq=$(cat $cpu/cpufreq/scaling_cur_freq 2>/dev/null)
            gov=$(cat $cpu/cpufreq/scaling_governor 2>/dev/null)
            [ -n "$freq" ] && echo "$(basename $cpu): $((freq/1000)) MHz ($gov)"
        done
        
        echo ""
    } >> "$OUTPUT_DIR/muestras.log" 2>/dev/null
    
    echo "  📍 Muestra $SAMPLE/30 completada"
    sleep 2
done

# --- Resumen final ---
echo ""
echo "=============================================="
echo "📊 RESUMEN DE RENDIMIENTO"
echo "=============================================="
echo ""

# Estadísticas de CPU de GameBoost
echo "=== GAMEBOOST PROMEDIO ==="
grep "CPU GAMEBOOST" -A1 "$OUTPUT_DIR/muestras.log" | grep "utime" | awk '{utime+=$1; stime+=$2; count++} END {print "Utime promedio: " utime/count " ticks"; print "Stime promedio: " stime/count " ticks"; print "Total: " (utime+stime)/(count*100) " segundos de CPU por muestra"}'

echo ""
echo "=== RAM PROMEDIO ==="
grep "GameBoost RSS" "$OUTPUT_DIR/muestras.log" | awk '{sum+=$3; count++} END {print "GameBoost RAM promedio: " sum/count " MB"}'
grep "Free Fire RSS" "$OUTPUT_DIR/muestras.log" | awk '{sum+=$3; count++} END {print "Free Fire RAM promedio: " sum/count " MB"}'

echo ""
echo "=== TEMPERATURA PROMEDIO ==="
grep "thermal_zone" "$OUTPUT_DIR/muestras.log" | awk '{temp[$1]+=$2; count[$1]++} END {for (z in temp) print z ": " temp[z]/count[z] "°C (promedio)"}'

echo ""
echo "=== FRECUENCIA CPU PROMEDIO ==="
grep "cpu[0-9]:" "$OUTPUT_DIR/muestras.log" | awk '{cpu[$1]+=$2; count[$1]++} END {for (c in cpu) print c ": " cpu[c]/count[c] " MHz (promedio)"}'

echo ""
echo "=============================================="
echo "✅ Medición completada"
echo "Resultados guardados en: $OUTPUT_DIR/"
echo "  - muestras.log (datos crudos cada 2s)"
echo "=============================================="

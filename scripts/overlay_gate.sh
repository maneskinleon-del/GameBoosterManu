#!/usr/bin/env bash
#
# Overlay single-writer gate (PR3, R1 C5): la VISIBILIDAD del FloatingPanelManager
# solo la decide el observer de proyección del GameBoostService. Invariante:
#   1. TODO FloatingPanelManager.getInstance(...) del árbol vive en GameBoostService.kt
#   2. exactamente 2 matches de getInstance(...).show()/hide() — el show y el hide
#      del MISMO observer de proyección. (El grep literal del auditor esperaba "1
#      línea"; con el observer consolidado son 2 llamadas en 1 solo site: el gate
#      expresa la misma propiedad — un único site de escritura — como 2 matches
#      exclusivos del servicio.)
#   3. cero toggleVisibility() invocaciones (writer ciego desde la UI) — la
#      definición en el FPM no cuenta.
# Uso: scripts/overlay_gate.sh [root]  · 0 ok · 1 violación · 2 root inexistente
# Self-test: scripts/gate_selftest.sh (casos OV-*).
set -euo pipefail

ROOT="${1:-app/src/main/java}"
if [ ! -d "$ROOT" ]; then
  echo "overlay gate: root inexistente: $ROOT" >&2
  exit 2
fi

viol=0

# 1. Todo uso del FPM vive en el servicio (único writer R1 C5).
#    NOTA: se filtran comentarios Kotlin (// y KDoc *) — pueden MENCIONAR el FPM sin
#    ser writers (falso positivo cazado por OV-POS2 del self-test, misma clase de
#    bug que el ancla ERE de PR#2: un gate sin self-test no lo habría visto).
fpm_sites=$(grep -rn 'FloatingPanelManager\.getInstance' "$ROOT" 2>/dev/null \
  | grep -vE ':[0-9]+:[[:space:]]*(//|\*)' || true)
if [ -n "$fpm_sites" ]; then
  outsiders=$(printf '%s\n' "$fpm_sites" | grep -v 'GameBoostService\.kt' || true)
  if [ -n "$outsiders" ]; then
    echo "overlay gate: FloatingPanelManager usado FUERA del servicio (único writer):"
    printf '%s\n' "$outsiders"
    viol=1
  fi
fi

# 2. Visibilidad: 2 matches (show+hide del observer de proyección)
vis_sites=$(grep -rnE 'FloatingPanelManager\.getInstance\([^)]*\)\.(show|hide)\(' "$ROOT" 2>/dev/null \
  | grep -vE ':[0-9]+:[[:space:]]*(//|\*)' || true)
vis_count=0
if [ -n "$vis_sites" ]; then
  vis_count=$(printf '%s\n' "$vis_sites" | grep -cE '.')
fi
if [ "$vis_count" -ne 2 ]; then
  echo "overlay gate: se esperaban EXACTAMENTE 2 writes de visibilidad (show+hide del"
  echo "observer de proyección en GameBoostService), hay $vis_count:"
  printf '%s\n' "$vis_sites"
  viol=1
fi

# 3. toggleVisibility(): writer ciego — ninguna invocación (la definición es legítima)
tv=$(grep -rn 'toggleVisibility()' "$ROOT" 2>/dev/null \
  | grep -v 'fun toggleVisibility' \
  | grep -vE ':[0-9]+:[[:space:]]*(//|\*)' || true)
if [ -n "$tv" ]; then
  echo "overlay gate: toggleVisibility() (writer ciego) encontrado:"
  printf '%s\n' "$tv"
  viol=1
fi

[ "$viol" -eq 0 ] || exit 1
echo "overlay gate OK — único writer de visibilidad: $(printf '%s' "$vis_sites" | head -1 | cut -d: -f1) (show+hide del observer de proyección)"

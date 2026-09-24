#!/usr/bin/env bash
#
# SSOT gate (PR2, auditado): todo archivo con writers privilegiados
# (settings put/delete, wm density, cmd power) debe integrar el funnel SSOT en su
# propio código — executePrivilegedCommand / recordApplied / recordBatch /
# collectIfSettings. Exención por LÍNEA (no por archivo) en GameBoostService:
# solo las 2 líneas NS3 conocidas de restoreSavedSettings.
#
# Uso: scripts/ssot_gate.sh [root]        (default: app/src/main/java)
# Exit: 0 = sin writers sin funnel · 1 = hits (imprime file:línea) · 2 = root inexistente
#
# Limitación conocida y documentada: un writer crudo añadido a un archivo que YA
# contiene evidencia de funnel no dispara (gate estático por archivo; el grep de
# línea pura marca falsos positivos en las llamadas multi-línea al funnel).
# Cobertura runtime de ese caso: anotada para PR#4 (ShizukuExecutor.recordCheck).
#
# Auto-test: scripts/gate_selftest.sh (corre en CI tras este gate).
set -euo pipefail

ROOT="${1:-app/src/main/java}"
PATTERN='settings put|settings delete|wm density|cmd power'
EVIDENCE='executePrivilegedCommand|recordApplied|recordBatch|collectIfSettings'
# NOTA ERE: el $ de las 2 líneas NS3 va escapado (\$) — en grep -E el $ mid-pattern
# es ancla de fin de línea, no literal (bug cazado por el CLEAN del self-test).
NS3_EXEMPT='wm density \$clampedDpi|settings put system pointer_speed \$savedPointerSpeed'

if [ ! -d "$ROOT" ]; then
  echo "SSOT gate: root inexistente: $ROOT" >&2
  exit 2
fi

bad=""
while IFS= read -r f; do
  if grep -qE "$EVIDENCE" "$f"; then
    continue
  fi
  if [[ "$f" == *GameBoostService.kt ]]; then
    extra=$( (grep -nE "$PATTERN" "$f" || true) | grep -vE "$NS3_EXEMPT" || true )
    if [ -n "$extra" ]; then
      bad+="$f:"$'\n'"$extra"$'\n'
    fi
    continue
  fi
  bad+="$f"$'\n'
done < <(grep -rlE "$PATTERN" "$ROOT" || true)

if [ -n "$bad" ]; then
  echo "SSOT gate: writers privilegiados SIN evidencia de funnel:"
  printf '%s' "$bad"
  exit 1
fi
echo "SSOT gate OK — todo writer con funnel o exención NS3 por línea ($ROOT)"

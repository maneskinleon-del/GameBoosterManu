#!/usr/bin/env bash
#
# Auto-test del SSOT gate (PR2, check 1b del auditor): un gate que no se testea a
# sí mismo es deuda — el bug del ERE ($ mid-pattern) se cazó precisamente con el
# caso CLEAN, y si no se hubiera corrido localmente el gate habría false-positiveado
# en cada CI hasta que alguien lo deshabilitara por ruido.
#
# Casos (contra scripts/ssot_gate.sh, la MISMA lógica que corre el CI):
#   CLEAN  árbol real del repo                        → exit 0
#   NEG1   writer crudo en archivo nuevo sin funnel   → exit 1
#   NEG2   writer NUEVO dentro de GameBoostService    → exit 1
#   POS    GameBoostService con SOLO las 2 líneas NS3 → exit 0 (la exención exenta)
#   LIMIT  writer crudo en archivo YA evidenciado     → exit 0 (limitación documentada)
#
# Los fixtures viven versionados en scripts/fixtures/gate/ y se copian a árboles
# mock temporales — nunca se escriben dentro de app/src/main/java.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATE="$REPO_ROOT/scripts/ssot_gate.sh"
FIX="$REPO_ROOT/scripts/fixtures/gate"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0

# expect <descripción> <exit esperado> <comando...>
expect() {
  local desc="$1" expected="$2"
  shift 2
  local out rc
  if out="$("$@" 2>&1)"; then rc=0; else rc=$?; fi
  if [ "$rc" -eq "$expected" ]; then
    echo "PASS  $desc (exit=$rc)"
    pass=$((pass + 1))
  else
    echo "FAIL  $desc (exit=$rc, esperado=$expected)"
    printf '%s\n' "$out" | sed 's/^/      | /'
    fail=$((fail + 1))
  fi
}

# CLEAN: el árbol real del repo debe pasar el gate
expect "CLEAN arbol real -> exit 0" 0 bash "$GATE" "$REPO_ROOT/app/src/main/java"

# NEG1: writer crudo, archivo nuevo, sin evidencia de funnel
mkdir -p "$TMP/neg1"
cp "$FIX/EvilWriter.kt" "$TMP/neg1/EvilWriter.kt"
expect "NEG1 writer crudo sin funnel -> exit 1" 1 bash "$GATE" "$TMP/neg1"

# NEG2: writer NUEVO dentro de GameBoostService (la exención NO debe taparlo)
mkdir -p "$TMP/neg2"
{ cat "$FIX/GameBoostService.kt"; echo 'val __gateTest = "settings put global evil2 1"'; } \
  > "$TMP/neg2/GameBoostService.kt"
expect "NEG2 writer nuevo en GameBoostService -> exit 1" 1 bash "$GATE" "$TMP/neg2"

# POS: GameBoostService con SOLO las 2 líneas NS3 → la exención por línea funciona
mkdir -p "$TMP/pos"
cp "$FIX/GameBoostService.kt" "$TMP/pos/GameBoostService.kt"
expect "POS exencion NS3 por linea -> exit 0" 0 bash "$GATE" "$TMP/pos"

# LIMIT: writer crudo en archivo YA evidenciado → pasa (limitación documentada
# del gate estático; el caso está cubierto por el runtime check de PR#4)
mkdir -p "$TMP/limit"
{
  echo '// fixture: archivo con evidencia de funnel'
  echo 'val sink = "recordApplied"'
  echo 'val writer = "settings put global evil3 1"'
} > "$TMP/limit/AlreadyEvidenced.kt"
expect "LIMIT archivo ya evidenciado -> exit 0 (documentado)" 0 bash "$GATE" "$TMP/limit"

# ── Overlay single-writer gate (PR3, R1 C5) ──────────────────────────
OVERLAY_GATE="$REPO_ROOT/scripts/overlay_gate.sh"

# OV-CLEAN: el árbol real debe pasar (tras consolidar W1/W2/W3/W4)
expect "OV-CLEAN arbol real -> exit 0" 0 bash "$OVERLAY_GATE" "$REPO_ROOT/app/src/main/java"

# Base mock: un servicio con SOLO el observer de proyección (show+hide en 2 líneas)
mkdir -p "$TMP/ov_base"
cat > "$TMP/ov_base/GameBoostService.kt" <<'EOF'
class FakeService {
    fun projection(want: Boolean) {
        if (want) FloatingPanelManager.getInstance(ctx).show()
        else FloatingPanelManager.getInstance(ctx).hide()
    }
}
EOF
expect "OV-POS mock con solo la proyeccion -> exit 0" 0 bash "$OVERLAY_GATE" "$TMP/ov_base"

# OV-NEG1: toggleVisibility desde la UI (el writer ciego de W1) → exit 1
mkdir -p "$TMP/ov1"
cp "$TMP/ov_base/GameBoostService.kt" "$TMP/ov1/GameBoostService.kt"
echo 'class FakeActivity { fun tap() { FloatingPanelManager.getInstance(ctx).toggleVisibility() } }' \
  > "$TMP/ov1/MainActivity.kt"
expect "OV-NEG1 toggleVisibility en la UI -> exit 1" 1 bash "$OVERLAY_GATE" "$TMP/ov1"

# OV-NEG2: show() extra fuera del servicio (el writer W3 de handleStart) → exit 1
mkdir -p "$TMP/ov2"
cp "$TMP/ov_base/GameBoostService.kt" "$TMP/ov2/GameBoostService.kt"
echo 'class Extra { fun ensure() { FloatingPanelManager.getInstance(ctx).show() } }' \
  > "$TMP/ov2/Extra.kt"
expect "OV-NEG2 show() extra fuera del servicio -> exit 1" 1 bash "$OVERLAY_GATE" "$TMP/ov2"

# OV-NEG3: show()+hide() duplicados DENTRO del servicio (el observer W2) → exit 1
mkdir -p "$TMP/ov3"
cat > "$TMP/ov3/GameBoostService.kt" <<'EOF'
class FakeService {
    fun projection(want: Boolean) {
        if (want) FloatingPanelManager.getInstance(ctx).show()
        else FloatingPanelManager.getInstance(ctx).hide()
    }
    fun secondObserver() { FloatingPanelManager.getInstance(ctx).show() }
}
EOF
expect "OV-NEG3 segundo observer dentro del servicio -> exit 1" 1 bash "$OVERLAY_GATE" "$TMP/ov3"

# OV-POS2: un COMENTARIO que menciona el FPM no debe disparar (regresión del falso
# positivo cazado en el árbol real — MainActivity:133 documentaba destroy() NO USAR)
mkdir -p "$TMP/ov4"
cp "$TMP/ov_base/GameBoostService.kt" "$TMP/ov4/GameBoostService.kt"
echo '// FloatingPanelManager.getInstance(ctx).destroy() — NO USAR (comentario)' \
  > "$TMP/ov4/MainActivity.kt"
expect "OV-POS2 comentario mencionando el FPM -> exit 0" 0 bash "$OVERLAY_GATE" "$TMP/ov4"

echo "-------------------------------------------"
echo "gate_selftest: $pass PASS / $fail FAIL"
[ "$fail" -eq 0 ]

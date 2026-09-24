// Fixture del SSOT gate: reproducción mínima de las 2 líneas NS3 exentas de
// GameBoostService.restoreSavedSettings (PR2). El gate debe:
//   - EXENTAR estas 2 líneas por-línea (caso POS → exit 0)
//   - FALLAR si se agrega cualquier otro writer al archivo (caso NEG2 → exit 1)
// No se compila (vive en scripts/, fuera de los sourceSets).
object GateFixtureGameBoostService {
    fun restoreSavedSettings(clampedDpi: Int, savedPointerSpeed: Int) {
        ShizukuExecutor.runCommand("wm density $clampedDpi")
        ShizukuExecutor.runCommand("settings put system pointer_speed $savedPointerSpeed")
    }
}

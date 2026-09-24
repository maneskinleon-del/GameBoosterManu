// Fixture del SSOT gate (NEG1): writer crudo en archivo NUEVO, sin ninguna
// evidencia de funnel SSOT. El gate debe fallar (exit 1) y reportar file:línea.
// No se compila (vive en scripts/, fuera de los sourceSets).
object GateFixtureEvilWriter {
    fun write() {
        ShizukuExecutor.runCommand("settings put global evil 1")
    }
}

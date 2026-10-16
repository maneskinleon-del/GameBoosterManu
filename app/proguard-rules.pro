# ─────────────────────────────────────────────────────────────────────
# GameBoost Pro — reglas R8/ProGuard (FASE A: shrink sin ofuscar)
# Plan-audit 2026-10-15. Cada regla justificada con evidencia grep/javap.
# ─────────────────────────────────────────────────────────────────────

# Justificación (ÚNICA regla keep de la app):
#   ShizukuExecutor.kt:247-253:
#     Shizuku::class.java.getDeclaredMethod("newProcess",
#         Array<String>::class.java, Array<String>::class.java, String::class.java)
#     .apply { isAccessible = true } → method.invoke(null, cmdArray, null, null)
#   rg -n "newProcess" app/src/main/java/ → SOLO L248 (el string) + L26/L29
#   (comentarios); CERO referencias estáticas al método.
#   javap -p (dev.rikka.shizuku:api:13.1.5):
#     private static rikka.shizuku.ShizukuRemoteProcess newProcess(String[], String[], String)
#   AAR api-13.1.5.aar → proguard.txt VACÍO (0 bytes): nadie lo keepcea.
#   Sin esta regla R8 elimina/renombra el miembro privado (alcanzable solo por
#   reflexión) → NoSuchMethodException SILENCIOSO → fallback Rish/Runtime:
#   0 crashes, degradación visible solo por la ausencia del log "Shizuku OK".
#   ⚠ Re-verificar la firma al cambiar shizuku en libs.versions.toml.
-keepclassmembers class rikka.shizuku.Shizuku {
    private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}

# FASE A — shrink sin ofuscar (medición pura). ELIMINAR en FASE B (decisión 2
# del operador): la ofuscación se activa y testea después, nunca junto con A.
-dontobfuscate

# ── Reglas NO agregadas — evidencia (plan-audit 2026-10-15) ──────────
# Room (19 matches: AppDatabase v2, 3 @Entity, 3 @Dao): NO hace falta —
#   room-runtime-2.6.1.aar proguard.txt ya trae
#   "-keep class * extends androidx.room.RoomDatabase"; los _Impl generados se
#   referencian estáticamente desde esa clase keepcida.
# Compose (28 @Composable): NO hace falta — consumer rules de los propios AARs
#   (runtime: -assumenosideeffects; ui: keeps ViewLayer/ModifierNodeElement +
#   dontwarn; material3: vacío); alcance estático desde MainActivity.
# JNI/nativos: NO aplica — rg "System.load|external fun|native fun" = 0.
# Lambdas Kotlin (18 matches de ?.invoke()): NO es reflexión → sin keep.
# Reflexión de framework (RishExecutor.kt:53 ActivityThread;
#   GameSessionManager.kt:600/619 setGameState): android.jar no se ofusca.
# Parcelable/Parcelize: 0 matches en app (el default file cubre CREATOR igual).
# -dontwarn: NINGUNO proactivo — si assembleDebug emite warnings, cada
#   -dontwarn se agrega con su causa documentada (sin supresión en frío).

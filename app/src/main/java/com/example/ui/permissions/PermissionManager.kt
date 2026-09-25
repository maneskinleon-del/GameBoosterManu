package com.example.ui.permissions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import rikka.shizuku.Shizuku

private const val TAG = "PermissionManager"

/**
 * Gestor centralizado de permisos y listeners de Shizuku para MainActivity.
 *
 * Stateless: recibe la Activity en el constructor, no mantiene estado mutable
 * entre ciclos de vida. El callback [onPermissionStateChanged] se conecta desde
 * la Activity al repo — PermissionManager no conoce al repositorio.
 *
 * Lifecycle: register() en onCreate (después de setear el callback),
 * unregister() en onDestroy.
 *
 * Refactor 5c-a: extraído de MainActivity para desacoplar lógica de permisos
 * de la Activity. Las funciones checkAndRequestPermissions e
 * isAccessibilityServiceEnabled fueron migradas/eliminadas aquí.
 *
 * Comportamiento preservado (refactor puro):
 * - shizukuBinderListener: silent check + onPermissionStateChanged callback
 * - shizukuPermissionListener: silent check SOLO (sin callback al repo)
 * - onCreate: register + checkAndRequest(false)
 * - onResume: checkAndRequest(true)
 * - onDestroy: unregister
 */
class PermissionManager(private val activity: ComponentActivity) {

    /**
     * Callback invocado SOLO cuando el binder de Shizuku se recibe (OnBinderReceived).
     * NO se invoca en OnRequestPermissionResult — preserva el comportamiento original
     * donde el grant de permiso no dispara onShizukuBinderReceived().
     */
    var onPermissionStateChanged: (() -> Unit)? = null

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        checkAndRequest(onlySilentCheck = true)
        onPermissionStateChanged?.invoke()
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        checkAndRequest(onlySilentCheck = true)
        // Sin callback al repo — evita re-apply redundante de boost settings
        // durante APPLYING justo después de que el usuario otorga el permiso.
    }

    /** Registra ambos listeners en Shizuku. Llamar después de setear el callback. */
    fun register() {
        Shizuku.addBinderReceivedListener(shizukuBinderListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
    }

    /** Desregistra ambos listeners. Llamar en onDestroy. */
    fun unregister() {
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    /**
     * Evalúa todos los permisos y, si [onlySilentCheck] es false, solicita los faltantes.
     *
     * El orden de evaluación es idéntico al original (MainActivity.checkAndRequestPermissions):
     *  1. overlay (SYSTEM_ALERT_WINDOW)
     *  2. Shizuku (ping + checkSelfPermission)
     *  3. POST_NOTIFICATIONS (solo Tiramisu+)
     *  4. battery optimization exemption (solo M+)
     */
    fun checkAndRequest(onlySilentCheck: Boolean = false) {
        val snapshot = PermissionSnapshot(
            hasOverlay = Settings.canDrawOverlays(activity),
            shizukuPing = Shizuku.pingBinder(),
            shizukuGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED,
            hasPostNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true,
            batteryUnrestricted = (activity.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(activity.packageName)) ?: true
        )

        val missing = missingPermissions(snapshot, Build.VERSION.SDK_INT)
        if (onlySilentCheck) return  // silent: solo evaluar, no pedir
        missing.forEach { requestPermissionAction(it) }
    }

    private fun requestPermissionAction(permission: Permission) {
        when (permission) {
            Permission.OVERLAY -> {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${activity.packageName}"))
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo abrir ajustes de overlay: ${e.message}")
                }
            }
            Permission.SHIZUKU -> Shizuku.requestPermission(0)
            Permission.POST_NOTIFICATIONS -> {
                ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
            Permission.BATTERY -> {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo abrir ajustes de batería: ${e.message}")
                }
            }
        }
    }
}

/** Permisos que el booster necesita, en orden de prioridad. */
enum class Permission { OVERLAY, SHIZUKU, POST_NOTIFICATIONS, BATTERY }

/**
 * Snapshot inmutable del estado de permisos del device.
 * Construido por [PermissionManager.checkAndRequest] para alimentar [missingPermissions].
 */
data class PermissionSnapshot(
    val hasOverlay: Boolean,
    val shizukuPing: Boolean,
    val shizukuGranted: Boolean,
    val hasPostNotifications: Boolean,
    val batteryUnrestricted: Boolean
)

/**
 * Lógica de decisión pura — testeable con JUnit puro (sin instrumentación).
 *
 * Reglas:
 * - OVERLAY: siempre faltante cuando !hasOverlay.
 * - SHIZUKU: solo faltante cuando el binder está vivo (ping) pero no concedido.
 *   Si ping es false, Shizuku no es solicitable → no se incluye.
 * - POST_NOTIFICATIONS: solo SDK 33+ (Tiramisu).
 * - BATTERY: solo SDK 23+ (M).
 */
fun missingPermissions(snapshot: PermissionSnapshot, sdkInt: Int): Set<Permission> = buildSet {
    if (!snapshot.hasOverlay) add(Permission.OVERLAY)
    if (snapshot.shizukuPing && !snapshot.shizukuGranted) add(Permission.SHIZUKU)
    if (sdkInt >= 33 && !snapshot.hasPostNotifications) add(Permission.POST_NOTIFICATIONS)
    if (sdkInt >= 23 && !snapshot.batteryUnrestricted) add(Permission.BATTERY)
}

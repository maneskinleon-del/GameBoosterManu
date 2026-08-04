package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.PreferenceManager

/**
 * BootReceiver — Reinicia el watchdog después de un boot.
 *
 * Cuando el dispositivo se reinicia, AlarmManager pierde todas las alarmas.
 * Este receptor se encarga de reprogramar el watchdog si el servicio
 * estaba activo antes del reinicio.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "📱 Dispositivo reiniciado. Verificando estado del servicio...")

        // Si el servicio estaba activo antes del reinicio, reprogramamos el watchdog
        if (PreferenceManager.isServiceRunning(context)) {
            Log.i(TAG, "🔄 Servicio estaba activo. Reprogramando watchdog...")

            // Programar el watchdog para que revise en 30 segundos
            ServiceWatchdogReceiver.schedule(context)

            // Intentar iniciar el servicio directamente
            val startIntent = Intent(context, GameBoostService::class.java).apply {
                action = GameBoostService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }

            Log.i(TAG, "✅ Servicio reiniciado post-boot")
        } else {
            Log.d(TAG, "⏹️ Servicio no estaba activo antes del reinicio, no se reprograma")
        }
    }
}

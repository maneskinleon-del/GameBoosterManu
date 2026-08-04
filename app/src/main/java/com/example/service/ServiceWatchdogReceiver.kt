package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.PreferenceManager

/**
 * ServiceWatchdogReceiver — Vigilante anti-LMK.
 *
 * Este BroadcastReceiver es activado periódicamente por AlarmManager.
 * Si GameBoostService no está corriendo pero debería estarlo (según
 * PreferenceManager), lo reinicia automáticamente.
 *
 * AlarmManager es un servicio del sistema, por lo que esta alarma
 * PERSISTE incluso si el proceso de la app es matado por el LMK.
 */
class ServiceWatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WatchdogReceiver"
        private const val REQUEST_CODE = 2002
        const val ACTION_WATCHDOG = "com.example.action.WATCHDOG_CHECK"
        private const val INTERVAL_MS = 30_000L // 30 segundos

        /**
         * Registra la alarma periódica del watchdog.
         * @param context Contexto de la aplicación
         */
        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: run {
                    Log.e(TAG, "AlarmManager no disponible")
                    return
                }

            val intent = Intent(context, ServiceWatchdogReceiver::class.java).apply {
                action = ACTION_WATCHDOG
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Usar RTC_WAKEUP para que despierte el dispositivo si está dormido
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + INTERVAL_MS,
                INTERVAL_MS,
                pendingIntent
            )

            Log.d(TAG, "⏰ Watchdog programado (cada ${INTERVAL_MS / 1000}s)")
        }

        /**
         * Cancela la alarma periódica del watchdog.
         * @param context Contexto de la aplicación
         */
        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return

            val intent = Intent(context, ServiceWatchdogReceiver::class.java).apply {
                action = ACTION_WATCHDOG
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "⏹️ Watchdog cancelado")
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_WATCHDOG) return

        try {
            val shouldBeRunning = PreferenceManager.isServiceRunning(context)

            if (!shouldBeRunning) {
                // El usuario desactivó el servicio, no lo reiniciamos
                Log.d(TAG, "Watchdog: servicio desactivado por usuario, omitiendo")
                cancel(context)
                return
            }

            if (GameBoostService.isRunning) {
                // El servicio está vivo, todo bien
                Log.d(TAG, "💚 Watchdog: servicio OK")
                return
            }

            // El servicio fue matado — lo reiniciamos
            Log.w(TAG, "💔 Watchdog: servicio CAÍDO. Reinciando...")

            val startIntent = Intent(context, GameBoostService::class.java).apply {
                action = GameBoostService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }

            Log.i(TAG, "✅ Watchdog: servicio reiniciado exitosamente")

        } catch (e: Exception) {
            Log.e(TAG, "Error en watchdog: ${e.message}")
        }
    }
}

package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

private const val TAG = "ServiceLauncher"

/**
 * Único punto de lanzamiento del GameBoostService. Reemplaza 6 sites dispersos:
 * - MainActivity.ensureGameBoostServiceRunning() → startIdle()
 * - DashboardScreen onCheckedChange → GSM maneja vía startBoost/stopBoost
 * - GameSessionManager.ensureBoostServiceRunning() → startBoost()
 * - BootReceiver direct start → startBoost()
 * - ServiceWatchdogReceiver direct start → startBoost()
 * - WatchdogManager.startCoreService() → startBoost()
 *
 * Race fix (5c-b Issue B): escribe pref SYNCRÓNICAMENTE ANTES del intent, con revert en catch.
 * Esto asegura que WatchdogManager.checkHealth() (L99-110) lea el valor correcto
 * entre que ServiceLauncher.startBoost() retorna y que el service procesa ACTION_START.
 *
 * Issue 2: si el intent falla (background restrictions, start rate limit), el pref se revierte.
 * Issue 3: stopBoost revierte a `true` en catch — el servicio sigue vivo aunque el intent falló.
 */
class ServiceLauncher(
    private val starter: ServiceStarter,
    private val setRunning: (Boolean) -> Unit,
    private val actionStart: String = GameBoostService.ACTION_START,
    private val actionStop: String = GameBoostService.ACTION_STOP
) {

    companion object {
        /** Factory para producción: crea launcher con AndroidServiceStarter + PreferenceManager. */
        fun create(context: Context): ServiceLauncher = ServiceLauncher(
            starter = AndroidServiceStarter(context),
            setRunning = { running ->
                com.example.data.PreferenceManager.setServiceRunning(context, running)
            },
            actionStart = GameBoostService.ACTION_START,
            actionStop = GameBoostService.ACTION_STOP
        )
    }

    /** Idle start — LMK protection solo. NO escribe pref (boost no activo). */
    fun startIdle() {
        // Punto 2 (Punto 2 de auditoría): preserva el guard del original
        // ensureGameBoostServiceRunning() — si el service ya corre, no reenviar Intent.
        // Android lo toleraría (onStartCommand no-op con action=null), pero evita
        // rate-limit en OEMs y el costo de un Intent redundante.
        if (GameBoostService.isRunning) return
        starter.startForeground(null)
        Log.d(TAG, "startIdle: servicio lanzado (LMK protection, no boost)")
    }

    /** Boost ON — pref BEFORE intent (race fix). Revert a false si falla. */
    fun startBoost() {
        try {
            setRunning(true)                  // pref=true ANTES del intent
            starter.startForeground(actionStart) // ACTION_START via startForegroundService
            Log.d(TAG, "startBoost: ACTION_START enviado, pref=true")
        } catch (e: Exception) {
            Log.e(TAG, "startBoost falló: ${e.message}", e)
            setRunning(false)                  // revert: el intent no se envió
            throw e
        }
    }

    /** Boost OFF — pref BEFORE intent. Revert a true si falla (service sigue vivo). */
    fun stopBoost() {
        try {
            setRunning(false)                  // pref=false ANTES del intent
            starter.startService(actionStop)   // ACTION_STOP via startService (no FGS)
            Log.d(TAG, "stopBoost: ACTION_STOP enviado, pref=false")
        } catch (e: Exception) {
            Log.e(TAG, "stopBoost falló: ${e.message}", e)
            setRunning(true)                   // revert: service sigue vivo
            throw e
        }
    }

    /** Recovery post-death — solo pref, NO envía intent. Uso: onRestored callback. */
    fun markStopped() {
        setRunning(false)
        Log.d(TAG, "markStopped: pref=false (recovery post-death)")
    }
}

/**
 * Producción: delega a Context.startForegroundService / startService con SDK gate.
 * @param context Context (no ComponentActivity) — funciona en BootReceiver/ServiceWatchdogReceiver.
 */
class AndroidServiceStarter(private val context: Context) : ServiceStarter {
    override fun startForeground(action: String?) {
        val intent = Intent(context, GameBoostService::class.java)
        if (action != null) intent.action = action
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    override fun startService(action: String?) {
        val intent = Intent(context, GameBoostService::class.java)
        if (action != null) intent.action = action
        context.startService(intent)
    }
}
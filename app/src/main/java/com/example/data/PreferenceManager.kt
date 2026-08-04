package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.manager.ProfileManager

object PreferenceManager {
    const val MAX_DPI = 600
    private const val PREF_NAME = "gameboost_prefs"
    private const val KEY_IS_RUNNING = "is_running"
    private const val KEY_PROFILE = "profile"
    private const val KEY_WINDOW_X = "window_x"
    private const val KEY_WINDOW_Y = "window_y"
    private const val KEY_DPI = "custom_dpi"
    private const val KEY_POINTER_SPEED = "pointer_speed"
    private const val KEY_AGGRESSIVE_OPTIMIZATION = "aggressive_optimization"
    private const val KEY_THERMAL_WATCHDOG = "thermal_watchdog"
    private const val KEY_AUTO_DETECT_GAMES = "auto_detect_games"
    private const val KEY_VIBRATION_FEEDBACK = "vibration_feedback"
    private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    private const val KEY_DEEP_SLEEP_OPTIMIZATIONS = "deep_sleep_optimizations"
    private const val KEY_FORCE_SCRCPY_MODE = "force_scrcpy_mode"

  /** Devuelve `true` si existe la clave custom_dpi guardada */
  fun isDpiForced(context: Context): Boolean =
    getPreferences(context).contains(KEY_DPI)
    
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getDpi(context: Context): Int {
        return getPreferences(context).getInt(KEY_DPI, -1)
    }

    fun saveDpi(context: Context, dpi: Int) {
        getPreferences(context).edit().putInt(KEY_DPI, dpi).apply()
    }

    fun getPointerSpeed(context: Context): Int {
        return getPreferences(context).getInt(KEY_POINTER_SPEED, 50)
    }

    fun savePointerSpeed(context: Context, speed: Int) {
        getPreferences(context).edit().putInt(KEY_POINTER_SPEED, speed).apply()
    }
    
    fun isServiceRunning(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_IS_RUNNING, false)
    }
    
    fun setServiceRunning(context: Context, running: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_IS_RUNNING, running).apply()
    }
    
    fun getProfile(context: Context): ProfileManager.ProfileType? {
        val name = getPreferences(context).getString(KEY_PROFILE, null)
        return name?.let {
            try {
                ProfileManager.ProfileType.valueOf(it)
            } catch (e: Exception) {
                null
            }
        }
    }
    
    fun saveProfile(context: Context, profile: ProfileManager.ProfileType) {
        getPreferences(context).edit().putString(KEY_PROFILE, profile.name).apply()
    }
    
    fun getWindowPosition(context: Context): Pair<Int, Int> {
        val prefs = getPreferences(context)
        return Pair(
            prefs.getInt(KEY_WINDOW_X, 50),
            prefs.getInt(KEY_WINDOW_Y, 200)
        )
    }
    
    fun saveWindowPosition(context: Context, x: Int, y: Int) {
        getPreferences(context).edit().apply {
            putInt(KEY_WINDOW_X, x)
            putInt(KEY_WINDOW_Y, y)
        }.apply()
    }

    fun isAggressiveOptimizationEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_AGGRESSIVE_OPTIMIZATION, false)
    }

    fun setAggressiveOptimizationEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_AGGRESSIVE_OPTIMIZATION, enabled).apply()
    }

    fun isThermalWatchdogEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_THERMAL_WATCHDOG, true)
    }

    fun setThermalWatchdogEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_THERMAL_WATCHDOG, enabled).apply()
    }

    fun isAutoDetectGamesEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_AUTO_DETECT_GAMES, true)
    }

    fun setAutoDetectGamesEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_AUTO_DETECT_GAMES, enabled).apply()
    }

    fun isVibrationFeedbackEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_VIBRATION_FEEDBACK, true)
    }

    fun setVibrationFeedbackEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_VIBRATION_FEEDBACK, enabled).apply()
    }

    fun isDeepSleepEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_DEEP_SLEEP_OPTIMIZATIONS, true)
    }

    fun setDeepSleepEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_DEEP_SLEEP_OPTIMIZATIONS, enabled).apply()
    }

    fun isForceScrcpyModeEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_FORCE_SCRCPY_MODE, false)
    }

    fun setForceScrcpyModeEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_FORCE_SCRCPY_MODE, enabled).apply()
    }

    // ─── Generic boolean helpers para SettingToggle ────────────────

    fun getPrefBoolean(context: Context, key: String, default: Boolean): Boolean {
        return getPreferences(context).getBoolean(key, default)
    }

    fun setPrefBoolean(context: Context, key: String, value: Boolean) {
        getPreferences(context).edit().putBoolean(key, value).apply()
    }
}

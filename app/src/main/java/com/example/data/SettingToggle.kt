package com.example.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wrapper unificado que sincroniza automáticamente un [MutableStateFlow]
 * con [SharedPreferences] usando [PreferenceManager].
 *
 * Ejemplo:
 *   val autoDetect = SettingToggle(context, "auto_detect_games", true)
 *   autoDetect.toggle()       // cambia estado + persiste
 *   autoDetect.value          // lectura síncrona
 *   autoDetect.state          // StateFlow para observación reactiva
 */
class SettingToggle(
    private val context: Context,
    private val prefKey: String,
    defaultValue: Boolean = true
) {
    private val _state = MutableStateFlow(
        PreferenceManager.getPrefBoolean(context, prefKey, defaultValue)
    )

    /** StateFlow reactivo para observar cambios */
    val state: StateFlow<Boolean> = _state.asStateFlow()

    /** Lectura síncrona del valor actual */
    val value: Boolean get() = _state.value

    /** Invierte el estado y persiste */
    fun toggle() {
        val newValue = !_state.value
        _state.value = newValue
        PreferenceManager.setPrefBoolean(context, prefKey, newValue)
    }

    /** Setea un valor específico y persiste */
    fun set(value: Boolean) {
        _state.value = value
        PreferenceManager.setPrefBoolean(context, prefKey, value)
    }

    override fun toString(): String = "SettingToggle($prefKey=$value)"
}

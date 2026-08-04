package com.example.manager

import android.content.Context
import com.example.R
import com.example.data.PreferenceManager
import kotlinx.coroutines.launch

/**
 * Sanitiza un string para uso seguro en comandos shell.
 * Solo permite: letras, dígitos, puntos, guiones, guiones bajos.
 */
private fun sanitizeForShell(input: String): String {
    return input.filter { c ->
        c.isLetterOrDigit() || c == '.' || c == '-' || c == '_'
    }
}

object ProfileManager {
    
    enum class ProfileType(
        val displayName: String,
        val icon: String,
        val iconRes: Int,
        val priority: Int,
        val governor: String,
        val refreshRate: String,
        val pointerSpeed: Int,
        val animationScale: Int,
        val thermalOverride: Boolean = false
    ) {
        EXTREME("🔥 EXTREME", "🔥", R.drawable.ic_extreme, 5, "performance", "120", 7, 0, true),
        ADS("⌨️ FF MOUSE", "⌨️", R.drawable.ic_ads, 4, "performance", "120", 5, 0),
        GAMING("🎮 GAMING", "🎮", R.drawable.ic_gaming, 3, "performance", "120", 6, 0),
        FREE_FIRE_TOUCH("🎯 FREE FIRE", "🎯", R.drawable.ic_gaming, 3, "performance", "120", 6, 0),
        BALANCED("⚖️ BALANCED", "⚖️", R.drawable.ic_balanced, 2, "schedutil", "60", 5, 1),
        POWER_SAVE("🔋 POWER SAVE", "🔋", R.drawable.ic_power_save, 1, "powersave", "60", 3, 1);
        
        companion object {
            fun fromPriority(priority: Int): ProfileType {
                return values().find { it.priority == priority } ?: BALANCED
            }
        }
    }
    
    private var currentProfile = ProfileType.BALANCED
    private var context: Context? = null
    
    fun init(context: Context) {
        this.context = context
        currentProfile = PreferenceManager.getProfile(context) ?: ProfileType.BALANCED
    }
    
    fun getCurrentProfile(): ProfileType = currentProfile
    
    fun applyProfile(profile: ProfileType): Boolean {
        currentProfile = profile
        val ctx = context ?: return false
        PreferenceManager.saveProfile(ctx, profile)
        
        val repository = com.example.data.repository.GameBoostRepository.getInstance(ctx)
        
        // Automatización total de la inyección según el perfil
        val commands = mutableListOf<String>()
        
        // 1. CPU Governor (sanitized)
        val safeGovernor = sanitizeForShell(profile.governor)
        val safeRefresh = sanitizeForShell(profile.refreshRate)
        val safeAnim = sanitizeForShell(profile.animationScale.toString())

        // Comando compatible con ambos layouts de kernel:
        // - /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor (per-CPU)
        // - /sys/devices/system/cpu/cpufreq/policy0/scaling_governor (policy)
        commands.add("for dir in /sys/devices/system/cpu/cpu[0-9]*/cpufreq /sys/devices/system/cpu/cpufreq/policy*; do [ -f \"\$dir/scaling_governor\" ] && echo $safeGovernor > \"\$dir/scaling_governor\" 2>/dev/null; done")
        
        // 2. Refresh Rate
        commands.add("settings put system peak_refresh_rate $safeRefresh.0")
        commands.add("settings put system min_refresh_rate $safeRefresh.0")
        
        // 3. Animations
        commands.add("settings put global window_animation_scale $safeAnim")
        commands.add("settings put global transition_animation_scale $safeAnim")
        commands.add("settings put global animator_duration_scale $safeAnim")
        
        // 4. Performance Mode
        val perfMode = profile.priority >= 4
        commands.add("cmd power set-fixed-performance-mode-enabled $perfMode")
        
        repository.repositoryScope.launch {
            repository.executePrivilegedCommands(commands, tag = "ProfileApply")

            // Especiales para Táctil y Puntero
            if (profile == ProfileType.ADS || profile == ProfileType.EXTREME) {
                repository.setPointerSpeed(profile.pointerSpeed * 10) // Mapear 0-10 a 0-100%
                if (profile == ProfileType.ADS && !repository.isMobiladorActive.value) {
                    repository.toggleMobilador()
                }
            }
        }
        
        return true
    }
    
    fun restoreDefaults() {
        applyProfile(ProfileType.BALANCED)
    }
}

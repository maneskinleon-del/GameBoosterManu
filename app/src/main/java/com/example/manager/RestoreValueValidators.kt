package com.example.manager

/**
 * Validadores de dominio para valores read-back del settings provider antes de
 * reinsertarse en comandos de restore (FIX H6 — 2026-09-22).
 *
 * REGLA FUNDAMENTAL: son PREDICADOS puros — nunca transforman el valor.
 *   valor válido   → el caller lo interpola byte a byte intacto.
 *   valor inválido → el caller NO ejecuta el comando y registra el fallo.
 *
 * No hay blacklist genérica de caracteres como sustituto de dominio: cada
 * dominio conocido tiene su validador específico. Para keys desconocidas
 * (baselines de sesiones viejas que ya no están en el inventario, ver nota de
 * migración en BoostKeys) existe un fallback estructural conservador
 * ([isSafeSettingsToken]) que solo acepta valores de una sola pieza sin
 * metacaracteres shell — es un fallback, no un sustituto de la validación
 * por dominio.
 *
 * JVM puro: sin Android, sin filesystem, sin Shizuku, sin efectos secundarios.
 */
object RestoreValueValidators {

    /** Longitud máxima tolerada para un valor de settings (blobs largos incluidos). */
    private const val MAX_VALUE_LENGTH = 1024

    /**
     * zen_mode AOSP: 0 (off), 1 (important interruptions), 2 (none),
     * 3 (alarms only). Valores fuera de rango → rechazo conservador.
     */
    fun isZenMode(v: String): Boolean = v.toIntOrNull()?.let { it in 0..3 } == true

    /**
     * Numérico decimal finito (entero o con decimales: "0", "-7", "120.0",
     * "300000"). Rechaza payloads con metacaracteres, espacios y no-finitos
     * ("Infinity"/"NaN").
     */
    fun isNumeric(v: String): Boolean {
        if (v.isEmpty() || v.length > MAX_VALUE_LENGTH) return false
        val d = v.toDoubleOrNull() ?: return false
        return d.isFinite()
    }

    /**
     * Token identificador: nombres de renderer/booleanos/etc. que Android
     * almacena como una sola palabra shell-inerte ("skiavk", "false", "true").
     */
    fun isIdentifierToken(v: String): Boolean =
        v.isNotEmpty() && v.length <= MAX_VALUE_LENGTH &&
            v.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }

    /** Switch 0/1 (switches globales de SystemTweaks/NetworkOptimizer). */
    fun isBoolish01(v: String): Boolean = v == "0" || v == "1"

    /** true/false de debug.hwui.overdraw y debug.hwui.show_dirty_regions. */
    fun isBoolToken(v: String): Boolean = v == "true" || v == "false"

    /**
     * Settings.Global.overlay_display_devices (AOSP): string, NO int.
     * "" = sin overlays; "WxH[/DPI](,WxH[/DPI])*" = displays virtuales AOSP.
     * "0" es el valor mágico que escribe el boost (SystemTweaks.apply). AOSP lo
     * ignora, pero el restore tiene que aceptarlo: si no, appliedValue="0" cae
     * en RESTORE_FAILED → RECOVERY_REQUIRED en el camino feliz.
     * No se cambia el writer a "": eso reescribiría baselines ya persistidos.
     */
    fun isOverlayDisplayDevices(v: String): Boolean {
        if (v == "0" || v.isEmpty()) return true
        return v.split(',').all { part ->
            val segs = part.split('/')
            (segs.size == 1 || segs.size == 2) &&
                Regex("""^\d+x\d+$""").matches(segs[0]) &&
                (segs.size == 1 || segs[1].toIntOrNull() != null)
        }
    }


    /** Modos Private DNS válidos en AOSP. */
    fun isPrivateDnsMode(v: String): Boolean = v == "off" || v == "opportunistic" || v == "hostname"

    /**
     * Specifier de Private DNS: hostname RFC-1123 con sufijo opcional
     * "/<uplink>" (formato legítimo de AOSP, p.ej. "DoT.example.com/eUplink").
     * Conserva "dns.google" y el formato con "/" intactos, byte a byte.
     */
    fun isDnsSpecifier(v: String): Boolean {
        if (v.isEmpty() || v.length > 255) return false
        val slash = v.indexOf('/')
        val host: String
        val uplink: String?
        if (slash == -1) {
            host = v
            uplink = null
        } else {
            if (v.indexOf('/', slash + 1) != -1) return false // solo un '/'
            host = v.substring(0, slash)
            uplink = v.substring(slash + 1)
        }
        if (host.isEmpty()) return false
        if (uplink != null && !isDnsLabel(uplink)) return false
        return host.split('.').all { isDnsLabel(it) }
    }

    private fun isDnsLabel(label: String): Boolean =
        label.length in 1..63 &&
            label.all { it.isLetterOrDigit() || it == '-' } &&
            !label.startsWith("-") &&
            !label.endsWith("-")

    /**
     * Blob de activity_manager_constants: lista "k=v" separada por comas
     * (p.ej. "max_cached_processes=128" o "cached_processes=32,a=1,b=2").
     */
    fun isActivityManagerConstants(v: String): Boolean {
        if (v.isEmpty() || v.length > MAX_VALUE_LENGTH) return false
        return v.split(',').all { part ->
            val kv = part.split('=')
            kv.size == 2 &&
                kv[0].isNotEmpty() && kv[0].all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' } &&
                kv[1].isNotEmpty() && kv[1].all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        }
    }

    /**
     * Fallback conservador para keys NO mapeadas (baselines de sesiones viejas):
     * una sola pieza sin espacios ni metacaracteres shell, longitud acotada.
     * NO sustituye a la validación por dominio de las keys conocidas.
     */
    fun isSafeSettingsToken(v: String): Boolean =
        v.isNotEmpty() && v.length <= MAX_VALUE_LENGTH &&
            v.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == '=' || it == ',' || it == '/' }

    /** Dominio por key conocida del inventario de restore (BoostKeys + optimizers).
     *
     * PR4: cobertura completa — TODA key de BoostKeys.all tiene dominio específico.
     * El test RestoreValueValidatorsPr4Test lo verifica (forKey != null para todas),
     * así que una key agregada al inventario sin validador revienta la suite.
     */
    fun forKey(key: String): ((String) -> Boolean)? = when (key) {
        "private_dns_mode" -> ::isPrivateDnsMode
        "private_dns_specifier" -> ::isDnsSpecifier
        "activity_manager_constants" -> ::isActivityManagerConstants
        "zen_mode" -> ::isZenMode
        "overlay_display_devices" -> ::isOverlayDisplayDevices
        "debug.hwui.renderer" -> ::isIdentifierToken
        // Writers reales ponen "false"/"true", no un identificador tipo skiavk.
        "debug.hwui.overdraw", "debug.hwui.show_dirty_regions" -> ::isBoolToken
        // TouchOptimizer (restore de backup por key)
        "pointer_speed", "long_press_timeout",
        "accessibility_display_magnification_enabled", "accessibility_autoclick_enabled" -> ::isNumeric
        // Switches 0/1 (SystemTweaks/NetworkOptimizer)
        "ble_scan_always_enabled", "wifi_scan_always_enabled", "bluetooth_disabled_profiles",
        "disable_window_blurs", "adaptive_connected_voice_enabled",
        "debug.sf.disable_hwc_vds", "debug.sf.disable_backpressure", "debug.sf.latch_unsignaled",
        "auto_sync" -> ::isBoolish01
        // Decimales (animation scales, refresh rates)
        "window_animation_scale", "transition_animation_scale", "animator_duration_scale",
        "peak_refresh_rate", "min_refresh_rate",
        // Numéricos enteros/medidos (niveles, ms, velocidades, MSAA)
        "send_action_app_error", "low_power_trigger_level", "debug.gl.msaa",
        "wifi_watchdog_on", "wifi_scan_interval_ms", "wifi_power_save",
        "wifi_low_latency_mode", "wifi_bt_coexistence" -> ::isNumeric
        else -> null
    }

    /**
     * Punto único de decisión para un valor read-back de una key:
     * key conocida → su dominio específico; key desconocida → fallback
     * estructural conservador. Nunca transforma: solo decide válido/inválido.
     */
    fun isValidRestoreValue(key: String, value: String): Boolean =
        (forKey(key) ?: ::isSafeSettingsToken)(value)

    // ── FIX H6b: dominio del namespace y estructura de la key ─────────

    /**
     * Namespaces del settings provider que este componente puede restaurar.
     * Evidencia (inventario autoritativo): `BoostKeys.all` (BoostSessionManager.kt,
     * globalKeys/systemKeys/secureKeys) es el ÚNICO productor de entradas de
     * baseline, y no existe en el repo ninguna construcción de
     * "settings get/put/delete" con namespace fuera de este conjunto
     * (auditoría T4B post-A1). Match exacto y case-sensitive: AOSP los define
     * en minúscula; "Global" no es un namespace válido.
     */
    val SETTINGS_NAMESPACES: Set<String> = setOf("global", "system", "secure")

    /**
     * Validación estructural del namespace: pertenencia exacta al dominio real
     * del proyecto. Rechaza metacaracteres shell por construcción (un namespace
     * con ";", "&&", ">", backticks, etc. no está en el conjunto).
     */
    fun isValidSettingsNamespace(namespace: String): Boolean =
        namespace in SETTINGS_NAMESPACES

    /**
     * Validación estructural de la key: un solo token shell-inerte
     * ([isIdentifierToken]: letras/dígitos/'.'/'-'/'_' sin espacios ni
     * metacaracteres). NO es una whitelist de inventario: keys legacy removidas
     * de BoostKeys (ver nota de migración) siguen siendo válidas mientras sean
     * tokens seguros — todas las keys reales del repo (BoostKeys + optimizers,
     * actuales y removidas documentadas) usan exclusivamente ese charset.
     */
    fun isValidSettingsKey(key: String): Boolean = isIdentifierToken(key)
}

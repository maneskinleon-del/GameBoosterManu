package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.R
import com.example.manager.ProfileManager
import kotlinx.coroutines.*

/**
 * Gestiona la ventana flotante (overlay) de GameBoost Pro.
 *
 * Se obtiene vía [getInstance] y usa el ApplicationContext para sobrevivir
 * a cambios de Activity. El overlay se muestra/oculta desde [GameBoostService]
 * cuando el boost está activo/inactivo.
 *
 * MEJORAS APLICADAS (Agosto 2026):
 * - WindowManager obtenida FRESCA cada vez desde ApplicationContext
 * - FLAG_NOT_TOUCH_MODAL para no bloquear toques fuera del overlay
 * - LayoutParams recreados en show() para dimensiones correctas
 * - toggleExpand() redimensiona el overlay al tamaño correcto
 * - Manejo robusto de posición inicial (evita fuera de pantalla)
 */
class FloatingPanelManager(private val appContext: Context) {
    
    companion object {
        private const val TAG = "FloatingPanelManager"
        @SuppressLint("StaticFieldLeak")
        private var instance: FloatingPanelManager? = null
        
        fun getInstance(context: Context): FloatingPanelManager {
            return instance ?: FloatingPanelManager(context.applicationContext).also { instance = it }
        }
    }
    
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var floatingView: View? = null
    private var isVisible = false
    private var isExpanded = false
    private var currentProfile = ProfileManager.ProfileType.BALANCED
    
    // LayoutParams base — se clona y ajusta en show() cada vez
    private fun createBaseLayoutParams(): WindowManager.LayoutParams {
        val position = com.example.data.PreferenceManager.getWindowPosition(appContext)
        val displayMetrics = appContext.resources.displayMetrics
        // Validar posición: si está fuera de pantalla, resetear a default
        val safeX = position.first.coerceIn(0, displayMetrics.widthPixels - 50)
        val safeY = position.second.coerceIn(0, displayMetrics.heightPixels - 50)
        
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = safeX
            y = safeY
        }
    }
    
    /** Obtiene WindowManager fresco desde ApplicationContext */
    private fun getWindowManager(): WindowManager {
        return appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    
    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        android.util.Log.d(TAG, "show() requested. isVisible=$isVisible, floatingView=$floatingView")
        
        mainHandler.post {
            if (isVisible && floatingView != null) {
                android.util.Log.d(TAG, "Already visible, ignoring show()")
                return@post
            }
            
            try {
                if (!android.provider.Settings.canDrawOverlays(appContext)) {
                    android.util.Log.e(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission not granted")
                    // Intentar abrir settings de overlay para que el usuario conceda el permiso
                    try {
                        val intent = Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${appContext.packageName}")
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        appContext.startActivity(intent)
                    } catch (_: Exception) {}
                    return@post
                }

                val wm = getWindowManager()

                // Si floatingView existe pero no es visible, removerlo primero
                if (floatingView != null) {
                    try { wm.removeView(floatingView) } catch (_: Exception) {}
                    floatingView = null
                }

                // Crear LayoutParams fresco cada vez
                val layoutParams = createBaseLayoutParams()

                // Inflar vista con tema para los componentes CardView
                val themeContext = android.view.ContextThemeWrapper(appContext, android.R.style.Theme_DeviceDefault_NoActionBar)
                val newView = LayoutInflater.from(themeContext).inflate(R.layout.floating_panel, null)
                floatingView = newView

                // Configurar listeners y perfil
                setupDragListener(newView, layoutParams, wm)
                updateProfileDisplay(currentProfile, newView)
                
                android.util.Log.d(TAG, "Adding view to WindowManager at x=${layoutParams.x}, y=${layoutParams.y}")
                wm.addView(newView, layoutParams)
                isVisible = true
                isExpanded = false
                android.util.Log.i(TAG, "✅ Floating panel shown successfully")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ Error showing floating panel: ${e.message}")
                android.util.Log.w(TAG, android.util.Log.getStackTraceString(e))
                isVisible = false
                floatingView = null
            }
        }
    }
    
    fun hide() {
        android.util.Log.d(TAG, "hide() requested. isVisible=$isVisible, floatingView=$floatingView")
        mainHandler.post {
            if (!isVisible || floatingView == null) return@post
            try {
                val wm = getWindowManager()
                wm.removeView(floatingView)
                android.util.Log.i(TAG, "Floating panel removed successfully")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error hiding floating panel: ${e.message}")
            } finally {
                floatingView = null
                isVisible = false
                isExpanded = false
            }
        }
    }
    
    fun updateMetrics(metrics: com.example.data.repository.SystemMetrics) {
        if (!isVisible || floatingView == null || !isExpanded) return
        
        floatingView?.post {
            floatingView?.findViewById<TextView>(R.id.tvStatTemp)?.text = "🌡️ ${metrics.cpuTemp.toInt()}°C"
            floatingView?.findViewById<TextView>(R.id.tvStatBattery)?.text = "⚡ ${metrics.batteryLevel}%"
            floatingView?.findViewById<TextView>(R.id.tvStatRam)?.text = "🧠 ${(metrics.ramUsed / 1024.0).format(1)}GB"
            floatingView?.findViewById<TextView>(R.id.tvStatPing)?.text = "🌐 ${metrics.ping}ms"
        }
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)

    fun updateProfile(profile: ProfileManager.ProfileType) {
        currentProfile = profile
        if (isVisible) {
            floatingView?.post {
                updateProfileDisplay(profile, floatingView)
            }
        }
    }
    
    private fun updateProfileDisplay(profile: ProfileManager.ProfileType, view: View?) {
        val v = view ?: floatingView ?: return
        
        v.findViewById<TextView>(R.id.tvProfileIconSmall)?.text = profile.icon
        v.findViewById<TextView>(R.id.tvProfileIconExpanded)?.text = profile.icon
        
        val panelCard = v.findViewById<androidx.cardview.widget.CardView>(R.id.panelCard)
        panelCard?.setCardBackgroundColor(
            when (profile) {
                ProfileManager.ProfileType.EXTREME -> ContextCompat.getColor(appContext, R.color.profile_extreme)
                ProfileManager.ProfileType.ADS -> ContextCompat.getColor(appContext, R.color.profile_ads)
                ProfileManager.ProfileType.GAMING -> ContextCompat.getColor(appContext, R.color.profile_gaming)
                ProfileManager.ProfileType.FREE_FIRE_TOUCH -> ContextCompat.getColor(appContext, R.color.profile_free_fire_touch)
                ProfileManager.ProfileType.BALANCED -> ContextCompat.getColor(appContext, R.color.profile_balanced)
                ProfileManager.ProfileType.POWER_SAVE -> ContextCompat.getColor(appContext, R.color.profile_power_save)
            }
        )
        
        if (profile == ProfileManager.ProfileType.EXTREME) {
            startGlowEffect(v)
        } else {
            stopGlowEffect(v)
        }
    }
    
    private fun startGlowEffect(view: View) {
        val glowView = view.findViewById<View>(R.id.glowEffect)
        glowView?.apply {
            visibility = View.VISIBLE
            animate().alpha(0.6f).scaleX(1.1f).scaleY(1.1f).setDuration(800).withEndAction {
                animate().alpha(0.2f).scaleX(0.9f).scaleY(0.9f).setDuration(800).withEndAction { 
                    if (isVisible && currentProfile == ProfileManager.ProfileType.EXTREME) startGlowEffect(view) 
                }.start()
            }.start()
        }
    }
    
    private fun stopGlowEffect(view: View) {
        view.findViewById<View>(R.id.glowEffect)?.apply {
            animate().cancel()
            visibility = View.GONE
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragListener(
        view: View,
        layoutParams: WindowManager.LayoutParams,
        wm: WindowManager
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var lastClickTime: Long = 0
        
        // La cruz cierra el overlay
        view.findViewById<View>(R.id.btnCollapse)?.setOnClickListener {
            hide()
        }

        view.findViewById<View>(R.id.llStatRam)?.setOnClickListener {
            GlobalScope.launch(Dispatchers.IO) {
                com.example.manager.ShizukuExecutor.runCommand("pm trim-caches 128M")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
        
        // Click en icono del perfil → expandir/colapsar
        val profileClickAction = View.OnClickListener {
            toggleExpand(!isExpanded, view, layoutParams, wm)
        }
        view.findViewById<View>(R.id.tvProfileIconSmall)?.setOnClickListener(profileClickAction)
        view.findViewById<View>(R.id.tvProfileIconExpanded)?.setOnClickListener(profileClickAction)

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }
                    
                    if (isDragging) {
                        layoutParams.x = (initialX + dx).toInt()
                        layoutParams.y = (initialY + dy).toInt()
                        
                        val displayMetrics = appContext.resources.displayMetrics
                        val maxX = displayMetrics.widthPixels - view.width
                        val maxY = displayMetrics.heightPixels - view.height
                        layoutParams.x = layoutParams.x.coerceIn(0, maxX)
                        layoutParams.y = layoutParams.y.coerceIn(0, maxY)
                        
                        wm.updateViewLayout(view, layoutParams)
                        com.example.data.PreferenceManager.saveWindowPosition(appContext, layoutParams.x, layoutParams.y)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastClickTime < 300) {
                            // Doble click → abrir app
                            val intent = Intent(appContext, com.example.MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            appContext.startActivity(intent)
                        } else {
                            toggleExpand(!isExpanded, view, layoutParams, wm)
                        }
                        lastClickTime = currentTime
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleExpand(expand: Boolean, view: View, layoutParams: WindowManager.LayoutParams, wm: WindowManager) {
        isExpanded = expand
        
        view.findViewById<View>(R.id.panelCard).visibility = if (expand) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.expandedCard).visibility = if (expand) View.VISIBLE else View.GONE
        
        // Forzar medición para que el layout tome el tamaño correcto
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        
        // Ajustar dimensiones al nuevo estado
        if (expand) {
            layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
            layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        
        try {
            wm.updateViewLayout(view, layoutParams)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "toggleExpand updateViewLayout: ${e.message}")
        }
    }
    
    fun destroy() {
        hide()
        try {
            floatingView?.let { 
                getWindowManager().removeView(it)
                floatingView = null
            }
        } catch (_: Exception) {}
        instance = null
    }
    
    fun toggleVisibility() {
        if (isVisible) hide() else show()
    }
}

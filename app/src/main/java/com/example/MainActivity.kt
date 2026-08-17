package com.example

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.PreferenceManager
import kotlin.math.roundToInt
import com.example.data.database.ProfileEntity
import com.example.data.repository.SystemMetrics
import com.example.manager.ProfileManager
import com.example.service.GameBoostService
import com.example.ui.FloatingPanelManager
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.WarningOrange
import com.example.ui.theme.ErrorRed
import com.example.ui.viewmodel.GameBoostViewModel
import rikka.shizuku.Shizuku

/** Valores discretos para el slider de DPI: de 280 a 600 en pasos de 40 */
private val DPI_STEPS = listOf(280, 320, 360, 400, 440, 480, 520, 560, 600)

class MainActivity : ComponentActivity() {

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        checkAndRequestPermissions(onlySilentCheck = true)
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        checkAndRequestPermissions(onlySilentCheck = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        ProfileManager.init(this)
        
        Shizuku.addBinderReceivedListener(shizukuBinderListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        
        checkAndRequestPermissions(onlySilentCheck = false)

        // ── Iniciar GameBoostService como foreground service ANTI-LMK ──
        // El servicio foreground con notificación protege el proceso del Low Memory Killer.
        // Se inicia siempre al abrir la app, independientemente del estado guardado.
        // Si el servicio es matado, START_REDELIVER_INTENT + watchdog lo reinician.
        ensureGameBoostServiceRunning()

        setContent {
            MyApplicationTheme {
                val viewModel: GameBoostViewModel = viewModel(
                    factory = GameBoostViewModel.Factory(LocalContext.current)
                )
                GameBoostApp(viewModel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        // ❌ NO destruir el overlay flotante aquí.
        // El overlay es una ventana independiente (WindowManager) que NO depende
        // del ciclo de vida de la Activity. Si la Activity es destruida por el sistema
        // (presión de memoria durante el juego), el overlay se pierde.
        // El overlay debe ser gestionado únicamente por GameBoostService.
        // FloatingPanelManager.getInstance(this).destroy() — NO USAR
    }
    
    /**
     * Inicia GameBoostService si no está ya corriendo.
     * Es independiente de PreferenceManager.isServiceRunning() para asegurar
     * que el servicio arranque incluso después de un crash o kill del proceso.
     */
    private fun ensureGameBoostServiceRunning() {
        try {
            if (!com.example.service.GameBoostService.isRunning) {
                val intent = Intent(this, com.example.service.GameBoostService::class.java)
                // ⚠️ SIN ACTION_START. El servicio se inicia solo con la notificación
                // (LMK protection) pero NO aplica perfiles ni restaura settings.
                // ACTION_START se envía SOLO desde toggleBoost() cuando el usuario o
                // la detección de juego activa el boost.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Log.d("MainActivity", "🚀 GameBoostService iniciado (LMK protection, idle)")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error al iniciar GameBoostService: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndRequestPermissions(onlySilentCheck = true)
    }

    override fun onStart() {
        super.onStart()
        // No ocultar el panel aquí si el servicio está corriendo.
        // Solo lo ocultamos si realmente queremos forzar la UI de la app.
    }

    override fun onStop() {
        super.onStop()
        // El servicio GameBoostService ya se encarga de mostrar el panel
        // si el boost está activo a través de su propio monitoreo.
    }

    private fun checkAndRequestPermissions(onlySilentCheck: Boolean = false) {
        if (!Settings.canDrawOverlays(this)) {
            if (!onlySilentCheck) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (e: Exception) {}
            }
        }

        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (!onlySilentCheck) Shizuku.requestPermission(0)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (!onlySilentCheck) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Accesibilidad: pedir UNA sola vez (flag persistente). Después, el
        // estado se muestra pasivo en la tarjeta ACCESIBILIDAD (apertura manual
        // desde el dashboard) — nunca más se secuestra el arranque con Ajustes.
        if (!isAccessibilityServiceEnabled()) {
            if (!onlySilentCheck && !PreferenceManager.isAccessibilityPrompted(this)) {
                PreferenceManager.setAccessibilityPrompted(this, true)
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {}
            }
        }
    }

    /** Detección robusta vía AccessibilityManager (no lee Settings.Secure por
     *  string — evita lecturas stale en proceso en Android 8+). */
    @Suppress("DEPRECATION")
    private fun isAccessibilityServiceEnabled(): Boolean {
        if (com.example.service.UnifiedAccessibilityService.isServiceRunning) return true
        val am = getSystemService(AccessibilityManager::class.java) ?: return false
        val expected = ComponentName(this, com.example.service.UnifiedAccessibilityService::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == expected.packageName &&
                   it.resolveInfo.serviceInfo.name == expected.className }
    }
}

enum class NavigationTab {
    TABLERO, OPTIMIZAR, LOGS, DIAGNOSTICO
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameBoostApp(viewModel: GameBoostViewModel) {
    var selectedTab by remember { mutableStateOf(NavigationTab.TABLERO) }
    val shizukuConnected by viewModel.shizukuConnected.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            Icons.Rounded.Memory, 
                            contentDescription = null, 
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text("GAMEBOOST PRO", fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { FloatingPanelManager.getInstance(context).toggleVisibility() }) {
                        Icon(Icons.AutoMirrored.Rounded.ViewQuilt, contentDescription = "Panel", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { 
                        if (!Shizuku.pingBinder()) {
                            try {
                                val intent = context.packageManager.getLaunchIntentForPackage("rikka.shizuku")
                                if (intent != null) context.startActivity(intent)
                                else Toast.makeText(context, "Shizuku no instalado", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error al abrir Shizuku", Toast.LENGTH_SHORT).show()
                            }
                        } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                            Shizuku.requestPermission(0)
                        } else {
                            viewModel.toggleShizukuState() 
                        }
                    }) {
                        Icon(
                            imageVector = if (shizukuConnected) Icons.Rounded.Power else Icons.Rounded.PowerOff,
                            contentDescription = null,
                            tint = if (shizukuConnected) MaterialTheme.colorScheme.secondary else WarningOrange
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0B1326)) {
                NavigationTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { 
                            val icon = when(tab) {
                                NavigationTab.TABLERO -> Icons.Rounded.Dashboard
                                NavigationTab.OPTIMIZAR -> Icons.Rounded.RocketLaunch
                                NavigationTab.LOGS -> Icons.Rounded.History
                                NavigationTab.DIAGNOSTICO -> Icons.Rounded.Analytics
                            }
                            Icon(icon, contentDescription = null)
                        },
                        label = { Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = Color.White.copy(alpha = 0.4f),
                            unselectedTextColor = Color.White.copy(alpha = 0.4f),
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            AnimatedContent(targetState = selectedTab, label = "") { tab ->
                when (tab) {
                    NavigationTab.TABLERO -> DashboardScreen(viewModel)
                    NavigationTab.OPTIMIZAR -> BoostScreen(viewModel)
                    NavigationTab.LOGS -> LogsScreen(viewModel)
                    NavigationTab.DIAGNOSTICO -> DiagnosticScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(viewModel: GameBoostViewModel) {
    val stats by viewModel.systemMetrics.collectAsStateWithLifecycle()
    val isBoostActive by viewModel.isBoostActive.collectAsStateWithLifecycle()
    val shizukuConnected by viewModel.shizukuConnected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile = profiles.find { it.isActive }
    
    val health by viewModel.healthStatus.collectAsStateWithLifecycle()
    
    var dpiIndex by remember { mutableIntStateOf(4) }
    val currentPointerSpeed by viewModel.pointerSpeed.collectAsStateWithLifecycle()
    var pointerSpeedValue by remember { mutableFloatStateOf(currentPointerSpeed.toFloat()) }

    // Sincronizar slider al DPI real del dispositivo al entrar a la pantalla (solo una vez)
    LaunchedEffect(Unit) {
        val nearestIdx = DPI_STEPS.indices.minBy { kotlin.math.abs(DPI_STEPS[it] - stats.dpi) }
        dpiIndex = nearestIdx
    }

    LaunchedEffect(currentPointerSpeed) {
        pointerSpeedValue = currentPointerSpeed.toFloat()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(
            title = "WATCHDOG - ESTADO DEL SISTEMA",
            icon = Icons.Rounded.Security
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HealthBadge("Shizuku", health.shizukuAlive)
                HealthBadge("Accesibilidad", health.accessibilityAlive)
                HealthBadge("Servicio", health.serviceAlive)
            }
            if (health.restartCount > 0) {
                Text(
                    "Reinicios automáticos: ${health.restartCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarningOrange,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        SectionCard(
            title = "CONFIGURACIÓN ACTUAL",
            icon = Icons.Rounded.Settings
        ) {
            ConfigRow("Perfil", activeProfile?.name ?: "Ninguno", "🎮")
            ConfigRow("DPI", "${stats.dpi}", "📱")
            ConfigRow("Puntero", "${currentPointerSpeed}/10", "🖱")
            ConfigRow("Animaciones", stats.animationScale, "⚡")
            ConfigRow("Refresco", stats.refreshRate, "📺")
            ConfigRow("Governor", stats.governor, "🖥")
            
            val externalDevicesConnected by viewModel.externalDevicesConnected.collectAsStateWithLifecycle()
            ConfigRow("Mobilador", if (externalDevicesConnected) "Detectado ✅" else "No detectado", "🖱")
        }

        SectionCard(
            title = "Auto-Boost Engine",
            subtitle = if (isBoostActive) "Optimización activa" else "Optimización inactiva",
            action = {
                Switch(
                    checked = isBoostActive,
                    onCheckedChange = { 
                        viewModel.toggleBoost()
                        val intent = Intent(context, GameBoostService::class.java)
                        if (!isBoostActive) {
                            intent.action = GameBoostService.ACTION_START
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
                            PreferenceManager.setServiceRunning(context, true)
                        } else {
                            intent.action = GameBoostService.ACTION_STOP
                            context.startService(intent)
                            PreferenceManager.setServiceRunning(context, false)
                            FloatingPanelManager.getInstance(context).hide()
                        }
                    }
                )
            }
        ) {
            val isMobiladorActive by viewModel.isMobiladorActive.collectAsStateWithLifecycle()
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Modo Mobilador", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = if (isMobiladorActive) MaterialTheme.colorScheme.primary else Color.White)
                    Text("Mappers, scrcpy y periféricos", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = isMobiladorActive,
                    onCheckedChange = { viewModel.toggleMobilador() },
                    modifier = Modifier.scale(0.8f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Thermostat, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(20.dp))
                    Text("${stats.cpuTemp.toInt()}°C", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                    Text("${stats.batteryLevel}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }

        // --- TARJETAS DE ESTADO SHIZUKU Y ACCESIBILIDAD ---
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            val isAccessibilityActive = com.example.service.UnifiedAccessibilityService.isServiceRunning
            
            StatusCard(
                modifier = Modifier.weight(1f),
                title = "SHIZUKU",
                isActive = shizukuConnected,
                icon = Icons.Rounded.Usb,
                onClick = { 
                    if (!Shizuku.pingBinder()) {
                        try {
                            val intent = context.packageManager.getLaunchIntentForPackage("rikka.shizuku")
                            if (intent != null) context.startActivity(intent)
                            else Toast.makeText(context, "Shizuku no instalado", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error al abrir Shizuku", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        viewModel.toggleShizukuState()
                    }
                }
            )
            StatusCard(
                modifier = Modifier.weight(1f),
                title = "ACCESIBILIDAD",
                isActive = isAccessibilityActive,
                icon = Icons.Rounded.AccessibilityNew,
                onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (e: Exception) {}
                }
            )
        }

        // --- BOTÓN DE RECONEXIÓN SHIZUKU ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
        ) {
            Row(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { 
                        viewModel.toggleShizukuState()
                        Toast.makeText(context, "🔄 Reconectando Shizuku...", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), contentColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("RECONECTAR SHIZUKU", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // --- TARJETA DE JUEGO DETECTADO ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), Color.Transparent)
                                )
                            )
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text("DETECTED GAMEPLAY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                        Text(stats.activeGame ?: "Buscando...", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
                            Text("Optimización Activa", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.quickClean() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear Cache", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.quickClean() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Rounded.ElectricBolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Optimize RAM", fontSize = 11.sp)
                    }
                }
            }
        }

        // --- AJUSTE DE PANTALLA (DPI) ---
        SectionCard(title = "AJUSTE DE PANTALLA", icon = Icons.Rounded.PhoneAndroid) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("DPI", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${DPI_STEPS[dpiIndex]}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = dpiIndex.toFloat(),
                onValueChange = { dpiIndex = it.roundToInt() },
                valueRange = 0f..(DPI_STEPS.lastIndex.toFloat()),
                steps = DPI_STEPS.size - 2,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary)
            )

            // Etiquetas de valores discretos
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DPI_STEPS.forEachIndexed { index, dpi ->
                    Text(
                        "$dpi",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (dpi == 280) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            dpi == 280 -> MaterialTheme.colorScheme.secondary
                            index == dpiIndex -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.alpha(if (dpi % 80 == 0) 1f else 0.7f)
                    )
                }
            }
            Text("Valores predefinidos. 280 es ideal para ZTE Nubia con pantalla alargada.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { viewModel.setDpi(DPI_STEPS[dpiIndex]) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.CheckCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.Black)
                    Text("APLICAR DPI", fontWeight = FontWeight.ExtraBold, color = Color.Black)
                }
            }
        }

        // --- AJUSTE DE PUNTERO ---
        SectionCard(title = "VELOCIDAD DEL PUNTERO", icon = Icons.Rounded.SettingsInputComponent) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Velocidad", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${pointerSpeedValue.toInt()}/10", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            }
            Slider(
                value = pointerSpeedValue,
                onValueChange = { pointerSpeedValue = it },
                valueRange = 0f..10f,
                steps = 9,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.secondary)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Lento", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Rápido", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Ajusta la sensibilidad del mouse o touch externo.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { viewModel.setPointerSpeed(pointerSpeedValue.toInt()) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f), contentColor = MaterialTheme.colorScheme.secondary)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("APLICAR VELOCIDAD", fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
fun StatusCard(modifier: Modifier, title: String, isActive: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f))
            Text(if (isActive) "Activo" else "Inactivo", fontWeight = FontWeight.ExtraBold, color = if (isActive) MaterialTheme.colorScheme.secondary else WarningOrange, fontSize = 12.sp)
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let {
                    Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            action?.invoke()
        }
        Spacer(modifier = Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
fun BoostScreen(viewModel: GameBoostViewModel) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val availableGovernors by viewModel.availableGovernors.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        CreateProfileDialog(
            availableGovernors = availableGovernors,
            onDismiss = { showCreateDialog = false },
            onSave = { name, desc, gov, refresh, icon ->
                viewModel.addCustomProfile(name, desc, gov, refresh, icon)
                showCreateDialog = false
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column {
                Text("MOTOR NÚCLEO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Perfiles de Rendimiento", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    ) {
                        Text("Sistema Listo", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontSize = 8.sp)
                    }
                }
                Text("Optimiza la asignación de hardware para escenarios de juego específicos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            }
        }

        items(profiles) { profile ->
            ProfileCardCompact(profile) { viewModel.setActiveProfile(profile.id) }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { showCreateDialog = true },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f), contentColor = Color.White),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Perfil Personalizado", fontWeight = FontWeight.SemiBold)
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("CONFIGURACIÓN AVANZADA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            
            val isAggressive by viewModel.isAggressiveOptimizationEnabled.collectAsStateWithLifecycle()
            val isThermalWatchdog by viewModel.isThermalWatchdogEnabled.collectAsStateWithLifecycle()
            val isAutoDetect by viewModel.isAutoDetectGamesEnabled.collectAsStateWithLifecycle()
            val isDeepSleep by viewModel.isDeepSleepEnabled.collectAsStateWithLifecycle()
            val isMsaa by viewModel.isMsaaEnabled.collectAsStateWithLifecycle()

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AdvancedToggle("Optimización Agresiva", "Fuerza el cierre de apps en segundo plano", isAggressive) { viewModel.toggleAggressiveOptimization() }
                    AdvancedToggle("Watchdog Térmico", "Monitorea y previene el sobrecalentamiento", isThermalWatchdog) { viewModel.toggleThermalWatchdog() }
                    AdvancedToggle("Auto-Detección de Juegos", "Activa perfiles automáticamente", isAutoDetect) { viewModel.toggleAutoDetectGames() }
                    AdvancedToggle("Optimización en Suspensión", "Ahorra recursos cuando la pantalla está apagada", isDeepSleep) { viewModel.toggleDeepSleep() }
                    AdvancedToggle("MSAA 4x", "Mejora calidad gráfica (GPU-intensive, puede bajar FPS en gama media)", isMsaa) { viewModel.toggleMsaa() }
                }
            }
        }
    }
}

@Composable
fun AdvancedToggle(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.scale(0.8f))
    }
}

@Composable
fun ConfigRow(label: String, value: String, icon: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, modifier = Modifier.width(24.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

// Color naranja para perfil activo (gaming/performance)
private val ActiveProfileOrange = Color(0xFFFF9100)

@Composable
fun ProfileCardCompact(profile: ProfileEntity, onClick: () -> Unit) {
    val isActive = profile.isActive
    val activeColor = ActiveProfileOrange
    val activeBackgroundAlpha = 0.25f // Static alpha for active profile icon background
    
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFF1A2744) else Color(0xFF111827)
        ),
        border = BorderStroke(
            width = if (isActive) 2.dp else 1.dp,
            color = if (isActive) activeColor.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.05f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // Icono del perfil con fondo destacado si está activo
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isActive) activeColor.copy(alpha = activeBackgroundAlpha)
                            else Color.White.copy(alpha = 0.05f)
                        )
                        .then(
                            if (isActive) Modifier.border(1.5.dp, activeColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(profile.icon, fontSize = 24.sp)
                }
                
                Spacer(modifier = Modifier.width(14.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isActive) activeColor else Color.White
                    )
                    Text(
                        profile.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                // Badge "ACTIVO" con animación
                if (isActive) {
                    Surface(
                        color = activeColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, activeColor.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(activeColor)
                            )
                            Text(
                                "ACTIVO",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = activeColor,
                                fontSize = 9.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(14.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ProfileTag(Icons.Rounded.SettingsInputComponent, profile.governor, isActive)
                ProfileTag(Icons.Rounded.SettingsSystemDaydream, "${profile.refreshRate} Hz", isActive)
            }
        }
    }
}

@Composable
fun ProfileTag(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, isActive: Boolean = false) {
    val tagColor = if (isActive) ActiveProfileOrange else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (isActive) Modifier.background(ActiveProfileOrange.copy(alpha = 0.1f))
                else Modifier
            )
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tagColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun LogsScreen(viewModel: GameBoostViewModel) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Registros de Actividad", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(logs) { log ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Info, contentDescription = null, tint = if (log.level == "ERROR") ErrorRed else MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(log.timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(log.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticScreen(viewModel: GameBoostViewModel) {
    val context = LocalContext.current
    var report by remember { mutableStateOf("Generando...") }
    var shizukuReport by remember { mutableStateOf("") }
    
    LaunchedEffect(Unit) { 
        report = viewModel.getDiagnosticReport() 
        shizukuReport = viewModel.getShizukuDiagnosis(context)
    }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Diagnóstico de Sistema", fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), color = Color.Black, shape = RoundedCornerShape(8.dp)) {
            Text(report, color = Color.Cyan, modifier = Modifier.padding(8.dp), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Estado de Shizuku", fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp), color = Color.Black, shape = RoundedCornerShape(8.dp)) {
            Text(shizukuReport, color = Color.Green, modifier = Modifier.padding(8.dp), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = { 
                report = viewModel.getDiagnosticReport() 
                shizukuReport = viewModel.getShizukuDiagnosis(context)
            }, 
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("ACTUALIZAR DIAGNÓSTICO")
        }
    }
}

@Composable
fun CreateProfileDialog(
    availableGovernors: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var governor by remember { mutableStateOf(availableGovernors.firstOrNull() ?: "schedutil") }
    var refreshRate by remember { mutableFloatStateOf(60f) }
    var icon by remember { mutableStateOf("🎮") }
    var hyperTouch by remember { mutableStateOf(true) }
    var lowLatency by remember { mutableStateOf(false) }
    var masterFilter by remember { mutableStateOf(true) }
    
    var showGovDropdown by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1326)), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("GAMEBOOST PRO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    Text("Calibración de Rendimiento", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre del Perfil") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color.White.copy(alpha = 0.1f)))
                        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color.White.copy(alpha = 0.1f)))
                    }
                    CalibrationSection(title = "Control del Governor", icon = Icons.Rounded.SettingsInputComponent) {
                        Text("Selecciona el comportamiento del escalado de CPU.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                        
                        Box {
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { showGovDropdown = true },
                                color = Color.White.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                            ) {
                                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(governor, style = MaterialTheme.typography.bodyMedium)
                                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                                }
                            }
                            
                            DropdownMenu(
                                expanded = showGovDropdown,
                                onDismissRequest = { showGovDropdown = false },
                                modifier = Modifier.background(Color(0xFF171F33))
                            ) {
                                availableGovernors.forEach { gov ->
                                    DropdownMenuItem(
                                        text = { Text(gov, color = Color.White) },
                                        onClick = {
                                            governor = gov
                                            showGovDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    CalibrationSection(title = "Motor de Pantalla", icon = Icons.Rounded.Screenshot) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Tasa de Refresco Objetivo", style = MaterialTheme.typography.bodySmall)
                            Text("${refreshRate.toInt()}Hz", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(value = refreshRate, onValueChange = { refreshRate = it }, valueRange = 60f..144f, steps = 3, colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary))
                        CalibrationToggle(label = "Hyper-Touch Sampling", checked = hyperTouch, onCheckedChange = { hyperTouch = it })
                    }
                    CalibrationSection(title = "Túnel de Red", icon = Icons.Rounded.Wifi) {
                        CalibrationToggle(label = "Modo Baja Latencia", checked = lowLatency, onCheckedChange = { lowLatency = it })
                        CalibrationToggle(label = "Restricciones de Datos", checked = true, onCheckedChange = {})
                    }
                    CalibrationSection(title = "Módulos Avanzados", icon = Icons.Rounded.Extension) {
                        CalibrationToggle(label = "Master Filter (AI)", checked = masterFilter, onCheckedChange = { masterFilter = it })
                        CalibrationToggle(label = "Afinidad de CPU", checked = true, onCheckedChange = {})
                    }
                }
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = { onSave(name.ifBlank { "Nuevo Perfil" }, description.ifBlank { "Configuración personalizada" }, governor, refreshRate.toInt().toString(), icon) }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Guardar Perfil", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Volver", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.clickable { onDismiss() })
                }
            }
        }
    }
}

@Composable
fun CalibrationSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
        }
        content()
    }
}

@Composable
fun CalibrationToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.scale(0.8f))
    }
}

@Composable
fun HealthBadge(label: String, isAlive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isAlive) Color(0xFF00E676) else ErrorRed)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isAlive) Color.White else Color.White.copy(alpha = 0.6f)
        )
        Text(
            text = if (isAlive) "✅" else "❌",
            fontSize = 10.sp
        )
    }
}

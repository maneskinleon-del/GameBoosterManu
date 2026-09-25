package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.example.data.PreferenceManager
import kotlin.math.roundToInt
import com.example.data.database.ProfileEntity
import com.example.manager.boostsession.BoostSessionState
import com.example.manager.boostsession.RestoreResult
import com.example.data.repository.FsmState
import com.example.data.repository.SystemMetrics
import com.example.manager.ProfileManager
import com.example.service.GameBoostService
import com.example.service.UnifiedAccessibilityService
import com.example.ui.permissions.PermissionManager
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.WarningOrange
import com.example.ui.theme.ErrorRed
import com.example.ui.viewmodel.GameBoostViewModel
import rikka.shizuku.Shizuku

/** Valores discretos para el slider de DPI: de 280 a 600 en pasos de 40 */
private val DPI_STEPS = listOf(280, 320, 360, 400, 440, 480, 520, 560, 600)

class MainActivity : ComponentActivity() {

    private val permissionManager = PermissionManager(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        ProfileManager.init(this)
        
        // Set callback BEFORE register — evita race donde un binder event se pierde
        permissionManager.onPermissionStateChanged = {
            com.example.data.repository.GameBoostRepository
                .getInstance(applicationContext).onShizukuBinderReceived()
        }
        permissionManager.register()
        permissionManager.checkAndRequest(onlySilentCheck = false)

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
        // PR2 (B3): este listener se registra en onCreate — sin este remove, cada
        // recreación de la Activity acumula otro callback en la lista estática de
        // Shizuku (N rechecks redundantes por evento de binder).
        permissionManager.unregister()
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
        permissionManager.checkAndRequest(onlySilentCheck = true)

        // (d) R1 (C5): re-evaluar overlay al reabrir la app es parte de la proyección:
        // resetear el request de usuario (null) hace que el observador del servicio
        // re-muestre el overlay si el boost sigue activo (el ✕ lo había ocultado).
        // No activa boost ni perf mode: solo refleja el estado ya activo.
        try {
            val repo = com.example.data.repository.GameBoostRepository.getInstance(this)
            repo.setOverlayRequested(null)
        } catch (e: Exception) {
            Log.w("GameBoostApp", "onResume overlay re-eval: ${e.message}")
        }
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
                    IconButton(onClick = { viewModel.toggleOverlay() }) {
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
    val depState by viewModel.dependencyState.collectAsStateWithLifecycle()
    
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
            title = "DEPENDENCIAS DEL SISTEMA",
            icon = Icons.Rounded.Security
        ) {
            val dep = depState
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DependencyRow("Shizuku", dep.shizuku.state.name, dep.shizuku.state == com.example.data.repository.DependencyState.Shizuku.ShizukuState.ON, Icons.Rounded.Usb)
                DependencyRow("Accesibilidad", dep.accessibility.state.name, dep.accessibility.state == com.example.data.repository.DependencyState.Accessibility.AccessibilityState.ACTIVE, Icons.Rounded.AccessibilityNew)
                DependencyRow("Batería", dep.batteryOptimization.state.name, dep.batteryOptimization.state == com.example.data.repository.DependencyState.BatteryOptimization.BatteryState.UNRESTRICTED, Icons.Rounded.BatteryChargingFull)
                DependencyRow("GameBoostService", dep.gameBoostService.state.name, dep.gameBoostService.state == com.example.data.repository.DependencyState.GameBoostService.ServiceState.RUNNING, Icons.Rounded.Memory)
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
                    // testTag("boost_switch"): selector estable para instrumentation
                    // (UiAutomator vía testTagsAsResourceId) y tests Compose.
                    modifier = Modifier.testTag("boost_switch"),
                    checked = isBoostActive,
                    onCheckedChange = { 
                        viewModel.toggleBoost()
                        val intent = Intent(context, GameBoostService::class.java)
                        if (!isBoostActive) {
                            intent.action = GameBoostService.ACTION_START
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
                            PreferenceManager.setServiceRunning(context, true)
                            // R1 (C5): boost ON manual → seguir al boost de nuevo (un
                            // request=false viejo no debe ocultar el overlay recién activado)
                            com.example.data.repository.GameBoostRepository
                                .getInstance(context).setOverlayRequested(null)
                        } else {
                            intent.action = GameBoostService.ACTION_STOP
                            context.startService(intent)
                            PreferenceManager.setServiceRunning(context, false)
                            // R1 (C5): la UI no escribe el overlay directamente — pide
                            // vía la proyección (el observador del servicio es el único
                            // writer de FPM.show/hide).
                            com.example.data.repository.GameBoostRepository
                                .getInstance(context).setOverlayRequested(false)
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

// Refresh inmediato de DependencyStateManager al volver de Settings (onResume),
        // sin esperar al intervalo de 15s. depState.accessibility es la fuente única de verdad.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshDependencyState()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // Onboarding dialog state
        var showOnboarding by remember {
            mutableStateOf(
                !UnifiedAccessibilityService.isServiceRunning &&
                !PreferenceManager.isAccessibilityOnboardingShown(context)
            )
        }

        // --- TARJETAS DE ESTADO SHIZUKU Y ACCESIBILIDAD ---
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
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
                isActive = depState.accessibility.state == com.example.data.repository.DependencyState.Accessibility.AccessibilityState.ACTIVE,
                icon = Icons.Rounded.AccessibilityNew,
                onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (e: Exception) {}
                }
            )
        }

        // Accessibility status banner
        if (depState.accessibility.state != com.example.data.repository.DependencyState.Accessibility.AccessibilityState.ACTIVE) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = WarningOrange.copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Servicio inactivo — toque la tarjeta para activar",
                         style = MaterialTheme.typography.labelSmall, color = WarningOrange)
                    Icon(Icons.Rounded.ArrowForward, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(20.dp))
                }
            }
        }

        // Onboarding dialog
        if (!UnifiedAccessibilityService.isServiceRunning && !PreferenceManager.isAccessibilityOnboardingShown(context)) {
            AccessibilityOnboardingDialog(
                onDismiss = {
                    PreferenceManager.setAccessibilityOnboardingShown(context, true)
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
fun DependencyRow(
    label: String,
    state: String,
    isActive: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Color.White)
        }
        Text(
            state,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (isActive) MaterialTheme.colorScheme.secondary else WarningOrange
        )
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

// ═══════════════════════════════════════════════════════════════════
// Tab 3 — ACTIVIDAD (evidencia real del boost, orientada al jugador)
//
// Regla epistemológica: cada indicador proviene de estado real —
// StateFlows del core (juego/boost/FSM/perfil) o del snapshot SSOT
// persistido (BoostSession schema v2). Sin métricas decorativas.
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun EvidenceStatusRow(label: String, value: String, alive: Boolean, detail: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (alive) Color(0xFF00E676) else Color(0xFF546E7A))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
            detail?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (alive) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EvidenceChip(text: String, good: Boolean) {
    Surface(
        color = if (good) Color(0xFF00E676).copy(alpha = 0.12f) else Color(0xFFFF9800).copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, (if (good) Color(0xFF00E676) else Color(0xFFFF9800)).copy(alpha = 0.3f))
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (good) Color(0xFF00E676) else Color(0xFFFF9800)
        )
    }
}

/** "hace Xs/Xm/Xh" desde un epoch millis (HealthStatus.lastCheck). */
private fun relativeAgeFromEpoch(millis: Long): String {
    val age = (System.currentTimeMillis() - millis).coerceAtLeast(0)
    return when {
        age < 60_000 -> "hace ${age / 1000}s"
        age < 3_600_000 -> "hace ${age / 60_000}m"
        else -> "hace ${age / 3_600_000}h"
    }
}

/** "hace Xs/Xm/Xh" a partir del timestamp HH:mm:ss del log, anclado al día actual. */
private fun relativeAge(timestamp: String): String {
    return try {
        val parts = timestamp.split(":").map { it.trim().toIntOrNull() ?: return timestamp }
        if (parts.size != 3) return timestamp
        val cal = java.util.Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, parts[0])
        cal.set(java.util.Calendar.MINUTE, parts[1])
        cal.set(java.util.Calendar.SECOND, parts[2])
        // Log de ayer (madrugada): si el parse quedó >1 min en el futuro, es de ayer.
        if (now - cal.timeInMillis < -60_000) cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val age = (now - cal.timeInMillis).coerceAtLeast(0)
        when {
            age < 60_000 -> "hace ${age / 1000}s"
            age < 3_600_000 -> "hace ${age / 60_000}m"
            else -> "hace ${age / 3_600_000}h"
        }
    } catch (_: Exception) { timestamp }
}

/**
 * Categoría visual del evento a partir del TAG REAL del log.
 * Mapeo exclusivo de los 19 tags productivos existentes — no se inventan eventos.
 */
private fun activityCategory(tag: String): Triple<String, Color, androidx.compose.ui.graphics.vector.ImageVector> = when (tag) {
    "Optimizer", "GameMode", "Mobilador", "DPI", "Pointer" ->
        Triple("BOOST", Color(0xFF2FD9F4), Icons.Rounded.RocketLaunch)
    "SysTweaks", "NetworkOpt", "RamManager", "GamingDND" ->
        Triple("OPTIMIZACIÓN", Color(0xFF4DE082), Icons.Rounded.CleaningServices)
    else ->
        Triple("SISTEMA", Color(0xFFCFBCFF), Icons.Rounded.Memory)
}

@Composable
private fun ActivityFilterChip(label: String, dotColor: Color, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) Color(0xFF2FD9F4).copy(alpha = 0.18f) else Color(0xFF171F33).copy(alpha = 0.6f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, if (selected) Color(0xFF2FD9F4).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.05f))
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(dotColor))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) Color(0xFF8AEBFF) else Color.White.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun ActivitySummaryCell(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color.White.copy(alpha = 0.45f))
        Spacer(modifier = Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

@Composable
fun LogsScreen(viewModel: GameBoostViewModel) {
    var activityFilter by remember { mutableStateOf("ALL") }
    val activeGame by viewModel.simulatedGame.collectAsStateWithLifecycle()
    val boostActive by viewModel.isBoostActive.collectAsStateWithLifecycle()
    val fsmState by viewModel.fsmState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val session by viewModel.sessionEvidence.collectAsStateWithLifecycle()
    val lastRestore by viewModel.lastRestoreReport.collectAsStateWithLifecycle()
    val recent by viewModel.recentActivity.collectAsStateWithLifecycle()
    val activeProfile = profiles.find { it.isActive }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Título + badge EN VIVO (mockup)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Actividad", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF4DE082)))
            Spacer(modifier = Modifier.weight(1f))
            EvidenceChip(text = "EN VIVO", good = true)
        }
        Text("Evidencia real del Game Boost", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Resumen superior 3 columnas (mockup) — datos reales
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF1A2338),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF2FD9F4).copy(alpha = 0.22f))
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                ActivitySummaryCell("EVENTOS", "${recent.size}", Color.White, Modifier.weight(1f))
                Box(modifier = Modifier.width(1.dp).height(28.dp).background(Color.White.copy(alpha = 0.08f)))
                ActivitySummaryCell("ÚLTIMO EVENTO", recent.firstOrNull()?.let { relativeAge(it.timestamp) } ?: "—", Color(0xFF8AEBFF), Modifier.weight(1f))
                Box(modifier = Modifier.width(1.dp).height(28.dp).background(Color.White.copy(alpha = 0.08f)))
                ActivitySummaryCell("ESTADO MOTOR", if (boostActive) "ACTIVO" else "INACTIVO", if (boostActive) Color(0xFF4DE082) else Color.White.copy(alpha = 0.5f), Modifier.weight(1f))
            }
        }

        // Filtros funcionales por categoría real de tag
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivityFilterChip("Todos", Color(0xFF8AEBFF), activityFilter == "ALL") { activityFilter = "ALL" }
            ActivityFilterChip("Boost", Color(0xFF2FD9F4), activityFilter == "BOOST") { activityFilter = "BOOST" }
            ActivityFilterChip("Optimizaciones", Color(0xFF4DE082), activityFilter == "OPT") { activityFilter = "OPT" }
            ActivityFilterChip("Sistema", Color(0xFFCFBCFF), activityFilter == "SYS") { activityFilter = "SYS" }
        }

        // ── Evidencia de sesión (snapshot SSOT persistido — acciones reales) ──
        SectionCard(
            title = "EVIDENCIA DE SESIÓN",
            icon = Icons.Rounded.FactCheck,
            subtitle = session?.let { "sesión ${it.sessionId?.takeLast(8) ?: "—"} · inicio ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it.updatedAt))}" }
        ) {
            if (session == null) {
                Text(
                    "Sin sesión de boost registrada. Activa el boost (manual o entrando a un juego) para capturar y aplicar cambios.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val s = session!!
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EvidenceChip(text = s.state.name, good = s.state == BoostSessionState.ACTIVE)
                    EvidenceChip(text = "${s.baselineCount} respaldadas", good = true)
                    EvidenceChip(text = "${s.appliedCount} escritas", good = s.appliedCount > 0)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("Valores aplicados por el boost en esta sesión:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                if (s.appliedEntries.isEmpty()) {
                    Text(
                        if (s.state == BoostSessionState.APPLYING) "Aplicando…" else "Aún sin escrituras registradas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    s.appliedEntries.take(8).forEach { (id, value) ->
                        Text(
                            "$id = $value",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                    if (s.appliedEntries.size > 8) {
                        Text(
                            "… y ${s.appliedEntries.size - 8} más",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── Última restauración (reporte verificado por relectura) ──
        SectionCard(title = "ÚLTIMA RESTAURACIÓN", icon = Icons.Rounded.RestartAlt) {
            val rep = lastRestore
            if (rep == null) {
                Text(
                    "Aún no hubo restauraciones en esta ejecución de la app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val verified = rep.results.values.count { it == RestoreResult.RESTORE_VERIFIED }
                val skipped = rep.results.values.count { it == RestoreResult.RESTORE_SKIPPED }
                val conflicts = rep.results.values.count { it == RestoreResult.RESTORE_CONFLICT }
                val failed = rep.results.values.count { it == RestoreResult.RESTORE_FAILED }
                EvidenceChip(
                    text = if (rep.allOk) "COMPLETADA Y VERIFICADA" else "CON FALLOS — RECOVERY PENDIENTE",
                    good = rep.allOk
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("$verified restauradas · $skipped ya en valor original · $conflicts conservadas (cambiadas por el usuario) · $failed fallidas", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
            }
        }

        // ── Timeline de eventos (mockup: rail + dots + chips) — 100% datos reales ──
        val filtered = if (activityFilter == "ALL") recent else recent.filter {
            activityCategory(it.tag).first == when (activityFilter) {
                "BOOST" -> "BOOST"; "OPT" -> "OPTIMIZACIÓN"; else -> "SISTEMA"
            }
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            // Rail vertical degradado
            Canvas(modifier = Modifier.matchParentSize().padding(start = 11.dp, top = 8.dp, bottom = 8.dp)) {
                drawLine(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF2FD9F4).copy(alpha = 0.5f), Color(0xFF4DE082).copy(alpha = 0.3f), Color.White.copy(alpha = 0.05f))
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 2f
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (filtered.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF171F33).copy(alpha = 0.65f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Sin registros", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                if (activityFilter == "ALL") "Aún no hay eventos en esta sesión." else "No hay eventos de esta categoría.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                filtered.forEach { log ->
                    val (catLabel, catColor, _) = activityCategory(log.tag)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Dot del timeline
                        Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier.size(14.dp).clip(CircleShape).background(Color(0xFF0B1326)).border(2.dp, catColor, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(catColor))
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        // Tarjeta del evento con barra de acento
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = Color(0xFF171F33).copy(alpha = 0.65f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                        ) {
                            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                                Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(catColor))
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = catColor.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp),
                                            border = BorderStroke(1.dp, catColor.copy(alpha = 0.3f))
                                        ) {
                                            Text(
                                                catLabel,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = catColor
                                            )
                                        }
                                        Spacer(modifier = Modifier.weight(1f))
                                        Text(relativeAge(log.timestamp), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(log.message, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticScreen(viewModel: GameBoostViewModel) {
    val context = LocalContext.current
    val health by viewModel.healthStatus.collectAsStateWithLifecycle()
    val deps by viewModel.dependencyState.collectAsStateWithLifecycle()
    val fsm by viewModel.fsmState.collectAsStateWithLifecycle()
    val activeGame by viewModel.simulatedGame.collectAsStateWithLifecycle()
    val sessionEvidence by viewModel.sessionEvidence.collectAsStateWithLifecycle()
    val autoDetect by viewModel.isAutoDetectGamesEnabled.collectAsStateWithLifecycle()
    val techLogs by viewModel.logs.collectAsStateWithLifecycle()
    var report by remember { mutableStateOf("") }
    var shizukuReport by remember { mutableStateOf("") }

    fun runDiagnosis() {
        report = viewModel.getDiagnosticReport()
        shizukuReport = viewModel.getShizukuDiagnosis(context)
    }

    LaunchedEffect(Unit) { runDiagnosis() }

    // ── Los 5 estados de componentes provienen de fuentes reales ──
    // Shizuku: DependencyStateManager (sondeo real) · Watchdog/Service: WatchdogManager
    // Accessibility: DependencyStateManager · Detector: FSM + toggle · FSM: GameSessionManager
    val shizukuReady = deps.shizuku.state == com.example.data.repository.DependencyState.Shizuku.ShizukuState.ON
    val a11yActive = deps.accessibility.state == com.example.data.repository.DependencyState.Accessibility.AccessibilityState.ACTIVE
    val serviceRunning = health.serviceAlive
    val detectorOk = autoDetect || activeGame != null
    val componentsReady = listOf(shizukuReady, a11yActive, serviceRunning, detectorOk, fsm != FsmState.DEGRADED && fsm != FsmState.RECOVERING).count { it }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // ── Banner global: derivado del conteo real de componentes ──
        val allOk = componentsReady == 5
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = if (allOk) Color(0xFF00E676).copy(alpha = 0.08f) else WarningOrange.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, (if (allOk) Color(0xFF00E676) else WarningOrange).copy(alpha = 0.3f))
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (allOk) Icons.Rounded.HealthAndSafety else Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = if (allOk) Color(0xFF00E676) else WarningOrange,
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(
                        if (allOk) "SISTEMA OPERATIVO" else "SISTEMA CON DEGRADACIONES",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (allOk) Color(0xFF00E676) else WarningOrange
                    )
                    Text(
                        "$componentsReady/5 componentes listos",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // ── MÓDULOS DE ARQUITECTURA: los 5 componentes con estado real ──
        SectionCard(
            title = "MÓDULOS DE ARQUITECTURA",
            subtitle = "$componentsReady/5 listos",
            icon = Icons.Rounded.Memory
        ) {
            ComponentCard("Shizuku", if (shizukuReady) "DISPONIBLE" else "NO DISPONIBLE", shizukuReady, Icons.Rounded.Usb)
            Spacer(modifier = Modifier.height(8.dp))
            ComponentCard("Watchdog", if (serviceRunning) "ACTIVO" else "DETENIDO", serviceRunning, Icons.Rounded.HealthAndSafety)
            Spacer(modifier = Modifier.height(8.dp))
            ComponentCard("Game Detection", if (detectorOk) "DETECTANDO" else "DESHABILITADO", detectorOk, Icons.Rounded.SportsEsports)
            Spacer(modifier = Modifier.height(8.dp))
            ComponentCard("Accessibility", if (a11yActive) "ACTIVO" else "INACTIVO", a11yActive, Icons.Rounded.AccessibilityNew)
            Spacer(modifier = Modifier.height(8.dp))
            ComponentCard(
                "Boost Engine (FSM)",
                when (fsm) {
                    FsmState.GAME_ACTIVE -> "ACTIVO — JUEGO DETECTADO"
                    FsmState.READY -> "READY"
                    FsmState.INITIALIZING -> "INICIALIZANDO"
                    FsmState.DEGRADED -> "DEGRADADO"
                    FsmState.RECOVERING -> "EN RECUPERACIÓN"
                },
                fsm == FsmState.GAME_ACTIVE || fsm == FsmState.READY,
                Icons.Rounded.ElectricBolt
            )
        }

        // ── SHIZUKU RUNTIME: solo campos con fuente real (diagnose()) ──
        SectionCard(title = "SHIZUKU RUNTIME", subtitle = if (shizukuReady) "DISPONIBLE" else "NO DISPONIBLE", icon = Icons.Rounded.Usb) {
            EvidenceStatusRow(
                "Estado del servicio",
                if (shizukuReady) "activo" else "sin señal",
                alive = shizukuReady,
                detail = deps.shizuku.detail.ifBlank { null }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                shizukuReport.ifBlank { "Sin diagnóstico aún" },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = AccentCyan,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(onClick = { runDiagnosis() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("VERIFICAR SERVICIO", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp)
            }
        }

        // ── WATCHDOG MONITOR: HealthStatus real (estado + reinicios + última comprobación) ──
        SectionCard(title = "WATCHDOG MONITOR", subtitle = if (serviceRunning) "ACTIVO" else "DETENIDO", icon = Icons.Rounded.HealthAndSafety) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("ESTADO", if (serviceRunning) "Estable" else "Detenido", good = serviceRunning, modifier = Modifier.weight(1f))
                MiniStat("REINICIOS", "${health.restartCount}", good = health.restartCount == 0, modifier = Modifier.weight(1f))
                MiniStat("ÚLT. CHECK", relativeAgeFromEpoch(health.lastCheck), good = true, modifier = Modifier.weight(1f))
            }
        }

        // ── BOOST ENGINE: FSM + sesión persistente (SSOT) ──
        SectionCard(title = "BOOST ENGINE (FSM)", subtitle = fsm.name, icon = Icons.Rounded.ElectricBolt) {
            EvidenceStatusRow("Juego en primer plano", activeGame ?: "ninguno", alive = activeGame != null)
            Spacer(modifier = Modifier.height(8.dp))
            EvidenceStatusRow(
                "Sesión actual",
                when (sessionEvidence?.state) {
                    BoostSessionState.ACTIVE -> "ACTIVA — ${sessionEvidence?.baselineCount ?: 0} keys respaldadas"
                    BoostSessionState.APPLYING -> "APLICANDO CAMBIOS"
                    BoostSessionState.BASELINE_CAPTURED -> "BASELINE CAPTURADO"
                    BoostSessionState.RESTORING -> "RESTAURANDO"
                    BoostSessionState.RESTORED -> "RESTAURADA (última sesión)"
                    BoostSessionState.RECOVERY_REQUIRED -> "RECOVERY REQUERIDO"
                    BoostSessionState.IDLE, null -> "sin sesión activa"
                },
                alive = sessionEvidence?.state == BoostSessionState.ACTIVE,
                detail = sessionEvidence?.sessionId?.let { "sesión $it" }
            )
            Spacer(modifier = Modifier.height(8.dp))
            EvidenceStatusRow(
                "Recuperación",
                if (sessionEvidence?.state == BoostSessionState.RECOVERY_REQUIRED) "RECOVERY PENDIENTE" else "sin pendientes",
                alive = sessionEvidence?.state != BoostSessionState.RECOVERY_REQUIRED
            )
        }

        // ── DIAGNÓSTICO DEL SISTEMA: checklist real (3 checks con fuente) ──
        SectionCard(title = "DIAGNÓSTICO DEL SISTEMA", subtitle = "verificación en vivo", icon = Icons.Rounded.FactCheck) {
            EvidenceStatusRow("Servicio de boost", if (health.serviceAlive) "corriendo" else "detenido", alive = health.serviceAlive)
            Spacer(modifier = Modifier.height(8.dp))
            EvidenceStatusRow("Enlace de accesibilidad", if (a11yActive) "activo" else "inactivo", alive = a11yActive)
            Spacer(modifier = Modifier.height(8.dp))
            EvidenceStatusRow("Batería sin restricciones", if (health.batteryUnrestricted) "ok" else "restringida", alive = health.batteryUnrestricted)
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(onClick = { runDiagnosis() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.FactCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("EJECUTAR VERIFICACIÓN RÁPIDA", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                report.ifBlank { "Sin diagnóstico aún" },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = AccentCyan,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            )
        }

        // ── REGISTRO TÉCNICO: preview real (3 líneas del log existente) ──
        SectionCard(
            title = "REGISTRO TÉCNICO DE SISTEMA",
            subtitle = "${techLogs.size} eventos",
            icon = Icons.Rounded.History
        ) {
            if (techLogs.isEmpty()) {
                Text(
                    "Sin registros en esta ejecución.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            } else {
                techLogs.take(3).forEach { log ->
                    Row(modifier = Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            when (log.level) {
                                "ERROR" -> "[ERR]"
                                "WARN" -> "[WRN]"
                                else -> "[INF]"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = when (log.level) {
                                "ERROR" -> ErrorRed
                                "WARN" -> WarningOrange
                                else -> AccentCyan
                            }
                        )
                        Text(
                            "${log.timestamp} ${log.message}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Tarjeta compacta de componente (grid del mockup): nombre + estado real + dot. */
@Composable
private fun ComponentCard(name: String, status: String, ok: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (ok) Color(0xFF00E676).copy(alpha = 0.05f) else WarningOrange.copy(alpha = 0.05f),
                RoundedCornerShape(10.dp)
            )
            .border(1.dp, (if (ok) Color(0xFF00E676) else WarningOrange).copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (ok) AccentCyan else WarningOrange, modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(status, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = if (ok) Color(0xFF00E676) else WarningOrange)
        }
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (ok) Color(0xFF00E676) else WarningOrange))
    }
}

/** Stat compacta estilo mockup (Watchdog): label + valor. */
@Composable
private fun MiniStat(label: String, value: String, good: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f))
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (good) Color(0xFF00E676) else WarningOrange
        )
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

@Composable
fun AccessibilityOnboardingDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1326)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Permiso de Accesibilidad requerido",
                         style = MaterialTheme.typography.headlineSmall,
                         fontWeight = FontWeight.Bold,
                         color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("En algunos dispositivos ZTE/MyOS puede ser necesario revisar la configuración de batería y autoinicio para mantener este servicio activo.",
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(20.dp))
                            Text("Configuración → Batería → Sin restricciones para GameBoost Pro",
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.onSurface)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                            Text("Configuración → Autoinicio → Activado para GameBoost Pro",
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Entendido", fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
        }
    }
}

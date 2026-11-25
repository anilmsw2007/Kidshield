package com.example.kidshield

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kidshield.data.UserPreferences
import com.example.kidshield.theme.KidShieldTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KidShieldTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ParentControlScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentControlScreen() {
    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    var isProUser by remember { mutableStateOf(userPrefs.isProUser) }
    var showProDialog by remember { mutableStateOf(false) }

    val isServiceRunning by OverlayService.isRunning.collectAsState()
    val secondsLeft by OverlayService.remainingSeconds.collectAsState()
    val isDeviceLocked by OverlayService.isLocked.collectAsState()

    var overlayPermissionGranted by remember { mutableStateOf(false) }
    var notificationPermissionGranted by remember { mutableStateOf(false) }

    // Check permissions
    LaunchedEffect(Unit) {
        overlayPermissionGranted = Settings.canDrawOverlays(context)
        notificationPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    // Permission Launchers
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        overlayPermissionGranted = Settings.canDrawOverlays(context)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
    }

    val allPermissionsGranted = overlayPermissionGranted && notificationPermissionGranted

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "KidShield Parental Lock",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (isProUser) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = Color(0xFFEAB308),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    "PRO",
                                    color = Color.Black,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF1E293B)
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF0F172A), Color(0xFF1E293B))
                    )
                )
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            if (!allPermissionsGranted) {
                PermissionRequiredScreen(
                    overlayPermissionGranted = overlayPermissionGranted,
                    notificationPermissionGranted = notificationPermissionGranted,
                    onRequestOverlay = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        overlayPermissionLauncher.launch(intent)
                    },
                    onRequestNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            } else if (isServiceRunning) {
                TimerActiveScreen(
                    secondsLeft = secondsLeft,
                    isLocked = isDeviceLocked,
                    onCancelTimer = {
                        val intent = Intent(context, OverlayService::class.java)
                        context.stopService(intent)
                    }
                )
            } else {
                ConfigureTimerScreen(
                    isProUser = isProUser,
                    onOpenProDialog = { showProDialog = true },
                    onStartTimer = { seconds, style, holdSeconds ->
                        val intent = Intent(context, OverlayService::class.java).apply {
                            putExtra(OverlayService.EXTRA_TIMER_SECONDS, seconds)
                            putExtra(OverlayService.EXTRA_SCREEN_STYLE, style)
                            putExtra(OverlayService.EXTRA_UNLOCK_HOLD_SECONDS, holdSeconds)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    }
                )
            }
        }
    }

    if (showProDialog) {
        ProUnlockDialog(
            onDismiss = { showProDialog = false },
            onUnlockPro = { txnRef ->
                userPrefs.isProUser = true
                userPrefs.proTxnRef = txnRef
                isProUser = true
                showProDialog = false
            }
        )
    }
}

@Composable
fun PermissionRequiredScreen(
    overlayPermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestNotification: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "⚠️",
            fontSize = 72.sp,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Permissions Required",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "To function correctly and display the lock screen overlay over other apps, KidShield needs the following permissions:",
            fontSize = 14.sp,
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))

        // Overlay Permission Card
        PermissionCard(
            title = "Display Over Other Apps",
            description = "Allows KidShield to show the fake broken screen when the timer runs out.",
            isGranted = overlayPermissionGranted,
            onRequest = onRequestOverlay
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Notification Permission Card (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionCard(
                title = "Notifications",
                description = "Required to keep the countdown timer running in the background.",
                isGranted = notificationPermissionGranted,
                onRequest = onRequestNotification
            )
        }
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) Color(0xFF1E293B) else Color(0xFF334155)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    description,
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            if (isGranted) {
                Text(
                    "Granted",
                    color = Color(0xFF10B981),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            } else {
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Grant", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun ConfigureTimerScreen(
    isProUser: Boolean,
    onOpenProDialog: () -> Unit,
    onStartTimer: (Long, Int, Int) -> Unit
) {
    var selectedStyle by remember { mutableStateOf(OverlayService.STYLE_BATTERY) }
    var timerMinutes by remember { mutableStateOf("") }
    var selectedMinutesPreset by remember { mutableStateOf<Int?>(1) }
    var unlockHoldSecondsPreset by remember { mutableStateOf<Int?>(4) }
    var customHoldSeconds by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Pro Upgrade / Status Banner Card
        ProBannerCard(isProUser = isProUser, onUpgradeClick = onOpenProDialog)

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Select Fake Screen Style",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        // Styles List
        StyleOptionCard(
            title = "🔋 Empty Battery",
            description = "Simulates a completely dead battery. The screen goes black with a low battery indicator.",
            isSelected = selectedStyle == OverlayService.STYLE_BATTERY,
            onClick = { selectedStyle = OverlayService.STYLE_BATTERY }
        )

        Spacer(modifier = Modifier.height(8.dp))

        StyleOptionCard(
            title = "⚠️ Temperature Warning",
            description = "Simulates a thermal crash warning alert. Displays 'System Warning: High Temperature!'",
            isSelected = selectedStyle == OverlayService.STYLE_OVERHEAT,
            onClick = { selectedStyle = OverlayService.STYLE_OVERHEAT }
        )

        Spacer(modifier = Modifier.height(8.dp))

        StyleOptionCard(
            title = "🌐 No Internet Connection",
            description = "Simulates a browser offline page. Standard Chrome Offline dinosaur theme.",
            isSelected = selectedStyle == OverlayService.STYLE_NO_INTERNET,
            onClick = { selectedStyle = OverlayService.STYLE_NO_INTERNET }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Set Screen Time Limit",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        // Presets Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf(1, 5, 10, 15, 30).forEach { mins ->
                val isSelected = selectedMinutesPreset == mins
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFF3B82F6) else Color(0xFF334155))
                        .clickable {
                            selectedMinutesPreset = mins
                            timerMinutes = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${mins}m",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Custom Timer Input
        OutlinedTextField(
            value = timerMinutes,
            onValueChange = { input ->
                if (input.all { it.isDigit() }) {
                    timerMinutes = input
                    selectedMinutesPreset = null
                }
            },
            label = { Text("Custom Minutes", color = Color(0xFF94A3B8)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF3B82F6),
                unfocusedBorderColor = Color(0xFF475569)
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Set Unlock/Cancel Hold Duration",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        // Presets Row for Hold Seconds
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf(2, 3, 5, 8, 10).forEach { secs ->
                val isSelected = unlockHoldSecondsPreset == secs
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFF3B82F6) else Color(0xFF334155))
                        .clickable {
                            unlockHoldSecondsPreset = secs
                            customHoldSeconds = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${secs}s",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Custom Hold Seconds Input
        OutlinedTextField(
            value = customHoldSeconds,
            onValueChange = { input ->
                if (input.all { it.isDigit() }) {
                    customHoldSeconds = input
                    unlockHoldSecondsPreset = null
                }
            },
            label = { Text("Custom Seconds (e.g. 6)", color = Color(0xFF94A3B8)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF3B82F6),
                unfocusedBorderColor = Color(0xFF475569)
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        val totalMinutes = selectedMinutesPreset ?: timerMinutes.toIntOrNull() ?: 0
        val holdSeconds = unlockHoldSecondsPreset ?: customHoldSeconds.toIntOrNull() ?: 4

        Button(
            onClick = {
                if (totalMinutes > 0) {
                    onStartTimer(totalMinutes * 60L, selectedStyle, holdSeconds)
                }
            },
            enabled = totalMinutes > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF10B981),
                disabledContainerColor = Color(0xFF1E293B)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("▶", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Start Timer ($totalMinutes min)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Ads Section: Shows Ad Banner only for Free users
        if (!isProUser) {
            FreeUserAdPlaceholder(onRemoveAdsClick = onOpenProDialog)
        }
    }
}

@Composable
fun StyleOptionCard(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) Color(0xFF3B82F6) else Color.Transparent,
        label = "BorderColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E293B)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                description,
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun TimerActiveScreen(
    secondsLeft: Long,
    isLocked: Boolean,
    onCancelTimer: () -> Unit
) {
    var showCancelIndicator by remember { mutableStateOf(false) }
    var cancelProgress by remember { mutableStateOf(0f) }
    val holdSeconds by OverlayService.unlockHoldSeconds.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            if (isLocked) Color(0xFFEF4444).copy(alpha = 0.2f) else Color(0xFF3B82F6).copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isLocked) "🔒" else "🔓",
                fontSize = 80.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isLocked) {
            Text(
                "SCREEN LOCKED",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFEF4444)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "The fake warning overlay is currently active.\nTo unlock, perform the long-press bypass on the locked screen overlay.",
                fontSize = 14.sp,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center
            )
        } else {
            val minutes = secondsLeft / 60
            val seconds = secondsLeft % 60
            val timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

            Text(
                "Timer Running",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF94A3B8)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                timeString,
                fontSize = 54.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Hand the phone to your child now. The lock will trigger automatically when the timer finishes.",
                fontSize = 14.sp,
                color = Color(0xFF64748B),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        Spacer(modifier = Modifier.height(64.dp))

        // Parental Cancel Action - requires hold so the child cannot easily bypass
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF334155))
                .pointerInput(holdSeconds) {
                    detectTapGestures(
                        onPress = {
                            showCancelIndicator = true
                            cancelProgress = 0f
                            coroutineScope {
                                val progressJob = launch {
                                    val totalDuration = holdSeconds * 1000L
                                    val steps = holdSeconds * 10
                                    for (i in 1..steps) {
                                        kotlinx.coroutines.delay(totalDuration / steps)
                                        cancelProgress = i.toFloat() / steps
                                    }
                                    onCancelTimer()
                                }
                                try {
                                    awaitRelease()
                                    progressJob.cancel()
                                } catch (e: Exception) {
                                    progressJob.cancel()
                                } finally {
                                    showCancelIndicator = false
                                    cancelProgress = 0f
                                }
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Hold (${holdSeconds}s) to Cancel Timer",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }

    if (showCancelIndicator) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .pointerInput(Unit) { detectTapGestures { } }, // block touches underneath
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    progress = cancelProgress,
                    color = Color.White,
                    strokeWidth = 6.dp,
                    modifier = Modifier.size(80.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "Canceling Timer...\nKeep pressing for $holdSeconds seconds",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun ProBannerCard(
    isProUser: Boolean,
    onUpgradeClick: () -> Unit
) {
    if (isProUser) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("👑", fontSize = 28.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        "KidShield Pro Active",
                        color = Color(0xFF34D399),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        "Ad-free protection enabled. All styles unlocked.",
                        color = Color(0xFFA7F3D0),
                        fontSize = 12.sp
                    )
                }
            }
        }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2E1065)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "⭐ Unlock KidShield Pro",
                        color = Color(0xFFFDE047),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "Remove all ads & get lifetime pro for ₹49 only!",
                        color = Color(0xFFE2E8F0),
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onUpgradeClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFACC15)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("₹49", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun FreeUserAdPlaceholder(onRemoveAdsClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color(0xFF475569),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    "AD",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "KidShield Sponsor Ad (AdMob Banner)",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "Remove Ads",
                color = Color(0xFF38BDF8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onRemoveAdsClick() }
            )
        }
    }
}

@Composable
fun ProUnlockDialog(
    onDismiss: () -> Unit,
    onUnlockPro: (String) -> Unit
) {
    val context = LocalContext.current
    var txnIdInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val upiId = "9451605191@ptyes"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E293B),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("👑", fontSize = 24.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Unlock KidShield Pro",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column {
                Text(
                    "Get a 100% Ad-Free experience and lifetime access to future premium prank screens for just ₹49.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        val uri = Uri.parse("upi://pay").buildUpon()
                            .appendQueryParameter("pa", upiId)
                            .appendQueryParameter("pn", "KidShield Pro")
                            .appendQueryParameter("tn", "KidShield Lifetime Pro Upgrade")
                            .appendQueryParameter("am", "49.00")
                            .appendQueryParameter("cu", "INR")
                            .build()

                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        try {
                            context.startActivity(Intent.createChooser(intent, "Pay via PhonePe / GPay / Paytm"))
                        } catch (e: Exception) {
                            errorMessage = "No UPI app found. Please install PhonePe, GPay, or Paytm."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Pay ₹49 via UPI (PhonePe / GPay)", color = Color.White, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    "Already paid? Enter 12-digit UPI UTR / Ref Number to activate:",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = txnIdInput,
                    onValueChange = { txnIdInput = it; errorMessage = null },
                    placeholder = { Text("e.g. 412345678901", color = Color(0xFF64748B), fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF10B981),
                        unfocusedBorderColor = Color(0xFF475569)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(errorMessage ?: "", color = Color(0xFFF87171), fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (txnIdInput.trim().length >= 6) {
                        onUnlockPro(txnIdInput.trim())
                    } else {
                        errorMessage = "Please enter valid UPI transaction reference number"
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
            ) {
                Text("Activate Pro", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later", color = Color(0xFF94A3B8))
            }
        }
    )
}

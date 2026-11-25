package com.example.kidshield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.kidshield.theme.KidShieldTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    companion object {
        const val EXTRA_TIMER_SECONDS = "extra_timer_seconds"
        const val EXTRA_SCREEN_STYLE = "extra_screen_style"
        const val EXTRA_UNLOCK_HOLD_SECONDS = "extra_unlock_hold_seconds"

        const val STYLE_BATTERY = 1
        const val STYLE_OVERHEAT = 2
        const val STYLE_NO_INTERNET = 3

        const val CHANNEL_ID = "KidShieldTimerChannel"
        const val NOTIFICATION_ID = 101

        val isRunning = MutableStateFlow(false)
        val remainingSeconds = MutableStateFlow(0L)
        val isLocked = MutableStateFlow(false)
        val currentStyle = MutableStateFlow(STYLE_BATTERY)
        val unlockHoldSeconds = MutableStateFlow(4)
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var countDownTimer: CountDownTimer? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val totalSeconds = intent?.getLongExtra(EXTRA_TIMER_SECONDS, 60L) ?: 60L
        val style = intent?.getIntExtra(EXTRA_SCREEN_STYLE, STYLE_BATTERY) ?: STYLE_BATTERY
        val holdSeconds = intent?.getIntExtra(EXTRA_UNLOCK_HOLD_SECONDS, 4) ?: 4

        currentStyle.value = style
        unlockHoldSeconds.value = holdSeconds
        isLocked.value = false
        isRunning.value = true
        remainingSeconds.value = totalSeconds

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(totalSeconds))

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(totalSeconds * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secLeft = millisUntilFinished / 1000
                remainingSeconds.value = secLeft
                updateNotification(secLeft)
            }

            override fun onFinish() {
                remainingSeconds.value = 0L
                isLocked.value = true
                showLockOverlay()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun showLockOverlay() {
        if (overlayView != null) return

        // Request Audio Focus to pause background sounds/YouTube
        requestSystemAudioFocus()

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)

            // Hide system status bar and navigation bar (Immersive Sticky Mode)
            @Suppress("DEPRECATION")
            systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )

            // Modern Android WindowInsets Controller for API 30+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: android.view.View) {
                        val controller = v.windowInsetsController
                        if (controller != null) {
                            controller.hide(
                                android.view.WindowInsets.Type.statusBars() 
                                or android.view.WindowInsets.Type.navigationBars()
                            )
                            controller.systemBarsBehavior = 
                                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        }
                    }
                    override fun onViewDetachedFromWindow(v: android.view.View) {}
                })
            }

            setContent {
                KidShieldTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Black
                    ) {
                        LockScreenContent(
                            style = currentStyle.value,
                            onUnlock = {
                                unlockAndStop()
                            }
                        )
                    }
                }
            }
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Window needs to be FOCUSABLE to control the system status bar visibility
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            // Allow extending into the camera notch (cutout) area for a true fullscreen
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowManager?.addView(overlayView, params)
    }

    private fun requestSystemAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun releaseSystemAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus { }
        }
    }

    private fun unlockAndStop() {
        removeOverlay()
        releaseSystemAudioFocus()
        stopSelf()
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
            overlayView = null
        }
    }

    private fun updateNotification(secondsLeft: Long) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(secondsLeft))
    }

    private fun buildNotification(secondsLeft: Long): Notification {
        val minutes = secondsLeft / 60
        val seconds = secondsLeft % 60
        val timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KidShield Timer Active")
            .setContentText("Lock will activate in $timeString")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "KidShield Timer Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        removeOverlay()
        releaseSystemAudioFocus()
        isRunning.value = false
        isLocked.value = false
        remainingSeconds.value = 0
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}

@Composable
fun LockScreenContent(style: Int, onUnlock: () -> Unit) {
    var showUnlockIndicator by remember { mutableStateOf(false) }
    var unlockProgress by remember { mutableStateOf(0f) }
    val holdSeconds by OverlayService.unlockHoldSeconds.collectAsState()

    val focusRequester = remember { FocusRequester() }
    val infiniteTransition = rememberInfiniteTransition(label = "Blink")

    // Request focus on creation to intercept hardware keys (like Back button)
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (style == OverlayService.STYLE_OVERHEAT) Color(0xFF120000) else Color.Black)
            // Intercept Back Press Key so the child cannot escape using physical keys
            .focusRequester(focusRequester)
            .onKeyEvent {
                true // Consume all key events
            }
    ) {
        when (style) {
            OverlayService.STYLE_BATTERY -> {
                val batteryAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "BatteryBlink"
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier.size(width = 120.dp, height = 220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Transparent)
                                .padding(bottom = 20.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Transparent)
                                    .padding(8.dp)
                                    .align(Alignment.Center)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(30.dp)
                                        .background(Color.Red)
                                        .align(Alignment.BottomCenter)
                                        .alpha(batteryAlpha)
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(40.dp, 15.dp)
                                .background(Color.DarkGray)
                                .align(Alignment.TopCenter)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "0% - Battery Empty",
                        color = Color.Red,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Connect charger immediately.\nPhone is shutting down...",
                        color = Color.LightGray,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }

            OverlayService.STYLE_OVERHEAT -> {
                val warnAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "WarnBlink"
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "⚠️",
                        fontSize = 100.sp,
                        modifier = Modifier.alpha(warnAlpha)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "System Warning: High Temperature!",
                        color = Color(0xFFFF5722),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Device temperature is 49.5°C. To prevent damage to internal hardware and battery degradation, all active applications have been suspended.\n\nPlease let the device cool down before using it.",
                        color = Color.LightGray,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp
                    )

                    Spacer(modifier = Modifier.height(40.dp))

                    CircularProgressIndicator(
                        color = Color(0xFFFF5722),
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(48.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Cooling down... (Estimated 5 min remaining)",
                        color = Color.DarkGray,
                        fontSize = 14.sp
                    )
                }
            }

            OverlayService.STYLE_NO_INTERNET -> {
                var showSpinner by remember { mutableStateOf(false) }

                LaunchedEffect(showSpinner) {
                    if (showSpinner) {
                        kotlinx.coroutines.delay(2000)
                        showSpinner = false
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFF1F3F4))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(Color.White)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "https://m.youtube.com",
                            color = Color.Black,
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (showSpinner) {
                            CircularProgressIndicator(
                                color = Color.Gray,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Text(
                                text = "↻",
                                color = Color.Gray,
                                fontSize = 24.sp,
                                modifier = Modifier.clickable { showSpinner = true }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(120.dp))

                    Text(
                        text = "🦖",
                        fontSize = 72.sp,
                        color = Color.DarkGray
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "No Internet Connection",
                        color = Color(0xFF3C4043),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "The connection was lost. Try checking your network connection or settings.\n\nERR_INTERNET_DISCONNECTED",
                        color = Color(0xFF5F6368),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = { showSpinner = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(text = "Try again", color = Color.White)
                    }
                }
            }
        }

        // Subtle Parent Unlock Secret Mark (bottom-right corner)
        val markColor = if (style == OverlayService.STYLE_NO_INTERNET) Color.Black else Color.White
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(markColor.copy(alpha = 0.03f))
                .border(1.dp, markColor.copy(alpha = 0.08f), CircleShape)
                .pointerInput(holdSeconds) {
                    detectTapGestures(
                        onPress = {
                            showUnlockIndicator = true
                            unlockProgress = 0f
                            coroutineScope {
                                val progressJob = launch {
                                    val totalDuration = holdSeconds * 1000L
                                    val steps = holdSeconds * 10
                                    for (i in 1..steps) {
                                        kotlinx.coroutines.delay(totalDuration / steps)
                                        unlockProgress = i.toFloat() / steps
                                    }
                                    onUnlock()
                                }
                                try {
                                    awaitRelease()
                                    progressJob.cancel()
                                } catch (e: Exception) {
                                    progressJob.cancel()
                                } finally {
                                    showUnlockIndicator = false
                                    unlockProgress = 0f
                                }
                            }
                        }
                    )
                }
        )

        if (showUnlockIndicator) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        progress = unlockProgress,
                        color = Color.White,
                        strokeWidth = 6.dp,
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Holding... Keep pressing for $holdSeconds seconds",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

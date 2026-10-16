package com.kiransaj.diyamonitor.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kiransaj.diyamonitor.MainActivity
import com.kiransaj.diyamonitor.R
import com.kiransaj.diyamonitor.core.AlertType
import com.kiransaj.diyamonitor.core.CalibrationException
import com.kiransaj.diyamonitor.core.Calibrator
import com.kiransaj.diyamonitor.core.CooldownGate
import com.kiransaj.diyamonitor.core.FlameAnalyzer
import com.kiransaj.diyamonitor.core.FrameMetrics
import com.kiransaj.diyamonitor.core.MetricsExtractor
import com.kiransaj.diyamonitor.core.NormRect
import com.kiransaj.diyamonitor.data.Prefs
import com.kiransaj.diyamonitor.telegram.TelegramOutbox
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that owns the camera. It analyses one frame every few seconds,
 * runs calibration and monitoring, and sends Telegram alerts. The monitor screen only
 * attaches a preview surface; analysis keeps running when the UI is gone.
 */
class MonitorService : LifecycleService() {

    private lateinit var prefs: Prefs
    private lateinit var outbox: TelegramOutbox

    /** All detection state is touched only on this thread. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private val cameraSelector: CameraSelector
        get() {
            val hasBack = runCatching { cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrNull() != false
            return if (hasBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        }
    private var started = false

    private val converter = FrameConverter()
    private val extractor = MetricsExtractor()
    private var calibrator: Calibrator? = null
    private var calibrationStartMs = 0L
    private var analyzer: FlameAnalyzer? = null
    private var lastAnalysisMs = 0L
    private var lastHeartbeatMs = 0L
    @Volatile private var lastFrameMs = 0L
    private var lastNotificationMs = 0L

    private val healthGate = CooldownGate(15 * 60_000L)
    private var chargerAlerted = false
    private var lowBatteryAlerted = false
    private var stallAlerted = false
    private var batteryReceiverRegistered = false
    private var lastBatteryText = ""

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val mode get() = MonitorState.mode.value

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        outbox = TelegramOutbox.get(this)
        MonitorState.roi.value = prefs.roi
        createChannel()
        if (!startInForeground()) {
            stopSelf()
            return
        }
        started = true
        MonitorState.mode.value = Mode.PREVIEW
        startCamera()
        lifecycleScope.launch { MonitorState.previewSurface.collect { attachPreview(it) } }
        registerBatteryReceiver()
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!started) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_CALIBRATE -> worker.execute { startCalibration() }
            ACTION_START -> worker.execute { startMonitoring(resumed = false) }
            ACTION_STOP -> worker.execute { stopMonitoring("stopped by user") }
            ACTION_RELEASE -> if (mode != Mode.MONITORING) stopSelf()
            null -> {
                // Restarted by the system after the process was killed.
                if (prefs.monitoringActive && prefs.baseline != null) {
                    worker.execute { startMonitoring(resumed = true) }
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(watchdog)
        if (batteryReceiverRegistered) unregisterReceiver(batteryReceiver)
        if (mode == Mode.MONITORING) {
            prefs.monitoringActive = false
            outbox.sendNow("⚠️ Diya Monitor: monitoring STOPPED (service shut down).")
        }
        releaseLocks()
        runCatching { cameraProvider?.unbindAll() }
        worker.shutdown()
        MonitorState.mode.value = Mode.STOPPED
        MonitorState.live.value = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(worker) { image -> analyze(image) }
                provider.unbindAll()
                preview = null
                provider.bindToLifecycle(this, cameraSelector, analysis)
                attachPreview(MonitorState.previewSurface.value)
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                MonitorState.message.value = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun attachPreview(surface: Preview.SurfaceProvider?) {
        val provider = cameraProvider ?: return
        try {
            if (surface == null) {
                preview?.let { provider.unbind(it) }
                preview = null
            } else {
                val p = preview ?: Preview.Builder().setResolutionSelector(resolutionSelector()).build().also {
                    provider.bindToLifecycle(this, cameraSelector, it)
                    preview = it
                }
                p.setSurfaceProvider(surface)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Preview attach failed", e)
        }
    }

    private fun resolutionSelector() = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
        .build()

    // -------------------------------------------------------------- analysis

    private fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            lastFrameMs = now
            if (now - lastAnalysisMs < prefs.checkIntervalSec * 1000L) return
            lastAnalysisMs = now

            val frame = converter.convert(image)
            extractor.brightThreshold = prefs.brightThreshold
            val m = extractor.extract(frame, MonitorState.roi.value, now)
            when (mode) {
                Mode.CALIBRATING -> calibrate(m)
                Mode.MONITORING -> monitor(m)
                else -> MonitorState.live.value = LiveReading(m)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed", e)
        } finally {
            image.close()
        }
    }

    private fun startCalibration() {
        if (mode == Mode.MONITORING) return
        calibrator = Calibrator()
        calibrationStartMs = System.currentTimeMillis()
        prefs.roi = MonitorState.roi.value
        MonitorState.message.value = "Calibrating… keep the flame steady and the phone still."
        MonitorState.mode.value = Mode.CALIBRATING
    }

    private fun calibrate(m: FrameMetrics) {
        val cal = calibrator ?: return
        cal.add(m)
        val duration = if (prefs.testMode) 15_000L else CALIBRATION_MS
        val progress = ((m.timestampMs - calibrationStartMs).toFloat() / duration).coerceIn(0f, 1f)
        MonitorState.live.value = LiveReading(m, calibrationProgress = progress)
        if (progress < 1f) return
        calibrator = null
        MonitorState.mode.value = Mode.PREVIEW
        try {
            val b = cal.build(minSamples = 5)
            prefs.baseline = b
            MonitorState.message.value = "Calibrated ✓ flame covers %.1f%% of the box, brightness %.0f. You can start monitoring."
                .format(b.areaPct, b.brightness)
        } catch (e: CalibrationException) {
            prefs.baseline = null
            MonitorState.message.value = "Calibration failed: ${e.message}"
        }
    }

    private fun startMonitoring(resumed: Boolean) {
        if (mode == Mode.MONITORING) return
        val baseline = prefs.baseline
        if (baseline == null) {
            MonitorState.message.value = "Calibrate first."
            return
        }
        MonitorState.roi.value = prefs.roi
        val config = prefs.detectionConfig()
        analyzer = FlameAnalyzer(baseline, config)
        healthGate.cooldownMs = config.cooldownMs
        lastHeartbeatMs = System.currentTimeMillis()
        lastFrameMs = System.currentTimeMillis()
        chargerAlerted = false
        lowBatteryAlerted = false
        stallAlerted = false
        prefs.monitoringActive = true
        acquireLocks()
        MonitorState.mode.value = Mode.MONITORING
        MonitorState.message.value = "Monitoring started."
        val text = if (resumed) "🔄 Diya Monitor restarted after the app was stopped — monitoring RESUMED."
        else "▶️ Diya Monitor: monitoring started."
        outbox.enqueue("$text\n$lastBatteryText".trim(), converter.jpeg(photoArea()))
        main.post { checkBattery(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))) }
    }

    private fun stopMonitoring(reason: String) {
        if (mode != Mode.MONITORING) return
        analyzer = null
        prefs.monitoringActive = false
        releaseLocks()
        MonitorState.mode.value = Mode.PREVIEW
        MonitorState.message.value = "Monitoring stopped."
        outbox.enqueue("⏹️ Diya Monitor: monitoring $reason.")
        updateNotification("Camera ready (not monitoring)")
        // Nobody is looking at the preview: no reason to keep the camera running.
        if (MonitorState.previewSurface.value == null) main.post { stopSelf() }
    }

    private fun monitor(m: FrameMetrics) {
        val a = analyzer ?: return
        val events = a.process(m)
        val snapshot = a.lastSnapshot
        MonitorState.live.value = LiveReading(m, snapshot)
        for (e in events) {
            outbox.enqueue(e.message(), converter.jpeg(photoArea()))
        }
        val now = m.timestampMs
        val heartbeatMs = (prefs.heartbeatHours * 3600_000L * if (prefs.testMode) 0.1 else 1.0).toLong()
        if (prefs.heartbeatEnabled && now - lastHeartbeatMs >= heartbeatMs) {
            lastHeartbeatMs = now
            val status = snapshot?.status?.name ?: "?"
            outbox.enqueue(
                "💓 Still monitoring — flame $status.\n\n${snapshot?.describe().orEmpty()}\n$lastBatteryText".trim(),
                converter.jpeg(photoArea()),
            )
        }
        if (now - lastNotificationMs > 30_000) {
            lastNotificationMs = now
            updateNotification("Monitoring — flame ${snapshot?.status ?: "?"}")
        }
    }

    /** Area included in photos: the flame box plus the soot zone above, with some context. */
    private fun photoArea(): NormRect {
        val roi = MonitorState.roi.value
        return roi.union(roi.aboveZone()).expanded(0.75f)
    }

    // ---------------------------------------------------------- phone health

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = checkBattery(intent)
    }

    private fun registerBatteryReceiver() {
        ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        batteryReceiverRegistered = true
    }

    private fun checkBattery(intent: Intent?) {
        intent ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        lastBatteryText = "Phone battery: $pct%${if (plugged) " (charging)" else " (NOT charging)"}, ${"%.1f".format(tempC)} °C"

        if (mode != Mode.MONITORING || !prefs.phoneHealthAlerts) return
        val now = System.currentTimeMillis()
        if (!plugged && !chargerAlerted) {
            chargerAlerted = true
            outbox.enqueue("${AlertType.CHARGER_DISCONNECTED.title}\nThe phone is running on battery. Plug in the charger for an all-night session.\n$lastBatteryText")
        } else if (plugged && chargerAlerted) {
            chargerAlerted = false
            outbox.enqueue("✅ Charger reconnected.\n$lastBatteryText")
        }
        if (pct in 0 until 20 && !plugged) {
            if (!lowBatteryAlerted && healthGate.tryFire(AlertType.BATTERY_LOW, now)) {
                lowBatteryAlerted = true
                outbox.enqueue("${AlertType.BATTERY_LOW.title}: $pct%. Monitoring will stop when the phone dies.\n$lastBatteryText")
            }
        } else if (pct >= 25 || plugged) {
            lowBatteryAlerted = false
        }
        if (tempC > 45.0 && healthGate.tryFire(AlertType.BATTERY_HOT, now)) {
            outbox.enqueue("${AlertType.BATTERY_HOT.title}: ${"%.1f".format(tempC)} °C. Move the phone away from the flame / out of direct heat.")
        }
    }

    /** Detects a camera that silently stopped delivering frames, and tries to restart it. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (mode == Mode.MONITORING) {
                val silentMs = System.currentTimeMillis() - lastFrameMs
                if (silentMs > STALL_MS) {
                    if (!stallAlerted) {
                        stallAlerted = true
                        outbox.enqueue("${AlertType.CAMERA_STALLED.title} for ${silentMs / 1000}s. Trying to restart the camera…")
                    }
                    startCamera()
                } else if (stallAlerted) {
                    stallAlerted = false
                    outbox.enqueue("✅ Camera is delivering frames again.")
                }
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    // ------------------------------------------------- foreground & wake locks

    private fun startInForeground(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No camera permission; cannot run")
            return false
        }
        val notification = buildNotification("Camera ready (not monitoring)")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // e.g. Android 14+ refuses camera foreground services started from the background.
            Log.e(TAG, "startForeground failed", e)
            if (prefs.monitoringActive) {
                outbox.sendNow("⚠️ Diya Monitor could not restart the camera in the background. Open the app to resume monitoring.")
            }
            false
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop monitoring", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        main.post {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiyaMonitor:monitor")
                    .apply { setReferenceCounted(false); acquire(14 * 3600_000L) }
            }
            if (wifiLock == null) {
                wifiLock = runCatching {
                    applicationContext.getSystemService(WifiManager::class.java)
                        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DiyaMonitor:wifi")
                        .apply { setReferenceCounted(false); acquire() }
                }.getOrNull()
            }
        }
    }

    private fun releaseLocks() {
        main.post {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            wakeLock = null
            wifiLock = null
        }
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 1
        private const val CALIBRATION_MS = 60_000L
        private const val WATCHDOG_MS = 30_000L
        private const val STALL_MS = 60_000L

        const val ACTION_PREVIEW = "com.kiransaj.diyamonitor.PREVIEW"
        const val ACTION_CALIBRATE = "com.kiransaj.diyamonitor.CALIBRATE"
        const val ACTION_START = "com.kiransaj.diyamonitor.START"
        const val ACTION_STOP = "com.kiransaj.diyamonitor.STOP"
        /** The UI no longer needs the camera; the service stops unless monitoring. */
        const val ACTION_RELEASE = "com.kiransaj.diyamonitor.RELEASE"

        fun send(context: Context, action: String) {
            val intent = Intent(context, MonitorService::class.java).setAction(action)
            if (action == ACTION_RELEASE || action == ACTION_STOP) {
                // Don't start the service just to tell it to stop.
                if (MonitorState.mode.value != Mode.STOPPED) context.startService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}

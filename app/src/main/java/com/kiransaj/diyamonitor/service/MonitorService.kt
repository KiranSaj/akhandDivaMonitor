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
import android.hardware.camera2.CaptureRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
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
import com.kiransaj.diyamonitor.core.Baseline
import com.kiransaj.diyamonitor.core.Blob
import com.kiransaj.diyamonitor.core.CalibrationException
import com.kiransaj.diyamonitor.core.Calibrator
import com.kiransaj.diyamonitor.core.CooldownGate
import com.kiransaj.diyamonitor.core.DetectionEvent
import com.kiransaj.diyamonitor.core.FlameAnalyzer
import com.kiransaj.diyamonitor.core.FlameDetector
import com.kiransaj.diyamonitor.core.FlameFinder
import com.kiransaj.diyamonitor.core.FlameRegion
import com.kiransaj.diyamonitor.core.FlameTracker
import com.kiransaj.diyamonitor.core.FrameMetrics
import com.kiransaj.diyamonitor.core.LumaFrame
import com.kiransaj.diyamonitor.core.MetricsExtractor
import com.kiransaj.diyamonitor.core.MoveDetector
import com.kiransaj.diyamonitor.core.NormRect
import com.kiransaj.diyamonitor.data.Prefs
import com.kiransaj.diyamonitor.log.NightLog
import com.kiransaj.diyamonitor.telegram.TelegramCommands
import com.kiransaj.diyamonitor.telegram.TelegramOutbox
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Foreground service that owns the camera. It finds the flames, learns their normal size,
 * analyses one frame every few seconds and sends Telegram alerts. The monitor screen only
 * attaches a preview surface; analysis keeps running when the UI is gone or the screen is off.
 */
class MonitorService : LifecycleService() {

    private lateinit var prefs: Prefs
    private lateinit var outbox: TelegramOutbox

    /** All detection state is touched only on this thread. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private val cameraSelector: CameraSelector
        get() {
            val hasBack = runCatching { cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrNull() != false
            return if (hasBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        }
    private var started = false

    private val converter = FrameConverter()
    private val extractor = MetricsExtractor()
    private val detector = FlameDetector()
    private var lastAnalysisMs = 0L
    @Volatile private var lastFrameMs = 0L

    // ---- setup (find flames + calibrate) ----
    private var setup: Setup? = null
    /** True when the current flame boxes / baselines were made in this camera session (exposure still locked). */
    private var regionsFresh = false
    private var baselinesFresh = false

    // ---- monitoring ----
    private var analyzer: FlameAnalyzer? = null
    private var flames: List<FlameRegion> = emptyList()
    private var flameBaselines: List<Baseline> = emptyList()
    private val moveDetector = MoveDetector()
    private var nightLog: NightLog? = null
    private var commands: TelegramCommands? = null
    private var lastHeartbeatMs = 0L
    private var lastNotificationMs = 0L
    private var lastBlobs: List<Blob> = emptyList()
    private var screenOffMs = 0L
    private var stalledWithScreenOff = false

    private val healthGate = CooldownGate(15 * 60_000L)
    private var chargerAlerted = false
    private var lowBatteryAlerted = false
    private var stallAlerted = false
    private var batteryReceiverRegistered = false
    private var lastBatteryText = ""
    @Volatile private var lastBattery: NightLog.BatteryInfo? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val mode get() = MonitorState.mode.value
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }

    /**
     * One run of "find the flames, then learn their normal size".
     * [keepRegions]: the user placed the boxes by hand, so skip finding.
     * [resumeMonitoring]: start (or continue) monitoring when done. [reason] is set for automatic re-setups.
     */
    private class Setup(
        val startMs: Long,
        val keepRegions: Boolean,
        val resumeMonitoring: Boolean,
        val restarted: Boolean,
        val reason: String?,
        val finder: FlameFinder,
    ) {
        var calibrationStartMs = 0L
        var flameCalibrators: List<Calibrator> = emptyList()
        var combinedCalibrator = Calibrator()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        outbox = TelegramOutbox.get(this)
        flames = prefs.flames
        flameBaselines = prefs.flameBaselines
        MonitorState.flames.value = flames
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
            ACTION_FIND -> worker.execute { startSetup(keepRegions = false, resumeMonitoring = false) }
            ACTION_START -> worker.execute { onStartPressed() }
            ACTION_STOP -> worker.execute { stopMonitoring("stopped by user") }
            ACTION_FLAMES_EDITED -> worker.execute { onFlamesEdited() }
            ACTION_RELEASE -> if (mode != Mode.MONITORING && setup?.resumeMonitoring != true) stopSelf()
            null -> {
                // Restarted by the system after the process was killed.
                if (prefs.monitoringActive && prefs.baseline != null) {
                    worker.execute { startSetup(keepRegions = false, resumeMonitoring = true, restarted = true) }
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
        commands?.stop()
        worker.execute { nightLog?.close() }
        releaseLocks()
        runCatching { cameraProvider?.unbindAll() }
        worker.shutdown()
        MonitorState.mode.value = Mode.STOPPED
        MonitorState.live.value = null
        MonitorState.forceScreenOn.value = false
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
                camera = provider.bindToLifecycle(this, cameraSelector, analysis)
                attachPreview(MonitorState.previewSurface.value)
                // A rebound camera has lost its exposure lock.
                worker.execute { baselinesFresh = false }
                if (mode == Mode.MONITORING) lockExposure()
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
                    camera = provider.bindToLifecycle(this, cameraSelector, it)
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

    /**
     * Darkens the exposure (so flames stand out and reflections fade) and then locks exposure
     * and white balance, so readings stay comparable all night. Call on the main thread.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun lockExposure() {
        val cam = camera ?: return
        val control = Camera2CameraControl.from(cam.cameraControl)
        control.clearCaptureRequestOptions()
        if (!prefs.exposureLock) {
            cam.cameraControl.setExposureCompensationIndex(0)
            return
        }
        val state = cam.cameraInfo.exposureState
        if (state.isExposureCompensationSupported) {
            val step = state.exposureCompensationStep.toFloat()
            val index = if (step > 0) (prefs.exposureEvTenths / 10f / step).roundToInt() else 0
            cam.cameraControl.setExposureCompensationIndex(index.coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper))
        }
        // Let auto-exposure settle on the new compensation before freezing it.
        main.postDelayed({
            runCatching {
                control.setCaptureRequestOptions(
                    CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                        .build()
                )
            }
        }, EXPOSURE_SETTLE_MS)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun unlockExposure() {
        val cam = camera ?: return
        runCatching {
            Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions()
            cam.cameraControl.setExposureCompensationIndex(0)
        }
    }

    // -------------------------------------------------------------- analysis

    private fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            lastFrameMs = now
            val interval = if (mode == Mode.FINDING) FIND_INTERVAL_MS else prefs.checkIntervalSec * 1000L
            if (now - lastAnalysisMs < interval) return
            lastAnalysisMs = now

            val frame = converter.convert(image)
            detector.brightThreshold = prefs.brightThreshold
            extractor.brightThreshold = prefs.brightThreshold
            val blobs = detector.detect(frame)
            lastBlobs = blobs
            MonitorState.blobs.value = blobs.take(8).map { it.box }
            when (mode) {
                Mode.FINDING -> find(blobs, frame, now)
                Mode.CALIBRATING -> calibrate(frame, now)
                Mode.MONITORING -> monitor(frame, blobs, now)
                else -> {
                    val (combined, perFlame) = measure(frame, now)
                    MonitorState.live.value = LiveReading(combined, flameRatios = ratios(perFlame))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed", e)
        } finally {
            image.close()
        }
    }

    /** Per-flame readings and their combination. */
    private fun measure(frame: LumaFrame, now: Long): Pair<FrameMetrics, List<FrameMetrics>> {
        val perFlame = flames.map { extractor.extract(frame, it.box, now) }
        val combined = FrameMetrics.combine(perFlame, flames.map { (it.box.width * it.box.height).toDouble() }, now)
        return combined to perFlame
    }

    private fun ratios(perFlame: List<FrameMetrics>): List<Double> =
        if (flameBaselines.size != perFlame.size) emptyList()
        else perFlame.indices.map { i -> flameBaselines[i].areaPct.let { b -> if (b > 0) perFlame[i].areaPct / b else 0.0 } }

    // ----------------------------------------------------------------- setup

    private fun onStartPressed() {
        if (mode == Mode.MONITORING || setup != null) return
        when {
            regionsFresh && baselinesFresh && flames.isNotEmpty() && prefs.baseline != null -> startMonitoring(restarted = false)
            regionsFresh && flames.isNotEmpty() -> startSetup(keepRegions = true, resumeMonitoring = true)
            else -> startSetup(keepRegions = false, resumeMonitoring = true)
        }
    }

    private fun onFlamesEdited() {
        if (mode == Mode.MONITORING || setup != null) return
        flames = MonitorState.flames.value
        prefs.flames = flames
        regionsFresh = true
        baselinesFresh = false
        MonitorState.message.value = "Flames updated (${flames.size}). Press Start: it learns their normal size first (60 s)."
    }

    private fun startSetup(keepRegions: Boolean, resumeMonitoring: Boolean, restarted: Boolean = false, reason: String? = null) {
        if (setup != null) return
        if (mode == Mode.MONITORING && reason == null) return
        setup = Setup(System.currentTimeMillis(), keepRegions, resumeMonitoring, restarted, reason, FlameFinder(prefs.expectedFlames))
        MonitorState.mode.value = Mode.FINDING
        MonitorState.message.value = if (keepRegions) "Locking exposure…" else "Finding the flames… keep the phone and lamp still."
        if (resumeMonitoring) acquireLocks()
        main.post { lockExposure() }
    }

    private fun find(blobs: List<Blob>, frame: LumaFrame, now: Long) {
        val s = setup ?: return
        val elapsed = now - s.startMs
        val findMs = if (prefs.testMode) FIND_MS_TEST else FIND_MS
        val total = SETTLE_MS + if (s.keepRegions) 0L else findMs
        MonitorState.live.value = LiveReading(measure(frame, now).first, calibrationProgress = (elapsed.toFloat() / total).coerceIn(0f, 1f))
        if (elapsed < SETTLE_MS) return // exposure is still settling
        if (!s.keepRegions) {
            s.finder.add(blobs)
            MonitorState.blobs.value = s.finder.candidates().map { it.box }
            if (elapsed < total) return
            val found = s.finder.flames()
            if (found.isEmpty()) {
                setupFailed("No flame found. Is the diya lit and in view? Tap a flame on the preview to mark it by hand, or lower the bright-pixel threshold in Settings.")
                return
            }
            flames = found
            MonitorState.flames.value = found
            regionsFresh = true
        }
        s.flameCalibrators = flames.map { Calibrator() }
        s.combinedCalibrator = Calibrator()
        s.calibrationStartMs = now
        MonitorState.mode.value = Mode.CALIBRATING
        MonitorState.message.value = "Found ${flames.size} flame${if (flames.size == 1) "" else "s"}. Learning their normal size… keep everything still."
    }

    private fun calibrate(frame: LumaFrame, now: Long) {
        val s = setup ?: return
        val (combined, perFlame) = measure(frame, now)
        perFlame.forEachIndexed { i, m -> s.flameCalibrators[i].add(m) }
        s.combinedCalibrator.add(combined)
        val duration = if (prefs.testMode) CALIBRATION_MS_TEST else CALIBRATION_MS
        val progress = ((now - s.calibrationStartMs).toFloat() / duration).coerceIn(0f, 1f)
        MonitorState.live.value = LiveReading(combined, calibrationProgress = progress)
        if (progress < 1f) return
        try {
            flameBaselines = s.flameCalibrators.mapIndexed { i, c ->
                try {
                    c.build(minSamples = 5)
                } catch (e: CalibrationException) {
                    throw CalibrationException("Flame ${i + 1}: ${e.message}")
                }
            }
            val combinedBaseline = s.combinedCalibrator.build(minSamples = 5)
            prefs.flames = flames
            prefs.flameBaselines = flameBaselines
            prefs.baseline = combinedBaseline
            baselinesFresh = true
            setup = null
            val expected = prefs.expectedFlames
            val summary = "Found ${flames.size} of $expected flame${if (expected == 1) "" else "s"} ✓ and learned their normal size."
            if (s.resumeMonitoring) {
                startMonitoring(restarted = s.restarted, reason = s.reason, summary = summary)
            } else {
                MonitorState.mode.value = Mode.PREVIEW
                MonitorState.message.value = "$summary Check the boxes, then press Start." +
                    if (flames.size < expected) " Tap a missing flame to add it." else ""
            }
        } catch (e: CalibrationException) {
            setupFailed("Calibration failed: ${e.message}")
        }
    }

    private fun setupFailed(message: String) {
        val s = setup ?: return
        setup = null
        val previous = prefs.flames
        val previousBaselines = prefs.flameBaselines
        if (s.resumeMonitoring && (s.reason != null || s.restarted) && previous.isNotEmpty() && prefs.baseline != null &&
            previousBaselines.size == previous.size
        ) {
            // Re-setup after a move or restart failed: carry on with the old boxes rather than stop watching.
            flames = previous
            flameBaselines = previousBaselines
            MonitorState.flames.value = previous
            outbox.enqueue("⚠️ Diya Monitor could not find the flames again ($message). Continuing with the previous settings: please check the lamp and the camera.", photo())
            startMonitoring(restarted = s.restarted, reason = s.reason, summary = null)
            return
        }
        if (analyzer != null) {
            // Monitoring was running and cannot continue without boxes.
            stopMonitoring("stopped: the flames could not be found again ($message)")
            return
        }
        regionsFresh = false
        baselinesFresh = false
        if (s.resumeMonitoring) {
            releaseLocks()
            prefs.monitoringActive = false
            outbox.enqueue("⚠️ Diya Monitor could not start monitoring: $message", photo())
        }
        MonitorState.mode.value = Mode.PREVIEW
        MonitorState.message.value = message
        main.post { unlockExposure() }
    }

    // ------------------------------------------------------------ monitoring

    private fun startMonitoring(restarted: Boolean, reason: String? = null, summary: String? = null) {
        if (mode == Mode.MONITORING && reason == null) return
        val baseline = prefs.baseline
        if (baseline == null || flames.isEmpty()) {
            MonitorState.message.value = "Press Start again: the flames need to be found first."
            return
        }
        val config = prefs.detectionConfig()
        val wasMonitoring = analyzer != null && reason != null
        analyzer = FlameAnalyzer(baseline, config)
        moveDetector.reset()
        healthGate.cooldownMs = config.cooldownMs
        lastFrameMs = System.currentTimeMillis()
        prefs.monitoringActive = true
        acquireLocks()
        MonitorState.mode.value = Mode.MONITORING
        MonitorState.message.value = "Monitoring."
        if (reason != null && wasMonitoring) {
            nightLog?.event("RESETUP $reason")
            outbox.enqueue("📷 The camera or lamp moved. ${summary ?: ""} Monitoring continues.".trim(), photo(boxes = true))
            return
        }
        lastHeartbeatMs = System.currentTimeMillis()
        chargerAlerted = false
        lowBatteryAlerted = false
        stallAlerted = false
        screenOffMs = 0L
        stalledWithScreenOff = false
        MonitorState.forceScreenOn.value = false
        nightLog?.close()
        nightLog = NightLog.start(this, System.currentTimeMillis()).also { it.event(if (restarted) "START restarted" else "START") }
        commands?.stop()
        commands = TelegramCommands(prefs) { command -> worker.execute { onTelegramCommand(command) } }.also { it.start() }
        val text = buildString {
            append(if (restarted) "🔄 Diya Monitor restarted after the app was stopped — monitoring RESUMED." else "▶️ Diya Monitor: monitoring started.")
            summary?.let { append("\n").append(it) }
            if (flames.size < prefs.expectedFlames) append("\n⚠️ Only ${flames.size} of ${prefs.expectedFlames} flames found: check the photo.")
            append("\n").append(lastBatteryText)
            append("\nSend /status or /log here at any time.")
        }
        outbox.enqueue(text.trim(), photo(boxes = true))
        main.post { checkBattery(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))) }
    }

    private fun stopMonitoring(reason: String) {
        // Monitoring may be paused for an automatic re-setup (camera moved): that still counts as running.
        val running = mode == Mode.MONITORING || analyzer != null
        if (setup?.resumeMonitoring == true) setup = null
        if (!running) {
            // Stop pressed while Start was still finding the flames.
            releaseLocks()
            prefs.monitoringActive = false
            if (mode == Mode.FINDING || mode == Mode.CALIBRATING) MonitorState.mode.value = Mode.PREVIEW
            main.post { unlockExposure() }
            return
        }
        analyzer = null
        prefs.monitoringActive = false
        prefs.flames = flames
        commands?.stop()
        commands = null
        // A whole hour with the screen off and no camera pause: screen-off works on this phone.
        if (screenOffMs >= 3600_000L && !stalledWithScreenOff) prefs.screenOffFailures = 0
        nightLog?.event("STOP $reason")
        nightLog?.close()
        nightLog = null
        releaseLocks()
        regionsFresh = false
        baselinesFresh = false
        MonitorState.forceScreenOn.value = false
        MonitorState.mode.value = Mode.PREVIEW
        MonitorState.message.value = "Monitoring stopped."
        outbox.enqueue("⏹️ Diya Monitor: monitoring $reason.")
        updateNotification("Camera ready (not monitoring)")
        main.post { unlockExposure() }
        // Nobody is looking at the preview: no reason to keep the camera running.
        if (MonitorState.previewSurface.value == null) main.post { stopSelf() }
    }

    private fun monitor(frame: LumaFrame, blobs: List<Blob>, now: Long) {
        val a = analyzer ?: return
        val tracked = FlameTracker.follow(flames, blobs)
        flames = tracked.regions
        MonitorState.flames.value = flames
        val (combined, perFlame) = measure(frame, now)
        val events = a.process(combined)
        val snapshot = a.lastSnapshot
        val flameRatios = ratios(perFlame)
        MonitorState.live.value = LiveReading(combined, snapshot, flameRatios = flameRatios)

        val screenOn = powerManager.isInteractive
        if (!screenOn) screenOffMs += prefs.checkIntervalSec * 1000L
        val log = nightLog
        for (e in events) {
            val photo = photo()
            outbox.enqueue(e.message(), photo)
            log?.event(if (e is DetectionEvent.Alert) "ALERT ${e.type}" else "RECOVERED ${e.type}")
            log?.snapshot(now, e.type.name.lowercase(), force = true) { photo }
        }
        log?.row(mode.name, combined, snapshot, perFlame, flameRatios, blobs, lastBattery, screenOn)
        log?.snapshot(now, "periodic", force = false) { photo(full = true) }

        val heartbeatMs = (prefs.heartbeatHours * 3600_000L * if (prefs.testMode) 0.1 else 1.0).toLong()
        if (prefs.heartbeatEnabled && now - lastHeartbeatMs >= heartbeatMs) {
            lastHeartbeatMs = now
            outbox.enqueue("💓 Still monitoring — flame ${snapshot?.status ?: "?"}.\n\n${statusText()}", photo(boxes = true))
        }
        if (now - lastNotificationMs > 30_000) {
            lastNotificationMs = now
            updateNotification("Monitoring — flame ${snapshot?.status ?: "?"}")
        }
        if (moveDetector.update(tracked, now)) {
            log?.event("MOVED")
            startSetup(keepRegions = false, resumeMonitoring = true, reason = "moved")
        }
    }

    private fun onTelegramCommand(command: String) {
        when (command) {
            "/status" -> outbox.enqueue("📋 Status\n\n${statusText()}", photo(boxes = true))
            "/log" -> sendLog()
            else -> outbox.enqueue(TelegramCommands.HELP)
        }
    }

    private fun statusText(): String = buildString {
        val live = MonitorState.live.value
        append("Mode: ${mode.name.lowercase()}\n")
        live?.flameRatios?.forEachIndexed { i, r -> append("Flame ${i + 1}: ${(r * 100).roundToInt()}% of normal size\n") }
        live?.snapshot?.let { append(it.describe()).append('\n') }
        append(lastBatteryText)
    }.trim()

    /** Zips the current (or latest) night log and queues it for Telegram. */
    private fun sendLog() {
        nightLog?.flush()
        val session = nightLog?.dir ?: NightLog.latestSession(this)
        if (session == null) {
            outbox.enqueue("No log recorded yet.")
            return
        }
        try {
            val zip = NightLog.zip(this, session)
            outbox.enqueueDocument(zip, "🗒️ Diya Monitor log ${session.name} (${zip.length() / 1024} KB)")
        } catch (e: Exception) {
            outbox.enqueue("Could not package the log: ${e.message}")
        }
    }

    /** Photo of the flames and the area above them; [full] = the whole frame; [boxes] = outline the flames. */
    private fun photo(full: Boolean = false, boxes: Boolean = false): ByteArray? {
        val area = if (full || flames.isEmpty()) NormRect(0f, 0f, 1f, 1f)
        else flames.map { it.box.union(it.box.aboveZone()) }.reduce { a, b -> a.union(b) }.expanded(0.75f)
        return converter.jpeg(area, if (boxes || full) flames.map { it.box } else emptyList())
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
        lastBattery = NightLog.BatteryInfo(pct, plugged, tempC)
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

    /**
     * Detects a camera that silently stopped delivering frames, and tries to restart it.
     * If that happened with the screen off, wakes the screen and keeps it on for the rest of the night.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (mode == Mode.MONITORING) {
                val silentMs = System.currentTimeMillis() - lastFrameMs
                if (silentMs > STALL_MS) {
                    if (!powerManager.isInteractive && !MonitorState.forceScreenOn.value) {
                        MonitorState.forceScreenOn.value = true
                        prefs.screenOffFailures = prefs.screenOffFailures + 1
                        wakeScreen()
                        worker.execute {
                            stalledWithScreenOff = true
                            nightLog?.event("STALL_SCREEN_OFF")
                        }
                        val disabled = !prefs.screenAutoOffEnabled
                        outbox.enqueue(
                            "📷 The camera paused when the screen turned off. I woke the screen and will keep it on (dark) for the rest of tonight." +
                                if (disabled) "\nIt happened twice, so screen auto-off is now disabled. You can re-enable it in Settings." else ""
                        )
                    }
                    if (!stallAlerted) {
                        stallAlerted = true
                        worker.execute { nightLog?.event("STALL ${silentMs / 1000}s") }
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

    /** Turns the screen on so the (shown-over-lock-screen) monitor activity is visible again. */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        runCatching {
            powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "DiyaMonitor:wake")
                .acquire(10_000)
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
        private const val CALIBRATION_MS_TEST = 15_000L
        /** Time for the exposure change and lock to take effect before frames are used. */
        private const val SETTLE_MS = 3_000L
        private const val EXPOSURE_SETTLE_MS = 1_500L
        private const val FIND_MS = 10_000L
        private const val FIND_MS_TEST = 4_000L
        private const val FIND_INTERVAL_MS = 500L
        private const val WATCHDOG_MS = 30_000L
        private const val STALL_MS = 60_000L

        const val ACTION_PREVIEW = "com.kiransaj.diyamonitor.PREVIEW"
        /** Find the flames and learn their normal size, without starting to monitor. */
        const val ACTION_FIND = "com.kiransaj.diyamonitor.FIND"
        const val ACTION_START = "com.kiransaj.diyamonitor.START"
        const val ACTION_STOP = "com.kiransaj.diyamonitor.STOP"
        /** The user added or removed a flame box by tapping; see [MonitorState.flames]. */
        const val ACTION_FLAMES_EDITED = "com.kiransaj.diyamonitor.FLAMES_EDITED"
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

package com.kiransaj.diyamonitor.service

import androidx.camera.core.Preview
import com.kiransaj.diyamonitor.core.FlameRegion
import com.kiransaj.diyamonitor.core.FrameMetrics
import com.kiransaj.diyamonitor.core.NormRect
import com.kiransaj.diyamonitor.core.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow

/** FINDING = locking exposure and looking for the flames; CALIBRATING = learning their normal size. */
enum class Mode { STOPPED, PREVIEW, FINDING, CALIBRATING, MONITORING }

data class LiveReading(
    /** Both flames combined. */
    val metrics: FrameMetrics,
    /** Present while monitoring. */
    val snapshot: Snapshot? = null,
    /** 0..1 while finding/calibrating. */
    val calibrationProgress: Float? = null,
    /** Each flame's size as a fraction of its own baseline (empty until calibrated). */
    val flameRatios: List<Double> = emptyList(),
)

/** In-process state shared between [MonitorService] and the UI. */
object MonitorState {
    val mode = MutableStateFlow(Mode.STOPPED)
    val live = MutableStateFlow<LiveReading?>(null)
    /** The watched flame boxes. */
    val flames = MutableStateFlow<List<FlameRegion>>(emptyList())
    /** Bright blobs in the latest frame, drawn faintly so the user can tap a missed flame. */
    val blobs = MutableStateFlow<List<NormRect>>(emptyList())
    /** Set by the monitor screen while its camera preview is visible. */
    val previewSurface = MutableStateFlow<Preview.SurfaceProvider?>(null)
    /** One-off status messages for the UI (calibration result, errors). */
    val message = MutableStateFlow<String?>(null)
    val queueSize = MutableStateFlow(0)
    val lastTelegramError = MutableStateFlow<String?>(null)
    /** Set when the camera paused with the screen off: keep the screen on for the rest of the night. */
    val forceScreenOn = MutableStateFlow(false)
}

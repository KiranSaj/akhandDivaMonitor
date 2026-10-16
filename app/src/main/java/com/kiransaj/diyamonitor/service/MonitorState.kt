package com.kiransaj.diyamonitor.service

import androidx.camera.core.Preview
import com.kiransaj.diyamonitor.core.FrameMetrics
import com.kiransaj.diyamonitor.core.NormRect
import com.kiransaj.diyamonitor.core.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow

enum class Mode { STOPPED, PREVIEW, CALIBRATING, MONITORING }

data class LiveReading(
    val metrics: FrameMetrics,
    /** Present while monitoring. */
    val snapshot: Snapshot? = null,
    /** 0..1 while calibrating. */
    val calibrationProgress: Float? = null,
)

/** In-process state shared between [MonitorService] and the UI. */
object MonitorState {
    val mode = MutableStateFlow(Mode.STOPPED)
    val live = MutableStateFlow<LiveReading?>(null)
    val roi = MutableStateFlow(NormRect.DEFAULT)
    /** Set by the monitor screen while its camera preview is visible. */
    val previewSurface = MutableStateFlow<Preview.SurfaceProvider?>(null)
    /** One-off status messages for the UI (calibration result, errors). */
    val message = MutableStateFlow<String?>(null)
    val queueSize = MutableStateFlow(0)
    val lastTelegramError = MutableStateFlow<String?>(null)
}

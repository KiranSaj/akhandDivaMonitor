package com.kiransaj.diyamonitor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kiransaj.diyamonitor.MainActivity
import com.kiransaj.diyamonitor.R
import com.kiransaj.diyamonitor.core.FlameRegion
import com.kiransaj.diyamonitor.data.Prefs
import com.kiransaj.diyamonitor.databinding.FragmentMonitorBinding
import com.kiransaj.diyamonitor.service.Mode
import com.kiransaj.diyamonitor.service.MonitorService
import com.kiransaj.diyamonitor.service.MonitorState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

class MonitorFragment : Fragment() {

    private var _binding: FragmentMonitorBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMonitorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = Prefs(requireContext())
        val hasCamera = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCamera) {
            binding.txtMessage.text = "Camera permission is needed. Grant it in Android Settings → Apps → Diya Monitor → Permissions."
            listOf(binding.btnCalibrate, binding.btnStart, binding.btnStop, binding.btnNight).forEach { it.isEnabled = false }
            return
        }

        binding.previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        binding.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        MonitorService.send(requireContext(), MonitorService.ACTION_PREVIEW)
        MonitorState.previewSurface.value = binding.previewView.surfaceProvider

        binding.flameOverlay.onTap = { x, y -> onPreviewTapped(x, y) }

        binding.btnCalibrate.setOnClickListener { MonitorService.send(requireContext(), MonitorService.ACTION_FIND) }
        binding.btnStart.setOnClickListener {
            if (!prefs.telegramConfigured) {
                MonitorState.message.value = "Set up Telegram first (Setup tab)."
                (activity as? MainActivity)?.selectTab(R.id.nav_setup)
            } else {
                MonitorService.send(requireContext(), MonitorService.ACTION_START)
            }
        }
        binding.btnStop.setOnClickListener { MonitorService.send(requireContext(), MonitorService.ACTION_STOP) }
        binding.btnNight.setOnClickListener { (activity as? MainActivity)?.setNightMode(true) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(MonitorState.mode, MonitorState.live) { mode, live -> mode to live }.collect { (mode, live) ->
                        binding.txtReadout.text = Format.long(mode, live, prefs.baseline)
                        val busy = mode == Mode.FINDING || mode == Mode.CALIBRATING
                        binding.btnCalibrate.isEnabled = mode == Mode.PREVIEW
                        binding.btnStart.isEnabled = mode == Mode.PREVIEW
                        binding.btnStop.isEnabled = mode == Mode.MONITORING || busy
                        binding.flameOverlay.editable = mode == Mode.PREVIEW
                        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
                        binding.progress.progress = ((live?.calibrationProgress ?: 0f) * 100).toInt()
                    }
                }
                launch { MonitorState.flames.collect { binding.flameOverlay.flames = it } }
                launch { MonitorState.blobs.collect { binding.flameOverlay.blobs = it } }
                launch {
                    combine(MonitorState.message, MonitorState.queueSize, MonitorState.lastTelegramError) { msg, q, err ->
                        listOfNotNull(
                            msg,
                            if (q > 0) "Telegram: $q message(s) waiting to send" else null,
                            err?.let { "Telegram error: $it" },
                        ).joinToString("\n")
                    }.collect { binding.txtMessage.text = it }
                }
            }
        }
    }

    /**
     * Tap on a box: remove it. Tap elsewhere: add a box around the nearest bright spot
     * (or a default box at the tap if there is none).
     */
    private fun onPreviewTapped(x: Float, y: Float) {
        val current = MonitorState.flames.value
        val hit = current.indexOfFirst { it.contains(x, y) }
        val updated = if (hit >= 0) {
            current.filterIndexed { i, _ -> i != hit }
        } else {
            if (current.size >= prefs.expectedFlames) {
                MonitorState.message.value = "Already ${current.size} flame box(es). Tap a wrong box to remove it first " +
                    "(or change the number of flames in Settings)."
                return
            }
            val near = MonitorState.blobs.value
                .map { it to max(abs((it.left + it.right) / 2 - x), abs((it.top + it.bottom) / 2 - y)) }
                .filter { it.second < TAP_RADIUS }
                .minByOrNull { it.second }?.first
            current + (if (near != null) FlameRegion.around(near) else FlameRegion.atTap(x, y))
        }
        MonitorState.flames.value = FlameRegion.separate(updated)
        MonitorService.send(requireContext(), MonitorService.ACTION_FLAMES_EDITED)
    }

    override fun onDestroyView() {
        if (MonitorState.previewSurface.value === binding.previewView.surfaceProvider) {
            MonitorState.previewSurface.value = null
        }
        context?.let { MonitorService.send(it, MonitorService.ACTION_RELEASE) }
        _binding = null
        super.onDestroyView()
    }

    companion object {
        /** How close (normalised) a tap must be to a bright spot to snap to it. */
        private const val TAP_RADIUS = 0.08f
    }
}

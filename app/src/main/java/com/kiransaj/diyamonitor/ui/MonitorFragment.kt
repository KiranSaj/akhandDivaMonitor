package com.kiransaj.diyamonitor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kiransaj.diyamonitor.MainActivity
import com.kiransaj.diyamonitor.R
import com.kiransaj.diyamonitor.data.Prefs
import com.kiransaj.diyamonitor.databinding.FragmentMonitorBinding
import com.kiransaj.diyamonitor.service.Mode
import com.kiransaj.diyamonitor.service.MonitorService
import com.kiransaj.diyamonitor.service.MonitorState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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

        MonitorState.roi.value = prefs.roi
        binding.roiOverlay.roi = prefs.roi
        binding.roiOverlay.onRoiChanged = { roi ->
            MonitorState.roi.value = roi
            prefs.roi = roi
            prefs.baseline = null
            MonitorState.message.value = "Box changed — press Calibrate (takes 60 s)."
        }

        binding.btnCalibrate.setOnClickListener { MonitorService.send(requireContext(), MonitorService.ACTION_CALIBRATE) }
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
                        val calibrated = prefs.baseline != null
                        binding.btnCalibrate.isEnabled = mode == Mode.PREVIEW
                        binding.btnStart.isEnabled = mode == Mode.PREVIEW && calibrated
                        binding.btnStop.isEnabled = mode == Mode.MONITORING
                        binding.roiOverlay.editable = mode == Mode.PREVIEW
                        val p = live?.calibrationProgress
                        binding.progress.visibility = if (mode == Mode.CALIBRATING) View.VISIBLE else View.GONE
                        binding.progress.progress = ((p ?: 0f) * 100).toInt()
                    }
                }
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

    override fun onResume() {
        super.onResume()
        // Keep the screen (and therefore the camera) on while this screen is shown.
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        if (MonitorState.mode.value != Mode.MONITORING) {
            requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onDestroyView() {
        if (MonitorState.previewSurface.value === binding.previewView.surfaceProvider) {
            MonitorState.previewSurface.value = null
        }
        context?.let { MonitorService.send(it, MonitorService.ACTION_RELEASE) }
        _binding = null
        super.onDestroyView()
    }
}

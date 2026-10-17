package com.kiransaj.diyamonitor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.kiransaj.diyamonitor.data.Prefs
import com.kiransaj.diyamonitor.databinding.FragmentSettingsBinding
import com.kiransaj.diyamonitor.service.Mode
import com.kiransaj.diyamonitor.service.MonitorState
import com.kiransaj.diyamonitor.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.reflect.KMutableProperty0

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val p = Prefs(requireContext())
        val c = binding.container

        header(c, "Sensitivity")
        slider(c, "Flame OUT when area below", p::outPct, 5, 30, 1) { "$it% of baseline (for 30 s)" }
        slider(c, "Dimming when area/brightness below", p::dimPct, 30, 90, 5) { "$it% of baseline (for 2 min)" }
        slider(c, "Soot: flame too big above", p::oversizePct, 120, 250, 10) { "$it% of baseline" }
        slider(c, "Flicker sensitivity", p::flickerSensitivity, 1, 5, 1) {
            listOf("very low", "low", "medium", "high", "very high")[it - 1]
        }
        slider(c, "Low oil: 30-min decline of", p::lowOilDeclinePct, 15, 50, 5) { "$it%" }
        slider(c, "Bright-pixel threshold (recalibrate after changing)", p::brightThreshold, 120, 250, 5) { "luma ≥ $it" }

        header(c, "Timing")
        slider(c, "Check interval", p::checkIntervalSec, 1, 10, 1) { "every $it s" }
        slider(c, "Alert cooldown (per alert type)", p::cooldownMin, 5, 60, 5) { "$it min" }
        switch(c, "Heartbeat message", p::heartbeatEnabled)
        slider(c, "Heartbeat every", p::heartbeatHours, 1, 6, 1) { "$it h" }

        header(c, "Other")
        switch(c, "Phone health alerts (battery, charger, temperature)", p::phoneHealthAlerts)
        switch(c, "Quiet test mode (silent Telegram, [TEST] prefix, all timings ×0.1)", p::testMode)

        note(c, "Changes take effect the next time you press Start.")

        header(c, "App updates")
        updateSection(c)
    }

    /** Two-step flow: "Check for update" finds a newer release, then the same button downloads and installs it. */
    private fun updateSection(parent: LinearLayout) {
        val updater = AppUpdater(requireContext())
        parent.addView(TextView(requireContext()).apply { text = "Installed version: ${updater.currentVersion}" })
        val status = TextView(requireContext()).apply { setPadding(0, dp(8), 0, dp(8)) }
        val button = Button(requireContext()).apply { text = "Check for update" }
        parent.addView(button)
        parent.addView(status)

        var pending: AppUpdater.Release? = null
        var busy = false

        fun background(work: suspend () -> Unit) {
            busy = true
            button.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    work()
                } catch (e: Exception) {
                    status.text = "❌ ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    busy = false
                    button.isEnabled = true
                }
            }
        }

        button.setOnClickListener {
            if (busy) return@setOnClickListener
            if (MonitorState.mode.value == Mode.MONITORING) {
                status.text = "Stop monitoring first: installing an update restarts the app."
                return@setOnClickListener
            }
            val release = pending
            if (release == null) {
                status.text = "Checking…"
                background {
                    val latest = withContext(Dispatchers.IO) { updater.latestRelease() }
                    if (latest != null && updater.isNewer(latest)) {
                        pending = latest
                        button.text = "Download and install ${latest.version}"
                    }
                    status.text = when {
                        latest == null -> "No release has been published yet."
                        !updater.isNewer(latest) -> "✅ You have the latest version (${updater.currentVersion})."
                        else -> buildString {
                            append("⬆️ Version ${latest.version} is available.")
                            if (latest.notes.isNotBlank()) append("\n\nWhat's new:\n${latest.notes}")
                        }
                    }
                }
            } else if (!updater.canInstall()) {
                status.text = "Allow Diya Monitor to install updates (switch on \"Allow from this source\"), then come back and press the button again."
                startActivity(updater.installPermissionIntent())
            } else {
                status.text = "Downloading ${release.version}…"
                background {
                    val apk = withContext(Dispatchers.IO) {
                        updater.download(release) { pct ->
                            status.post { status.text = "Downloading ${release.version}… $pct%" }
                        }
                    }
                    status.text = "Downloaded. Tap \"Install\" on the next screen. Your settings are kept."
                    updater.install(apk)
                }
            }
        }
    }

    private fun header(parent: LinearLayout, text: String) {
        parent.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 18f
            setPadding(0, dp(20), 0, dp(4))
        })
    }

    private fun note(parent: LinearLayout, text: String) {
        parent.addView(TextView(requireContext()).apply {
            this.text = text
            alpha = 0.7f
            setPadding(0, dp(16), 0, dp(16))
        })
    }

    private fun slider(
        parent: LinearLayout,
        label: String,
        prop: KMutableProperty0<Int>,
        min: Int,
        max: Int,
        step: Int,
        format: (Int) -> String,
    ) {
        val text = TextView(requireContext()).apply { setPadding(0, dp(10), 0, 0) }
        fun render(v: Int) {
            text.text = "$label: ${format(v)}"
        }
        val seek = SeekBar(requireContext()).apply {
            this.max = (max - min) / step
            progress = ((prop.get().coerceIn(min, max)) - min) / step
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                    val v = min + progress * step
                    render(v)
                    if (fromUser) prop.set(v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        render(prop.get())
        parent.addView(text)
        parent.addView(seek)
    }

    private fun switch(parent: LinearLayout, label: String, prop: KMutableProperty0<Boolean>) {
        parent.addView(SwitchCompat(requireContext()).apply {
            text = label
            isChecked = prop.get()
            setPadding(0, dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, checked -> prop.set(checked) }
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

package com.kiransaj.diyamonitor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import com.kiransaj.diyamonitor.data.Prefs
import com.kiransaj.diyamonitor.databinding.FragmentSettingsBinding
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

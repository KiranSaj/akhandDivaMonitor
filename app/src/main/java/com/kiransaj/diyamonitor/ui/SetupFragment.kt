package com.kiransaj.diyamonitor.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.kiransaj.diyamonitor.data.Prefs
import com.kiransaj.diyamonitor.databinding.FragmentSetupBinding
import com.kiransaj.diyamonitor.telegram.TelegramClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupFragment : Fragment() {

    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs
    private val client = TelegramClient()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = Prefs(requireContext())
        binding.editToken.setText(prefs.botToken)
        binding.editChatId.setText(prefs.chatId)

        binding.btnSave.setOnClickListener {
            save()
            status("Saved.")
        }
        binding.btnFindChat.setOnClickListener {
            save()
            runTelegram("Looking for your chat…") {
                val chat = client.findLatestChat(prefs.botToken)
                if (chat == null) {
                    "No messages found. Open your bot in Telegram, send it any message (e.g. \"hi\"), then try again."
                } else {
                    withContext(Dispatchers.Main) {
                        binding.editChatId.setText(chat.first)
                        prefs.chatId = chat.first
                    }
                    "Found chat ${chat.first} (${chat.second}). Saved — now press \"Send test message\"."
                }
            }
        }
        binding.btnTest.setOnClickListener {
            save()
            runTelegram("Sending…") {
                client.sendMessage(prefs.botToken, prefs.chatId, "🪔 Diya Monitor test message — Telegram alerts are working!")
                "✅ Test message sent. Check Telegram."
            }
        }
        binding.btnBattery.setOnClickListener { requestBatteryExemption() }
    }

    override fun onResume() {
        super.onResume()
        val pm = requireContext().getSystemService(PowerManager::class.java)
        val exempt = pm.isIgnoringBatteryOptimizations(requireContext().packageName)
        binding.txtBatteryStatus.text = if (exempt) "✅ Battery optimisation is OFF for Diya Monitor." else "⚠️ Battery optimisation is ON — Android may stop monitoring overnight."
    }

    private fun save() {
        prefs.botToken = binding.editToken.text.toString()
        prefs.chatId = binding.editChatId.text.toString()
    }

    private fun runTelegram(progress: String, block: suspend () -> String) {
        status(progress)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: Exception) {
                    "❌ ${e.message ?: e.javaClass.simpleName}"
                }
            }
            if (_binding != null) status(result)
        }
    }

    private fun status(text: String) {
        binding.txtStatus.text = text
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val ctx = requireContext()
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

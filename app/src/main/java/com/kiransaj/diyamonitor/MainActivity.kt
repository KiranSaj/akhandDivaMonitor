package com.kiransaj.diyamonitor

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kiransaj.diyamonitor.data.Prefs
import com.kiransaj.diyamonitor.databinding.ActivityMainBinding
import com.kiransaj.diyamonitor.service.Mode
import com.kiransaj.diyamonitor.service.MonitorState
import com.kiransaj.diyamonitor.telegram.TelegramOutbox
import com.kiransaj.diyamonitor.ui.MonitorFragment
import com.kiransaj.diyamonitor.ui.SettingsFragment
import com.kiransaj.diyamonitor.ui.SetupFragment
import com.kiransaj.diyamonitor.ui.Format
import com.kiransaj.diyamonitor.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var lastNightTap = 0L

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Re-create the current screen so it can pick up a newly granted camera permission.
        currentTab()?.let { show(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.bottomNav.setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }
        if (savedInstanceState == null) {
            val start = if (prefs.telegramConfigured) R.id.nav_monitor else R.id.nav_setup
            binding.bottomNav.menu.findItem(start).isChecked = true
            show(start)
        }

        binding.nightOverlay.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastNightTap < 2000) setNightMode(false)
            else Toast.makeText(this, "Tap again to leave night mode", Toast.LENGTH_SHORT).show()
            lastNightTap = now
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.nightOverlay.visibility == View.VISIBLE) setNightMode(false)
                else moveTaskToBack(true) // keep running; don't destroy the monitor screen
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(MonitorState.mode, MonitorState.live) { mode, live -> mode to live }.collect { (mode, live) ->
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    binding.nightText.text = "🪔 $time · ${Format.short(mode, live)}"
                }
            }
        }

        requestPermissions()
        checkForInterruptedSession()
        dailyUpdateCheck()
    }

    /** At most once a day: look for a newer release, show a hint and announce it on Telegram once. */
    private fun dailyUpdateCheck() {
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheckMs < AppUpdater.DAILY_CHECK_MS) return
        lifecycleScope.launch {
            val updater = AppUpdater(this@MainActivity)
            val latest = withContext(Dispatchers.IO) { runCatching { updater.latestRelease() }.getOrNull() }
                ?: return@launch
            prefs.lastUpdateCheckMs = now
            if (!updater.isNewer(latest)) return@launch
            Toast.makeText(this@MainActivity, "Update ${latest.version} available: Settings → Check for update", Toast.LENGTH_LONG).show()
            if (prefs.announcedUpdateVersion != latest.version && prefs.telegramConfigured) {
                prefs.announcedUpdateVersion = latest.version
                val notes = latest.notes.take(800).ifBlank { null }?.let { "\n\nWhat's new:\n$it" }.orEmpty()
                TelegramOutbox.get(this@MainActivity).enqueue(
                    "⬆️ Diya Monitor ${latest.version} is available (installed: ${updater.currentVersion}).\n" +
                        "When monitoring is stopped, open the app → Settings → Check for update.$notes"
                )
            }
        }
    }

    fun show(itemId: Int) {
        val fragment: Fragment = when (itemId) {
            R.id.nav_setup -> SetupFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> MonitorFragment()
        }
        supportFragmentManager.beginTransaction().replace(R.id.fragmentContainer, fragment).commit()
    }

    private fun currentTab(): Int? = binding.bottomNav.selectedItemId.takeIf { it != 0 }

    fun selectTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    /** Black overlay, minimum brightness, screen kept on so the camera keeps running. */
    fun setNightMode(on: Boolean) {
        binding.nightOverlay.visibility = if (on) View.VISIBLE else View.GONE
        binding.bottomNav.visibility = if (on) View.GONE else View.VISIBLE
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun requestPermissions() {
        val wanted = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    /** If monitoring was on but the service is gone, the app was killed: tell the user. */
    private fun checkForInterruptedSession() {
        if (!prefs.monitoringActive) return
        lifecycleScope.launch {
            delay(5_000) // give a system-restarted service a chance to come up first
            if (prefs.monitoringActive && MonitorState.mode.value != Mode.MONITORING) {
                prefs.monitoringActive = false
                TelegramOutbox.get(this@MainActivity).enqueue(
                    "⚠️ Diya Monitor: monitoring was interrupted (the app was closed or killed). Press Start in the app to resume."
                )
                Toast.makeText(this@MainActivity, "Previous monitoring session was interrupted", Toast.LENGTH_LONG).show()
            }
        }
    }
}

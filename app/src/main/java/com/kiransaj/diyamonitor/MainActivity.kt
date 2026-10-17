package com.kiransaj.diyamonitor

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val screenHandler = Handler(Looper.getMainLooper())
    /** True once the screen-off delay has passed without a touch. */
    private var screenIdle = false
    private val screenIdleRunnable = Runnable {
        screenIdle = true
        updateKeepScreenOn()
    }

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
            binding.bottomNav.post { updateKeepScreenOn() }
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

        // These run even while the activity is stopped (screen off), so a forced wake-up can take effect.
        lifecycleScope.launch { MonitorState.mode.collect { armScreenTimer() } }
        lifecycleScope.launch { MonitorState.forceScreenOn.collect { applyForcedScreen(it) } }

        requestPermissions()
        checkForInterruptedSession()
        dailyUpdateCheck()
    }

    override fun onDestroy() {
        screenHandler.removeCallbacks(screenIdleRunnable)
        super.onDestroy()
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

    /** Black overlay at minimum brightness. Whether the screen stays on is decided by [updateKeepScreenOn]. */
    fun setNightMode(on: Boolean) {
        binding.nightOverlay.visibility = if (on) View.VISIBLE else View.GONE
        binding.bottomNav.visibility = if (on) View.GONE else View.VISIBLE
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        updateKeepScreenOn()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        armScreenTimer()
    }

    /** Restarts the "turn the screen off after N minutes" countdown (only while monitoring). */
    private fun armScreenTimer() {
        screenIdle = false
        screenHandler.removeCallbacks(screenIdleRunnable)
        if (MonitorState.mode.value == Mode.MONITORING && prefs.screenAutoOffEnabled) {
            screenHandler.postDelayed(screenIdleRunnable, prefs.screenOffMinutes * 60_000L)
        }
        updateKeepScreenOn()
    }

    /**
     * Screen policy: always on while finding/calibrating or when forced (the camera paused with the
     * screen off). While monitoring, on until N minutes after the last touch, then the phone's own
     * timeout turns it off and locks it. Otherwise on while the Monitor tab is shown.
     */
    fun updateKeepScreenOn() {
        val mode = MonitorState.mode.value
        val keepOn = when {
            MonitorState.forceScreenOn.value -> true
            mode == Mode.MONITORING -> !(prefs.screenAutoOffEnabled && screenIdle)
            mode == Mode.FINDING || mode == Mode.CALIBRATING -> true
            else -> binding.bottomNav.selectedItemId == R.id.nav_monitor
        }
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** After a camera pause with the screen off: show over the lock screen, wake up, stay on and dark. */
    @Suppress("DEPRECATION")
    private fun applyForcedScreen(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(flags) else window.clearFlags(flags)
        }
        if (on) setNightMode(true) else updateKeepScreenOn()
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
            val running = MonitorState.mode.value in setOf(Mode.MONITORING, Mode.FINDING, Mode.CALIBRATING)
            if (prefs.monitoringActive && !running) {
                prefs.monitoringActive = false
                TelegramOutbox.get(this@MainActivity).enqueue(
                    "⚠️ Diya Monitor: monitoring was interrupted (the app was closed or killed). Press Start in the app to resume."
                )
                Toast.makeText(this@MainActivity, "Previous monitoring session was interrupted", Toast.LENGTH_LONG).show()
            }
        }
    }
}

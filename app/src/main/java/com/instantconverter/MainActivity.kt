package com.instantconverter

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val PREFS = "settings"
        private const val KEY_BOOT = "start_at_boot"

        fun startAtBoot(context: Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_BOOT, true)

        fun isAccessibilityEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, SelectionAccessibilityService::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == me
            }
        }
    }

    private lateinit var overlayStatus: TextView
    private lateinit var overlayButton: Button
    private lateinit var accessStatus: TextView
    private lateinit var accessButton: Button
    private lateinit var notifRow: View
    private lateinit var notifStatus: TextView
    private lateinit var notifButton: Button
    private lateinit var toggleBubble: Button
    private lateinit var rateText: TextView

    private fun <T : View> view(id: Int): T = findViewById<T>(id)!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayStatus = view(R.id.overlay_status)
        overlayButton = view(R.id.overlay_button)
        accessStatus = view(R.id.access_status)
        accessButton = view(R.id.access_button)
        notifRow = view(R.id.notif_row)
        notifStatus = view(R.id.notif_status)
        notifButton = view(R.id.notif_button)
        toggleBubble = view(R.id.toggle_bubble)
        rateText = view(R.id.rate_text)

        overlayButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        accessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        view<Button>(R.id.app_info_button).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        notifButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        toggleBubble.setOnClickListener {
            if (BubbleService.instance != null) BubbleService.stop(this) else BubbleService.start(this)
            toggleBubble.postDelayed({ refreshUi() }, 300)
        }

        val bootSwitch = view<Switch>(R.id.boot_switch)
        bootSwitch.isChecked = startAtBoot(this)
        bootSwitch.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_BOOT, checked).apply()
        }

        val input = view<EditText>(R.id.try_input)
        view<Button>(R.id.try_button).setOnClickListener {
            val text = input.text.toString()
            if (BubbleService.convert(this, text, explicit = true)) {
                moveTaskToBack(true)
            } else {
                android.widget.Toast.makeText(this, R.string.need_overlay, android.widget.Toast.LENGTH_LONG).show()
            }
        }

        view<Button>(R.id.refresh_rate).setOnClickListener {
            rateText.text = getString(R.string.rate_updating)
            RateRepository.refresh(this, force = true) { refreshUi() }
        }

        RateRepository.refresh(this) { refreshUi() }
    }

    override fun onResume() {
        super.onResume()
        // Display the bubble as soon as the app is launched.
        if (Settings.canDrawOverlays(this) && BubbleService.instance == null) {
            BubbleService.start(this)
        }
        refreshUi()
        toggleBubble.postDelayed({ refreshUi() }, 400)
    }

    private fun refreshUi() {
        val overlay = Settings.canDrawOverlays(this)
        overlayStatus.text = getString(if (overlay) R.string.status_granted else R.string.status_missing)
        overlayButton.visibility = if (overlay) View.GONE else View.VISIBLE

        val access = isAccessibilityEnabled(this)
        accessStatus.text = getString(if (access) R.string.status_enabled else R.string.status_disabled)
        accessButton.visibility = if (access) View.GONE else View.VISIBLE

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            notifRow.visibility = View.VISIBLE
            notifStatus.text = getString(if (granted) R.string.status_granted else R.string.status_missing)
            notifButton.visibility = if (granted) View.GONE else View.VISIBLE
        } else {
            notifRow.visibility = View.GONE
        }

        toggleBubble.isEnabled = overlay
        toggleBubble.text = getString(
            if (BubbleService.instance != null) R.string.hide_bubble else R.string.show_bubble
        )

        val conv = RateRepository.converter(this)
        val updated = RateRepository.lastUpdate(this)
        val updatedText = if (updated == 0L) getString(R.string.rate_default)
        else getString(
            R.string.rate_updated,
            DateUtils.getRelativeTimeSpanString(updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        )
        rateText.text = "${conv.formatRate(Currency.EUR)} (${getString(R.string.rate_fixed)})\n" +
            "${conv.formatRate(Currency.USD)} ($updatedText)"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshUi()
    }
}

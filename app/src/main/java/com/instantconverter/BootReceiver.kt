package com.instantconverter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Shows the bubble again after the phone restarts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            if (MainActivity.startAtBoot(context)) BubbleService.start(context)
        }
    }
}

package com.example.eyeprotect.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MonitoringRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != MonitoringForegroundService.ACTION_RESTART) return
        if (!MonitoringForegroundService.shouldAutoRestart(context)) return
        MonitoringForegroundService.start(context)
    }
}

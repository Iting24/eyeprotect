package com.example.eyeprotect.monitoring

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import com.example.eyeprotect.PreferenceKeys

class NightShiftOverlayService : Service(), SensorEventListener {

    private lateinit var windowManager: WindowManager
    private lateinit var sensorManager: SensorManager
    private lateinit var prefs: android.content.SharedPreferences
    private var lightSensor: Sensor? = null
    private var overlayView: View? = null
    private var lastAppliedColor: Int? = null
    private var currentMode: NightShiftMode = NightShiftMode.AUTO
    private var manualWarmth: Float = NightShiftProfiles.DEFAULT_MANUAL_WARMTH
    private var lastLux: Float = DEFAULT_LUX

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        prefs = getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        reloadConfig()
        showOverlay()
        applyCurrentProfile()

        if (currentMode == NightShiftMode.AUTO) {
            startLightMonitoring()
        } else {
            stopLightMonitoring()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLightMonitoring()
        hideOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        if (overlayView != null) return

        val view = View(this)
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (_: Exception) {
            overlayView = null
            stopSelf()
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Exception) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        lastAppliedColor = null
    }

    private fun reloadConfig() {
        currentMode = NightShiftMode.fromPref(
            prefs.getString(PreferenceKeys.PREF_NIGHT_SHIFT_MODE, NightShiftMode.AUTO.prefValue)
        )
        manualWarmth = prefs.getFloat(
            PreferenceKeys.PREF_NIGHT_SHIFT_MANUAL_WARMTH,
            NightShiftProfiles.DEFAULT_MANUAL_WARMTH
        ).coerceIn(0f, 1f)
    }

    private fun startLightMonitoring() {
        val sensor = lightSensor ?: return
        sensorManager.unregisterListener(this)
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun stopLightMonitoring() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (currentMode != NightShiftMode.AUTO) return
        lastLux = event?.values?.firstOrNull() ?: return
        applyCurrentProfile()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun applyCurrentProfile() {
        val color = when (currentMode) {
            NightShiftMode.AUTO -> NightShiftProfiles.colorForAutoLux(lastLux, prefs)
            NightShiftMode.MANUAL -> NightShiftProfiles.colorForManualWarmth(manualWarmth)
        }
        if (lastAppliedColor == color) return
        overlayView?.setBackgroundColor(color)
        lastAppliedColor = color
    }

    companion object {
        private const val DEFAULT_LUX = 80f

        fun start(context: Context) {
            val intent = Intent(context, NightShiftOverlayService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NightShiftOverlayService::class.java))
        }
    }
}

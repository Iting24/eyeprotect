package com.example.eyeprotect.monitoring

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class EyeExerciseOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val seconds = intent?.getIntExtra(EXTRA_SECONDS, 30) ?: 30
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        showOverlay(seconds)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopSelf() }, seconds * 1000L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay(seconds: Int) {
        if (overlayView != null) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xCC000000.toInt())
        }
        val title = TextView(this).apply {
            text = "眼球體操（${seconds}s）"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
        }
        val tip = TextView(this).apply {
            text = "請跟著節奏眨眼與轉動眼球。"
            setTextColor(0xFFE0E0E0.toInt())
        }
        val close = Button(this).apply {
            text = "結束"
            setOnClickListener { stopSelf() }
        }
        container.addView(title)
        container.addView(tip)
        container.addView(close)
        overlayView = container

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 120
        }

        try {
            windowManager.addView(overlayView, params)
        } catch (_: Exception) {
            overlayView = null
            stopSelf()
        }
    }

    private fun hideOverlay() {
        val v = overlayView ?: return
        try {
            windowManager.removeViewImmediate(v)
        } catch (_: Exception) {
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
            }
        }
        overlayView = null
    }

    companion object {
        private const val EXTRA_SECONDS = "seconds"

        fun start(context: Context, seconds: Int) {
            val intent = Intent(context, EyeExerciseOverlayService::class.java).putExtra(EXTRA_SECONDS, seconds)
            context.startService(intent)
        }
    }
}

package com.example.eyeprotect

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.eyeprotect.monitoring.TrueToneOverlayService
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        findViewById<BottomNavigationView>(R.id.bottom_nav).setupWithNavController(navHost.navController)

        syncTrueToneOverlay()
    }

    override fun onResume() {
        super.onResume()
        syncTrueToneOverlay()
    }

    private fun syncTrueToneOverlay() {
        val prefs = getSharedPreferences(PreferenceKeys.PREFS_NAME, MODE_PRIVATE)
        val trueToneEnabled = prefs.getBoolean(PreferenceKeys.PREF_TRUE_TONE_ENABLED, false)
        val hasOverlayPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

        when {
            trueToneEnabled && hasOverlayPermission -> TrueToneOverlayService.start(this)
            else -> TrueToneOverlayService.stop(this)
        }
    }
}

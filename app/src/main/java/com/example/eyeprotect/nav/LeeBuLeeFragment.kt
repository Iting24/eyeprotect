package com.example.eyeprotect.nav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.eyeprotect.R
import com.example.eyeprotect.eyeleebulee.service.LeeBuLeeMonitoringService
import com.example.eyeprotect.ui.theme.EyeprotectTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LeeBuLeeFragment : Fragment() {
    private var monitoringEnabled by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = requiredRuntimePermissions().all { results[it] == true || hasPermission(it) }
            if (allGranted) {
                checkOverlayPermissionAndStart()
            } else {
                monitoringEnabled = false
                Toast.makeText(requireContext(), "權限不足，無法啟動走路安全護衛", Toast.LENGTH_SHORT).show()
            }
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(requireContext())) {
                startMonitoring()
            } else {
                monitoringEnabled = false
                Toast.makeText(requireContext(), "需要懸浮窗權限才能顯示安全泡泡", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setContent {
                EyeprotectTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        LeeBuLeeScreen(
                            monitoringEnabled = monitoringEnabled,
                            onMonitoringChange = { enabled ->
                                if (enabled) {
                                    requestPermissionsAndStart()
                                } else {
                                    stopMonitoring()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val missingPermissions = requiredRuntimePermissions().filterNot(::hasPermission)
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            checkOverlayPermissionAndStart()
        }
    }

    private fun checkOverlayPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(requireContext())) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        ContextCompat.startForegroundService(
            requireContext(),
            Intent(requireContext(), LeeBuLeeMonitoringService::class.java)
        )
        monitoringEnabled = true
        Toast.makeText(requireContext(), "走路安全護衛已啟動", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        requireContext().stopService(Intent(requireContext(), LeeBuLeeMonitoringService::class.java))
        monitoringEnabled = false
        Toast.makeText(requireContext(), "走路安全護衛已關閉", Toast.LENGTH_SHORT).show()
    }

    private fun requiredRuntimePermissions(): List<String> {
        return buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun LeeBuLeeScreen(
    monitoringEnabled: Boolean,
    onMonitoringChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(stringResource(id = R.string.title_leebulee), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(id = R.string.leebulee_subtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Switch(
                checked = monitoringEnabled,
                onCheckedChange = onMonitoringChange
            )
            Text(
                text = if (monitoringEnabled) {
                    stringResource(id = R.string.leebulee_monitoring_on)
                } else {
                    stringResource(id = R.string.leebulee_monitoring_off)
                },
                style = MaterialTheme.typography.titleMedium
            )
        }
        Text(
            text = stringResource(id = R.string.leebulee_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

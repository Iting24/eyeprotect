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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
                    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF55D7CB)) {
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFFC8FFF5),
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 3.dp
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                Surface(
                    modifier = Modifier.size(70.dp),
                    color = Color.White,
                    shape = CircleShape,
                    shadowElevation = 2.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("👀", style = MaterialTheme.typography.headlineMedium)
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = "走路安全護衛",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color(0xFF173B39),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "讓眼睛寶寶陪你注意前方",
                        color = Color(0xFF416866),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shape = RoundedCornerShape(22.dp),
            shadowElevation = 3.dp
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (monitoringEnabled) "安全護衛正在陪你" else "開啟安全護衛",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF173B39),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (monitoringEnabled) {
                                stringResource(id = R.string.leebulee_monitoring_on)
                            } else {
                                stringResource(id = R.string.leebulee_monitoring_off)
                            },
                            color = if (monitoringEnabled) Color(0xFF247E72) else Color(0xFF6B7D7B),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = monitoringEnabled,
                        onCheckedChange = onMonitoringChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF43BFAF)
                        )
                    )
                }

                Surface(color = Color(0xFFDDF2FF), shape = RoundedCornerShape(16.dp)) {
                    Text(
                        text = "小泡泡會留在畫面旁邊，拖曳可以移動；點一下可開關安全畫面。",
                        modifier = Modifier.padding(14.dp),
                        color = Color(0xFF36536B),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White.copy(alpha = 0.94f),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "使用方式",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF173B39),
                    fontWeight = FontWeight.SemiBold
                )
                Text("① 開啟護衛並允許相機與懸浮窗權限", color = Color(0xFF556B68))
                Text("② 走路低頭時會顯示透明安全畫面", color = Color(0xFF556B68))
                Text("③ 不使用時回到這裡關閉即可", color = Color(0xFF556B68))
                Text(
                    text = stringResource(id = R.string.leebulee_description),
                    color = Color(0xFF7A8C89),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

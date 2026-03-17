package com.example.eyeprotect.nav

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.eyeprotect.ui.theme.EyeprotectTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class DashboardFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val viewModel: DashboardViewModel by viewModels()
        return ComposeView(requireContext()).apply {
            setContent { DashboardRoute(viewModel) }
        }
    }
}

@Composable
private fun DashboardRoute(viewModel: DashboardViewModel) {
    EyeprotectTheme(darkTheme = true) {
        Surface(modifier = Modifier.fillMaxSize()) {
            DashboardScreen(viewModel = viewModel)
        }
    }
}

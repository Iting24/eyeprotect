package com.example.eyeprotect.nav

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.eyeprotect.R

fun NavController.returnToDashboard() {
    if (!popBackStack(R.id.dashboardFragment, false)) {
        navigate(R.id.dashboardFragment)
    }
}

@Composable
fun BackToDashboardButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onBack,
        modifier = modifier.heightIn(min = 44.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "<",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "返回",
                modifier = Modifier.padding(start = 4.dp),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

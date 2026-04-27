package com.example.eyeprotect

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI.onNavDestinationSelected
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.example.eyeprotect.ui.theme.EyeprotectTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        val navItems = bottomNav.menu.toBottomNavItems()
        bottomNav.visibility = View.INVISIBLE

        var selectedDestinationId by mutableIntStateOf(
            navController.currentDestination?.id ?: R.id.dashboardFragment
        )
        navController.addOnDestinationChangedListener { _, destination, _ ->
            selectedDestinationId = destination.id
        }

        val composeBottomNav = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                EyeprotectTheme {
                    EyeProtectBottomNavigationBar(
                        items = navItems,
                        selectedDestinationId = selectedDestinationId,
                        onNavigate = { item ->
                            if (selectedDestinationId != item.destinationId) {
                                onNavDestinationSelected(item.menuItem, navController)
                            }
                        }
                    )
                }
            }
        }
        findViewById<ConstraintLayout>(R.id.main_root).addView(
            composeBottomNav,
            ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        )
    }
}

private data class BottomNavItem(
    val destinationId: Int,
    val iconRes: Int,
    val selectedIconRes: Int = iconRes,
    val labelRes: Int,
    val menuItem: MenuItem
)

@Composable
private fun EyeProtectBottomNavigationBar(
    items: List<BottomNavItem>,
    selectedDestinationId: Int,
    onNavigate: (BottomNavItem) -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    val tabBarColor = if (isDarkTheme) Color(0xFF2A2420) else Color(0xFF1A1A1A)

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(10f)
                .padding(start = 32.dp, end = 32.dp, bottom = 24.dp)
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(tabBarColor),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                items.forEach { item ->
                    EyeProtectNavigationItem(
                        item = item,
                        selected = selectedDestinationId == item.destinationId,
                        onClick = { onNavigate(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EyeProtectNavigationItem(
    item: BottomNavItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = if (selected) item.selectedIconRes else item.iconRes),
            contentDescription = stringResource(id = item.labelRes),
            modifier = Modifier.size(22.dp),
            tint = if (selected) Color(0xFF1A1A1A) else Color.White.copy(alpha = 0.5f)
        )
    }
}

private fun android.view.Menu.toBottomNavItems(): List<BottomNavItem> {
    return listOf(
        BottomNavItem(
            destinationId = R.id.dashboardFragment,
            iconRes = R.drawable.ic_nav_dashboard_eye_closed,
            selectedIconRes = R.drawable.ic_nav_dashboard_eye_open,
            labelRes = R.string.nav_dashboard,
            menuItem = findItem(R.id.dashboardFragment)
        ),
        BottomNavItem(
            destinationId = R.id.visionToolFragment,
            iconRes = R.drawable.ic_nav_vision,
            labelRes = R.string.nav_vision_tool,
            menuItem = findItem(R.id.visionToolFragment)
        ),
        BottomNavItem(
            destinationId = R.id.eyeExerciseFragment,
            iconRes = R.drawable.ic_nav_exercise,
            labelRes = R.string.nav_eye_exercise,
            menuItem = findItem(R.id.eyeExerciseFragment)
        ),
        BottomNavItem(
            destinationId = R.id.settingsFragment,
            iconRes = R.drawable.ic_nav_settings,
            labelRes = R.string.nav_settings,
            menuItem = findItem(R.id.settingsFragment)
        )
    )
}

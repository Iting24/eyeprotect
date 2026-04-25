package com.example.eyeprotect

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI.onNavDestinationSelected
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
            ConstraintLayout.LayoutParams(0, 86.dpToPx()).apply {
                marginStart = 22.dpToPx()
                marginEnd = 22.dpToPx()
                bottomMargin = 12.dpToPx()
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        )
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
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
    val indicatorColor = Color(0xFF2F7EF5).copy(alpha = 0.12f)

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0.dp)
    ) {
        items.forEach { item ->
            EyeProtectNavigationItem(
                item = item,
                selected = selectedDestinationId == item.destinationId,
                indicatorColor = indicatorColor,
                onClick = { onNavigate(item) }
            )
        }
    }
}

@Composable
private fun RowScope.EyeProtectNavigationItem(
    item: BottomNavItem,
    selected: Boolean,
    indicatorColor: Color,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        alwaysShowLabel = true,
        icon = {
            Icon(
                painter = painterResource(id = if (selected) item.selectedIconRes else item.iconRes),
                contentDescription = stringResource(id = item.labelRes),
                modifier = Modifier.size(22.dp)
            )
        },
        label = {
            Text(
                text = stringResource(id = item.labelRes),
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color(0xFF2F7EF5),
            selectedTextColor = Color(0xFF2F7EF5),
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            indicatorColor = indicatorColor
        )
    )
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

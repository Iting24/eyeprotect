package com.example.eyeprotect

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.core.view.updateLayoutParams
import com.example.eyeprotect.monitoring.NightShiftOverlayService
import com.example.eyeprotect.ui.theme.EyeDesignTokens
import com.example.eyeprotect.ui.theme.EyeprotectTheme
import com.google.android.material.bottomnavigation.BottomNavigationView
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
        configureBottomNavSpacer(bottomNav)
        val navItems = bottomNavItems()
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
                                navController.navigate(
                                    item.destinationId,
                                    null,
                                    NavOptions.Builder()
                                        .setLaunchSingleTop(true)
                                        .setRestoreState(true)
                                        .setPopUpTo(navController.graph.startDestinationId, false, true)
                                        .build()
                                )
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

        syncNightShiftOverlay()
    }

    override fun onResume() {
        super.onResume()
        syncNightShiftOverlay()
    }

    private fun syncNightShiftOverlay() {
        val prefs = getSharedPreferences(PreferenceKeys.PREFS_NAME, MODE_PRIVATE)
        val nightShiftEnabled = prefs.getBoolean(PreferenceKeys.PREF_NIGHT_SHIFT_ENABLED, false)
        val hasOverlayPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

        when {
            nightShiftEnabled && hasOverlayPermission -> NightShiftOverlayService.start(this)
            else -> NightShiftOverlayService.stop(this)
        }
    }
}

private val BottomNavMaxWidth = 348.dp
private val BottomNavLandscapeMaxWidth = 420.dp

private data class BottomNavItem(
    val destinationId: Int,
    val iconRes: Int,
    val selectedIconRes: Int = iconRes,
    val labelRes: Int
)

@Composable
private fun EyeProtectBottomNavigationBar(
    items: List<BottomNavItem>,
    selectedDestinationId: Int,
    onNavigate: (BottomNavItem) -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val elevation = EyeDesignTokens.elevation
    val pillBackground = Color(0xFF1C1C1E)
    val navShape = RoundedCornerShape(40.dp)
    val barHeight = if (isLandscape) 54.dp else 62.dp
    val bottomPadding = if (isLandscape) 8.dp else 12.dp
    val maxWidth = if (isLandscape) BottomNavLandscapeMaxWidth else BottomNavMaxWidth

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(10f)
                .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding)
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .height(barHeight)
                .shadow(
                    elevation = elevation.high + 2.dp,
                    shape = navShape,
                    ambientColor = Color(0xFF000000).copy(alpha = 0.16f),
                    spotColor = Color(0xFF000000).copy(alpha = 0.10f)
                )
                .border(
                    border = BorderStroke(0.5.dp, Color(0xFF3A3A3C)),
                    shape = navShape
                )
                .clip(navShape)
                .background(pillBackground),
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
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val animatedSize by animateDpAsState(
        targetValue = when {
            isLandscape && selected -> 42.dp
            isLandscape -> 38.dp
            selected -> 46.dp
            else -> 42.dp
        },
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "navItemSize"
    )
    val tint by animateColorAsState(
        targetValue = if (selected) Color(0xFF000000) else Color(0xFF8E8E93),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "navItemTint"
    )

    Box(
        modifier = Modifier
            .size(animatedSize)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(if (isLandscape) 36.dp else 40.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = item.selectedIconRes),
                    contentDescription = stringResource(id = item.labelRes),
                    modifier = Modifier.size(if (isLandscape) 18.dp else 20.dp),
                    tint = tint
                )
            }
        } else {
            Icon(
                painter = painterResource(id = item.iconRes),
                contentDescription = stringResource(id = item.labelRes),
                modifier = Modifier.size(if (isLandscape) 18.dp else 20.dp),
                tint = tint
            )
        }
    }
}

private fun bottomNavItems(): List<BottomNavItem> {
    return listOf(
        BottomNavItem(
            destinationId = R.id.dashboardFragment,
            iconRes = R.drawable.ic_nav_dashboard_eye_closed,
            selectedIconRes = R.drawable.ic_nav_dashboard_eye_open,
            labelRes = R.string.nav_dashboard
        ),
        BottomNavItem(
            destinationId = R.id.visionToolFragment,
            iconRes = R.drawable.ic_nav_vision,
            labelRes = R.string.nav_vision_tool
        ),
        BottomNavItem(
            destinationId = R.id.eyeExerciseFragment,
            iconRes = R.drawable.ic_nav_exercise,
            labelRes = R.string.nav_eye_exercise
        ),
        BottomNavItem(
            destinationId = R.id.acupressureFragment,
            iconRes = R.drawable.ic_nav_acupressure,
            labelRes = R.string.nav_acupressure
        ),
        BottomNavItem(
            destinationId = R.id.leeBuLeeFragment,
            iconRes = R.drawable.ic_nav_leebulee,
            labelRes = R.string.nav_leebulee
        ),
        BottomNavItem(
            destinationId = R.id.settingsFragment,
            iconRes = R.drawable.ic_nav_settings,
            labelRes = R.string.nav_settings
        )
    )
}

private fun MainActivity.configureBottomNavSpacer(bottomNav: BottomNavigationView) {
    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val density = resources.displayMetrics.density
    val spacerHeightPx = ((if (isLandscape) 64 else 86) * density).toInt()
    val spacerBottomMarginPx = ((if (isLandscape) 8 else 12) * density).toInt()
    bottomNav.updateLayoutParams<ViewGroup.MarginLayoutParams> {
        height = spacerHeightPx
        bottomMargin = spacerBottomMarginPx
    }
}

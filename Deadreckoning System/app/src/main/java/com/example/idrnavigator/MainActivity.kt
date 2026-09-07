package com.example.idrnavigator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.idrnavigator.logging.CsvLogger
import com.example.idrnavigator.sensors.GnssManager
import com.example.idrnavigator.sensors.ImuSensorManager
import com.example.idrnavigator.ui.DataCollectionScreen
import com.example.idrnavigator.ui.NavigationHudScreen
import com.example.idrnavigator.ui.NavigationViewModel
import com.example.idrnavigator.ui.theme.CockpitBackground
import com.example.idrnavigator.ui.theme.CockpitPrimaryText
import com.example.idrnavigator.ui.theme.CockpitSurface
import com.example.idrnavigator.ui.theme.IDRNavigatorTheme
import com.example.idrnavigator.ui.theme.VehicleMarkerAccent

class MainActivity : ComponentActivity() {
    private lateinit var imuSensorManager: ImuSensorManager
    private lateinit var gnssManager: GnssManager
    private lateinit var csvLogger: CsvLogger
    private lateinit var navigationViewModel: NavigationViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize osmdroid with high-performance caching and descriptive user agent
        val osmConfig = org.osmdroid.config.Configuration.getInstance()
        val osmBase = java.io.File(filesDir, "osmdroid")
        val osmTiles = java.io.File(osmBase, "tiles")
        if (!osmBase.exists()) osmBase.mkdirs()
        if (!osmTiles.exists()) osmTiles.mkdirs()

        // Clean out previously cached 403 / watermarked tiles once
        val cleanupMarker = java.io.File(filesDir, ".cleaned_tiles_v2")
        if (!cleanupMarker.exists()) {
            java.io.File(osmTiles, "cache.db").delete()
            java.io.File(osmTiles, "cache.db-journal").delete()
            try { cleanupMarker.createNewFile() } catch (_: Exception) {}
        }

        osmConfig.osmdroidBasePath = osmBase
        osmConfig.osmdroidTileCache = osmTiles

        osmConfig.load(this, getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
        val appUserAgent = "${packageName}/1.0 (Android; Mobile)"
        osmConfig.userAgentValue = appUserAgent
        osmConfig.additionalHttpRequestProperties["User-Agent"] = appUserAgent
        osmConfig.tileDownloadThreads = 2
        osmConfig.tileFileSystemThreads = 2
        osmConfig.cacheMapTileCount = 60
        osmConfig.tileDownloadMaxQueueSize = 30
        osmConfig.expirationExtendedDuration = 1000L * 60 * 60 * 24 * 30 // 30 days cache

        imuSensorManager = ImuSensorManager(this)
        gnssManager = GnssManager(this)
        csvLogger = CsvLogger(this)
        navigationViewModel = NavigationViewModel(this, imuSensorManager, gnssManager)
        
        checkBatteryOptimization()

        enableEdgeToEdge()
        setContent {
            IDRNavigatorTheme {
                MainScreen(
                    navigationViewModel = navigationViewModel,
                    csvLogger = csvLogger
                )
            }
        }
    }

    private fun checkBatteryOptimization() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "Battery optimization prompt skipped or denied", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::imuSensorManager.isInitialized) imuSensorManager.stop()
        if (::gnssManager.isInitialized) gnssManager.stop()
        if (::csvLogger.isInitialized) csvLogger.stop()
    }
}

@Composable
fun MainScreen(
    navigationViewModel: NavigationViewModel,
    csvLogger: CsvLogger
) {
    var currentTab by remember { mutableStateOf(0) }
    var mapStyle by rememberSaveable { mutableStateOf(com.example.idrnavigator.map.AppMapStyle.SATELLITE) }
    
    val context = LocalContext.current
    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || 
                         permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            navigationViewModel.imuSensorManager.start()
            navigationViewModel.gnssManager.start()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            navigationViewModel.imuSensorManager.stop()
            navigationViewModel.gnssManager.stop()
            csvLogger.stop()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = CockpitBackground,
        bottomBar = {
            NavigationBar(
                containerColor = CockpitSurface,
                contentColor = CockpitPrimaryText
            ) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.Place, contentDescription = "HUD") },
                    label = { Text("Driver HUD") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CockpitBackground,
                        selectedTextColor = VehicleMarkerAccent,
                        indicatorColor = VehicleMarkerAccent,
                        unselectedIconColor = CockpitPrimaryText,
                        unselectedTextColor = CockpitPrimaryText
                    )
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Engineering") },
                    label = { Text("Engineering") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CockpitBackground,
                        selectedTextColor = VehicleMarkerAccent,
                        indicatorColor = VehicleMarkerAccent,
                        unselectedIconColor = CockpitPrimaryText,
                        unselectedTextColor = CockpitPrimaryText
                    )
                )
            }
        }
    ) { innerPadding ->
        if (currentTab == 0) {
            NavigationHudScreen(
                viewModel = navigationViewModel,
                mapStyle = mapStyle,
                onMapStyleChanged = { mapStyle = it },
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            DataCollectionScreen(
                viewModel = navigationViewModel,
                csvLogger = csvLogger,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
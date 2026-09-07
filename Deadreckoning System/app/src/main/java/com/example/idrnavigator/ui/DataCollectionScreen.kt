package com.example.idrnavigator.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.idrnavigator.logging.CsvLogger
import com.example.idrnavigator.sensors.GnssManager
import com.example.idrnavigator.sensors.ImuData
import com.example.idrnavigator.sensors.ImuSensorManager
import com.example.idrnavigator.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.example.idrnavigator.service.SensorForegroundService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DataCollectionScreen(
    viewModel: NavigationViewModel,
    csvLogger: CsvLogger,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imuData by viewModel.alignedImuFlow.collectAsState(initial = ImuData())
    val gpsData by viewModel.gnssManager.gpsDataFlow.collectAsState()

    var isRecording by remember { mutableStateOf(false) }
    var loggedCount by remember { mutableStateOf(0) }
    var savedFile by remember { mutableStateOf<File?>(null) }
    var startTime by remember { mutableStateOf(0L) }
    var elapsedTimeStr by remember { mutableStateOf("00:00") }

    LaunchedEffect(isRecording) {
        if (!isRecording) return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                viewModel.alignedImuFlow.collect { currentImu ->
                    csvLogger.logRow(currentImu, viewModel.gnssManager.gpsDataFlow.value)
                }
            }
        } finally {
            csvLogger.stop()
            savedFile = csvLogger.savedFile
        }
    }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            loggedCount = csvLogger.loggedRowCount
            val elapsed = System.currentTimeMillis() - startTime
            val minutes = (elapsed / 1000) / 60
            val seconds = (elapsed / 1000) % 60
            elapsedTimeStr = String.format(Locale.US, "%02d:%02d", minutes, seconds)
            delay(100)
        }
    }

    Scaffold(
        containerColor = CockpitBackground,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Banner: Logging Control Card
            Card(
                colors = CardDefaults.cardColors(containerColor = CockpitSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (isRecording) {
                                    isRecording = false
                                    SensorForegroundService.stop(context)
                                } else {
                                    csvLogger.start()
                                    savedFile = null
                                    loggedCount = 0
                                    startTime = System.currentTimeMillis()
                                    isRecording = true
                                    SensorForegroundService.start(context)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) GnssDegraded else VehicleMarkerAccent,
                                contentColor = CockpitBackground
                            )
                        ) {
                            Text(
                                text = if (isRecording) "Stop Recording" else "Start Recording",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (savedFile != null && !isRecording) {
                            IconButton(
                                onClick = {
                                    val file = savedFile ?: return@IconButton
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/csv"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share CSV"))
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share CSV",
                                    tint = CockpitPrimaryText
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Time: $elapsedTimeStr",
                            color = CockpitSecondaryText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Rows: $loggedCount",
                            color = CockpitSecondaryText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Estimator / Inference Engine Card
            val uiState by viewModel.uiState.collectAsState(initial = NavigationUiState())
            Card(
                colors = CardDefaults.cardColors(containerColor = CockpitSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Dead Reckoning Model",
                            color = CockpitPrimaryText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        FilterChip(
                            selected = uiState.deadReckoningMode == com.example.idrnavigator.fusion.DeadReckoningMode.AI_TCN,
                            onClick = { viewModel.toggleDeadReckoningMode() },
                            label = {
                                Text(
                                    text = if (uiState.deadReckoningMode == com.example.idrnavigator.fusion.DeadReckoningMode.AI_TCN) "AI TCN" else "Classical",
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = InsDeadReckoning,
                                selectedLabelColor = CockpitPrimaryText,
                                containerColor = CockpitBackground,
                                labelColor = CockpitSecondaryText
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (uiState.deadReckoningMode == com.example.idrnavigator.fusion.DeadReckoningMode.AI_TCN) {
                            "Model: tiny_tcn.onnx (ONNX Runtime Mobile)\nInput Tensor: [1, 11, 10] @ 10Hz | Latency: ${uiState.aiLatencyMs} ms\nRaw Predicted Speed: ${"%.1f".format(uiState.rawAiVelocityKmH)} km/h"
                        } else {
                            "Model: Classical Strapdown (Forward Accel Integration + 3D ZUPT)\nHeading: Gyro Z Integration + Mag Filter"
                        },
                        color = CockpitSecondaryText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }


            // Live Sensor Stream Cards
            SensorDataCard(
                title = "Accelerometer (m/s²)",
                x = imuData.accelX, y = imuData.accelY, z = imuData.accelZ
            )
            SensorDataCard(
                title = "Gyroscope (rad/s)",
                x = imuData.gyroX, y = imuData.gyroY, z = imuData.gyroZ
            )
            SensorDataCard(
                title = "Magnetometer (μT)",
                x = imuData.magX, y = imuData.magY, z = imuData.magZ
            )

            // GPS Card
            Card(
                colors = CardDefaults.cardColors(containerColor = CockpitSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "GNSS Telemetry",
                        color = CockpitSecondaryText,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (gpsData.hasFix) {
                        Text(text = "Lat: ${gpsData.lat}", color = CockpitPrimaryText, style = MaterialTheme.typography.bodyMedium)
                        Text(text = "Lon: ${gpsData.lon}", color = CockpitPrimaryText, style = MaterialTheme.typography.bodyMedium)
                        Text(text = "Accuracy: ${gpsData.accuracy} m", color = CockpitPrimaryText, style = MaterialTheme.typography.bodyMedium)
                        Text(text = "Speed: ${gpsData.speed} m/s", color = CockpitPrimaryText, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(text = "Waiting for GNSS fix...", color = GnssDegraded, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun SensorDataCard(title: String, x: Float, y: Float, z: Float) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CockpitSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = CockpitSecondaryText,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "X: ${"%.4f".format(x)}", color = CockpitPrimaryText, style = MaterialTheme.typography.bodyMedium)
                Text(text = "Y: ${"%.4f".format(y)}", color = CockpitPrimaryText, style = MaterialTheme.typography.bodyMedium)
                Text(text = "Z: ${"%.4f".format(z)}", color = CockpitPrimaryText, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

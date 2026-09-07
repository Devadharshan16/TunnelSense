package com.example.idrnavigator.ui.theme

import androidx.compose.ui.graphics.Color

val CockpitBackground = Color(0xFF0E1013)
val CockpitSurface = Color(0xFF1A1D21)
val CockpitPrimaryText = Color(0xFFE8EAED)
val CockpitSecondaryText = Color(0xFF8B9099)
val CockpitDivider = Color(0xFF2A2E35)

val GnssActive = Color(0xFF4FD8E8)         // Cyan — GPS fused active
val GnssDegraded = Color(0xFFF5A623)       // Amber — Transitioning
val InsDeadReckoning = Color(0xFF8E6CFF)   // Electric Violet — Pure INS dead reckoning
val VehicleMarkerAccent = Color(0xFF4FD8E8)

// Drift warning colors (INS mode running too long)
val DriftWarningAmber = Color(0xFFF5A623)   // amber — approaching threshold
val DriftCriticalRed = Color(0xFFFF4444)    // red   — well past threshold
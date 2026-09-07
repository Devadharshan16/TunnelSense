package com.example.idrnavigator.inference

import com.example.idr.core.model.IdrLatLon
import com.example.idrnavigator.sensors.ImuData

typealias LatLon = IdrLatLon

interface PositionEstimator {
    fun estimateVelocity(imuWindow: List<ImuData>): Float
    fun estimateHeading(imuWindow: List<ImuData>, dt: Float, currentMagHeading: Float): Float
    fun estimatePosition(
        lastPosition: LatLon,
        velocity: Float,
        headingDeg: Float,
        deltaTimeSeconds: Float
    ): LatLon
}

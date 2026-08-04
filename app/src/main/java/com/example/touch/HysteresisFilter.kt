package com.example.touch

import kotlin.math.sqrt

class HysteresisFilter(private val deadZonePx: Float = 3f) {
    private var lastX = 0f
    private var lastY = 0f
    private var accumulatedDx = 0f
    private var accumulatedDy = 0f

    fun filter(eventX: Float, eventY: Float): Pair<Float, Float>? {
        val dx = eventX - lastX
        val dy = eventY - lastY
        accumulatedDx += dx
        accumulatedDy += dy

        // Dead zone: ignorar movimientos menores al umbral
        if (sqrt((accumulatedDx * accumulatedDx + accumulatedDy * accumulatedDy).toDouble()) < deadZonePx) {
            return null // No mover
        }

        lastX = eventX
        lastY = eventY
        val result = Pair(accumulatedDx, accumulatedDy)
        accumulatedDx = 0f
        accumulatedDy = 0f
        return result
    }
    
    fun reset() {
        lastX = 0f
        lastY = 0f
        accumulatedDx = 0f
        accumulatedDy = 0f
    }
}

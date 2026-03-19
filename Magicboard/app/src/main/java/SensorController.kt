package com.example.magicboard

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

class SensorController(
    context: Context,
    private val onPrediction: (Int) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "MAGIC_TRICK"

        private const val FACE_DOWN_THRESHOLD = 1.0f
        private const val LIFT_THRESHOLD = 1.2f

        private const val REVEAL_THRESHOLD = 3.0f
        private const val REVEAL_STABLE_MS = 150L

        private const val MIN_DECISION_MS = 700L
        private const val HOLD_TIME = 2000L

        private const val SHAKE_THRESHOLD = 15.0f
        private const val SHAKE_DEBOUNCE_MS = 120L
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val handler = Handler(Looper.getMainLooper())

    private enum class State {
        IDLE,
        WAIT_FACE_DOWN,
        READY,
        LIFTING
    }

    private var state = State.IDLE
    private var stillnessPending = false

    private var liftStart = 0L
    private var holdComplete = false
    private var shakeSeen = false
    private var liftSide = 0
    private var revealSince = 0L
    private var lastShakePeak = 0L

    private val stillnessRunnable = Runnable {
        if (state == State.WAIT_FACE_DOWN && stillnessPending) {
            state = State.READY
            stillnessPending = false
            Log.d(TAG, "STILLNESS OK -> READY")
        }
    }

    fun start() {
        accel?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        Log.d(TAG, "sensors started accel=$accel")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        resetGesture()
        state = State.IDLE
        Log.d(TAG, "sensors stopped")
    }

    fun armTrick() {
        Log.d(TAG, "ARM -> WAIT_FACE_DOWN")
        handler.removeCallbacksAndMessages(null)
        resetGesture()
        state = State.WAIT_FACE_DOWN
    }

    fun disarmTrick() {
        Log.d(TAG, "DISARM")
        handler.removeCallbacksAndMessages(null)
        resetGesture()
        state = State.IDLE
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val mag = sqrt(x * x + y * y)

        Log.d(
            TAG,
            "state=$state mag=${"%.2f".format(mag)} x=${"%.2f".format(x)} y=${"%.2f".format(y)} z=${"%.2f".format(z)} shakes=$shakeSeen"
        )

        when (state) {
            State.IDLE -> Unit

            State.WAIT_FACE_DOWN -> {
                if (mag < FACE_DOWN_THRESHOLD) {
                    if (!stillnessPending) {
                        stillnessPending = true
                        handler.postDelayed(stillnessRunnable, 3000L)
                        Log.d(TAG, "FACE DOWN candidate -> starting stillness timer")
                    }
                } else {
                    if (stillnessPending) {
                        stillnessPending = false
                        handler.removeCallbacks(stillnessRunnable)
                        Log.d(TAG, "Movement while waiting -> cancelled stillness")
                    }
                }
            }

            State.READY -> {
                if (mag > LIFT_THRESHOLD) {
                    liftStart = System.currentTimeMillis()
                    holdComplete = false
                    shakeSeen = false
                    revealSince = 0L
                    lastShakePeak = 0L

                    liftSide = detectSide(x, y)
                    state = State.LIFTING

                    handler.postDelayed({
                        if (state == State.LIFTING) {
                            holdComplete = true
                            Log.d(TAG, "HOLD_COMPLETE")
                        }
                    }, HOLD_TIME)

                    Log.d(TAG, "LIFT_START side=$liftSide")
                }
            }

            State.LIFTING -> {
                val now = System.currentTimeMillis()
                val elapsed = now - liftStart

                if (!holdComplete && elapsed >= HOLD_TIME) {
                    holdComplete = true
                    Log.d(TAG, "HOLD_COMPLETE (fallback)")
                }

                if (mag > SHAKE_THRESHOLD && now - lastShakePeak > SHAKE_DEBOUNCE_MS) {
                    shakeSeen = true
                    lastShakePeak = now
                    Log.d(TAG, "SHAKE_PEAK (mag=${"%.1f".format(mag)})")
                }

                if (mag >= REVEAL_THRESHOLD) {
                    if (revealSince == 0L) {
                        revealSince = now
                        Log.d(TAG, "REVEAL started")
                    }

                    val stableReveal = now - revealSince >= REVEAL_STABLE_MS
                    val decisionWindowReady = elapsed >= MIN_DECISION_MS

                    if (stableReveal && decisionWindowReady) {
                        val result = decideNumber()
                        Log.d(TAG, "RESULT=$result")
                        onPrediction(result)

                        handler.removeCallbacksAndMessages(null)
                        resetGesture()
                        state = State.IDLE
                        Log.d(TAG, "RESET -> IDLE")
                    }
                } else {
                    revealSince = 0L
                }
            }
        }
    }

    private fun detectSide(x: Float, y: Float): Int {
        return if (abs(x) >= abs(y)) {
            if (x < 0f) 2 else 4
        } else {
            if (y > 0f) 1 else 3
        }
    }

    private fun decideNumber(): Int {
        return if (shakeSeen) {
            when (liftSide) {
                1 -> 9
                3 -> 10
                else -> fallbackQuickOrHold()
            }
        } else if (holdComplete) {
            when (liftSide) {
                1 -> 5
                2 -> 6
                3 -> 7
                4 -> 8
                else -> 5
            }
        } else {
            when (liftSide) {
                1 -> 1
                2 -> 2
                3 -> 3
                4 -> 4
                else -> 1
            }
        }
    }

    private fun fallbackQuickOrHold(): Int {
        return if (holdComplete) 5 else 1
    }

    private fun resetGesture() {
        stillnessPending = false
        liftStart = 0L
        holdComplete = false
        shakeSeen = false
        liftSide = 0
        revealSince = 0L
        lastShakePeak = 0L
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
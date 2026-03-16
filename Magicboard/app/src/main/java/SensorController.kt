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

        // tuning constants (adjust if needed)
        private const val FACE_DOWN_THRESHOLD = 1.0f   // mag < this => considered "still on table"
        private const val LIFT_THRESHOLD = 1.2f        // mag > this => started lifting
        private const val SHAKE_THRESHOLD = 18f        // mag > this => shake peak
        private const val STILL_TIME = 3000L           // must be still on table this long to become READY
        private const val HOLD_TIME = 2000L            // hold window after lift start
        private const val REQUIRED_SHAKE_COUNT = 3
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val handler = Handler(Looper.getMainLooper())

    private enum class State { IDLE, WAIT_FACE_DOWN, READY, LIFTING }
    private var state = State.IDLE

    private var stillnessPending = false
    private var liftStart = 0L
    private var holdComplete = false
    private var shakeCount = 0
    private var liftSide = 0

    private val stillnessRunnable = Runnable {
        // only promote if still waiting and stillness was not cancelled
        if (state == State.WAIT_FACE_DOWN && stillnessPending) {
            state = State.READY
            stillnessPending = false
            Log.d(TAG, "STILLNESS OK -> TRICK READY")
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
        state = State.IDLE
        stillnessPending = false
        Log.d(TAG, "sensors stopped")
    }

    fun armTrick() {
        Log.d(TAG, "ARM: entering WAIT_FACE_DOWN")
        handler.removeCallbacksAndMessages(null)
        state = State.WAIT_FACE_DOWN
        stillnessPending = false
    }

    fun disarmTrick() {
        Log.d(TAG, "DISARM")
        handler.removeCallbacksAndMessages(null)
        state = State.IDLE
        stillnessPending = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val mag = sqrt(x * x + y * y)

        Log.d(TAG, "state=$state mag=${"%.2f".format(mag)} x=${"%.2f".format(x)} y=${"%.2f".format(y)} z=${"%.2f".format(z)} shakes=$shakeCount")

        when (state) {
            State.IDLE -> {
                // do nothing
            }

            State.WAIT_FACE_DOWN -> {
                // must be relatively still (low mag) to start the stillness timer
                if (mag < FACE_DOWN_THRESHOLD) {
                    if (!stillnessPending) {
                        stillnessPending = true
                        handler.postDelayed(stillnessRunnable, STILL_TIME)
                        Log.d(TAG, "FACE DOWN candidate -> starting stillness timer")
                    }
                    // if stillnessPending true, keep waiting; do not change state yet
                } else {
                    // movement observed while waiting -> cancel pending stillness
                    if (stillnessPending) {
                        stillnessPending = false
                        handler.removeCallbacks(stillnessRunnable)
                        Log.d(TAG, "Movement while waiting -> cancelled stillness")
                    }
                }
            }

            State.READY -> {
                // Now we only consider a real lift when mag exceeds LIFT_THRESHOLD
                if (mag > LIFT_THRESHOLD) {
                    liftStart = System.currentTimeMillis()
                    holdComplete = false
                    shakeCount = 0
                    liftSide = detectSide(x, y)
                    state = State.LIFTING
                    handler.postDelayed({
                        if (state == State.LIFTING) {
                            holdComplete = true
                            Log.d(TAG, "HOLD window elapsed -> holdComplete=true")
                        }
                    }, HOLD_TIME)
                    Log.d(TAG, "LIFT DETECTED side=$liftSide")
                }
            }

            State.LIFTING -> {
                // count strong peaks as shakes
                if (mag > SHAKE_THRESHOLD) {
                    shakeCount++
                    Log.d(TAG, "SHAKE_PEAK #$shakeCount (mag=${"%.1f".format(mag)})")
                }

                // when user places phone back down (mag falls below face-down threshold)
                if (mag < FACE_DOWN_THRESHOLD) {
                    val result = decideNumber()
                    Log.d(TAG, "PICKUP-END -> result=$result")
                    onPrediction(result)
                    // reset
                    handler.removeCallbacksAndMessages(null)
                    state = State.IDLE
                    stillnessPending = false
                }
            }
        }
    }

    private fun detectSide(x: Float, y: Float): Int {
        // 1 = TOP, 2 = LEFT, 3 = BOTTOM, 4 = RIGHT
        return if (abs(x) > abs(y)) {
            if (x > 0f) 4 else 2 // sign conventions may vary by device; adjust if needed
        } else {
            if (y > 0f) 3 else 1
        }
    }

    private fun decideNumber(): Int {
        // shake priority (apply to top/bottom; mapping uses liftSide convention)
        if (shakeCount >= REQUIRED_SHAKE_COUNT) {
            return when (liftSide) {
                1 -> 9
                3 -> 10
                else -> fallback()
            }
        }
        // hold vs quick
        return if (holdComplete) {
            when (liftSide) {
                1 -> 5
                2 -> 6
                3 -> 7
                4 -> 8
                else -> fallback()
            }
        } else {
            when (liftSide) {
                1 -> 1
                2 -> 2
                3 -> 3
                4 -> 4
                else -> fallback()
            }
        }
    }

    private fun fallback(): Int = 1

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
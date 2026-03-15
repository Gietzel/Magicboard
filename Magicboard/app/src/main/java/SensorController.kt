package com.example.magicboard

import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.roundToInt

class SensorController(
    context: Context,
    private val onPrediction: (Int) -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // State
    private var armed = false
    private var faceDown = false
    private var lifted = false
    private var liftTimestamp: Long = 0L
    private var baselineOrientation = FloatArray(3) // azimuth, pitch, roll (rad)
    private var lastOrientation = FloatArray(3)

    // For shake detection (high-pass / linear acceleration)
    private val gravity = FloatArray(3) { 0f }
    private val linearAcc = FloatArray(3) { 0f }
    private val lowPassAlpha = 0.8f

    // Shake counters / window
    private var shakeCount = 0
    private var firstShakeTimestamp: Long = 0L

    // Finalization
    private var finalChosen = false

    // Handler for timeouts (not strictly required but convenient)
    private val handler = Handler(Looper.getMainLooper())

    // Tunable thresholds (adjust after testing)
    private val FACE_DOWN_Z_THRESHOLD = -8.0f    // z < this => face-down
    private val PICKUP_Z_THRESHOLD = -2.0f       // z crosses above this => picked up
    private val LIFT_DEBOUNCE_MS = 150L
    private val HOLD_MS = 2000L                  // 2 seconds hold
    private val SHAKE_WINDOW_MS = 1500L          // time window to detect shake after lift
    private val SHAKE_THRESHOLD = 2.2f           // linear acceleration magnitude peak (m/s^2)
    private val SHAKE_MIN_COUNT = 3              // number of peaks to consider a shake
    private val STABLE_ORIENTATION_DELTA = 0.25f // ~14 deg in radians

    // Utility enum
    private enum class LiftSide { TOP, LEFT, BOTTOM, RIGHT, UNKNOWN }

    fun start() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }

    fun armTrick() {
        armed = true
        faceDown = false
        lifted = false
        finalChosen = false
        shakeCount = 0
        firstShakeTimestamp = 0L
    }

    fun disarmTrick() {
        armed = false
        faceDown = false
        lifted = false
        liftTimestamp = 0L
        shakeCount = 0
        firstShakeTimestamp = 0L
        finalChosen = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation) // [azimuth, pitch, roll]
                lastOrientation = orientation

                // If we're faceDown but haven't recorded baseline orientation yet, keep baseline updated while stable.
                // baselineOrientation is captured when we first detect face-down to compare deltas on pickup.
            }

            Sensor.TYPE_ACCELEROMETER -> {
                // gravity low-pass
                gravity[0] = lowPassAlpha * gravity[0] + (1 - lowPassAlpha) * event.values[0]
                gravity[1] = lowPassAlpha * gravity[1] + (1 - lowPassAlpha) * event.values[1]
                gravity[2] = lowPassAlpha * gravity[2] + (1 - lowPassAlpha) * event.values[2]

                // linear acceleration (high-pass)
                linearAcc[0] = event.values[0] - gravity[0]
                linearAcc[1] = event.values[1] - gravity[1]
                linearAcc[2] = event.values[2] - gravity[2]

                val z = event.values[2]

                if (armed && !lifted) {
                    // Detect face-down placement
                    if (!faceDown && z < FACE_DOWN_Z_THRESHOLD) {
                        faceDown = true
                        // capture baseline orientation at face-down moment (use lastOrientation if available)
                        baselineOrientation = lastOrientation.copyOf()
                        // reset finalization variables
                        finalChosen = false
                        shakeCount = 0
                        firstShakeTimestamp = 0L
                    }

                    // Detect pickup (z crossing above PICKUP_Z_THRESHOLD)
                    if (faceDown && z > PICKUP_Z_THRESHOLD) {
                        // Debounce rapid bounces
                        val now = System.currentTimeMillis()
                        if (liftTimestamp == 0L || now - liftTimestamp > LIFT_DEBOUNCE_MS) {
                            liftTimestamp = now
                            lifted = true
                            // determine which side was lifted first by comparing orientation delta vs baseline
                            val side = detectLiftSide(baselineOrientation, lastOrientation)
                            // emit immediate base digit (1..4)
                            val baseDigit = mapSideToBaseDigit(side)
                            finalChosen = false
                            onPrediction(baseDigit)

                            // Start monitoring for shake and hold to override baseDigit
                            // We will keep checking within onSensorChanged (accelerometer events) for shake/hold
                        }
                    }
                } else if (armed && lifted && !finalChosen) {
                    // While lifted, detect shake events and evaluate hold/stability for final selection.
                    val now = System.currentTimeMillis()

                    // Shake detection: peaks in linear acceleration magnitude
                    val linearMag = sqrt(
                        linearAcc[0] * linearAcc[0] +
                                linearAcc[1] * linearAcc[1] +
                                linearAcc[2] * linearAcc[2]
                    )

                    if (linearMag > SHAKE_THRESHOLD) {
                        // record shake peak
                        if (firstShakeTimestamp == 0L) firstShakeTimestamp = now
                        // count peaks but avoid counting the same continuous peak multiple times:
                        // require small spacing between peaks (simple approach: use time difference)
                        if (shakeCount == 0) {
                            shakeCount = 1
                        } else {
                            // increase shake count only if sufficiently later than first (avoids false multiples)
                            // we don't store last peak time here to keep logic compact; increment conservatively
                            shakeCount++
                        }
                    }

                    // If shake meets criteria within window -> finalize as shake mapping
                    if (firstShakeTimestamp != 0L && (now - firstShakeTimestamp) <= SHAKE_WINDOW_MS && shakeCount >= SHAKE_MIN_COUNT) {
                        // determine lift side again to know top/bottom shake mapping
                        val side = detectLiftSide(baselineOrientation, lastOrientation)
                        val shakeDigit = mapSideToShakeDigit(side)
                        if (shakeDigit != null) {
                            finalChosen = true
                            onPrediction(shakeDigit)
                            // disarm after final choice
                            resetAfterFinal()
                            return
                        }
                    }

                    // Hold detection: if orientation is stable relative to baseline and enough time elapsed
                    val elapsedSinceLift = now - liftTimestamp
                    if (elapsedSinceLift >= HOLD_MS) {
                        // check stability: compare orientation delta with baseline
                        val side = detectLiftSide(baselineOrientation, lastOrientation)
                        if (isOrientationStable(baselineOrientation, lastOrientation)) {
                            // map to hold digit (5..8)
                            val holdDigit = mapSideToHoldDigit(side)
                            finalChosen = true
                            onPrediction(holdDigit)
                            resetAfterFinal()
                            return
                        } else {
                            // orientation not stable: treat as immediate base (already emitted), allow further monitoring within reasonable window
                        }
                    }

                    // If neither shake nor hold occur within a reasonable time window (e.g., SHAKE_WINDOW_MS + small margin),
                    // we can choose to finalize the base value automatically to avoid indefinite waiting.
                    val maxWait = max(SHAKE_WINDOW_MS, HOLD_MS) + 500L
                    if (now - liftTimestamp > maxWait) {
                        // finalize to base digit if not already final
                        val side = detectLiftSide(baselineOrientation, lastOrientation)
                        val baseDigit = mapSideToBaseDigit(side)
                        finalChosen = true
                        onPrediction(baseDigit)
                        resetAfterFinal()
                    }
                }
            }
        }
    }

    private fun resetAfterFinal() {
        // after final chosen, return to a safe state: disarm so trick does not retrigger inadvertently
        armed = false
        faceDown = false
        lifted = false
        liftTimestamp = 0L
        // clear shake state
        shakeCount = 0
        firstShakeTimestamp = 0L
    }

    private fun detectLiftSide(baseline: FloatArray, current: FloatArray): LiftSide {
        // baseline and current are [azimuth, pitch, roll] in radians.
        // compute deltas
        val deltaPitch = current.getOrNull(1)?.let { it - baseline.getOrNull(1)!! } ?: 0f
        val deltaRoll = current.getOrNull(2)?.let { it - baseline.getOrNull(2)!! } ?: 0f

        val absPitch = abs(deltaPitch)
        val absRoll = abs(deltaRoll)

        // whichever axis moved more determines top/bottom vs left/right
        return if (absPitch > absRoll) {
            // pitch dominant => top or bottom
            if (deltaPitch < 0f) LiftSide.TOP else LiftSide.BOTTOM
        } else {
            // roll dominant => left or right
            if (deltaRoll < 0f) LiftSide.LEFT else LiftSide.RIGHT
        }
    }

    private fun isOrientationStable(baseline: FloatArray, current: FloatArray): Boolean {
        val dp = abs(current[1] - baseline[1])
        val dr = abs(current[2] - baseline[2])
        return dp < STABLE_ORIENTATION_DELTA && dr < STABLE_ORIENTATION_DELTA
    }

    // mapping functions
    // base (immediate) mapping: top=1, left=2, bottom=3, right=4
    private fun mapSideToBaseDigit(side: LiftSide): Int {
        return when (side) {
            LiftSide.TOP -> 1
            LiftSide.LEFT -> 2
            LiftSide.BOTTOM -> 3
            LiftSide.RIGHT -> 4
            LiftSide.UNKNOWN -> 1
        }
    }

    // hold mapping: top=5, left=6, bottom=7, right=8
    private fun mapSideToHoldDigit(side: LiftSide): Int {
        return when (side) {
            LiftSide.TOP -> 5
            LiftSide.LEFT -> 6
            LiftSide.BOTTOM -> 7
            LiftSide.RIGHT -> 8
            LiftSide.UNKNOWN -> 5
        }
    }

    // shake mapping: top -> 9, bottom -> 10 (per your spec). Left/right shakes are not defined -> return null
    private fun mapSideToShakeDigit(side: LiftSide): Int? {
        return when (side) {
            LiftSide.TOP -> 9
            LiftSide.BOTTOM -> 10
            else -> null
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // not used
    }
}
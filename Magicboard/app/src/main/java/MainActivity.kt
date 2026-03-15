package com.example.magicboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var drawingView: DrawingView
    private lateinit var sensorController: SensorController
    private lateinit var clearButton: ImageButton
    private lateinit var eraserButton: ImageButton
    private lateinit var paletteButton: ImageButton
    private lateinit var colorPaletteContainer: LinearLayout
    private lateinit var modeToggle: ImageButton

    private var modeActive = false

    private val paletteColors = listOf(
        Color.BLACK,
        Color.DKGRAY,
        Color.RED,
        Color.parseColor("#FF9800"),
        Color.YELLOW,
        Color.GREEN,
        Color.CYAN,
        Color.BLUE,
        Color.MAGENTA,
        Color.parseColor("#7B1FA2")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawingView = findViewById(R.id.drawingView)
        clearButton = findViewById(R.id.clearButton)
        eraserButton = findViewById(R.id.eraserButton)
        paletteButton = findViewById(R.id.paletteButton)
        colorPaletteContainer = findViewById(R.id.colorPaletteContainer)
        modeToggle = findViewById(R.id.modeToggle)

        sensorController = SensorController(this) { digit ->
            runOnUiThread {
                // map digit (1..10) to drawable resource; adjust names if your assets are named differently
                val resId = when (digit) {
                    1 -> R.drawable.digit_1
                    2 -> R.drawable.digit_2
                    3 -> R.drawable.digit_3
                    4 -> R.drawable.digit_4
                    5 -> R.drawable.digit_5
                    6 -> R.drawable.digit_6
                    7 -> R.drawable.digit_7
                    8 -> R.drawable.digit_8
                    9 -> R.drawable.digit_9
                    10 -> R.drawable.digit_10
                    else -> R.drawable.digit_1
                }
                val bmp = android.graphics.BitmapFactory.decodeResource(resources, resId)
                // add number as animated bitmap layer
                drawingView.addNumberBitmap(bmp, animate = true)
            }
        }

        clearButton.setOnClickListener {
            drawingView.clearCanvas()
        }

        eraserButton.setOnClickListener {
            if (isEraserOn()) {
                drawingView.disableEraser()
                eraserButton.alpha = 1f
            } else {
                drawingView.enableEraser()
                eraserButton.alpha = 0.6f
            }
        }

        paletteButton.setOnClickListener {
            // toggle palette container visibility
            colorPaletteContainer.visibility =
                if (colorPaletteContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        populatePalette()

        // Mode toggle: animate and arm/disarm sensors
        modeToggle.setOnClickListener {
            modeActive = !modeActive
            if (modeActive) activateMode() else deactivateMode()
        }

        // initial visual state
        updateModeVisual(false)
    }

    override fun onResume() {
        super.onResume()
        sensorController.start()
    }

    override fun onPause() {
        super.onPause()
        sensorController.stop()
    }

    private fun activateMode() {
        sensorController.armTrick()
        animateModeToggleOn()
        updateModeVisual(true)
    }

    private fun deactivateMode() {
        sensorController.disarmTrick()
        animateModeToggleOff()
        updateModeVisual(false)
    }

    private fun updateModeVisual(active: Boolean) {
        if (active) {
            // tint to an accent color
            modeToggle.setColorFilter(Color.parseColor("#FF5722"))
            modeToggle.scaleX = 1.08f
            modeToggle.scaleY = 1.08f
        } else {
            modeToggle.setColorFilter(Color.parseColor("#666666"))
            modeToggle.scaleX = 1f
            modeToggle.scaleY = 1f
        }
    }

    private fun animateModeToggleOn() {
        // quick rotate + pop
        modeToggle.animate().rotation(360f).scaleX(1.15f).scaleY(1.15f).setDuration(360)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    modeToggle.rotation = 0f
                }
            }).start()
    }

    private fun animateModeToggleOff() {
        // subtle reverse animation
        modeToggle.animate().rotation(-18f).scaleX(1f).scaleY(1f).setDuration(220)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    modeToggle.rotation = 0f
                }
            }).start()
    }

    private fun populatePalette() {
        colorPaletteContainer.removeAllViews()
        val size = (48 * resources.displayMetrics.density).toInt()
        val margin = (8 * resources.displayMetrics.density).toInt()

        for (c in paletteColors) {
            val colorView = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).also {
                    it.setMargins(margin / 2, 0, margin / 2, 0)
                }
                background = createRoundColorDrawable(c)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    drawingView.disableEraser()
                    drawingView.setColor(c)
                    eraserButton.alpha = 1f
                }
            }
            colorPaletteContainer.addView(colorView)
        }
        // ensure visible on start
        colorPaletteContainer.visibility = View.VISIBLE
    }

    private fun isEraserOn(): Boolean {
        return eraserButton.alpha < 1f
    }

    private fun createRoundColorDrawable(color: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            setStroke(2, Color.LTGRAY)
        }
}
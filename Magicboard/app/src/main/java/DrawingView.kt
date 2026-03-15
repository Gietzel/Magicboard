package com.example.magicboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

class DrawingView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    // Layer model: either a user path (with paint) or a bitmap (predicted number)
    private sealed class Layer {
        data class PathLayer(val path: Path, val paint: Paint) : Layer()
        data class BitmapLayer(val bitmap: Bitmap, var revealedWidth: Int = -1) : Layer()
    }

    // layers keep draw order (older -> below)
    private val layers = mutableListOf<Layer>()

    // current drawing path & paint (for user drawing)
    private val currentPath = Path()
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 12f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    // ensure PorterDuff CLEAR works reliably
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onDraw(canvas: Canvas) {
        // draw all layers in order
        for (layer in layers) {
            when (layer) {
                is Layer.PathLayer -> canvas.drawPath(layer.path, layer.paint)
                is Layer.BitmapLayer -> {
                    val bmp = layer.bitmap
                    if (layer.revealedWidth > 0 && layer.revealedWidth < bmp.width) {
                        // draw only a clipped rect (reveal animation left->right)
                        val save = canvas.save()
                        canvas.clipRect(0, 0, layer.revealedWidth * (width / bmp.width.toFloat()).toInt(), height)
                        // center the bitmap (scale to fit width while preserving aspect ratio)
                        drawBitmapCentered(canvas, bmp)
                        canvas.restoreToCount(save)
                    } else {
                        drawBitmapCentered(canvas, bmp)
                    }
                }
            }
        }

        // draw current (in-progress) path last so user sees what they are drawing now
        canvas.drawPath(currentPath, currentPaint)
    }

    private fun drawBitmapCentered(canvas: Canvas, bmp: Bitmap) {
        // scale and center the bitmap inside the view width - keep aspect ratio
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW == 0f || viewH == 0f) return
        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()
        val scale = minOf(viewW / bmpW * 0.7f, viewH / bmpH * 0.7f) // leave margins
        val destW = bmpW * scale
        val destH = bmpH * scale
        val left = (viewW - destW) / 2f
        val top = (viewH - destH) / 2f
        val dst = RectF(left, top, left + destW, top + destH)
        canvas.drawBitmap(bmp, null, dst, null)
    }

    // Touch handling -> user drawing paths
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath.moveTo(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath.lineTo(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                // commit current path to layers with a copy of the paint
                val paintCopy = Paint(currentPaint)
                val pathCopy = Path(currentPath)
                layers.add(Layer.PathLayer(pathCopy, paintCopy))
                currentPath.reset()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // clear everything (paths + bitmaps)
    fun clearCanvas() {
        layers.clear()
        currentPath.reset()
        invalidate()
    }

    // color / stroke
    fun setColor(color: Int) {
        currentPaint.xfermode = null
        currentPaint.color = color
    }

    fun setStrokeWidth(width: Float) {
        currentPaint.strokeWidth = width
    }

    // eraser toggles by setting xfermode on currentPaint; committed paths keep the paint copy
    fun enableEraser() {
        currentPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        // strokeWidth may remain the same
    }

    fun disableEraser() {
        currentPaint.xfermode = null
    }

    // Add number bitmap as a new layer. If animate==true, reveal left->right with ValueAnimator.
    fun addNumberBitmap(bitmap: Bitmap, animate: Boolean = true) {
        val layer = Layer.BitmapLayer(bitmap, if (animate) 0 else -1)
        layers.add(layer)
        invalidate()

        if (animate) {
            // animate revealedWidth from 0 -> bitmap.width
            val animator = ValueAnimator.ofInt(0, bitmap.width)
            animator.duration = 700L
            animator.addUpdateListener { va ->
                layer.revealedWidth = va.animatedValue as Int
                invalidate()
            }
            animator.start()
        }
    }

    // Helper in case you want to remove last bitmap only
    fun removeLastBitmap() {
        for (i in layers.size - 1 downTo 0) {
            if (layers[i] is Layer.BitmapLayer) {
                layers.removeAt(i)
                invalidate()
                return
            }
        }
    }
}
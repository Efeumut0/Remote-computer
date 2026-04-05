package com.uzaktanbildirim.mobile

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {
    private val baseMatrix = Matrix()
    private val supportMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val drawableRect = RectF()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private var currentScale = 1f

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        post { resetZoom() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) {
            return super.onTouchEvent(event)
        }

        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && currentScale > 1f) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (dx != 0f || dy != 0f) {
                        supportMatrix.postTranslate(dx, dy)
                        constrainTranslation()
                        applyCurrentMatrix()
                        isDragging = true
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                isDragging = false
            }
        }

        return true
    }

    override fun performClick(): Boolean = super.performClick()

    fun resetZoom() {
        val targetDrawable = drawable ?: return
        if (width <= 0 || height <= 0) {
            return
        }

        baseMatrix.reset()
        supportMatrix.reset()

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val drawableWidth = max(1, targetDrawable.intrinsicWidth).toFloat()
        val drawableHeight = max(1, targetDrawable.intrinsicHeight).toFloat()
        val scale = min(viewWidth / drawableWidth, viewHeight / drawableHeight)
        val dx = (viewWidth - drawableWidth * scale) / 2f
        val dy = (viewHeight - drawableHeight * scale) / 2f

        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)
        currentScale = 1f
        applyCurrentMatrix()
    }

    private fun applyCurrentMatrix() {
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(supportMatrix)
        imageMatrix = drawMatrix
    }

    private fun constrainTranslation() {
        val targetDrawable = drawable ?: return
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(supportMatrix)
        drawableRect.set(0f, 0f, targetDrawable.intrinsicWidth.toFloat(), targetDrawable.intrinsicHeight.toFloat())
        drawMatrix.mapRect(drawableRect)

        var deltaX = 0f
        var deltaY = 0f

        if (drawableRect.width() <= width) {
            deltaX = width / 2f - drawableRect.centerX()
        } else {
            if (drawableRect.left > 0f) {
                deltaX = -drawableRect.left
            } else if (drawableRect.right < width) {
                deltaX = width - drawableRect.right
            }
        }

        if (drawableRect.height() <= height) {
            deltaY = height / 2f - drawableRect.centerY()
        } else {
            if (drawableRect.top > 0f) {
                deltaY = -drawableRect.top
            } else if (drawableRect.bottom < height) {
                deltaY = height - drawableRect.bottom
            }
        }

        if (deltaX != 0f || deltaY != 0f) {
            supportMatrix.postTranslate(deltaX, deltaY)
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (currentScale * detector.scaleFactor).coerceIn(1f, 4.5f)
            val factor = newScale / currentScale
            supportMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            currentScale = newScale
            constrainTranslation()
            applyCurrentMatrix()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > 1f) {
                supportMatrix.reset()
                currentScale = 1f
            } else {
                val factor = 2.2f
                supportMatrix.postScale(factor, factor, e.x, e.y)
                currentScale = factor
                constrainTranslation()
            }
            applyCurrentMatrix()
            return true
        }
    }
}

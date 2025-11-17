package com.yyj.aiapp.floating

import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

class FloatingTouchListener(
    private val params: WindowManager.LayoutParams,
    private val windowManager: WindowManager
) : View.OnTouchListener {

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isDragging = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) {
                    isDragging = true
                }
                if (isDragging) {
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(v, params)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    v.performClick()
                }
                isDragging = false
            }
        }
        return true
    }
}

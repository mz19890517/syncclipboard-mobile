package expo.modules.floatingball

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 悬浮球视图
 * 交互逻辑（参考 FV 悬浮球）：
 * - 单击 / 双击 / 长按
 * - 快速轻扫（上/下/左/右）触发手势动作，球回弹到原位并吸边
 * - 慢速拖动移动位置，松手吸附到最近边缘并记忆坐标
 */
@SuppressLint("ViewConstructor")
class FloatingBallView(
    context: Context,
    private val onGesture: (gesture: String) -> Unit,
    private val onPositionChanged: (x: Int, y: Int) -> Unit,
    private val onPositionSaved: (x: Int, y: Int) -> Unit,
) : View(context) {

    companion object {
        private const val LONG_PRESS_TIMEOUT = 500L
        private const val DOUBLE_TAP_WINDOW = 300L
        private const val FLICK_MAX_DURATION = 350L
        private const val FLICK_MIN_DISTANCE_DP = 50f
        private const val FLICK_MIN_VELOCITY = 1800f // px/s
        private const val GESTURE_NONE = ""
    }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop * 1.2f
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val tapHandler = Handler(Looper.getMainLooper())

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        color = Color.parseColor("#66FFFFFF")
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    // 触摸状态
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTime = 0L
    private var isDragging = false
    private var startLpX = 0
    private var startLpY = 0
    private var lastMoveTime = 0L
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    private var velocityX = 0f
    private var velocityY = 0f
    private var longPressFired = false
    private var lastTapUpTime = 0L
    private var lastTapUpX = 0f
    private var lastTapUpY = 0f
    private var pendingSingleTap: Runnable? = null
    private var longPressRunnable: Runnable? = null
    private var animator: ValueAnimator? = null

    /** 当前窗口坐标，由 Module 同步 */
    var lpX = 0
        private set
    var lpY = 0
        private set

    fun setWindowPosition(x: Int, y: Int) {
        lpX = x
        lpY = y
    }

    fun setOpacity(opacity: Float) {
        alpha = opacity.coerceIn(0.2f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = width / 2f - ringPaint.strokeWidth

        if (circlePaint.shader == null) {
            circlePaint.shader = RadialGradient(
                cx - radius * 0.3f, cy - radius * 0.35f, radius * 1.4f,
                Color.parseColor("#7EB8FF"), Color.parseColor("#2D6FE0"), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, radius, circlePaint)
        canvas.drawCircle(cx, cy, radius - ringPaint.strokeWidth / 2, ringPaint)

        // 绘制剪贴板图标：圆角矩形板 + 顶部小夹子
        val boardW = radius * 0.78f
        val boardH = radius * 0.98f
        val boardRect = RectF(cx - boardW / 2, cy - boardH / 2 + radius * 0.08f, cx + boardW / 2, cy + boardH / 2 + radius * 0.08f)
        canvas.drawRoundRect(boardRect, radius * 0.16f, radius * 0.16f, iconPaint)
        iconPaint.color = Color.parseColor("#2D6FE0")
        val innerRect = RectF(boardRect.centerX() - boardW * 0.32f, boardRect.top + boardH * 0.18f, boardRect.centerX() + boardW * 0.32f, boardRect.bottom - boardH * 0.12f)
        canvas.drawRoundRect(innerRect, radius * 0.08f, radius * 0.08f, iconPaint)
        iconPaint.color = Color.WHITE
        val clipRect = RectF(cx - boardW * 0.22f, boardRect.top - radius * 0.10f, cx + boardW * 0.22f, boardRect.top + radius * 0.14f)
        canvas.drawRoundRect(clipRect, radius * 0.08f, radius * 0.08f, iconPaint)

        // 三条内容线
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#9DBFF5")
        }
        for (i in 0..2) {
            val lineTop = innerRect.top + innerRect.height() * (0.22f + i * 0.26f)
            val lineW = innerRect.width() * (if (i == 2) 0.55f else 0.75f)
            val rect = RectF(innerRect.left + innerRect.width() * 0.12f, lineTop, innerRect.left + innerRect.width() * 0.12f + lineW, lineTop + radius * 0.06f)
            canvas.drawRoundRect(rect, radius * 0.03f, radius * 0.03f, linePaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelAnimations()
                downRawX = event.rawX
                downRawY = event.rawY
                downTime = System.currentTimeMillis()
                lastMoveTime = downTime
                lastMoveX = downRawX
                lastMoveY = downRawY
                velocityX = 0f
                velocityY = 0f
                isDragging = false
                longPressFired = false
                parent?.requestDisallowInterceptTouchEvent(true)
                scheduleLongPress(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging && hypot(dx, dy) > touchSlop) {
                    isDragging = true
                    cancelLongPress()
                }
                if (isDragging) {
                    val now = System.currentTimeMillis()
                    val dt = (now - lastMoveTime).coerceAtLeast(1)
                    val instVX = (event.rawX - lastMoveX) / dt * 1000f
                    val instVY = (event.rawY - lastMoveY) / dt * 1000f
                    velocityX = velocityX * 0.7f + instVX * 0.3f
                    velocityY = velocityY * 0.7f + instVY * 0.3f
                    lastMoveTime = now
                    lastMoveX = event.rawX
                    lastMoveY = event.rawY
                    moveBy(dx.toInt(), dy.toInt())
                }
                return true
            }
            MotionEvent.ACTION_UP -> finishTouch(event)
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                if (isDragging) snapToNearestEdge(save = false)
            }
        }
        return super.onTouchEvent(event) || true
    }

    private fun finishTouch(event: MotionEvent) {
        cancelLongPress()
        val upTime = System.currentTimeMillis()
        val duration = upTime - downTime
        val totalDx = event.rawX - downRawX
        val totalDy = event.rawY - downRawY
        val totalDist = hypot(totalDx, totalDy)

        if (!isDragging && !longPressFired && duration >= LONG_PRESS_TIMEOUT * 0.9) {
            // 极端情况：长按已超时但 handler 未触发
            onGesture("longPress")
            return
        }

        if (!isDragging && totalDist <= touchSlop) {
            handleTapCandidate(upTime, event.rawX, event.rawY)
            return
        }

        if (isDragging) {
            val speed = hypot(velocityX, velocityY)
            val dominantX = abs(totalDx) >= abs(totalDy)
            val flickDistance = if (dominantX) abs(totalDx) else abs(totalDy)
            val minDistPx = FLICK_MIN_DISTANCE_DP * density
            if (duration <= FLICK_MAX_DURATION && flickDistance >= minDistPx && speed >= FLICK_MIN_VELOCITY) {
                val gesture = when {
                    dominantX && totalDx > 0 -> "swipeRight"
                    dominantX -> "swipeLeft"
                    totalDy > 0 -> "swipeDown"
                    else -> "swipeUp"
                }
                snapToNearestEdge(save = false)
                onGesture(gesture)
            } else {
                snapToNearestEdge(save = true)
            }
        }
    }

    private fun handleTapCandidate(upTime: Long, rawX: Float, rawY: Float) {
        val isDouble = upTime - lastTapUpTime <= DOUBLE_TAP_WINDOW &&
            hypot(rawX - lastTapUpX, rawY - lastTapUpY) <= touchSlop * 2
        if (isDouble) {
            pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
            pendingSingleTap = null
            lastTapUpTime = 0
            onGesture("doubleTap")
        } else {
            lastTapUpTime = upTime
            lastTapUpX = rawX
            lastTapUpY = rawY
            pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
            val runnable = Runnable {
                pendingSingleTap = null
                onGesture("tap")
            }
            pendingSingleTap = runnable
            tapHandler.postDelayed(runnable, DOUBLE_TAP_WINDOW)
        }
    }

    private fun scheduleLongPress(event: MotionEvent) {
        val runnable = Runnable {
            if (!isDragging) {
                longPressFired = true
                onGesture("longPress")
            }
        }
        longPressRunnable = runnable
        longPressHandler.postDelayed(runnable, LONG_PRESS_TIMEOUT)
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    fun moveBy(dx: Int, dy: Int) {
        lpX += dx
        lpY += dy
        onPositionChanged(lpX, lpY)
    }

    /** 吸附到最近的左右边缘；save 为真时回调保存最终坐标 */
    fun snapToNearestEdge(save: Boolean) {
        val screenWidth = resources.displayMetrics.widthPixels
        val targetX = if (lpX + width / 2 < screenWidth / 2) 0 else screenWidth - width
        animateTo(targetX, lpY.clampToScreenY(), save)
    }

    private fun Int.clampToScreenY(): Int =
        coerceIn(0, (resources.displayMetrics.heightPixels - this@FloatingBallView.height).coerceAtLeast(0))

    fun animateTo(targetX: Int, targetY: Int, save: Boolean) {
        cancelAnimations()
        val startX = lpX
        val startY = lpY
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                lpX = (startX + (targetX - startX) * t).toInt()
                lpY = (startY + (targetY - startY) * t).toInt()
                onPositionChanged(lpX, lpY)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (save) onPositionSaved(lpX, lpY)
                }
            })
            start()
        }
    }

    fun cancelAnimations() {
        animator?.cancel()
        animator = null
    }

    fun cleanup() {
        cancelLongPress()
        pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
        cancelAnimations()
    }
}

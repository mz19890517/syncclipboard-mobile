package expo.modules.floatingball

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * 悬浮球原生模块
 * 负责悬浮窗生命周期、手势到动作的分发、位置记忆。
 * 动作全部在原生侧解析，不依赖 JS 运行时（面板通过 QuickActionActivity 承载）。
 */
class FloatingBallModule : Module() {

    companion object {
        private const val PREFS_NAME = "floating_ball_prefs"
        private const val KEY_POS_X = "pos_x"
        private const val KEY_POS_Y = "pos_y"
        private const val QUICK_ACTION_ACTIVITY =
            "com.jericx.syncclipboardmobile.quickaction.QuickActionActivity"

        /** 默认手势映射：单击/上滑=全部，双击=图片，下滑=文本，右滑=文件，左滑=收藏，长按=主界面 */
        val DEFAULT_ACTIONS: Map<String, String> = mapOf(
            "tap" to "panelAll",
            "doubleTap" to "panelImage",
            "longPress" to "openApp",
            "swipeUp" to "panelAll",
            "swipeDown" to "panelText",
            "swipeLeft" to "panelFav",
            "swipeRight" to "panelFile",
        )
    }

    private var ballView: FloatingBallView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var currentActions: Map<String, String> = DEFAULT_ACTIONS

    private val prefs: SharedPreferences?
        get() = appContext.reactContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun definition() = ModuleDefinition {
        Name("FloatingBallModule")

        Function("isShowing") { ballView != null }

        Function("show") { sizeDp: Double, opacity: Double, locked: Boolean, actions: Map<String, String> ->
            val context = appContext.reactContext ?: return@Function false
            if (!Settings.canDrawOverlays(context)) return@Function false
            if (actions.isNotEmpty()) {
                currentActions = DEFAULT_ACTIONS + actions.filterKeys { it.isNotBlank() }
            } else {
                currentActions = DEFAULT_ACTIONS
            }
            showBall(context, sizeDp.toInt().coerceIn(28, 96), opacity.toFloat(), locked)
            true
        }

        Function("hide") {
            removeBall()
        }

        Function("updateConfig") { sizeDp: Double, opacity: Double, locked: Boolean, actions: Map<String, String> ->
            val context = appContext.reactContext ?: return@Function false
            if (actions.isNotEmpty()) {
                currentActions = DEFAULT_ACTIONS + actions.filterKeys { it.isNotBlank() }
            }
            val view = ballView ?: return@Function false
            view.isLocked = locked
            val lp = layoutParams ?: return@Function false
            val sizePx = dp2px(context, sizeDp.toInt().coerceIn(28, 96))
            lp.width = sizePx
            lp.height = sizePx
            view.setOpacity(opacity.toFloat())
            view.invalidate()
            windowManager(view.context)?.updateViewLayout(view, lp)
            true
        }

        Function("resetPosition") {
            prefs?.edit()?.clear()?.apply()
            // 球在显示中时同步回到默认位置（右侧、屏幕上三分之一处）
            val context = appContext.reactContext
            val view = ballView
            if (context != null && view != null) {
                val screenWidth = context.resources.displayMetrics.widthPixels
                view.jumpTo(
                    screenWidth - view.width,
                    context.resources.displayMetrics.heightPixels / 3
                )
            }
        }

        Function("openMainApp") {
            val context = appContext.reactContext ?: return@Function false
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } ?: return@Function false
            runCatching { context.startActivity(intent) }
            true
        }
    }

    private fun windowManager(context: Context): WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private fun dp2px(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    @SuppressLint("InflateParams")
    private fun showBall(context: Context, sizeDp: Int, opacity: Float, locked: Boolean) {
        if (ballView != null) {
            updateConfigInternal(context, sizeDp, opacity)
            ballView?.isLocked = locked
            return
        }
        val sizePx = dp2px(context, sizeDp)
        val savedX = prefs?.getInt(KEY_POS_X, -1) ?: -1
        val savedY = prefs?.getInt(KEY_POS_Y, -1) ?: -1
        val screenWidth = context.resources.displayMetrics.widthPixels

        val initialX = if (savedX >= 0) savedX.coerceIn(0, (screenWidth - sizePx).coerceAtLeast(0)) else 0
        val initialY = if (savedY >= 0) savedY else (context.resources.displayMetrics.heightPixels / 3)

        val view = FloatingBallView(
            context,
            onGesture = { gesture -> handleGesture(gesture) },
            onPositionChanged = { x, y -> updateWindowPosition(x, y) },
            onPositionSaved = { x, y -> savePosition(x, y) },
        )
        view.setOpacity(opacity)

        val lp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        view.setWindowPosition(initialX, initialY)
        view.isLocked = locked
        windowManager(context)?.addView(view, lp)
        ballView = view
        layoutParams = lp
    }

    private fun updateConfigInternal(context: Context, sizeDp: Int, opacity: Float) {
        val view = ballView ?: return
        val lp = layoutParams ?: return
        val sizePx = dp2px(context, sizeDp)
        lp.width = sizePx
        lp.height = sizePx
        view.setOpacity(opacity)
        view.invalidate()
        runCatching { windowManager(context)?.updateViewLayout(view, lp) }
    }

    private fun updateWindowPosition(x: Int, y: Int) {
        val view = ballView ?: return
        val lp = layoutParams ?: return
        lp.x = x
        lp.y = y
        runCatching { windowManager(view.context)?.updateViewLayout(view, lp) }
    }

    private fun savePosition(x: Int, y: Int) {
        prefs?.edit()?.putInt(KEY_POS_X, x)?.putInt(KEY_POS_Y, y)?.apply()
    }

    private fun removeBall() {
        val view = ballView ?: return
        view.cleanup()
        runCatching { windowManager(view.context)?.removeView(view) }
        ballView = null
        layoutParams = null
    }

    /** 手势 → 动作分发（原生解析，JS 不参与热路径） */
    private fun handleGesture(gesture: String) {
        val action = currentActions[gesture] ?: return
        performAction(action)
    }

    private fun performAction(action: String) {
        val context = appContext.reactContext ?: return
        when (action) {
            "panelAll" -> launchPanel(context, "all")
            "panelText" -> launchPanel(context, "text")
            "panelImage" -> launchPanel(context, "image")
            "panelFile" -> launchPanel(context, "file")
            "panelFav" -> launchPanel(context, "fav")
            "openApp" -> launchMainApp(context)
            "upload" -> launchQuickAction(context, "upload")
            "download" -> launchQuickAction(context, "download")
        }
    }

    private fun launchIntentFlags(): Int =
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

    private fun launchPanel(context: Context, panelType: String) {
        val intent = Intent().apply {
            component = ComponentName(context, QUICK_ACTION_ACTIVITY)
            putExtra("panelType", panelType)
            addFlags(launchIntentFlags())
        }
        runCatching { context.startActivity(intent) }
    }

    private fun launchQuickAction(context: Context, direction: String) {
        val intent = Intent().apply {
            component = ComponentName(context, QUICK_ACTION_ACTIVITY)
            putExtra("direction", direction)
            addFlags(launchIntentFlags())
        }
        runCatching { context.startActivity(intent) }
    }

    private fun launchMainApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: return
        runCatching { context.startActivity(intent) }
    }
}

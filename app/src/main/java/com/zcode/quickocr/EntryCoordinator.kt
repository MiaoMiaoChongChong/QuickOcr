package com.zcode.quickocr

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 入口协调器：接管悬浮球展示与全局截屏路由。
 *
 * - 关闭通知栏入口 + 已启用无障碍时，BallService 完全无需运行，不再占用任何通知；
 * - 悬浮球带矢量图标、贴边自动吸附、位置记忆、闲置半透明缩边与按压动效。
 */
object EntryCoordinator {

    private const val TAG = "QOCR-Coord"
    private const val A11Y_BALL_DELAY_MS = 120L
    private const val IDLE_DOCK_DELAY_MS = 2500L
    private const val IDLE_ALPHA = 0.42f
    private const val IDLE_SCALE = 0.86f

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var ballSizePx: Int = 0
    private var snapAnimator: ValueAnimator? = null
    private var captureScheduled = false

    private val idleDockRunnable = Runnable {
        ballView?.let { v ->
            if (v.visibility == View.VISIBLE) {
                v.animate()
                    .alpha(IDLE_ALPHA)
                    .scaleX(IDLE_SCALE)
                    .scaleY(IDLE_SCALE)
                    .setDuration(220L)
                    .start()
            }
        }
    }

    fun isBallVisible(): Boolean = ballView != null

    /** 在截屏瞬间暂时隐藏悬浮球，防止把悬浮球一起截进识别画面。 */
    fun setBallTemporarilyHidden(hidden: Boolean) {
        val apply = Runnable {
            val v = ballView ?: return@Runnable
            if (hidden) {
                cancelIdleDock()
                v.visibility = View.INVISIBLE
            } else {
                v.visibility = View.VISIBLE
                wakeBall(pressed = false)
                scheduleIdleDock()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply.run()
        } else {
            main.post(apply)
        }
    }

    /** 同步悬浮球与前台服务的运行状态，并在屏幕尺寸/方向变化时夹紧悬浮球位置。 */
    fun sync(context: Context) {
        val appCtx = context.applicationContext
        val runSync = Runnable {
            val notifOn = Prefs.notifEnabled(appCtx)
            val ballOn = Prefs.ballEnabled(appCtx)
            val a11yReady = OcrA11yService.isEnabled(appCtx) && OcrA11yService.connected

            if (!notifOn && a11yReady && ProjectionHolder.hasProjection()) {
                Log.i(TAG, "QOCR 无障碍已就绪且通知入口已关闭，释放冗余 MediaProjection")
                ProjectionHolder.clearAll()
            }

            if (ballOn && Settings.canDrawOverlays(appCtx)) {
                if (ballView == null) {
                    showBall(appCtx)
                } else {
                    clampBallToBounds(appCtx)
                }
            } else {
                hideBall()
            }

            val needFgs = notifOn || ProjectionHolder.hasProjection()
            Log.i(
                TAG,
                "QOCR sync ball=$ballOn notif=$notifOn a11yReady=$a11yReady " +
                    "hasProj=${ProjectionHolder.hasProjection()} needFgs=$needFgs svcRunning=${BallService.isRunning}"
            )
            if (needFgs) {
                if (BallService.isRunning) {
                    BallService.refresh(appCtx)
                } else {
                    BallService.start(appCtx)
                }
            } else if (BallService.isRunning) {
                BallService.stopServiceOnly(appCtx)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runSync.run()
        } else {
            main.post(runSync)
        }
    }

    /** 统一截屏入口：优先无障碍静默截屏（不启动任何前台服务），否则走 MediaProjection 备用通道。 */
    fun triggerCapture(context: Context, delayMs: Long = 650L, fromBall: Boolean = false) {
        val appCtx = context.applicationContext
        val runCapture = Runnable {
            if (captureScheduled) {
                Log.i(TAG, "QOCR capture already scheduled, skip (防抖)")
            } else if (OcrA11yService.isEnabled(appCtx)) {
                captureScheduled = true
                setBallTemporarilyHidden(true)
                val waitMs = if (fromBall) A11Y_BALL_DELAY_MS else delayMs
                Log.i(TAG, "QOCR a11y capture scheduled in ${waitMs}ms")
                main.postDelayed({
                    captureScheduled = false
                    val started = OcrA11yService.captureIfAvailable { bmp ->
                        main.post {
                            setBallTemporarilyHidden(false)
                            if (bmp != null) {
                                Log.i(TAG, "QOCR a11y frame grabbed")
                                OverlayController.startCrop(appCtx, bmp)
                            } else {
                                Log.w(TAG, "QOCR a11y failed → projection fallback")
                                fallbackOrToast(appCtx, delayMs)
                            }
                        }
                    }
                    if (!started) {
                        setBallTemporarilyHidden(false)
                        Log.w(TAG, "QOCR a11y not bound → projection fallback")
                        fallbackOrToast(appCtx, delayMs)
                    }
                }, waitMs)
            } else if (ProjectionHolder.hasProjection()) {
                BallService.capture(appCtx, delayMs = if (fromBall) A11Y_BALL_DELAY_MS else delayMs)
            } else {
                Toast.makeText(
                    appCtx,
                    "请先开启无障碍权限，或点「录屏权限」授权",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCapture.run()
        } else {
            main.post(runCapture)
        }
    }

    private fun fallbackOrToast(appCtx: Context, delayMs: Long) {
        if (ProjectionHolder.hasProjection()) {
            BallService.capture(appCtx, delayMs = delayMs)
        } else {
            Toast.makeText(
                appCtx,
                "无障碍截屏失败，请确认无障碍服务已开启或重新切换开关",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showBall(context: Context) {
        val appCtx = context.applicationContext
        if (ballView != null || !Settings.canDrawOverlays(appCtx)) return
        val windowManager = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = windowManager

        val dp = appCtx.resources.displayMetrics.density
        val size = (48 * dp).roundToInt()
        ballSizePx = size

        val container = FrameLayout(appCtx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE63B7BFF.toInt())
                setStroke((1.5f * dp).roundToInt().coerceAtLeast(1), 0x80FFFFFF.toInt())
            }
            elevation = 6f * dp
            clipToPadding = false
        }
        val iconSize = (22 * dp).roundToInt()
        val icon = ImageView(appCtx).apply {
            setImageResource(R.drawable.ic_tile)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        container.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))

        val (screenW, screenH) = getScreenBounds(windowManager)
        val marginX = (6 * dp).roundToInt()
        val minY = (32 * dp).roundToInt()
        val maxY = (screenH - (48 * dp).roundToInt() - size).coerceAtLeast(minY)
        val initialX = if (Prefs.ballOnRight(appCtx)) {
            (screenW - size - marginX).coerceAtLeast(marginX)
        } else {
            marginX
        }
        val initialY = (minY + (maxY - minY) * Prefs.ballYRatio(appCtx)).roundToInt().coerceIn(minY, maxY)

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        container.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelSnapAnimator()
                    cancelIdleDock()
                    wakeBall(pressed = true)
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!moved && (abs(dx) > 8 * dp || abs(dy) > 8 * dp)) {
                        moved = true
                        view.animate().scaleX(1f).scaleY(1f).setDuration(80L).start()
                    }
                    if (moved) {
                        val (sw, sh) = getScreenBounds(windowManager)
                        val curMaxY = (sh - (48 * dp).roundToInt() - size).coerceAtLeast(minY)
                        params.x = (startX + dx.toInt()).coerceIn(0, (sw - size).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(minY, curMaxY)
                        try {
                            wm?.updateViewLayout(view, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(100L).start()
                    if (moved) {
                        val (sw, sh) = getScreenBounds(windowManager)
                        val curMaxY = (sh - (48 * dp).roundToInt() - size).coerceAtLeast(minY)
                        val centerX = params.x + size / 2f
                        val toRight = centerX > sw / 2f
                        val targetX = if (toRight) {
                            (sw - size - marginX).coerceAtLeast(marginX)
                        } else {
                            marginX
                        }
                        val yRatio = if (curMaxY > minY) {
                            (params.y - minY).toFloat() / (curMaxY - minY).toFloat()
                        } else 0.38f
                        Prefs.setBallOnRight(appCtx, toRight)
                        Prefs.setBallYRatio(appCtx, yRatio)
                        animateSnapX(view, params, targetX)
                        scheduleIdleDock()
                    } else {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        val ready = OcrA11yService.isEnabled(appCtx) || ProjectionHolder.hasProjection()
                        Log.i(TAG, "QOCR ball tap ready=$ready")
                        if (ready) {
                            triggerCapture(appCtx, fromBall = true)
                        } else {
                            scheduleIdleDock()
                            try {
                                appCtx.startActivity(
                                    Intent(appCtx, ConsentActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) {
                                Toast.makeText(
                                    appCtx,
                                    "请先打开 OCR快照 完成授权",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(100L).start()
                    scheduleIdleDock()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
            ballView = container
            ballParams = params
            scheduleIdleDock()
        } catch (e: Exception) {
            Log.e(TAG, "QOCR addBall failed", e)
        }
    }

    fun hideBall() {
        cancelIdleDock()
        cancelSnapAnimator()
        val v = ballView ?: return
        ballView = null
        ballParams = null
        try {
            wm?.removeView(v)
        } catch (_: Exception) {}
    }

    private fun clampBallToBounds(appCtx: Context) {
        val v = ballView ?: return
        val p = ballParams ?: return
        val windowManager = wm ?: return
        val dp = appCtx.resources.displayMetrics.density
        val size = ballSizePx.takeIf { it > 0 } ?: (48 * dp).roundToInt()
        val (sw, sh) = getScreenBounds(windowManager)
        val marginX = (6 * dp).roundToInt()
        val minY = (32 * dp).roundToInt()
        val maxY = (sh - (48 * dp).roundToInt() - size).coerceAtLeast(minY)
        p.x = if (Prefs.ballOnRight(appCtx)) {
            (sw - size - marginX).coerceAtLeast(marginX)
        } else {
            marginX
        }
        p.y = (minY + (maxY - minY) * Prefs.ballYRatio(appCtx)).roundToInt().coerceIn(minY, maxY)
        try {
            windowManager.updateViewLayout(v, p)
        } catch (_: Exception) {}
    }

    private fun animateSnapX(view: View, params: WindowManager.LayoutParams, targetX: Int) {
        cancelSnapAnimator()
        val startX = params.x
        if (startX == targetX) return
        snapAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                if (ballView !== view) {
                    anim.cancel()
                    return@addUpdateListener
                }
                params.x = anim.animatedValue as Int
                try {
                    wm?.updateViewLayout(view, params)
                } catch (_: Exception) {
                    anim.cancel()
                }
            }
            start()
        }
    }

    private fun cancelSnapAnimator() {
        snapAnimator?.cancel()
        snapAnimator = null
    }

    private fun wakeBall(pressed: Boolean) {
        val v = ballView ?: return
        val targetScale = if (pressed) 0.92f else 1f
        v.animate()
            .alpha(1f)
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(120L)
            .start()
    }

    private fun scheduleIdleDock() {
        main.removeCallbacks(idleDockRunnable)
        main.postDelayed(idleDockRunnable, IDLE_DOCK_DELAY_MS)
    }

    private fun cancelIdleDock() {
        main.removeCallbacks(idleDockRunnable)
    }

    private fun getScreenBounds(windowManager: WindowManager): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.maximumWindowMetrics.bounds
            b.width().coerceAtLeast(1) to b.height().coerceAtLeast(1)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
        }
    }
}

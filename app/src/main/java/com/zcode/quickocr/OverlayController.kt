package com.zcode.quickocr

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 悬浮层控制器：
 * 阶段一 = 冻结屏幕 + 拖框选区；阶段二 = 识别结果勾选复制。
 * 关闭悬浮层即回到原应用，无需任何界面切换。
 */
object OverlayController {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wm: WindowManager? = null
    private var appCtx: Context? = null
    private var currentRoot: View? = null
    private var screenshot: Bitmap? = null
    private var cropView: CropView? = null

    /** 由截屏服务调用（任意线程），内部切到主线程。 */
    fun startCrop(context: Context, bitmap: Bitmap) {
        main.post { showCropInternal(context.applicationContext, bitmap) }
    }

    private fun showCropInternal(ctx: Context, bitmap: Bitmap) {
        dismissCurrent()
        appCtx = ctx
        screenshot = bitmap
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val dp = ctx.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()

        val root = FrameLayout(ctx)
        val iv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
        }
        root.addView(iv, FrameLayout.LayoutParams(-1, -1))

        val cv = CropView(ctx, bitmap)
        cropView = cv
        root.addView(cv, FrameLayout.LayoutParams(-1, -1))

        val hint = TextView(ctx).apply {
            text = "拖动选框圈住要提取的文字"
            setTextColor(Color.WHITE)
            textSize = 14f
            background = rounded(0xB3000000.toInt(), d(18).toFloat())
            setPadding(d(14), d(8), d(14), d(8))
        }
        root.addView(hint, FrameLayout.LayoutParams(
            -2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = d(36) })

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val btnCancel = bigButton(ctx, "取消", 0x99666666.toInt())
        val btnOk = bigButton(ctx, "识 别", 0xFF4F8CFF.toInt())
        btnCancel.setOnClickListener { dismissCurrent() }
        btnOk.setOnClickListener { doRecognize(ctx) }
        bar.addView(btnCancel)
        bar.addView(btnOk, marginStart(ctx, d(16)))
        root.addView(bar, FrameLayout.LayoutParams(
            -2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = d(48) })

        val close = TextView(ctx).apply {
            text = "✕ 关闭"
            setTextColor(Color.WHITE)
            textSize = 15f
            background = rounded(0xB3000000.toInt(), d(16).toFloat())
            setPadding(d(14), d(8), d(14), d(8))
            setOnClickListener { dismissCurrent() }
        }
        root.addView(close, FrameLayout.LayoutParams(
            -2, -2, Gravity.TOP or Gravity.END
        ).apply { topMargin = d(36); rightMargin = d(20) })

        addOverlay(root, edgeBack = true)
    }

    private fun marginStart(ctx: Context, v: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.marginStart = v
        return lp
    }

    private fun doRecognize(ctx: Context) {
        val cv = cropView ?: return
        val shot = screenshot ?: return
        val bounds = cv.imageBounds()
        val scale = bounds.width() / shot.width.toFloat()
        val l = ((cv.sel.left - bounds.left) / scale).toInt().coerceIn(0, shot.width - 1)
        val t = ((cv.sel.top - bounds.top) / scale).toInt().coerceIn(0, shot.height - 1)
        val r = ((cv.sel.right - bounds.left) / scale).toInt().coerceIn(l + 1, shot.width)
        val b = ((cv.sel.bottom - bounds.top) / scale).toInt().coerceIn(t + 1, shot.height)
        val crop = Bitmap.createBitmap(shot, l, t, r - l, b - t)

        cv.setLocked(true)
        toast("识别中…")
        scope.launch {
            val res = try {
                withContext(Dispatchers.Default) {
                    OcrEngine.ensureInit(appCtx!!)
                    OcrEngine.detect(crop)
                }
            } catch (e: Exception) {
                null
            }
            main.post {
                if (res == null) {
                    toast("识别失败")
                    cv.setLocked(false)
                    return@post
                }
                val lines = res.textLines
                    .filter { it.text.isNotBlank() }
                    .sortedWith(compareBy(
                        { it.points.minOfOrNull { p -> p.y } ?: Int.MAX_VALUE },
                        { it.points.minOfOrNull { p -> p.x } ?: Int.MAX_VALUE }
                    ))
                    .map { it.text }
                if (lines.isEmpty()) {
                    toast("未识别到文字")
                    cv.setLocked(false)
                } else {
                    showResult(ctx, lines)
                }
            }
        }
    }

    private fun showResult(ctx: Context, lines: List<String>) {
        dismissCurrent()
        val dp = ctx.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()
        val screenH = ctx.resources.displayMetrics.heightPixels

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedTop(0xF1141418.toInt(), d(22).toFloat())
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(18), d(16), d(18), d(10))
        }
        header.addView(TextView(ctx).apply {
            text = "识别结果 · ${lines.size} 行"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(ctx).apply {
            text = "✕ 关闭"
            setTextColor(0xFF9E9E9E.toInt())
            textSize = 15f
            setPadding(d(8), d(4), d(4), d(4))
            setOnClickListener { dismissCurrent() }
        })
        panel.addView(header, LinearLayout.LayoutParams(-1, -2))

        // 每行后是否换行：与设置页共用同一偏好，这里改动会同步写回
        val lineBreakCb = CheckBox(ctx).apply {
            text = "每行后换行（连续文章可取消）"
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 13f
            isChecked = Prefs.lineBreak(ctx)
            setPadding(d(18), d(2), d(18), d(2))
            setOnCheckedChangeListener { _, v -> Prefs.setLineBreak(ctx, v) }
        }
        panel.addView(lineBreakCb, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = true }
        scroll.layoutParams = LinearLayout.LayoutParams(-1, (screenH * 0.44f).toInt())

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(10), d(4), d(10), d(10))
        }
        val checks = mutableListOf<CheckBox>()

        lines.forEachIndexed { i, text ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(0xFF1F1F26.toInt(), d(10).toFloat())
                setPadding(d(12), d(8), d(12), d(8))
            }
            val cb = CheckBox(ctx).apply { isClickable = false }
            val tv = TextView(ctx).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 15.5f
                setPadding(d(10), d(2), d(4), d(2))
            }
            row.addView(cb)
            row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
            row.setOnClickListener {
                cb.toggle()
            }
            row.setOnLongClickListener {
                copyText(ctx, text)
                toast("已复制：${text.take(20)}${if (text.length > 20) "…" else ""}")
                true
            }
            container.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = if (i > 0) d(6) else 0
            })
            checks.add(cb)
        }
        scroll.addView(container)

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(d(12), d(6), d(12), d(18))
        }
        val btnAll = bigButton(ctx, "全选", 0xFF3A3A44.toInt(), small = true)
        val btnCopy = bigButton(ctx, "复制选中", 0xFF4F8CFF.toInt(), small = true)
        val btnCopyAll = bigButton(ctx, "复制全部", 0xFF3A6E9E.toInt(), small = true)

        btnAll.setOnClickListener {
            val anyUnchecked = checks.any { !it.isChecked }
            checks.forEach { it.isChecked = anyUnchecked }
        }
        btnCopy.setOnClickListener {
            val sel = lines.filterIndexed { i, _ -> checks[i].isChecked }
            if (sel.isEmpty()) {
                toast("先勾选文字，或点「全选」")
            } else {
                copyText(ctx, sel.joinToString(if (lineBreakCb.isChecked) "\n" else ""))
                toast("已复制 ${sel.size} 行")
                dismissCurrent()
            }
        }
        btnCopyAll.setOnClickListener {
            copyText(ctx, lines.joinToString(if (lineBreakCb.isChecked) "\n" else ""))
            toast("已复制全部 ${lines.size} 行")
            dismissCurrent()
        }

        footer.addView(btnAll)
        footer.addView(btnCopy, marginStart(ctx, d(10)))
        footer.addView(btnCopyAll, marginStart(ctx, d(10)))

        val footHint = TextView(ctx).apply {
            text = "长按任意一行可直接复制该行"
            setTextColor(0xFF777777.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, d(10))
        }

        panel.addView(scroll)
        panel.addView(footHint, LinearLayout.LayoutParams(-1, -2))
        panel.addView(footer, LinearLayout.LayoutParams(-1, -2))

        addOverlay(panel, bottomAligned = true)
    }

    private fun addOverlay(content: View, bottomAligned: Boolean = false, edgeBack: Boolean = false) {
        val v: View = if (edgeBack) {
            EdgeBackFrameLayout(content.context) { dismissCurrent() }.apply {
                addView(content, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
        } else content
        val params = WindowManager.LayoutParams(
            if (bottomAligned) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.MATCH_PARENT,
            if (bottomAligned) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        if (bottomAligned) params.gravity = Gravity.BOTTOM
        wm?.addView(v, params)
        currentRoot = v
        v.isFocusableInTouchMode = true
        v.requestFocus()
        v.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                dismissCurrent()
                true
            } else false
        }
    }

    private fun dismissCurrent() {
        val v = currentRoot ?: return
        currentRoot = null
        try {
            wm?.removeView(v)
        } catch (_: Exception) {}
    }

    /**
     * 覆盖 MIUI 全面屏侧滑返回：系统手势有时不把 KEYCODE_BACK 发给 overlay 窗口，
     * 这里在 dispatchTouchEvent 里识别「从左右边缘起始的水平滑动」直接关闭本层。
     */
    private class EdgeBackFrameLayout(
        context: Context,
        private val onBack: () -> Unit
    ) : FrameLayout(context) {
        private var startX = 0f
        private var startY = 0f
        private var fromEdge = false

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX
                    startY = ev.rawY
                    val edge = 48f * resources.displayMetrics.density
                    fromEdge = startX < edge ||
                        startX > resources.displayMetrics.widthPixels - edge
                }
                MotionEvent.ACTION_MOVE -> {
                    if (fromEdge) {
                        val dx = ev.rawX - startX
                        val dy = ev.rawY - startY
                        if (abs(dx) > 90f * resources.displayMetrics.density &&
                            abs(dx) > abs(dy) * 1.4f
                        ) {
                            fromEdge = false
                            onBack()
                            return true
                        }
                    }
                }
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    private fun copyText(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ocr", text))
    }

    private fun toast(msg: String) {
        main.post {
            appCtx?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun bigButton(ctx: Context, label: String, bg: Int, small: Boolean = false): TextView {
        val dp = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = if (small) 14f else 16f
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(bg, (if (small) 12 else 16) * dp)
            setPadding(
                (if (small) 18 else 30) * dp.toInt(),
                (if (small) 8 else 12) * dp.toInt(),
                (if (small) 18 else 30) * dp.toInt(),
                (if (small) 8 else 12) * dp.toInt()
            )
        }
    }

    private fun rounded(color: Int, radiusPx: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
        }
    }

    private fun roundedTop(color: Int, radiusPx: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(
                radiusPx, radiusPx, radiusPx, radiusPx,
                0f, 0f, 0f, 0f
            )
        }
    }

    /** 冻结画面上的选区视图。 */
    private class CropView(
        context: Context,
        val bitmap: Bitmap
    ) : View(context) {

        val sel = RectF()
        private var mode = 0
        private var downX = 0f
        private var downY = 0f
        private var offX = 0f
        private var offY = 0f
        private var locked = false

        private val dim = Paint().apply { color = 0x59000000 }
        private val clear = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 2.5f * resources.displayMetrics.density
        }
        private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        private val touchSlop = 34f * resources.displayMetrics.density

        fun setLocked(l: Boolean) {
            locked = l
        }

        fun imageBounds(): RectF {
            val vw = width.toFloat()
            val vh = height.toFloat()
            val scale = min(vw / bitmap.width, vh / bitmap.height)
            val dw = bitmap.width * scale
            val dh = bitmap.height * scale
            return RectF((vw - dw) / 2f, (vh - dh) / 2f, (vw + dw) / 2f, (vh + dh) / 2f)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            if (sel.isEmpty) {
                val b = imageBounds()
                val bw = b.width()
                val bh = b.height()
                sel.set(
                    b.left + bw * 0.1f,
                    b.top + bh * 0.18f,
                    b.right - bw * 0.1f,
                    b.top + bh * 0.55f
                )
            }
        }

        private fun hitTest(x: Float, y: Float): Int {
            val nearL = abs(x - sel.left) <= touchSlop
            val nearR = abs(x - sel.right) <= touchSlop
            val nearT = abs(y - sel.top) <= touchSlop
            val nearB = abs(y - sel.bottom) <= touchSlop
            // 四角优先：角上的圆点同时调上下左右
            if (nearL && nearT) return 6
            if (nearR && nearT) return 7
            if (nearL && nearB) return 8
            if (nearR && nearB) return 9
            val inV = x >= sel.left - touchSlop && x <= sel.right + touchSlop
            val inH = y >= sel.top - touchSlop && y <= sel.bottom + touchSlop
            if (nearL && inH) return 2
            if (nearR && inH) return 3
            if (nearT && inV) return 4
            if (nearB && inV) return 5
            if (sel.contains(x, y)) return 1
            return 0
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (locked) return true
            val x = e.x
            val y = e.y
            val b = imageBounds()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x
                    downY = y
                    mode = hitTest(x, y)
                    if (mode == 0) sel.set(x, y, x, y)
                    if (mode == 1) {
                        offX = x - sel.left
                        offY = y - sel.top
                    }
                }
                MotionEvent.ACTION_MOVE -> when (mode) {
                    0 -> sel.set(min(x, downX), min(y, downY), max(x, downX), max(y, downY))
                    1 -> sel.offsetTo(x - offX, y - offY)
                    2 -> sel.left = x
                    3 -> sel.right = x
                    4 -> sel.top = y
                    5 -> sel.bottom = y
                    6 -> { sel.left = x; sel.top = y }
                    7 -> { sel.right = x; sel.top = y }
                    8 -> { sel.left = x; sel.bottom = y }
                    9 -> { sel.right = x; sel.bottom = y }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 最小尺寸已在拖动过程中硬限制，松手无需处理
                }
            }
            if (sel.left < b.left) sel.left = b.left
            if (sel.top < b.top) sel.top = b.top
            if (sel.right > b.right) sel.right = b.right
            if (sel.bottom > b.bottom) sel.bottom = b.bottom
            // 角点拖过对边时自动翻转，保持 left<right / top<bottom
            if (sel.left > sel.right) { val t = sel.left; sel.left = sel.right; sel.right = t }
            if (sel.top > sel.bottom) { val t = sel.top; sel.top = sel.bottom; sel.bottom = t }
            // 最小尺寸硬限制：拖动过程中推到 60px 就停住（跟随手指的那条边停，另一边保持），绝不缩小又弹大
            val minSize = 60f
            if (sel.width() < minSize) {
                when (mode) {
                    2, 6, 8 -> sel.left = sel.right - minSize          // 拖左边
                    3, 7, 9 -> sel.right = sel.left + minSize          // 拖右边
                    0 -> if (x < downX) sel.left = sel.right - minSize else sel.right = sel.left + minSize
                    else -> { sel.left = sel.right - minSize }
                }
            }
            if (sel.height() < minSize) {
                when (mode) {
                    4, 6, 7 -> sel.top = sel.bottom - minSize          // 拖上边
                    5, 8, 9 -> sel.bottom = sel.top + minSize          // 拖下边
                    0 -> if (y < downY) sel.top = sel.bottom - minSize else sel.bottom = sel.top + minSize
                    else -> { sel.top = sel.bottom - minSize }
                }
            }
            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val sc = canvas.saveLayer(null, null)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            canvas.drawRect(sel, clear)
            canvas.restoreToCount(sc)
            canvas.drawRect(sel, border)
            val r = 12f * resources.displayMetrics.density
            val corners = listOf(
                sel.left to sel.top,
                sel.right to sel.top,
                sel.left to sel.bottom,
                sel.right to sel.bottom
            )
            for ((px, py) in corners) {
                canvas.drawCircle(px, py, r, cornerPaint)
            }
        }
    }
}

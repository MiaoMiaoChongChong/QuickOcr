package com.zcode.quickocr

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 悬浮层控制器：
 * 阶段一 = 冻结屏幕 + 精密标尺框选 / 全屏识别；
 * 阶段二 = 逐字选择 / 按行勾选 / 快速编辑 + 重选区域，关闭即回到原应用。
 */
object OverlayController {

    private const val TAB_CHAR = 0
    private const val TAB_LINE = 1
    private const val TAB_EDIT = 2

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var wm: WindowManager? = null
    private var appCtx: Context? = null
    private var currentRoot: View? = null
    private var screenshot: Bitmap? = null
    private var cropView: CropView? = null
    private var cropImageView: ImageView? = null
    private var lastCropNormRect: RectF? = null
    private var ocrJob: Job? = null
    private var sessionGen: Long = 0L

    /** 由截屏服务调用（任意线程），内部切到主线程。 */
    fun startCrop(context: Context, bitmap: Bitmap) {
        main.post {
            val ctx = context.applicationContext
            closeSession()
            appCtx = ctx
            screenshot = bitmap
            wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            showCropInternal(ctx, bitmap, initialNormRect = null)
        }
    }

    /** 从主界面的内存历史记录直接打开结果面板，可继续逐字选择或复制。 */
    fun openFromHistory(context: Context, lines: List<String>) {
        if (lines.isEmpty()) return
        main.post {
            val ctx = context.applicationContext
            closeSession()
            appCtx = ctx
            wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            showResult(ctx, lines, canRecrop = false)
        }
    }

    private fun showCropInternal(ctx: Context, bitmap: Bitmap, initialNormRect: RectF?) {
        if (bitmap.isRecycled) return
        dismissOverlayWindowOnly()

        val dp = ctx.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).roundToInt()
        val topInset = getTopSafeInset(ctx)
        val bottomInset = getBottomSafeInset(ctx)

        val root = FrameLayout(ctx).apply {
            alpha = 0f
        }
        val iv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
        }
        cropImageView = iv
        root.addView(iv, FrameLayout.LayoutParams(-1, -1))

        val cv = CropView(ctx, bitmap, initialNormRect)
        cropView = cv
        root.addView(cv, FrameLayout.LayoutParams(-1, -1))

        val hint = TextView(ctx).apply {
            text = "拖动标尺框住要提取的文字"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            background = roundedWithStroke(0xCC141720.toInt(), 0x29FFFFFF, d(20).toFloat(), d(1))
            setPadding(d(14), d(7), d(14), d(7))
        }
        root.addView(hint, FrameLayout.LayoutParams(
            -2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = topInset + d(12) })

        val close = TextView(ctx).apply {
            text = "✕ 关闭"
            setTextColor(Color.WHITE)
            textSize = 14f
            background = rippleRounded(0xCC141720.toInt(), d(18).toFloat(), strokeColor = 0x29FFFFFF, strokePx = d(1))
            setPadding(d(13), d(7), d(13), d(7))
            setOnClickListener { closeSession() }
        }
        root.addView(close, FrameLayout.LayoutParams(
            -2, -2, Gravity.TOP or Gravity.END
        ).apply { topMargin = topInset + d(12); rightMargin = d(16) })

        // 识别中遮罩提示胶囊
        val loadingPill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedWithStroke(0xE6141720.toInt(), 0x33FFFFFF, d(24).toFloat(), d(1))
            setPadding(d(18), d(12), d(20), d(12))
            visibility = View.GONE
        }
        val spinner = ProgressBar(ctx).apply {
            indeterminateTintList = ColorStateList.valueOf(0xFF4F8CFF.toInt())
        }
        val loadingTv = TextView(ctx).apply {
            text = "正在离线识别…"
            setTextColor(Color.WHITE)
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(d(10), 0, 0, 0)
        }
        loadingPill.addView(spinner, LinearLayout.LayoutParams(d(22), d(22)))
        loadingPill.addView(loadingTv)
        root.addView(loadingPill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // 底部胶囊操作栏：取消 | 全屏识别 | 识别
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedWithStroke(0xE6181C24.toInt(), 0x2EFFFFFF, d(24).toFloat(), d(1))
            setPadding(d(8), d(8), d(8), d(8))
        }
        val btnCancel = bigButton(ctx, "取消", 0xFF2B3140.toInt(), small = true)
        val btnFull = bigButton(ctx, "全屏识别", 0xFF2B3140.toInt(), small = true)
        val btnOk = bigButton(ctx, "识 别", 0xFF4F8CFF.toInt(), small = true)

        fun setBusy(busy: Boolean) {
            cv.setLocked(busy)
            loadingPill.visibility = if (busy) View.VISIBLE else View.GONE
            btnCancel.isEnabled = !busy
            btnFull.isEnabled = !busy
            btnOk.isEnabled = !busy
            bar.alpha = if (busy) 0.55f else 1f
        }

        btnCancel.setOnClickListener { closeSession() }
        btnFull.setOnClickListener {
            cv.selectFullImage()
            doRecognize(ctx) { busy -> setBusy(busy) }
        }
        btnOk.setOnClickListener {
            doRecognize(ctx) { busy -> setBusy(busy) }
        }
        bar.addView(btnCancel)
        bar.addView(btnFull, marginStart(d(8)))
        bar.addView(btnOk, marginStart(d(8)))

        root.addView(bar, FrameLayout.LayoutParams(
            -2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = bottomInset + d(20) })

        addOverlay(root, bottomAligned = false, edgeBack = true, onBack = { closeSession() })
        root.animate().alpha(1f).setDuration(140L).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun marginStart(v: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.marginStart = v
        return lp
    }

    private fun doRecognize(ctx: Context, onBusy: (Boolean) -> Unit) {
        val cv = cropView ?: return
        val shot = screenshot
        if (shot == null || shot.isRecycled) {
            toast("截图已失效，请重新截取")
            closeSession()
            return
        }
        val bounds = cv.imageBounds()
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        val scale = bounds.width() / shot.width.toFloat()
        val l = ((cv.sel.left - bounds.left) / scale).toInt().coerceIn(0, shot.width - 1)
        val t = ((cv.sel.top - bounds.top) / scale).toInt().coerceIn(0, shot.height - 1)
        val r = ((cv.sel.right - bounds.left) / scale).toInt().coerceIn(l + 1, shot.width)
        val b = ((cv.sel.bottom - bounds.top) / scale).toInt().coerceIn(t + 1, shot.height)

        lastCropNormRect = RectF(
            l / shot.width.toFloat(),
            t / shot.height.toFloat(),
            r / shot.width.toFloat(),
            b / shot.height.toFloat()
        )

        val crop = try {
            Bitmap.createBitmap(shot, l, t, r - l, b - t)
        } catch (e: Exception) {
            toast("裁剪区域失败")
            return
        }

        onBusy(true)
        val myGen = sessionGen
        ocrJob?.cancel()
        ocrJob = scope.launch {
            val res = try {
                OcrEngine.ensureInit(ctx)
                OcrEngine.detect(crop)
            } catch (_: Exception) {
                null
            } finally {
                if (!crop.isRecycled) {
                    crop.recycle()
                }
            }
            main.post {
                if (myGen != sessionGen || currentRoot == null) return@post
                if (res == null) {
                    toast("识别失败")
                    onBusy(false)
                    return@post
                }
                val lines = OcrEngine.sortLines(res.textLines)
                if (lines.isEmpty()) {
                    toast("未识别到文字，可拖动选框重试")
                    onBusy(false)
                } else {
                    OcrHistory.add(lines)
                    showResult(ctx, lines, canRecrop = screenshot?.isRecycled == false)
                }
            }
        }
    }

    private fun showResult(ctx: Context, initialLines: List<String>, canRecrop: Boolean) {
        dismissOverlayWindowOnly()
        val dp = ctx.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).roundToInt()
        val screenH = ctx.resources.displayMetrics.heightPixels
        val bottomInset = getBottomSafeInset(ctx)

        var currentLines = initialLines
        var activeTab = if (Prefs.charSelectMode(ctx)) TAB_CHAR else TAB_LINE
        var withBreaks = Prefs.lineBreak(ctx)

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedTop(0xF5141720.toInt(), d(22).toFloat(), strokeColor = 0x22FFFFFF, strokePx = d(1))
            translationY = 40f * dp
            alpha = 0f
        }

        // 顶部拖拽把手条
        val handleBar = View(ctx).apply {
            background = rounded(0x44FFFFFF, d(2).toFloat())
        }
        panel.addView(handleBar, LinearLayout.LayoutParams(d(38), d(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = d(10)
        })

        // 标题行：标题 + 重选 + 关闭
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(18), d(10), d(14), d(6))
        }
        val titleTv = TextView(ctx).apply {
            text = "识别结果 · ${currentLines.size} 行"
            setTextColor(Color.WHITE)
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))

        if (canRecrop && screenshot != null && !screenshot!!.isRecycled) {
            val btnRecrop = TextView(ctx).apply {
                text = "重选区域"
                setTextColor(0xFFD6E2FF.toInt())
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                background = rippleRounded(0xFF232838.toInt(), d(10).toFloat())
                setPadding(d(10), d(5), d(10), d(5))
                setOnClickListener {
                    val shot = screenshot
                    if (shot != null && !shot.isRecycled) {
                        showCropInternal(ctx, shot, lastCropNormRect)
                    } else {
                        toast("原截图已释放，请重新截屏")
                    }
                }
            }
            header.addView(btnRecrop, LinearLayout.LayoutParams(-2, -2).apply {
                marginEnd = d(8)
            })
        }

        val closeBtn = TextView(ctx).apply {
            text = "✕ 关闭"
            setTextColor(0xFF9EA5B5.toInt())
            textSize = 14.5f
            background = rippleRounded(Color.TRANSPARENT, d(10).toFloat())
            setPadding(d(8), d(5), d(8), d(5))
            setOnClickListener { closeSession() }
        }
        header.addView(closeBtn)
        panel.addView(header, LinearLayout.LayoutParams(-1, -2))

        // 模式分段条（逐字 | 按行 | 编辑） + 换行勾选
        val subBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(16), d(2), d(16), d(6))
        }

        val modeSwitch = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(0xFF212633.toInt(), d(10).toFloat())
            setPadding(d(3), d(3), d(3), d(3))
        }
        val tabChar = TextView(ctx).apply {
            text = "逐字"
            textSize = 12.5f
            setPadding(d(11), d(5), d(11), d(5))
        }
        val tabLine = TextView(ctx).apply {
            text = "按行"
            textSize = 12.5f
            setPadding(d(11), d(5), d(11), d(5))
        }
        val tabEdit = TextView(ctx).apply {
            text = "编辑"
            textSize = 12.5f
            setPadding(d(11), d(5), d(11), d(5))
        }
        modeSwitch.addView(tabChar)
        modeSwitch.addView(tabLine)
        modeSwitch.addView(tabEdit)
        subBar.addView(modeSwitch)

        val spacer = View(ctx)
        subBar.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        val lineBreakCb = CheckBox(ctx).apply {
            text = "每行换行"
            setTextColor(0xFFB9C0CE.toInt())
            buttonTintList = ColorStateList.valueOf(0xFF4F8CFF.toInt())
            textSize = 12.5f
            isChecked = withBreaks
        }
        subBar.addView(lineBreakCb)
        panel.addView(subBar, LinearLayout.LayoutParams(-1, -2))

        // ---------------- 模式 0：逐字选择 ----------------
        val charGroup = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val controlBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(14), d(2), d(14), d(6))
        }

        val hintIdle = TextView(ctx).apply {
            text = "直接划选文字，或点起点再点终点；拖动水滴可微调"
            setTextColor(0xFF8E96A4.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, d(3), 0, d(3))
        }

        val nudgeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val btnStartMinus = smallNudgeBtn(ctx, "起点 ◀")
        val btnStartPlus = smallNudgeBtn(ctx, "▶")
        val selCountTv = TextView(ctx).apply {
            setTextColor(0xFF8AB4FF.toInt())
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val btnEndMinus = smallNudgeBtn(ctx, "◀")
        val btnEndPlus = smallNudgeBtn(ctx, "▶ 终点")
        nudgeRow.addView(btnStartMinus)
        nudgeRow.addView(btnStartPlus, marginStart(d(5)))
        nudgeRow.addView(selCountTv, LinearLayout.LayoutParams(0, -2, 1f))
        nudgeRow.addView(btnEndMinus)
        nudgeRow.addView(btnEndPlus, marginStart(d(5)))

        val selPreviewTv = TextView(ctx).apply {
            setTextColor(0xFFD0D5DF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(d(6), d(4), d(6), 0)
            visibility = View.GONE
        }

        controlBox.addView(hintIdle)
        controlBox.addView(nudgeRow)
        controlBox.addView(selPreviewTv)
        charGroup.addView(controlBox, LinearLayout.LayoutParams(-1, -2))

        val charScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            background = roundedWithStroke(0xFF1B1F2A.toInt(), 0x1AFFFFFF, d(12).toFloat(), d(1))
        }
        val charView = CharSelectableTextView(ctx).apply {
            text = buildJoinedText(currentLines, withBreaks)
            attachScrollView(charScroll)
        }
        charScroll.addView(charView, FrameLayout.LayoutParams(-1, -2))
        charGroup.addView(charScroll, LinearLayout.LayoutParams(-1, (screenH * 0.38f).toInt()).apply {
            leftMargin = d(12)
            rightMargin = d(12)
            bottomMargin = d(6)
        })

        btnStartMinus.setOnClickListener { charView.nudgeStart(-1) }
        btnStartPlus.setOnClickListener { charView.nudgeStart(1) }
        btnEndMinus.setOnClickListener { charView.nudgeEnd(-1) }
        btnEndPlus.setOnClickListener { charView.nudgeEnd(1) }

        // ---------------- 模式 1：按行勾选 ----------------
        val lineGroup = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val lineScroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = true }
        lineScroll.layoutParams = LinearLayout.LayoutParams(-1, (screenH * 0.40f).toInt())

        val lineContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(12), d(4), d(12), d(8))
        }
        val checks = mutableListOf<CheckBox>()

        fun rebuildLineRows() {
            lineContainer.removeAllViews()
            checks.clear()
            currentLines.forEachIndexed { i, lineText ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rippleRounded(0xFF1F2430.toInt(), d(10).toFloat())
                    setPadding(d(10), d(8), d(12), d(8))
                }
                val cb = CheckBox(ctx).apply {
                    isClickable = false
                    buttonTintList = ColorStateList.valueOf(0xFF4F8CFF.toInt())
                }
                val tv = TextView(ctx).apply {
                    this.text = lineText
                    setTextColor(Color.WHITE)
                    textSize = 15.5f
                    setPadding(d(8), d(2), d(4), d(2))
                }
                row.addView(cb)
                row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
                row.setOnClickListener { cb.toggle() }
                row.setOnLongClickListener {
                    row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    copyText(ctx, lineText)
                    toast("已复制：${lineText.take(20)}${if (lineText.length > 20) "…" else ""}")
                    true
                }
                lineContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = if (i > 0) d(6) else 0
                })
                checks.add(cb)
            }
        }
        rebuildLineRows()
        lineScroll.addView(lineContainer)
        lineGroup.addView(lineScroll)

        val lineFootHint = TextView(ctx).apply {
            text = "长按任意一行可直接复制该行"
            setTextColor(0xFF7D8596.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, d(6))
        }
        lineGroup.addView(lineFootHint, LinearLayout.LayoutParams(-1, -2))

        // ---------------- 模式 2：快捷编辑纠错 ----------------
        val editGroup = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val editBox = EditText(ctx).apply {
            setText(buildJoinedText(currentLines, withBreaks))
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF6B7280.toInt())
            hint = "在此修改错别字后直接复制"
            textSize = 15.5f
            gravity = Gravity.TOP or Gravity.START
            background = roundedWithStroke(0xFF1B1F2A.toInt(), 0x264F8CFF, d(12).toFloat(), d(1))
            setPadding(d(14), d(12), d(14), d(12))
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isVerticalScrollBarEnabled = true
        }
        editGroup.addView(editBox, LinearLayout.LayoutParams(-1, (screenH * 0.36f).toInt()).apply {
            leftMargin = d(12)
            rightMargin = d(12)
            bottomMargin = d(6)
        })
        val editHint = TextView(ctx).apply {
            text = "修改后的内容会自动同步到逐字与按行视图"
            setTextColor(0xFF7D8596.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, d(6))
        }
        editGroup.addView(editHint, LinearLayout.LayoutParams(-1, -2))

        // ---------------- 底部操作栏 ----------------
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(d(12), d(6), d(12), bottomInset + d(14))
        }
        val btnAll = bigButton(ctx, "全选", 0xFF313746.toInt(), small = true)
        val btnCopy = bigButton(ctx, "复制选中", 0xFF4F8CFF.toInt(), small = true)
        val btnCopyAll = bigButton(ctx, "复制全部", 0xFF2B5B9E.toInt(), small = true)

        fun updateCharUi() {
            val totalLen = charView.text?.length ?: 0
            if (activeTab == TAB_CHAR && charView.hasSelection()) {
                val selected = charView.getSelectedText()
                hintIdle.visibility = View.GONE
                nudgeRow.visibility = View.VISIBLE
                selPreviewTv.visibility = View.VISIBLE
                selCountTv.text = "已选 ${selected.length} 字"
                val previewClean = if (selected.length <= 20) {
                    selected.replace('\n', '↵')
                } else {
                    "${selected.take(9).replace('\n', '↵')} … ${selected.takeLast(9).replace('\n', '↵')}"
                }
                selPreviewTv.text = "「$previewClean」"
                val fullSelected = totalLen > 0 && charView.selStart == 0 && charView.selEnd == totalLen
                btnAll.text = if (fullSelected) "清空" else "全选"
            } else {
                hintIdle.visibility = View.VISIBLE
                nudgeRow.visibility = View.GONE
                selPreviewTv.visibility = View.GONE
                btnAll.text = "全选"
            }
        }

        fun syncEditsBackToLines() {
            val edited = editBox.text?.toString() ?: ""
            val updated = edited.split('\n').filter { it.isNotEmpty() }
            currentLines = if (updated.isEmpty() && edited.isNotBlank()) listOf(edited) else updated
            titleTv.text = "识别结果 · ${currentLines.size} 行"
            charView.setTextPreservingSelection(buildJoinedText(currentLines, withBreaks), null)
            rebuildLineRows()
        }

        fun styleTab(tab: TextView, selected: Boolean) {
            tab.background = if (selected) rounded(0xFF4F8CFF.toInt(), d(8).toFloat()) else null
            tab.setTextColor(if (selected) Color.WHITE else 0xFF9EA5B5.toInt())
            tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }

        fun applyTabUi() {
            charGroup.visibility = if (activeTab == TAB_CHAR) View.VISIBLE else View.GONE
            lineGroup.visibility = if (activeTab == TAB_LINE) View.VISIBLE else View.GONE
            editGroup.visibility = if (activeTab == TAB_EDIT) View.VISIBLE else View.GONE
            lineBreakCb.visibility = if (activeTab == TAB_EDIT) View.INVISIBLE else View.VISIBLE

            styleTab(tabChar, activeTab == TAB_CHAR)
            styleTab(tabLine, activeTab == TAB_LINE)
            styleTab(tabEdit, activeTab == TAB_EDIT)

            if (activeTab == TAB_EDIT) {
                btnAll.visibility = View.GONE
                btnCopy.visibility = View.GONE
                btnCopyAll.text = "复制修改结果"
                editBox.requestFocus()
            } else {
                btnAll.visibility = View.VISIBLE
                btnCopy.visibility = View.VISIBLE
                btnCopyAll.text = "复制全部"
                hideIme(ctx, editBox)
            }
            updateCharUi()
        }

        fun switchTab(nextTab: Int) {
            if (activeTab == nextTab) return
            if (activeTab == TAB_EDIT) {
                syncEditsBackToLines()
            } else if (nextTab == TAB_EDIT) {
                editBox.setText(buildJoinedText(currentLines, withBreaks))
                editBox.setSelection(editBox.text?.length ?: 0)
            }
            activeTab = nextTab
            if (nextTab == TAB_CHAR) Prefs.setCharSelectMode(ctx, true)
            if (nextTab == TAB_LINE) Prefs.setCharSelectMode(ctx, false)
            applyTabUi()
        }

        charView.onSelectionChanged = { _, _ -> updateCharUi() }
        tabChar.setOnClickListener { switchTab(TAB_CHAR) }
        tabLine.setOnClickListener { switchTab(TAB_LINE) }
        tabEdit.setOnClickListener { switchTab(TAB_EDIT) }

        lineBreakCb.setOnCheckedChangeListener { _, checked ->
            val prev = withBreaks
            withBreaks = checked
            Prefs.setLineBreak(ctx, checked)
            val newText = buildJoinedText(currentLines, checked)
            charView.setTextPreservingSelection(newText) { off ->
                remapOffsetBetweenBreakModes(currentLines, off, prev, checked)
            }
            editBox.setText(newText)
        }

        btnAll.setOnClickListener {
            if (activeTab == TAB_CHAR) {
                val totalLen = charView.text?.length ?: 0
                if (totalLen > 0 && charView.selStart == 0 && charView.selEnd == totalLen) {
                    charView.clearSelection()
                } else {
                    charView.selectAll()
                }
            } else if (activeTab == TAB_LINE) {
                val anyUnchecked = checks.any { !it.isChecked }
                checks.forEach { it.isChecked = anyUnchecked }
            }
        }

        btnCopy.setOnClickListener {
            if (activeTab == TAB_CHAR) {
                val sel = charView.getSelectedText()
                if (sel.isEmpty()) {
                    toast("先划选文字，或点击起点与终点字符")
                } else {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    copyText(ctx, sel)
                    toast("已复制 ${sel.length} 字")
                    closeSession()
                }
            } else if (activeTab == TAB_LINE) {
                val sel = currentLines.filterIndexed { i, _ -> checks[i].isChecked }
                if (sel.isEmpty()) {
                    toast("先勾选文字，或点「全选」")
                } else {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    copyText(ctx, sel.joinToString(if (withBreaks) "\n" else ""))
                    toast("已复制 ${sel.size} 行")
                    closeSession()
                }
            }
        }

        btnCopyAll.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (activeTab == TAB_EDIT) {
                val edited = editBox.text?.toString() ?: ""
                if (edited.isEmpty()) {
                    toast("当前内容为空")
                } else {
                    copyText(ctx, edited)
                    toast("已复制 ${edited.length} 字")
                    closeSession()
                }
            } else {
                val full = buildJoinedText(currentLines, withBreaks)
                copyText(ctx, full)
                toast(if (activeTab == TAB_CHAR) "已复制全部 ${full.length} 字" else "已复制全部 ${currentLines.size} 行")
                closeSession()
            }
        }

        footer.addView(btnAll)
        footer.addView(btnCopy, marginStart(d(10)))
        footer.addView(btnCopyAll, marginStart(d(10)))

        panel.addView(charGroup, LinearLayout.LayoutParams(-1, -2))
        panel.addView(lineGroup, LinearLayout.LayoutParams(-1, -2))
        panel.addView(editGroup, LinearLayout.LayoutParams(-1, -2))
        panel.addView(footer, LinearLayout.LayoutParams(-1, -2))

        applyTabUi()
        addOverlay(panel, bottomAligned = true, edgeBack = false, onBack = { closeSession() })
        panel.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(170L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun buildJoinedText(lines: List<String>, withBreaks: Boolean): String =
        lines.joinToString(if (withBreaks) "\n" else "")

    private fun remapOffsetBetweenBreakModes(
        lines: List<String>,
        offset: Int,
        fromBreak: Boolean,
        toBreak: Boolean
    ): Int {
        if (fromBreak == toBreak || lines.isEmpty()) return offset
        var fromPos = 0
        var toPos = 0
        for (i in lines.indices) {
            val len = lines[i].length
            if (offset <= fromPos + len) {
                return toPos + (offset - fromPos)
            }
            fromPos += len
            toPos += len
            if (i < lines.lastIndex) {
                val fromSep = if (fromBreak) 1 else 0
                val toSep = if (toBreak) 1 else 0
                if (offset <= fromPos + fromSep) {
                    return toPos + (if (offset > fromPos) toSep else 0)
                }
                fromPos += fromSep
                toPos += toSep
            }
        }
        return toPos
    }

    private fun smallNudgeBtn(ctx: Context, label: String): TextView {
        val dp = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            background = rippleRounded(0xFF2B3140.toInt(), 8f * dp)
            setPadding((9f * dp).roundToInt(), (5f * dp).roundToInt(), (9f * dp).roundToInt(), (5f * dp).roundToInt())
        }
    }

    private fun addOverlay(
        content: View,
        bottomAligned: Boolean,
        edgeBack: Boolean,
        onBack: () -> Unit
    ) {
        val v: View = if (edgeBack) {
            EdgeBackFrameLayout(content.context, onBack).apply {
                addView(content, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
        } else {
            BackDispatchFrameLayout(content.context, onBack).apply {
                addView(content, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ))
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (bottomAligned) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        if (bottomAligned) {
            params.gravity = Gravity.BOTTOM
            @Suppress("DEPRECATION")
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        }
        wm?.addView(v, params)
        currentRoot = v
        v.isFocusableInTouchMode = true
        v.requestFocus()
        v.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                onBack()
                true
            } else false
        }
    }

    /** 仅移除当前悬浮视图，保留 screenshot（供 Crop <-> Result 重选区域切换复用）。 */
    private fun dismissOverlayWindowOnly() {
        val v = currentRoot
        currentRoot = null
        cropView = null
        cropImageView?.setImageDrawable(null)
        cropImageView = null
        if (v != null) {
            hideIme(v.context, v)
            try {
                wm?.removeView(v)
            } catch (_: Exception) {}
        }
    }

    /** 彻底结束本次识别会话：取消后台任务、移除视图并回收全屏截图 Bitmap。 */
    private fun closeSession() {
        sessionGen++
        ocrJob?.cancel()
        ocrJob = null
        dismissOverlayWindowOnly()
        val shot = screenshot
        screenshot = null
        lastCropNormRect = null
        if (shot != null && !shot.isRecycled) {
            shot.recycle()
        }
    }

    private fun hideIme(ctx: Context, view: View) {
        try {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        } catch (_: Exception) {}
    }

    /** 确保容器内任意子控件拿到焦点后，实体/手势返回键仍能可靠关闭悬浮层。 */
    private open class BackDispatchFrameLayout(
        context: Context,
        protected val onBack: () -> Unit
    ) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    /**
     * 覆盖 MIUI/HyperOS 全面屏侧滑返回：系统手势有时不把 KEYCODE_BACK 发给 overlay 窗口，
     * 这里在 dispatchTouchEvent 里识别「从左右边缘起始的水平滑动」直接关闭本层。
     */
    private class EdgeBackFrameLayout(
        context: Context,
        onBack: () -> Unit
    ) : BackDispatchFrameLayout(context, onBack) {
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

    private fun getTopSafeInset(ctx: Context): Int {
        val wmSys = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ins = wmSys.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
            )
            if (ins.top > 0) return ins.top
        }
        val resId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) ctx.resources.getDimensionPixelSize(resId)
        else (28 * ctx.resources.displayMetrics.density).roundToInt()
    }

    private fun getBottomSafeInset(ctx: Context): Int {
        val wmSys = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ins = wmSys.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.navigationBars()
            )
            if (ins.bottom > 0) return ins.bottom
        }
        return (20 * ctx.resources.displayMetrics.density).roundToInt()
    }

    private fun bigButton(ctx: Context, label: String, bg: Int, small: Boolean = false): TextView {
        val dp = ctx.resources.displayMetrics.density
        val padH = ((if (small) 18f else 30f) * dp).roundToInt()
        val padV = ((if (small) 9f else 12f) * dp).roundToInt()
        return TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = if (small) 14f else 16f
            setTypeface(typeface, Typeface.BOLD)
            background = rippleRounded(bg, (if (small) 14f else 16f) * dp)
            setPadding(padH, padV, padH, padV)
        }
    }

    private fun rippleRounded(
        color: Int,
        radiusPx: Float,
        strokeColor: Int = 0,
        strokePx: Int = 0
    ): RippleDrawable {
        val content = if (strokePx > 0) {
            roundedWithStroke(color, strokeColor, radiusPx, strokePx)
        } else {
            rounded(color, radiusPx)
        }
        val mask = rounded(Color.WHITE, radiusPx)
        return RippleDrawable(ColorStateList.valueOf(0x38FFFFFF), content, mask)
    }

    private fun rounded(color: Int, radiusPx: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
        }
    }

    private fun roundedWithStroke(color: Int, strokeColor: Int, radiusPx: Float, strokePx: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            setStroke(strokePx, strokeColor)
        }
    }

    private fun roundedTop(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(
                radiusPx, radiusPx, radiusPx, radiusPx,
                0f, 0f, 0f, 0f
            )
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }
    }

    /** 冻结画面上的精密选区视图：L 型角标 + 四边微调杠 + 拖拽三分网格与像素尺寸气泡。 */
    private class CropView(
        context: Context,
        val bitmap: Bitmap,
        private val initialNormRect: RectF? = null
    ) : View(context) {

        val sel = RectF()
        private var mode = 0
        private var isDragging = false
        private var downX = 0f
        private var downY = 0f
        private var offX = 0f
        private var offY = 0f
        private var locked = false

        private val dp = resources.displayMetrics.density
        private val dim = Paint().apply { color = 0x73000000 }
        private val clear = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xCCFFFFFF.toInt()
            strokeWidth = 1.2f * dp
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x44FFFFFF
            strokeWidth = 1f * dp
        }
        private val handleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x66000000
            strokeWidth = 5.5f * dp
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF4F8CFF.toInt()
            strokeWidth = 3.5f * dp
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val edgeBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 3f * dp
            strokeCap = Paint.Cap.ROUND
        }
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC141720.toInt()
            style = Paint.Style.FILL
        }
        private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11.5f * dp
            typeface = Typeface.DEFAULT_BOLD
        }
        private val touchSlop = 32f * dp

        fun setLocked(l: Boolean) {
            locked = l
        }

        fun selectFullImage() {
            val b = imageBounds()
            sel.set(b)
            isDragging = false
            invalidate()
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
                val norm = initialNormRect
                if (norm != null) {
                    sel.set(
                        b.left + bw * norm.left.coerceIn(0f, 0.95f),
                        b.top + bh * norm.top.coerceIn(0f, 0.95f),
                        b.left + bw * norm.right.coerceIn(0.05f, 1f),
                        b.top + bh * norm.bottom.coerceIn(0.05f, 1f)
                    )
                } else {
                    sel.set(
                        b.left + bw * 0.1f,
                        b.top + bh * 0.18f,
                        b.right - bw * 0.1f,
                        b.top + bh * 0.55f
                    )
                }
            }
        }

        private fun hitTest(x: Float, y: Float): Int {
            val nearL = abs(x - sel.left) <= touchSlop
            val nearR = abs(x - sel.right) <= touchSlop
            val nearT = abs(y - sel.top) <= touchSlop
            val nearB = abs(y - sel.bottom) <= touchSlop
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
                    isDragging = true
                    if (mode == 0) sel.set(x, y, x, y)
                    if (mode == 1) {
                        offX = x - sel.left
                        offY = y - sel.top
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 1) {
                        val curW = sel.width()
                        val curH = sel.height()
                        val nl = (x - offX).coerceIn(b.left, (b.right - curW).coerceAtLeast(b.left))
                        val nt = (y - offY).coerceIn(b.top, (b.bottom - curH).coerceAtLeast(b.top))
                        sel.set(nl, nt, nl + curW, nt + curH)
                        invalidate()
                        return true
                    }
                    when (mode) {
                        0 -> sel.set(min(x, downX), min(y, downY), max(x, downX), max(y, downY))
                        2 -> sel.left = x
                        3 -> sel.right = x
                        4 -> sel.top = y
                        5 -> sel.bottom = y
                        6 -> { sel.left = x; sel.top = y }
                        7 -> { sel.right = x; sel.top = y }
                        8 -> { sel.left = x; sel.bottom = y }
                        9 -> { sel.right = x; sel.bottom = y }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                }
            }

            if (mode != 1) {
                if (sel.left < b.left) sel.left = b.left
                if (sel.top < b.top) sel.top = b.top
                if (sel.right > b.right) sel.right = b.right
                if (sel.bottom > b.bottom) sel.bottom = b.bottom
                if (sel.left > sel.right) { val t = sel.left; sel.left = sel.right; sel.right = t }
                if (sel.top > sel.bottom) { val t = sel.top; sel.top = sel.bottom; sel.bottom = t }

                val minSize = min(48f * dp, min(b.width(), b.height()))
                if (sel.width() < minSize) {
                    when (mode) {
                        2, 6, 8 -> sel.left = (sel.right - minSize).coerceAtLeast(b.left)
                        3, 7, 9 -> sel.right = (sel.left + minSize).coerceAtMost(b.right)
                        0 -> if (x < downX) {
                            sel.left = (sel.right - minSize).coerceAtLeast(b.left)
                        } else {
                            sel.right = (sel.left + minSize).coerceAtMost(b.right)
                        }
                        else -> sel.left = (sel.right - minSize).coerceAtLeast(b.left)
                    }
                }
                if (sel.height() < minSize) {
                    when (mode) {
                        4, 6, 7 -> sel.top = (sel.bottom - minSize).coerceAtLeast(b.top)
                        5, 8, 9 -> sel.bottom = (sel.top + minSize).coerceAtMost(b.bottom)
                        0 -> if (y < downY) {
                            sel.top = (sel.bottom - minSize).coerceAtLeast(b.top)
                        } else {
                            sel.bottom = (sel.top + minSize).coerceAtMost(b.bottom)
                        }
                        else -> sel.top = (sel.bottom - minSize).coerceAtLeast(b.top)
                    }
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

            if (isDragging && sel.width() > 60f * dp && sel.height() > 60f * dp) {
                val thirdW = sel.width() / 3f
                val thirdH = sel.height() / 3f
                canvas.drawLine(sel.left + thirdW, sel.top, sel.left + thirdW, sel.bottom, gridPaint)
                canvas.drawLine(sel.left + 2f * thirdW, sel.top, sel.left + 2f * thirdW, sel.bottom, gridPaint)
                canvas.drawLine(sel.left, sel.top + thirdH, sel.right, sel.top + thirdH, gridPaint)
                canvas.drawLine(sel.left, sel.top + 2f * thirdH, sel.right, sel.top + 2f * thirdH, gridPaint)
            }

            // 四边中点拖动条
            val barHalf = min(11f * dp, min(sel.width(), sel.height()) * 0.25f)
            if (barHalf > 3f * dp) {
                val midX = sel.centerX()
                val midY = sel.centerY()
                canvas.drawLine(midX - barHalf, sel.top, midX + barHalf, sel.top, edgeBarPaint)
                canvas.drawLine(midX - barHalf, sel.bottom, midX + barHalf, sel.bottom, edgeBarPaint)
                canvas.drawLine(sel.left, midY - barHalf, sel.left, midY + barHalf, edgeBarPaint)
                canvas.drawLine(sel.right, midY - barHalf, sel.right, midY + barHalf, edgeBarPaint)
            }

            // 四角 L 型精准直角标尺（底影 + 主色亮线）
            val arm = min(20f * dp, min(sel.width(), sel.height()) * 0.35f)
            drawCornerBracket(canvas, sel.left, sel.top, arm, arm)
            drawCornerBracket(canvas, sel.right, sel.top, -arm, arm)
            drawCornerBracket(canvas, sel.left, sel.bottom, arm, -arm)
            drawCornerBracket(canvas, sel.right, sel.bottom, -arm, -arm)

            // 拖拽时显示像素尺寸胶囊
            if (isDragging) {
                val b = imageBounds()
                if (b.width() > 0f) {
                    val scale = b.width() / bitmap.width.toFloat()
                    val pxW = (sel.width() / scale).roundToInt().coerceAtLeast(1)
                    val pxH = (sel.height() / scale).roundToInt().coerceAtLeast(1)
                    val label = "$pxW × $pxH"
                    val tw = badgeTextPaint.measureText(label)
                    val padH = 8f * dp
                    val padV = 4f * dp
                    val bw = tw + padH * 2f
                    val bh = 22f * dp
                    val bx = sel.left.coerceIn(8f * dp, (width - bw - 8f * dp).coerceAtLeast(8f * dp))
                    val by = if (sel.top > bh + 18f * dp) sel.top - bh - 8f * dp else sel.top + 8f * dp
                    val r = RectF(bx, by, bx + bw, by + bh)
                    canvas.drawRoundRect(r, 8f * dp, 8f * dp, badgeBgPaint)
                    canvas.drawText(label, bx + padH, by + bh - padV - 2f * dp, badgeTextPaint)
                }
            }
        }

        private fun drawCornerBracket(canvas: Canvas, cx: Float, cy: Float, dx: Float, dy: Float) {
            canvas.drawLine(cx, cy + dy, cx, cy, handleShadowPaint)
            canvas.drawLine(cx, cy, cx + dx, cy, handleShadowPaint)
            canvas.drawLine(cx, cy + dy, cx, cy, handlePaint)
            canvas.drawLine(cx, cy, cx + dx, cy, handlePaint)
        }
    }
}

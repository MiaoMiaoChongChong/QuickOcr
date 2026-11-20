package com.zcode.quickocr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 悬浮窗内使用的自绘逐字选择 TextView。
 *
 * 不依赖系统的 setTextIsSelectable(true)（悬浮窗无 Activity Token，系统 PopupWindow
 * 水滴与 ActionMode 极易触发 BadTokenException 或在 MIUI/HyperOS 上失效），全部选区高亮与
 * 水滴手柄均在 Canvas 上自绘，并通过触摸与外部微调接口精确到单个字符。
 */
class CharSelectableTextView(context: Context) : TextView(context) {

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_DRAG_START = 1
        private const val MODE_DRAG_END = 2
        private const val MODE_DRAG_SWEEP = 3
        private const val ACCENT_COLOR = 0xFF4F8CFF.toInt()
        private const val HIGHLIGHT_COLOR = 0x664F8CFF
    }

    var onSelectionChanged: ((start: Int, end: Int) -> Unit)? = null

    var selStart: Int = 0
        private set
    var selEnd: Int = 0
        private set

    private val dp = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val handleHitRadius = 28f * dp
    private val handleRadius = 10f * dp
    private val cursorWidth = 2f * dp
    private val edgeAutoScrollZone = 36f * dp
    private val autoScrollStepPx = (8f * dp).toInt().coerceAtLeast(1)

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HIGHLIGHT_COLOR
        style = Paint.Style.FILL
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT_COLOR
        style = Paint.Style.FILL
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT_COLOR
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
    }
    private val handlePath = Path()
    private val highlightRect = RectF()
    private val handleRect = RectF()

    private val startHandleCenter = PointF(Float.NaN, Float.NaN)
    private val endHandleCenter = PointF(Float.NaN, Float.NaN)

    private var scrollViewRef: WeakReference<ScrollView>? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private var mode = MODE_NONE
    private var dragAnchor = 0
    private var grabDx = 0f
    private var grabDy = 0f

    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastRawY = 0f
    private var downChar = -1
    private var movedBeyondSlop = false

    private var sweepAnchorChar = 0
    private var tapAnchorChar = 0
    private var justAnchoredSingleChar = false

    private var autoScrolling = false
    private val svRect = Rect()

    private val longPressRunnable = Runnable {
        if (mode == MODE_NONE && !movedBeyondSlop && downChar >= 0 && text.isNotEmpty()) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            parent?.requestDisallowInterceptTouchEvent(true)
            mode = MODE_DRAG_SWEEP
            sweepAnchorChar = downChar
            justAnchoredSingleChar = false
            setSelectionRangeInternal(downChar, charEnd(downChar), resetTapAnchor = false)
        }
    }

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (!autoScrolling || mode == MODE_NONE) return
            val sv = scrollViewRef?.get()
            if (sv == null || !sv.isAttachedToWindow) {
                autoScrolling = false
                return
            }
            if (!sv.getGlobalVisibleRect(svRect)) {
                autoScrolling = false
                return
            }
            var deltaY = 0
            if (lastRawY < svRect.top + edgeAutoScrollZone && sv.canScrollVertically(-1)) {
                deltaY = -autoScrollStepPx
            } else if (lastRawY > svRect.bottom - edgeAutoScrollZone && sv.canScrollVertically(1)) {
                deltaY = autoScrollStepPx
            }
            if (deltaY != 0) {
                val beforeY = sv.scrollY
                sv.scrollBy(0, deltaY)
                val actualDelta = sv.scrollY - beforeY
                if (actualDelta != 0) {
                    lastTouchY += actualDelta
                    updateDragAt(lastTouchX, lastTouchY)
                }
            }
            uiHandler.postDelayed(this, 16L)
        }
    }

    init {
        setTextIsSelectable(false)
        setTextColor(Color.WHITE)
        textSize = 16.5f
        setLineSpacing(10f * dp, 1f)
        setPadding(
            (22f * dp).toInt(),
            (14f * dp).toInt(),
            (22f * dp).toInt(),
            (38f * dp).toInt()
        )
    }

    fun attachScrollView(sv: ScrollView) {
        scrollViewRef = WeakReference(sv)
    }

    override fun hasSelection(): Boolean = selEnd > selStart

    fun getSelectedText(): String {
        val t = text
        if (!hasSelection() || t.isNullOrEmpty()) return ""
        val s = selStart.coerceIn(0, t.length)
        val e = selEnd.coerceIn(s, t.length)
        return t.subSequence(s, e).toString()
    }

    fun selectAll() {
        val len = text?.length ?: 0
        if (len <= 0) return
        justAnchoredSingleChar = false
        setSelectionRangeInternal(0, len, resetTapAnchor = true)
    }

    fun clearSelection() {
        justAnchoredSingleChar = false
        if (selStart == 0 && selEnd == 0) return
        selStart = 0
        selEnd = 0
        startHandleCenter.set(Float.NaN, Float.NaN)
        endHandleCenter.set(Float.NaN, Float.NaN)
        invalidate()
        onSelectionChanged?.invoke(0, 0)
    }

    fun setSelectionRange(start: Int, end: Int) {
        justAnchoredSingleChar = false
        setSelectionRangeInternal(start, end, resetTapAnchor = true)
    }

    /** 按 codepoint 微调起点（delta = -1 向左扩，+1 向右缩），保证不越过终点且不劈裂代理对。 */
    fun nudgeStart(delta: Int) {
        if (!hasSelection() || delta == 0) return
        val newStart = if (delta < 0) prevCodepointBoundary(selStart) else nextCodepointBoundary(selStart)
        if (newStart in 0 until selEnd && newStart != selStart) {
            justAnchoredSingleChar = false
            setSelectionRangeInternal(newStart, selEnd, resetTapAnchor = true)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /** 按 codepoint 微调终点（delta = -1 向左缩，+1 向右扩），保证不越过起点且不劈裂代理对。 */
    fun nudgeEnd(delta: Int) {
        if (!hasSelection() || delta == 0) return
        val len = text?.length ?: return
        val newEnd = if (delta < 0) prevCodepointBoundary(selEnd) else nextCodepointBoundary(selEnd)
        if (newEnd in (selStart + 1)..len && newEnd != selEnd) {
            justAnchoredSingleChar = false
            setSelectionRangeInternal(selStart, newEnd, resetTapAnchor = true)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun setTextPreservingSelection(newText: String, remapOffset: ((Int) -> Int)? = null) {
        val hadSel = hasSelection()
        val oldS = selStart
        val oldE = selEnd
        text = newText
        if (hadSel && remapOffset != null && newText.isNotEmpty()) {
            val mappedS = snapStartBoundary(newText, remapOffset(oldS).coerceIn(0, newText.length))
            val mappedE = snapEndBoundary(newText, remapOffset(oldE).coerceIn(0, newText.length))
            if (mappedE > mappedS) {
                justAnchoredSingleChar = false
                selStart = mappedS
                selEnd = mappedE
                invalidate()
                onSelectionChanged?.invoke(selStart, selEnd)
                return
            }
        }
        clearSelection()
    }

    override fun onDetachedFromWindow() {
        uiHandler.removeCallbacks(longPressRunnable)
        stopAutoScroll()
        scrollViewRef = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val lay = layout
        val t = text
        if (lay != null && !t.isNullOrEmpty() && hasSelection()) {
            val s = selStart.coerceIn(0, t.length)
            val e = selEnd.coerceIn(s, t.length)
            if (e > s) {
                val padL = (totalPaddingLeft - scrollX).toFloat()
                val padT = (totalPaddingTop - scrollY).toFloat()
                val startLine = lay.getLineForOffset(s)
                val startX = padL + lay.getPrimaryHorizontal(s)
                val endLine = lay.getLineForOffset((e - 1).coerceAtLeast(0))
                val endX = padL + if (e >= lay.getLineEnd(endLine)) {
                    lay.getLineRight(endLine)
                } else {
                    lay.getPrimaryHorizontal(e)
                }

                for (line in startLine..endLine) {
                    val segLeft = if (line == startLine) startX else padL + lay.getLineLeft(line)
                    val segRight = if (line == endLine) endX else padL + lay.getLineRight(line)
                    val l = min(segLeft, segRight)
                    val r = max(segLeft, segRight)
                    if (r - l >= 1.5f) {
                        val top = padT + lay.getLineTop(line) + 1f * dp
                        val bottom = padT + visualLineBottom(lay, line)
                        highlightRect.set(l, top, r, max(top + 2f, bottom))
                        canvas.drawRoundRect(highlightRect, 4f * dp, 4f * dp, highlightPaint)
                    }
                }
            }
        }

        super.onDraw(canvas)

        if (lay != null && !t.isNullOrEmpty() && hasSelection()) {
            val s = selStart.coerceIn(0, t.length)
            val e = selEnd.coerceIn(s, t.length)
            if (e > s) {
                val padL = (totalPaddingLeft - scrollX).toFloat()
                val padT = (totalPaddingTop - scrollY).toFloat()

                val startLine = lay.getLineForOffset(s)
                val startX = padL + lay.getPrimaryHorizontal(s)
                val startTop = padT + lay.getLineTop(startLine)
                val startBot = padT + visualLineBottom(lay, startLine)

                val endLine = lay.getLineForOffset((e - 1).coerceAtLeast(0))
                val endX = padL + if (e >= lay.getLineEnd(endLine)) {
                    lay.getLineRight(endLine)
                } else {
                    lay.getPrimaryHorizontal(e)
                }
                val endTop = padT + lay.getLineTop(endLine)
                val endBot = padT + visualLineBottom(lay, endLine)

                val halfW = cursorWidth / 2f
                canvas.drawRect(startX - halfW, startTop, startX + halfW, startBot, cursorPaint)
                canvas.drawRect(endX - halfW, endTop, endX + halfW, endBot, cursorPaint)

                val r = handleRadius
                // 起点水滴：右上角尖端对齐光标底部
                drawTeardropHandle(
                    canvas = canvas,
                    left = startX - 2f * r,
                    top = startBot,
                    right = startX,
                    bottom = startBot + 2f * r,
                    radii = floatArrayOf(r, r, 0f, 0f, r, r, r, r)
                )
                startHandleCenter.set(startX - r, startBot + r)

                // 终点水滴：左上角尖端对齐光标底部
                drawTeardropHandle(
                    canvas = canvas,
                    left = endX,
                    top = endBot,
                    right = endX + 2f * r,
                    bottom = endBot + 2f * r,
                    radii = floatArrayOf(0f, 0f, r, r, r, r, r, r)
                )
                endHandleCenter.set(endX + r, endBot + r)
            } else {
                startHandleCenter.set(Float.NaN, Float.NaN)
                endHandleCenter.set(Float.NaN, Float.NaN)
            }
        } else {
            startHandleCenter.set(Float.NaN, Float.NaN)
            endHandleCenter.set(Float.NaN, Float.NaN)
        }
    }

    private fun drawTeardropHandle(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radii: FloatArray
    ) {
        handlePath.reset()
        handleRect.set(left, top, right, bottom)
        handlePath.addRoundRect(handleRect, radii, Path.Direction.CW)
        canvas.drawPath(handlePath, handleFillPaint)
        canvas.drawPath(handlePath, handleStrokePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val t = text
        val lay = layout
        if (t.isNullOrEmpty() || lay == null) return super.onTouchEvent(event)

        val x = event.x
        val y = event.y
        lastTouchX = x
        lastTouchY = y
        lastRawY = event.rawY

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                uiHandler.removeCallbacks(longPressRunnable)
                stopAutoScroll()

                val hitMode = hitTestHandles(x, y)
                if (hitMode != MODE_NONE) {
                    val padL = (totalPaddingLeft - scrollX).toFloat()
                    val padT = (totalPaddingTop - scrollY).toFloat()
                    mode = hitMode
                    if (hitMode == MODE_DRAG_START) {
                        dragAnchor = selEnd
                        val line = lay.getLineForOffset(selStart.coerceIn(0, t.length))
                        val anchorX = padL + lay.getPrimaryHorizontal(selStart)
                        val anchorMidY = padT + (lay.getLineTop(line) + visualLineBottom(lay, line)) * 0.5f
                        grabDx = x - anchorX
                        grabDy = y - anchorMidY
                    } else {
                        dragAnchor = selStart
                        val line = lay.getLineForOffset((selEnd - 1).coerceAtLeast(0))
                        val anchorX = padL + if (selEnd >= lay.getLineEnd(line)) {
                            lay.getLineRight(line)
                        } else {
                            lay.getPrimaryHorizontal(selEnd)
                        }
                        val anchorMidY = padT + (lay.getLineTop(line) + visualLineBottom(lay, line)) * 0.5f
                        grabDx = x - anchorX
                        grabDy = y - anchorMidY
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    return true
                }

                downX = x
                downY = y
                movedBeyondSlop = false
                downChar = getCharAt(x, y)
                mode = MODE_NONE
                if (downChar >= 0) {
                    uiHandler.postDelayed(longPressRunnable, 260L)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    MODE_DRAG_START, MODE_DRAG_END, MODE_DRAG_SWEEP -> {
                        updateDragAt(x, y)
                        ensureAutoScrollRunning()
                        return true
                    }
                    MODE_NONE -> {
                        val dx = x - downX
                        val dy = y - downY
                        if (!movedBeyondSlop && hypot(dx, dy) > touchSlop) {
                            movedBeyondSlop = true
                            uiHandler.removeCallbacks(longPressRunnable)
                            val sv = scrollViewRef?.get()
                            val canScroll = sv != null &&
                                (sv.canScrollVertically(-1) || sv.canScrollVertically(1))
                            if ((!canScroll || abs(dx) > abs(dy) * 1.25f) && downChar >= 0) {
                                parent?.requestDisallowInterceptTouchEvent(true)
                                mode = MODE_DRAG_SWEEP
                                sweepAnchorChar = downChar
                                justAnchoredSingleChar = false
                                val cur = getCharAt(x, y)
                                if (cur >= 0) {
                                    val s = min(sweepAnchorChar, cur)
                                    val e = charEnd(max(sweepAnchorChar, cur))
                                    setSelectionRangeInternal(s, e, resetTapAnchor = false)
                                } else {
                                    setSelectionRangeInternal(downChar, charEnd(downChar), resetTapAnchor = false)
                                }
                                ensureAutoScrollRunning()
                                return true
                            }
                            return false
                        }
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                uiHandler.removeCallbacks(longPressRunnable)
                stopAutoScroll()
                if (mode == MODE_NONE && !movedBeyondSlop) {
                    val c = getCharAt(x, y)
                    if (c >= 0) {
                        if (justAnchoredSingleChar &&
                            selStart == tapAnchorChar &&
                            selEnd == charEnd(tapAnchorChar)
                        ) {
                            if (c == tapAnchorChar) {
                                clearSelection()
                            } else {
                                val s = min(tapAnchorChar, c)
                                val e = charEnd(max(tapAnchorChar, c))
                                justAnchoredSingleChar = false
                                setSelectionRangeInternal(s, e, resetTapAnchor = false)
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        } else {
                            tapAnchorChar = c
                            justAnchoredSingleChar = true
                            setSelectionRangeInternal(c, charEnd(c), resetTapAnchor = false)
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                }
                mode = MODE_NONE
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(longPressRunnable)
                stopAutoScroll()
                mode = MODE_NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateDragAt(x: Float, y: Float) {
        val t = text ?: return
        if (t.isEmpty()) return
        when (mode) {
            MODE_DRAG_START, MODE_DRAG_END -> {
                val pos = getCursorOffsetAt(x - grabDx, y - grabDy)
                justAnchoredSingleChar = false
                if (pos < dragAnchor) {
                    mode = MODE_DRAG_START
                    setSelectionRangeInternal(pos, dragAnchor, resetTapAnchor = false)
                } else if (pos > dragAnchor) {
                    mode = MODE_DRAG_END
                    setSelectionRangeInternal(dragAnchor, pos, resetTapAnchor = false)
                } else {
                    // 拖拽到锚点时保持至少选中 1 个字符，避免选区塌缩成零宽而消失
                    if (mode == MODE_DRAG_START) {
                        val s = prevCodepointBoundary(dragAnchor)
                        if (s < dragAnchor) {
                            setSelectionRangeInternal(s, dragAnchor, resetTapAnchor = false)
                        } else {
                            val e = nextCodepointBoundary(dragAnchor)
                            setSelectionRangeInternal(dragAnchor, e, resetTapAnchor = false)
                        }
                    } else {
                        val e = nextCodepointBoundary(dragAnchor)
                        if (e > dragAnchor) {
                            setSelectionRangeInternal(dragAnchor, e, resetTapAnchor = false)
                        } else {
                            val s = prevCodepointBoundary(dragAnchor)
                            setSelectionRangeInternal(s, dragAnchor, resetTapAnchor = false)
                        }
                    }
                }
            }
            MODE_DRAG_SWEEP -> {
                val cur = getCharAt(x, y)
                if (cur >= 0) {
                    val s = min(sweepAnchorChar, cur)
                    val e = charEnd(max(sweepAnchorChar, cur))
                    justAnchoredSingleChar = false
                    setSelectionRangeInternal(s, e, resetTapAnchor = false)
                }
            }
        }
    }

    private fun hitTestHandles(x: Float, y: Float): Int {
        if (!hasSelection()) return MODE_NONE
        val dStart = if (!startHandleCenter.x.isNaN()) {
            hypot(x - startHandleCenter.x, y - startHandleCenter.y)
        } else Float.MAX_VALUE
        val dEnd = if (!endHandleCenter.x.isNaN()) {
            hypot(x - endHandleCenter.x, y - endHandleCenter.y)
        } else Float.MAX_VALUE
        val minDist = min(dStart, dEnd)
        if (minDist > handleHitRadius) return MODE_NONE
        return if (dStart <= dEnd) MODE_DRAG_START else MODE_DRAG_END
    }

    private fun ensureAutoScrollRunning() {
        val sv = scrollViewRef?.get() ?: return
        if (!sv.canScrollVertically(-1) && !sv.canScrollVertically(1)) return
        if (!autoScrolling) {
            autoScrolling = true
            uiHandler.postDelayed(autoScrollRunnable, 16L)
        }
    }

    private fun stopAutoScroll() {
        autoScrolling = false
        uiHandler.removeCallbacks(autoScrollRunnable)
    }

    private fun visualLineBottom(lay: Layout, line: Int): Float {
        val spacing = if (line < lay.lineCount - 1) lay.spacingAdd else 0f
        return lay.getLineBottom(line) - spacing
    }

    private fun setSelectionRangeInternal(start: Int, end: Int, resetTapAnchor: Boolean) {
        val t = text
        if (t.isNullOrEmpty()) {
            clearSelection()
            return
        }
        val len = t.length
        var s = min(start, end).coerceIn(0, len)
        var e = max(start, end).coerceIn(0, len)
        s = snapStartBoundary(t, s)
        e = snapEndBoundary(t, e)
        if (resetTapAnchor) justAnchoredSingleChar = false
        if (e <= s) {
            if (selStart != 0 || selEnd != 0) {
                selStart = 0
                selEnd = 0
                invalidate()
                onSelectionChanged?.invoke(0, 0)
            }
            return
        }
        if (selStart == s && selEnd == e) return
        selStart = s
        selEnd = e
        invalidate()
        onSelectionChanged?.invoke(selStart, selEnd)
    }

    private fun getCharAt(x: Float, y: Float): Int {
        val lay = layout ?: return -1
        val t = text
        if (t.isNullOrEmpty()) return -1
        val adjX = (x - totalPaddingLeft + scrollX).coerceAtLeast(0f)
        val adjY = (y - totalPaddingTop + scrollY).coerceIn(0f, max(0, lay.height - 1).toFloat())
        val line = lay.getLineForVertical(adjY.toInt())
        val lineStart = lay.getLineStart(line)
        var lineEnd = lay.getLineEnd(line)
        if (lineEnd > lineStart && t[lineEnd - 1] == '\n') {
            lineEnd--
        }
        if (lineStart >= lineEnd) {
            return charStart(lineStart.coerceAtMost(t.length - 1))
        }
        val rawOff = lay.getOffsetForHorizontal(line, adjX).coerceIn(lineStart, lineEnd)
        if (rawOff >= lineEnd) {
            return charStart(lineEnd - 1)
        }
        val xAtRaw = lay.getPrimaryHorizontal(rawOff)
        val picked = if (adjX < xAtRaw && rawOff > lineStart) rawOff - 1 else rawOff
        return charStart(picked)
    }

    private fun getCursorOffsetAt(x: Float, y: Float): Int {
        val lay = layout ?: return 0
        val t = text
        if (t.isNullOrEmpty()) return 0
        val adjX = (x - totalPaddingLeft + scrollX).coerceAtLeast(0f)
        val adjY = (y - totalPaddingTop + scrollY).coerceIn(0f, max(0, lay.height - 1).toFloat())
        val line = lay.getLineForVertical(adjY.toInt())
        val off = lay.getOffsetForHorizontal(line, adjX).coerceIn(0, t.length)
        return snapStartBoundary(t, off)
    }

    private fun charStart(idx: Int): Int {
        val t = text
        if (t.isNullOrEmpty()) return 0
        val clamped = idx.coerceIn(0, t.length - 1)
        return if (clamped > 0 && Character.isLowSurrogate(t[clamped]) && Character.isHighSurrogate(t[clamped - 1])) {
            clamped - 1
        } else {
            clamped
        }
    }

    private fun charEnd(idx: Int): Int {
        val t = text
        if (t.isNullOrEmpty()) return 0
        val s = charStart(idx)
        return if (s + 1 < t.length && Character.isHighSurrogate(t[s]) && Character.isLowSurrogate(t[s + 1])) {
            s + 2
        } else {
            (s + 1).coerceAtMost(t.length)
        }
    }

    private fun prevCodepointBoundary(offset: Int): Int {
        val t = text ?: return 0
        if (offset <= 0) return 0
        val p = (offset - 1).coerceIn(0, t.length - 1)
        return if (p > 0 && Character.isLowSurrogate(t[p]) && Character.isHighSurrogate(t[p - 1])) {
            p - 1
        } else {
            p
        }
    }

    private fun nextCodepointBoundary(offset: Int): Int {
        val t = text ?: return 0
        if (offset >= t.length) return t.length
        return if (offset + 1 < t.length &&
            Character.isHighSurrogate(t[offset]) &&
            Character.isLowSurrogate(t[offset + 1])
        ) {
            offset + 2
        } else {
            offset + 1
        }
    }

    private fun snapStartBoundary(t: CharSequence, offset: Int): Int {
        val o = offset.coerceIn(0, t.length)
        return if (o > 0 && o < t.length && Character.isLowSurrogate(t[o]) && Character.isHighSurrogate(t[o - 1])) {
            o - 1
        } else {
            o
        }
    }

    private fun snapEndBoundary(t: CharSequence, offset: Int): Int {
        val o = offset.coerceIn(0, t.length)
        return if (o > 0 && o < t.length && Character.isLowSurrogate(t[o]) && Character.isHighSurrogate(t[o - 1])) {
            (o + 1).coerceAtMost(t.length)
        } else {
            o
        }
    }
}

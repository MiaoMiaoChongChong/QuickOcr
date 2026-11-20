package com.zcode.quickocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.equationl.ncnnandroidppocr.OCR
import com.equationl.ncnnandroidppocr.bean.Device
import com.equationl.ncnnandroidppocr.bean.DrawModel
import com.equationl.ncnnandroidppocr.bean.ImageSize
import com.equationl.ncnnandroidppocr.bean.ModelType
import com.equationl.ncnnandroidppocr.bean.OcrResult
import com.equationl.ncnnandroidppocr.bean.OcrTextLineResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * OCR 引擎单例：从 assets 加载 PP-OCRv5 端侧模型，完全离线推理。
 *
 * - 支持按设置动态切换 Size720 / Size1080 分辨率档位；
 * - 送检前在四周填充自适应背景留白，防止贴边首尾字漏检；
 * - 提供基于行高容差的二维文本行聚类排序，缓解轻度倾斜/混排乱序。
 */
object OcrEngine {

    private const val EDGE_PAD_PX = 20

    private var ocr: OCR? = null
    private var initializedHighRes: Boolean? = null
    private val lock = Any()

    fun ensureInit(context: Context) {
        val highRes = Prefs.highResOcr(context)
        synchronized(lock) {
            if (ocr != null && initializedHighRes == highRes) return
            if (ocr != null) {
                try {
                    ocr?.release()
                } catch (_: Exception) {}
                ocr = null
                initializedHighRes = null
            }
            val o = OCR()
            val imgSize = if (highRes) ImageSize.Size1080 else ImageSize.Size720
            val ok = o.initModelFromAssert(
                context.assets,
                ModelType.Mobile,
                imgSize,
                Device.CPU
            )
            if (!ok) throw IllegalStateException("模型初始化失败")
            ocr = o
            initializedHighRes = highRes
        }
    }

    fun isReady(): Boolean = synchronized(lock) { ocr != null }

    fun detect(bitmap: Bitmap): OcrResult? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val padded = createPaddedCopy(bitmap, EDGE_PAD_PX)
        return try {
            synchronized(lock) {
                ocr?.detectBitmap(padded, DrawModel.None)
            }
        } finally {
            if (!padded.isRecycled) {
                padded.recycle()
            }
        }
    }

    /**
     * 按视觉行聚类再从左到右排序，避免单像素高度偏差导致同行字符被拆行乱序。
     */
    fun sortLines(raw: List<OcrTextLineResult>): List<String> {
        val boxes = raw.filter { it.text.isNotBlank() }.map { line ->
            val pts = line.points
            if (pts.isEmpty()) {
                LineBox(line.text, 0f, 0f, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, 1f)
            } else {
                var minX = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                for (p in pts) {
                    val fx = p.x.toFloat()
                    val fy = p.y.toFloat()
                    if (fx < minX) minX = fx
                    if (fx > maxX) maxX = fx
                    if (fy < minY) minY = fy
                    if (fy > maxY) maxY = fy
                }
                val h = max(1f, maxY - minY)
                LineBox(line.text, minX, maxX, minY, maxY, (minY + maxY) * 0.5f, h)
            }
        }
        if (boxes.isEmpty()) return emptyList()

        val byY = boxes.sortedWith(compareBy({ it.centerY }, { it.minX }))
        val clusters = mutableListOf<RowCluster>()

        for (box in byY) {
            if (box.centerY == Float.MAX_VALUE) {
                clusters.add(RowCluster(box))
                continue
            }
            var bestCluster: RowCluster? = null
            var bestDist = Float.MAX_VALUE
            for (c in clusters) {
                if (c.avgCenterY == Float.MAX_VALUE) continue
                val minH = min(box.height, c.avgHeight)
                val centerDiff = abs(box.centerY - c.avgCenterY)
                val overlap = min(box.maxY, c.maxY) - max(box.minY, c.minY)
                val sameRow = centerDiff <= minH * 0.5f || overlap >= minH * 0.4f
                if (sameRow && centerDiff < bestDist) {
                    bestDist = centerDiff
                    bestCluster = c
                }
            }
            if (bestCluster != null) {
                bestCluster.add(box)
            } else {
                clusters.add(RowCluster(box))
            }
        }

        return clusters
            .sortedBy { it.avgCenterY }
            .flatMap { cluster -> cluster.items.sortedBy { it.minX } }
            .map { it.text }
    }

    private fun createPaddedCopy(src: Bitmap, pad: Int): Bitmap {
        val bg = sampleBorderColor(src)
        val out = Bitmap.createBitmap(src.width + pad * 2, src.height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(bg)
        canvas.drawBitmap(src, pad.toFloat(), pad.toFloat(), null)
        return out
    }

    /** 采样原图四角及四边中点共 8 像素求中位 RGB，让留白贴合当前页面亮暗主题。 */
    private fun sampleBorderColor(bmp: Bitmap): Int {
        val w = bmp.width
        val h = bmp.height
        val xMax = (w - 1).coerceAtLeast(0)
        val yMax = (h - 1).coerceAtLeast(0)
        val xm = xMax / 2
        val ym = yMax / 2
        val coords = arrayOf(
            0 to 0, xMax to 0, 0 to yMax, xMax to yMax,
            xm to 0, xm to yMax, 0 to ym, xMax to ym
        )
        val rs = IntArray(coords.size)
        val gs = IntArray(coords.size)
        val bs = IntArray(coords.size)
        for (i in coords.indices) {
            val (px, py) = coords[i]
            val c = bmp.getPixel(px, py)
            rs[i] = Color.red(c)
            gs[i] = Color.green(c)
            bs[i] = Color.blue(c)
        }
        rs.sort()
        gs.sort()
        bs.sort()
        val mid = coords.size / 2
        return Color.rgb(rs[mid], gs[mid], bs[mid])
    }

    private data class LineBox(
        val text: String,
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
        val centerY: Float,
        val height: Float
    )

    private class RowCluster(first: LineBox) {
        val items = mutableListOf(first)
        var avgCenterY = first.centerY
        var avgHeight = first.height
        var minY = first.minY
        var maxY = first.maxY

        fun add(box: LineBox) {
            val n = items.size.toFloat()
            items.add(box)
            avgCenterY = (avgCenterY * n + box.centerY) / (n + 1f)
            avgHeight = (avgHeight * n + box.height) / (n + 1f)
            if (box.minY < minY) minY = box.minY
            if (box.maxY > maxY) maxY = box.maxY
        }
    }
}

package com.zcode.quickocr

import android.content.Context
import android.graphics.Bitmap
import com.equationl.ncnnandroidppocr.OCR
import com.equationl.ncnnandroidppocr.bean.Device
import com.equationl.ncnnandroidppocr.bean.DrawModel
import com.equationl.ncnnandroidppocr.bean.ImageSize
import com.equationl.ncnnandroidppocr.bean.ModelType
import com.equationl.ncnnandroidppocr.bean.OcrResult

/**
 * OCR 引擎单例：从 assets 加载 PP-OCRv5 端侧模型，完全离线推理。
 * detect() 加锁串行执行，保护底层 native 调用。
 */
object OcrEngine {
    private var ocr: OCR? = null
    private val lock = Any()

    fun ensureInit(context: Context) {
        synchronized(lock) {
            if (ocr != null) return
            val o = OCR()
            val ok = o.initModelFromAssert(
                context.assets,
                ModelType.Mobile,
                ImageSize.Size720,
                Device.CPU
            )
            if (!ok) throw IllegalStateException("模型初始化失败")
            ocr = o
        }
    }

    fun isReady(): Boolean = synchronized(lock) { ocr != null }

    fun detect(bitmap: Bitmap): OcrResult? = synchronized(lock) {
        ocr?.detectBitmap(bitmap, DrawModel.None)
    }
}

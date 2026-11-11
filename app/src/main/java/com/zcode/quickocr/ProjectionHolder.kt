package com.zcode.quickocr

import android.content.Intent
import android.media.projection.MediaProjection

/**
 * 录屏授权持有者。
 * Android 14+ 授权 token 一次性，但 MediaProjection 对象在不停止的前提下可跨多次截屏复用——
 * 常驻持有它，之后每次截屏只需新建 VirtualDisplay，不再重复授权、不再弹任何界面。
 */
object ProjectionHolder {
    var resultCode: Int = Int.MIN_VALUE
    var resultData: Intent? = null
    var mediaProjection: MediaProjection? = null

    fun hasProjection(): Boolean = mediaProjection != null

    /** 只失效授权 token（projection 本体保留复用）。 */
    fun invalidateToken() {
        resultCode = Int.MIN_VALUE
        resultData = null
    }

    /** 彻底清空（用户在系统里撤销授权时调用）。 */
    fun clearAll() {
        invalidateToken()
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }
}

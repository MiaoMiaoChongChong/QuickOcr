package com.zcode.quickocr

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 磁贴 / 通知栏入口的透明中转页：启动它会让系统收起下拉面板。
 * 已有截屏能力（无障碍或 MediaProjection）就直接进截屏流程；
 * 否则引导去开无障碍，不再静默弹录屏授权框。
 */
class ConsentActivity : AppCompatActivity() {

    companion object { private const val TAG = "QOCR-Consent" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val a11y = OcrA11yService.isEnabled(this)
        Log.i(
            TAG,
            "QOCR onCreate savedState=${savedInstanceState != null} " +
                "hasProj=${ProjectionHolder.hasProjection()} a11y=$a11y"
        )
        if (ProjectionHolder.hasProjection() || a11y) {
            BallService.capture(this)
            finish()
            return
        }
        if (savedInstanceState != null) {
            finish()
            return
        }
        Toast.makeText(this, "未检测到无障碍权限，请开启「OCR快照」后再点磁贴", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {}
        finish()
    }
}

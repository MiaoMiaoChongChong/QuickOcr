package com.zcode.quickocr

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/**
 * 无障碍截图渠道：Android 11+ 的 takeScreenshot() 一次授权长期有效。
 *
 * 开关存在系统 `settings secure enabled_accessibility_services`，进程重启后系统会自动
 * 重新绑定服务，不像 MediaProjection 那样每次发一个不可持久化的 IBinder token。
 * 本服务只提供截屏能力：不读取界面节点、不处理无障碍事件、不联网。
 */
class OcrA11yService : AccessibilityService() {

    companion object {
        private const val TAG = "QOCR-A11y"

        @Volatile
        private var instance: OcrA11yService? = null

        /** 以系统 AccessibilityManager 的已启用列表为准；Settings.Secure 字符串作兜底。 */
        fun isEnabled(c: Context): Boolean {
            val flat = ComponentName(c, OcrA11yService::class.java).flattenToString()
            val short = "${c.packageName}/.OcrA11yService"
            val className = OcrA11yService::class.java.name
            try {
                val am = c.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                val enabled = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                )
                if (enabled.any {
                        it.resolveInfo?.serviceInfo?.name == className ||
                            it.id?.contains("OcrA11yService") == true
                    }) return true
            } catch (e: Throwable) {
                Log.w(TAG, "QOCR a11y manager query failed", e)
            }
            val raw = Settings.Secure.getString(
                c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return raw.split(':').any {
                it.equals(flat, ignoreCase = true) || it.equals(short, ignoreCase = true)
            }
        }

        val connected: Boolean get() = instance != null

        /** 返回 false 表示服务尚未绑定，调用方应回退到 MediaProjection。 */
        fun captureIfAvailable(onResult: (Bitmap?) -> Unit): Boolean {
            val svc = instance ?: return false
            svc.takeShot(onResult)
            return true
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 系统在用户打开服务详情时也会临时 bind 来读配置，此刻 connected=true
        // 并不代表已启用，必须用 enabled list 复核，日志里一并打出结果。
        Log.i(TAG, "QOCR a11y bound, enabledInSettings=${isEnabled(this)}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "QOCR a11y destroyed")
        super.onDestroy()
    }

    /** 必须 copy 成软件位图：OverlayController 要用 Bitmap.createBitmap 裁剪选区。 */
    private fun takeShot(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "QOCR takeScreenshot requires API 30+")
            onResult(null)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val bmp = try {
                            Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer, screenshot.colorSpace
                            )?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (e: Exception) {
                            Log.e(TAG, "QOCR bitmap copy failed", e)
                            null
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                        Log.i(TAG, "QOCR a11y screenshot bmp=${bmp != null}")
                        onResult(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "QOCR a11y screenshot failed code=$errorCode")
                        onResult(null)
                    }
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "QOCR takeScreenshot threw", e)
            onResult(null)
        }
    }
}

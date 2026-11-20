package com.zcode.quickocr

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import java.util.function.Consumer

/**
 * 偏好 + 下拉磁贴的组件开关。
 *
 * 磁贴默认不加入控制中心：manifest 里 OcrTileService 是 enabled=false，
 * SystemUI 的 queryIntentServices 查不到它，既不会出现在编辑页也不会被自动加入。
 * 用户在「二、识别入口」点按钮后才启用组件，并走 API 33+ 的
 * StatusBarManager.requestAddTileService 弹系统确认框，由用户自己决定是否添加。
 */
object Prefs {
    private const val NAME = "entry"
    private const val KEY_LINE_BREAK = "line_break_between_lines"
    private const val KEY_BALL_ENABLED = "ball_enabled"
    private const val KEY_NOTIF_ENABLED = "notif_enabled"
    private const val KEY_CHAR_SELECT_MODE = "char_select_mode"
    private const val KEY_HIGH_RES_OCR = "high_res_ocr"
    private const val KEY_BALL_ON_RIGHT = "ball_on_right"
    private const val KEY_BALL_Y_RATIO = "ball_y_ratio"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 悬浮球开关，默认 true。 */
    fun ballEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_BALL_ENABLED, true)

    fun setBallEnabled(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_BALL_ENABLED, v).apply()
    }

    /** 通知栏常驻入口开关，默认 true。 */
    fun notifEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_NOTIF_ENABLED, true)

    fun setNotifEnabled(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_NOTIF_ENABLED, v).apply()
    }

    /** 结果面板默认模式：true=逐字选择，false=按行勾选。 */
    fun charSelectMode(c: Context): Boolean = sp(c).getBoolean(KEY_CHAR_SELECT_MODE, true)

    fun setCharSelectMode(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_CHAR_SELECT_MODE, v).apply()
    }

    /** 高精度识别（1080p），默认 false（720p）。 */
    fun highResOcr(c: Context): Boolean = sp(c).getBoolean(KEY_HIGH_RES_OCR, false)

    fun setHighResOcr(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_HIGH_RES_OCR, v).apply()
    }

    /** 悬浮球停靠侧：false=左侧，true=右侧。 */
    fun ballOnRight(c: Context): Boolean = sp(c).getBoolean(KEY_BALL_ON_RIGHT, false)

    fun setBallOnRight(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_BALL_ON_RIGHT, v).apply()
    }

    /** 悬浮球纵向位置比例（0f..1f）。 */
    fun ballYRatio(c: Context): Float = sp(c).getFloat(KEY_BALL_Y_RATIO, 0.38f).coerceIn(0f, 1f)

    fun setBallYRatio(c: Context, v: Float) {
        sp(c).edit().putFloat(KEY_BALL_Y_RATIO, v.coerceIn(0f, 1f)).apply()
    }

    /** 复制多行时每行之间是否插入换行符，默认 true（保持原有行为）。 */
    fun lineBreak(c: Context): Boolean = sp(c).getBoolean(KEY_LINE_BREAK, true)

    fun setLineBreak(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_LINE_BREAK, v).apply()
    }

    private fun component(c: Context) = ComponentName(c, OcrTileService::class.java)

    /** 以系统实际组件状态为准：覆盖安装后 manifest 默认值(false)会生效，不与本地偏好分叉。 */
    fun tileEnabled(c: Context): Boolean =
        c.packageManager.getComponentEnabledSetting(component(c)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** 同步组件启用状态到系统；DONT_KILL_APP 避免把正在跑的入口服务杀掉。 */
    fun setTileEnabled(c: Context, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        c.packageManager.setComponentEnabledSetting(
            component(c), state, PackageManager.DONT_KILL_APP
        )
    }

    /** 弹系统确认框请求加入下拉磁贴；不支持/失败时给出手动添加的回退指引。 */
    fun requestAddTile(activity: Activity, onDone: () -> Unit) {
        setTileEnabled(activity, true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            manualHint(activity)
            onDone()
            return
        }
        try {
            val sbm = activity.getSystemService(Context.STATUS_BAR_SERVICE) as StatusBarManager
            sbm.requestAddTileService(
                component(activity),
                "OCR框选",
                Icon.createWithResource(activity, R.drawable.ic_tile),
                activity.mainExecutor,
                Consumer<Int> { result ->
                    // StatusBarManager 的 TILE_ADD_REQUEST_RESULT_* 常量，部分为 @hide，这里用字面量
                    val msg = when (result) {
                        2 -> "已加入下拉磁贴"
                        1 -> "磁贴已在下拉中"
                        3 -> "已取消，未加入"
                        0 -> "未加入磁贴"
                        else -> "系统未接受请求($result)，请下拉 → 编辑 → 应用快捷开关手动添加"
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    onDone()
                }
            )
        } catch (e: Throwable) {
            manualHint(activity)
            onDone()
        }
    }

    private fun manualHint(activity: Activity) {
        Toast.makeText(
            activity,
            "本系统不支持自动添加，请下拉控制中心 → 编辑 → 应用快捷开关，手动添加「OCR框选」",
            Toast.LENGTH_LONG
        ).show()
    }
}

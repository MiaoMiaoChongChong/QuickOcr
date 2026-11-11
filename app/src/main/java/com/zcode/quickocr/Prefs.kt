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

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

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

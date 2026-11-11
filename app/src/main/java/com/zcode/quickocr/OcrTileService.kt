package com.zcode.quickocr

import android.app.PendingIntent
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * 下拉控制中心的「OCR框选」磁贴。
 * 点击 → 悬浮窗权限检查 → 录屏授权检查 → 启动截屏服务。
 */
class OcrTileService : TileService() {

    override fun onClick() {
        super.onClick()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先打开 OCR快照 授权悬浮窗", Toast.LENGTH_LONG).show()
            launchFromTile(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        // 统一走透明中转页：startActivityAndCollapse 会先收起下拉控制中心，
        // 否则延迟截屏抓到的是下拉菜单本身。ACTION_CLOSE_SYSTEM_DIALOGS 自 API 31 起
        // 对第三方应用无效，不能靠广播收面板。
        // ConsentActivity 自行判断：已持有 projection → 直接截屏；否则 → 弹一次性授权。
        launchFromTile(
            Intent(this, ConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @Suppress("DEPRECATION")
    private fun launchFromTile(intent: Intent) {
        try {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } catch (e: NoSuchMethodError) {
            startActivityAndCollapse(intent)
        }
    }
}

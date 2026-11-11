package com.zcode.quickocr

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnA11y: Button
    private lateinit var btnBall: Button
    private lateinit var btnNotif: Button
    private lateinit var btnTile: Button
    private lateinit var btnProjection: Button
    private lateinit var cbLineBreak: CheckBox

    private fun prefs() = getSharedPreferences("entry", MODE_PRIVATE)

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK && r.data != null) {
                ProjectionHolder.resultCode = r.resultCode
                ProjectionHolder.resultData = r.data
                // 只注册授权并常驻投影，不立刻弹框选层：授权完立刻截屏会把用户打断，
                // 且关闭框选层回到本页时服务可能刚被重投递 intent 干扰，状态显示会误导。
                BallService.startWithToken(this, r.resultCode, r.data!!, capture = false)
                Toast.makeText(this, "录屏授权成功，点悬浮球/通知栏/磁贴即可截屏", Toast.LENGTH_SHORT).show()
                // 服务异步建立 MediaProjection，稍后再刷状态
                statusText.postDelayed({ refreshUi() }, 900)
            } else {
                Toast.makeText(this, "未授予录屏权限", Toast.LENGTH_SHORT).show()
                refreshUi()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.status)
        btnA11y = findViewById(R.id.btnA11y)
        btnProjection = findViewById(R.id.btnProjection)
        btnBall = findViewById(R.id.btnBall)
        btnNotif = findViewById(R.id.btnNotif)
        btnTile = findViewById(R.id.btnTile)
        cbLineBreak = findViewById(R.id.cbLineBreak)

        // 无障碍截图渠道：开关由系统持久化，一次开启重启后仍有效
        btnA11y.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(
                    this,
                    "找到「OCR快照」并开启；若提示受限，先到应用信息 → 高级设置 → 允许受限制的设置",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                Toast.makeText(this, "悬浮窗权限已开启 ✓", Toast.LENGTH_SHORT).show()
            }
        }

        btnProjection.setOnClickListener { requestProjection() }

        btnBall.setOnClickListener { toggleEntry("ball_enabled") }
        btnNotif.setOnClickListener { toggleEntry("notif_enabled") }

        // 下拉磁贴：默认不加入，点按钮才启用组件并弹系统确认框
        btnTile.setOnClickListener {
            if (Prefs.tileEnabled(this)) {
                Prefs.setTileEnabled(this, false)
                Toast.makeText(this, "已停用下拉磁贴", Toast.LENGTH_SHORT).show()
                refreshUi()
            } else {
                Prefs.requestAddTile(this) { refreshUi() }
            }
        }

        // 复制多行时是否在每行后插入换行符；结果面板里的勾选与这里共用同一状态
        cbLineBreak.isChecked = Prefs.lineBreak(this)
        cbLineBreak.setOnCheckedChangeListener { _, v -> Prefs.setLineBreak(this, v) }

        if (intent?.getBooleanExtra("auto_start", false) == true) {
            applyEntry()
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) { OcrEngine.ensureInit(applicationContext) }
            } catch (_: Exception) {}
            refreshUi()
        }
    }

    override fun onResume() {
        super.onResume()
        cbLineBreak.isChecked = Prefs.lineBreak(this)
        refreshUi()
        applyEntry()
    }

    private fun refreshUi() {
        btnBall.text = if (prefs().getBoolean("ball_enabled", true)) "关闭悬浮球" else "开启悬浮球"
        btnNotif.text = if (prefs().getBoolean("notif_enabled", true)) "关闭通知栏入口" else "开启通知栏入口"
        btnTile.text = if (Prefs.tileEnabled(this)) "移除下拉磁贴" else "添加下拉磁贴"
        statusText.text = when {
            OcrA11yService.isEnabled(this) -> "截屏能力：无障碍已就绪 ✓（重启后仍有效）"
            ProjectionHolder.hasProjection() -> "录屏授权：已就绪 ✓"
            ProjectionHolder.resultData != null -> "录屏授权：正在建立截屏能力…"
            else -> "未完成：请确认无障碍开关已打开，或点「录屏权限」用备用方案"
        }
    }

    /** 按当前偏好应用入口状态（服务运行时发 apply，未运行则启动/停止）。 */
    private fun applyEntry() {
        val ballOn = prefs().getBoolean("ball_enabled", true)
        val notifOn = prefs().getBoolean("notif_enabled", true)
        val tileOn = Prefs.tileEnabled(this)
        // ★磁贴入口同样依赖 BallService，必须计入；否则只留磁贴时会被误判为「无入口」
        // 而 stop 服务 → teardown → clearAll → 刚拿到的录屏授权被清空。
        Log.i(
            "QOCR-UI",
            "QOCR applyEntry ball=$ballOn notif=$notifOn tile=$tileOn running=${BallService.isRunning}"
        )
        if (ballOn || notifOn || tileOn) {
            if (BallService.isRunning) {
                startService(Intent(this, BallService::class.java))
            } else {
                BallService.start(this)
            }
        } else if (BallService.isRunning) {
            BallService.stop(this)
        }
    }

    private fun toggleEntry(key: String) {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        prefs().edit().putBoolean(key, !prefs().getBoolean(key, true)).apply()
        applyEntry()
        refreshUi()
    }

    private fun requestProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}

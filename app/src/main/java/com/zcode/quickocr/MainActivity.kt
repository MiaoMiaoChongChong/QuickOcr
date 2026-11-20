package com.zcode.quickocr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var mainContent: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    private lateinit var badgeA11y: TextView
    private lateinit var badgeOverlay: TextView
    private lateinit var badgeProjection: TextView
    private lateinit var badgeTile: TextView

    private lateinit var swBall: SwitchCompat
    private lateinit var swNotif: SwitchCompat
    private lateinit var swLineBreak: SwitchCompat
    private lateinit var swHighRes: SwitchCompat

    private lateinit var cardHistorySection: LinearLayout
    private lateinit var historyContainer: LinearLayout

    private var bindingUi = false

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "未授予通知权限，通知栏入口可能无法显示", Toast.LENGTH_SHORT).show()
            }
            EntryCoordinator.sync(this)
            refreshUi()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK && r.data != null) {
                ProjectionHolder.resultCode = r.resultCode
                ProjectionHolder.resultData = r.data
                BallService.startWithToken(this, r.resultCode, r.data!!, capture = false)
                Toast.makeText(this, "录屏授权成功，点悬浮球/通知栏/磁贴即可截屏", Toast.LENGTH_SHORT).show()
                statusText.postDelayed({ refreshUi() }, 900)
            } else {
                Toast.makeText(this, "未授予录屏权限", Toast.LENGTH_SHORT).show()
                refreshUi()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        mainContent = findViewById(R.id.mainContent)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.status)
        badgeA11y = findViewById(R.id.badgeA11y)
        badgeOverlay = findViewById(R.id.badgeOverlay)
        badgeProjection = findViewById(R.id.badgeProjection)
        badgeTile = findViewById(R.id.badgeTile)
        swBall = findViewById(R.id.swBall)
        swNotif = findViewById(R.id.swNotif)
        swLineBreak = findViewById(R.id.swLineBreak)
        swHighRes = findViewById(R.id.swHighRes)
        cardHistorySection = findViewById(R.id.cardHistorySection)
        historyContainer = findViewById(R.id.historyContainer)

        val baseHPad = mainContent.paddingLeft
        val baseTopPad = mainContent.paddingTop
        val baseBotPad = mainContent.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(mainContent) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(baseHPad, baseTopPad + bars.top, baseHPad, baseBotPad + bars.bottom)
            insets
        }

        findViewById<View>(R.id.rowA11y).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(
                    this,
                    "找到「OCR快照」并开启；若提示受限，先到应用信息 → 高级设置 → 允许受限制的设置",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
                Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.rowOverlay).setOnClickListener {
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

        findViewById<View>(R.id.rowProjection).setOnClickListener { requestProjection() }

        findViewById<View>(R.id.rowBall).setOnClickListener {
            swBall.toggle()
        }
        swBall.setOnCheckedChangeListener { _, checked ->
            if (bindingUi) return@setOnCheckedChangeListener
            setBallEnabled(checked)
        }

        findViewById<View>(R.id.rowNotif).setOnClickListener {
            swNotif.toggle()
        }
        swNotif.setOnCheckedChangeListener { _, checked ->
            if (bindingUi) return@setOnCheckedChangeListener
            setNotifEnabled(checked)
        }

        findViewById<View>(R.id.rowTile).setOnClickListener {
            if (Prefs.tileEnabled(this)) {
                Prefs.setTileEnabled(this, false)
                EntryCoordinator.sync(this)
                Toast.makeText(this, "已停用下拉磁贴", Toast.LENGTH_SHORT).show()
                refreshUi()
            } else {
                Prefs.requestAddTile(this) { refreshUi() }
            }
        }

        findViewById<View>(R.id.rowLineBreak).setOnClickListener {
            swLineBreak.toggle()
        }
        swLineBreak.setOnCheckedChangeListener { _, checked ->
            if (bindingUi) return@setOnCheckedChangeListener
            Prefs.setLineBreak(this, checked)
        }

        findViewById<View>(R.id.rowHighRes).setOnClickListener {
            swHighRes.toggle()
        }
        swHighRes.setOnCheckedChangeListener { _, checked ->
            if (bindingUi) return@setOnCheckedChangeListener
            Prefs.setHighResOcr(this, checked)
            Toast.makeText(
                this,
                if (checked) "已切换为 1080p 高精度模式" else "已切换为 720p 标准速度模式",
                Toast.LENGTH_SHORT
            ).show()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.Default) { OcrEngine.ensureInit(applicationContext) }
                } catch (_: Exception) {}
            }
        }

        findViewById<View>(R.id.btnClearHistory).setOnClickListener {
            OcrHistory.clear()
            renderHistory()
            Toast.makeText(this, "已清空最近识别缓存", Toast.LENGTH_SHORT).show()
        }

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
        refreshUi()
        applyEntry()
    }

    private fun refreshUi() {
        bindingUi = true
        try {
            swBall.isChecked = Prefs.ballEnabled(this)
            swNotif.isChecked = Prefs.notifEnabled(this)
            swLineBreak.isChecked = Prefs.lineBreak(this)
            swHighRes.isChecked = Prefs.highResOcr(this)
        } finally {
            bindingUi = false
        }

        val a11yOn = OcrA11yService.isEnabled(this)
        val overlayOn = Settings.canDrawOverlays(this)
        val projOn = ProjectionHolder.hasProjection()
        val tileOn = Prefs.tileEnabled(this)

        styleBadge(badgeA11y, if (a11yOn) "已开启" else "去开启", ok = a11yOn, warn = !a11yOn && !projOn)
        styleBadge(badgeOverlay, if (overlayOn) "已开启" else "去授权", ok = overlayOn, warn = !overlayOn)
        styleBadge(
            badgeProjection,
            when {
                projOn -> "已就绪"
                ProjectionHolder.resultData != null -> "建立中"
                else -> "按需授权"
            },
            ok = projOn,
            warn = false
        )
        styleBadge(badgeTile, if (tileOn) "已启用 · 点此移除" else "添加磁贴", ok = tileOn, warn = false)

        val ready = (a11yOn || projOn) && overlayOn
        statusDot.setBackgroundResource(
            when {
                ready -> R.drawable.bg_badge_ok
                a11yOn || projOn || overlayOn -> R.drawable.bg_badge_warn
                else -> R.drawable.bg_badge_neutral
            }
        )
        statusText.text = when {
            a11yOn && overlayOn -> "状态就绪：无障碍静默截屏 + 悬浮层可用"
            projOn && overlayOn -> "状态就绪：录屏备用通道可用（系统保留一条通知）"
            !overlayOn && (a11yOn || projOn) -> "还差一步：请开启「悬浮窗权限」以显示选字面板"
            ProjectionHolder.resultData != null -> "录屏授权：正在建立截屏能力…"
            else -> "待配置：推荐开启「无障碍截屏」与「悬浮窗权限」"
        }

        renderHistory()
    }

    private fun styleBadge(tv: TextView, label: String, ok: Boolean, warn: Boolean) {
        tv.text = label
        when {
            ok -> {
                tv.setBackgroundResource(R.drawable.bg_badge_ok)
                tv.setTextColor(0xFF4ADE80.toInt())
            }
            warn -> {
                tv.setBackgroundResource(R.drawable.bg_badge_warn)
                tv.setTextColor(0xFFFBBF24.toInt())
            }
            else -> {
                tv.setBackgroundResource(R.drawable.bg_badge_neutral)
                tv.setTextColor(0xFFCBD5E1.toInt())
            }
        }
    }

    private fun renderHistory() {
        val entries = OcrHistory.list()
        if (entries.isEmpty()) {
            cardHistorySection.visibility = View.GONE
            historyContainer.removeAllViews()
            return
        }
        cardHistorySection.visibility = View.VISIBLE
        historyContainer.removeAllViews()

        val dp = resources.displayMetrics.density
        fun d(v: Int) = (v * dp).roundToInt()
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        entries.forEachIndexed { idx, entry ->
            if (idx > 0) {
                val div = View(this).apply {
                    setBackgroundColor(0xFF202633.toInt())
                }
                historyContainer.addView(div, LinearLayout.LayoutParams(-1, d(1)).apply {
                    leftMargin = d(12)
                    rightMargin = d(12)
                })
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_row_ripple)
                isClickable = true
                isFocusable = true
                setPadding(d(12), d(11), d(12), d(11))
            }

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val tvPreview = TextView(this).apply {
                text = entry.preview()
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val tvMeta = TextView(this).apply {
                text = "${fmt.format(Date(entry.timestampMs))} · ${entry.lines.size} 行 · 点开逐字选 / 长按复制"
                setTextColor(0xFF7D8596.toInt())
                textSize = 11.5f
                setPadding(0, d(2), 0, 0)
            }
            col.addView(tvPreview)
            col.addView(tvMeta)
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

            val btnCopy = TextView(this).apply {
                text = "复制"
                setTextColor(0xFF8AB4FF.toInt())
                textSize = 12f
                setBackgroundResource(R.drawable.bg_badge_info)
                setPadding(d(10), d(4), d(10), d(4))
                setOnClickListener { copyEntry(entry) }
            }
            row.addView(btnCopy, LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = d(10)
            })

            row.setOnClickListener {
                if (Settings.canDrawOverlays(this)) {
                    OverlayController.openFromHistory(this, entry.lines)
                } else {
                    copyEntry(entry)
                }
            }
            row.setOnLongClickListener {
                copyEntry(entry)
                true
            }
            historyContainer.addView(row)
        }
    }

    private fun copyEntry(entry: OcrHistory.Entry) {
        val joined = entry.lines.joinToString(if (Prefs.lineBreak(this)) "\n" else "")
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ocr", joined))
        Toast.makeText(this, "已复制 ${joined.length} 字", Toast.LENGTH_SHORT).show()
    }

    /** 按当前偏好同步悬浮球与前台服务状态。 */
    private fun applyEntry() {
        Log.i(
            "QOCR-UI",
            "QOCR applyEntry ball=${Prefs.ballEnabled(this)} notif=${Prefs.notifEnabled(this)} " +
                "tile=${Prefs.tileEnabled(this)} running=${BallService.isRunning}"
        )
        EntryCoordinator.sync(this)
    }

    private fun setBallEnabled(enabled: Boolean) {
        if (enabled && !Settings.canDrawOverlays(this)) {
            refreshUi()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(this, "请先开启「悬浮窗权限」", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setBallEnabled(this, enabled)
        EntryCoordinator.sync(this)
        refreshUi()
    }

    private fun setNotifEnabled(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Prefs.setNotifEnabled(this, true)
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            refreshUi()
            return
        }
        Prefs.setNotifEnabled(this, enabled)
        EntryCoordinator.sync(this)
        if (!enabled) {
            if (!OcrA11yService.isEnabled(this) && ProjectionHolder.hasProjection()) {
                Toast.makeText(
                    this,
                    "通知入口已关闭；当前使用录屏备用通道，开启无障碍后可彻底隐藏通知",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "已关闭通知栏入口，不再占用通知栏", Toast.LENGTH_SHORT).show()
            }
        }
        refreshUi()
    }

    private fun requestProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}

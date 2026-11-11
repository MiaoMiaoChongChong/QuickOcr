package com.zcode.quickocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * 统一入口服务：悬浮球 + 通知栏入口 + 截屏能力 三合一。
 *
 * ★Android 14+：一个 MediaProjection 只允许 createVirtualDisplay 一次，故 VD/ImageReader
 * 常驻不释放，并缓存 reader 的最近一帧——画面静止时不产新帧也能截到当前画面。
 * ★所有截屏请求统一先收起系统面板（通知栏/控制中心/授权弹窗）再延迟抓帧，
 * 避免截到通知界面或授权过渡画面；连点做防抖，避免排队弹出多层框选。
 */
class BallService : Service() {

    companion object {
        private const val TAG = "QOCR-Svc"
        private const val CHANNEL = "ocr_entry"
        private const val NOTIF_ID = 1002
        private const val CAPTURE_DELAY_MS = 650L
        const val EXTRA_STOP = "stop"
        const val EXTRA_TOGGLE_BALL = "toggle_ball"
        const val EXTRA_TOGGLE_NOTIF = "toggle_notif"
        const val EXTRA_CAPTURE = "capture"

        var isRunning = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, BallService::class.java)
            )
        }

        fun capture(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, BallService::class.java).putExtra(EXTRA_CAPTURE, true)
            )
        }

        /** capture=false 只注册授权并常驻投影，不立刻弹框选（App 内授权按钮用）。 */
        fun startWithToken(context: Context, rc: Int, data: Intent, capture: Boolean = true) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BallService::class.java)
                    .putExtra("resultCode", rc)
                    .putExtra("resultData", data)
                    .putExtra(EXTRA_CAPTURE, capture)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BallService::class.java).putExtra(EXTRA_STOP, true)
            )
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var ballView: View? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var registered = false
    private var callbackProjection: MediaProjection? = null

    /** 常驻 VD 的最近一帧；不 close，直到被新帧替换或服务销毁。 */
    private var cachedImage: Image? = null
    private val frameLock = Any()
    private var captureScheduled = false

    private fun prefs() = getSharedPreferences("entry", MODE_PRIVATE)
    private fun ballEnabled() = prefs().getBoolean("ball_enabled", true)
    private fun notifEnabled() = prefs().getBoolean("notif_enabled", true)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.i(TAG, "QOCR onCreate")
    }

    override fun onDestroy() {
        isRunning = false
        registered = false
        callbackProjection = null
        releaseCaptureObjects()
        try { ProjectionHolder.mediaProjection?.stop() } catch (_: Exception) {}
        removeBall()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(
            TAG,
            "QOCR cmd startId=$startId capture=${intent?.getBooleanExtra(EXTRA_CAPTURE, false) == true} " +
                "token=${intent?.getIntExtra("resultCode", Int.MIN_VALUE) != Int.MIN_VALUE} " +
                "stop=${intent?.getBooleanExtra(EXTRA_STOP, false) == true} " +
                "hasProj=${ProjectionHolder.hasProjection()}"
        )
        if (intent?.getBooleanExtra(EXTRA_STOP, false) == true) {
            main.post { teardown() }
            return START_NOT_STICKY
        }

        val tokenInIntent = intent?.getIntExtra("resultCode", Int.MIN_VALUE)?.let { it != Int.MIN_VALUE } == true &&
            intent.getParcelableExtra<Intent>("resultData") != null

        // START_STICKY 会把带一次性 token 的旧 intent 重投递回来，绝不能再用它触发截屏。
        if (tokenInIntent && ProjectionHolder.hasProjection()) {
            Log.w(TAG, "QOCR 忽略重投递的旧 token intent")
            main.post { applyStates() }
            return START_STICKY
        }

        try {
            promoteForeground(includeMedia = ProjectionHolder.hasProjection() || tokenInIntent)
        } catch (e: Exception) {
            Log.e(TAG, "QOCR startForeground 失败", e)
            Toast.makeText(this, "入口服务启动受限：${e.message}", Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.getBooleanExtra(EXTRA_TOGGLE_BALL, false) == true) {
            prefs().edit().putBoolean("ball_enabled", !ballEnabled()).apply()
        }
        if (intent?.getBooleanExtra(EXTRA_TOGGLE_NOTIF, false) == true) {
            prefs().edit().putBoolean("notif_enabled", !notifEnabled()).apply()
        }

        if (!ProjectionHolder.hasProjection() && tokenInIntent) {
            try {
                promoteForeground(includeMedia = true)
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val data = intent!!.getParcelableExtra<Intent>("resultData")!!
                val mp = mpm.getMediaProjection(
                    intent.getIntExtra("resultCode", Int.MIN_VALUE), data
                ) ?: throw IllegalStateException("MediaProjection 为空")
                ProjectionHolder.mediaProjection = mp
                acquireProjectionCallback()
                Log.i(TAG, "QOCR projection acquired")
            } catch (e: Exception) {
                Log.e(TAG, "QOCR getMediaProjection 失败", e)
                Toast.makeText(this, "截屏授权失败：${e.message}", Toast.LENGTH_LONG).show()
                ProjectionHolder.clearAll()
                registered = false
                callbackProjection = null
                refreshNotification()
                return START_STICKY
            }
        }

        main.post { applyStates() }

        if (intent?.getBooleanExtra(EXTRA_CAPTURE, false) == true &&
            (ProjectionHolder.hasProjection() || OcrA11yService.isEnabled(this))
        ) {
            main.post { startCaptureInternal() }
        }
        return START_STICKY
    }

    /** 挂前台。includeMedia=true 时带 mediaProjection 类型（须在 getMediaProjection 之前挂好）。 */
    private fun promoteForeground(includeMedia: Boolean = ProjectionHolder.hasProjection()) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "OCR快照入口", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n = buildNotification()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (includeMedia && Build.VERSION.SDK_INT >= 30) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        ServiceCompat.startForeground(this, NOTIF_ID, n, types)
        Log.i(TAG, "QOCR promoteForeground includeMedia=$includeMedia types=$types")
    }

    private fun acquireProjectionCallback() {
        val mp = ProjectionHolder.mediaProjection ?: return
        if (registered && callbackProjection === mp) return
        registered = true
        callbackProjection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "QOCR projection onStop（系统作废）")
                main.post {
                    ProjectionHolder.clearAll()
                    registered = false
                    callbackProjection = null
                    releaseCaptureObjects()
                    try { promoteForeground() } catch (_: Exception) {}
                    refreshNotification()
                }
            }
        }, main)
    }

    /** 常驻 VD + 缓存最近一帧。每个 MediaProjection 只建一次 VD。 */
    private fun ensureVirtualDisplay(mp: MediaProjection) {
        if (virtualDisplay != null && reader != null) return
        releaseCaptureObjects()
        val wmSys = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wmSys.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        thread = HandlerThread("ocr-capture").also { it.start() }
        val bgHandler = Handler(thread!!.looper)
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            synchronized(frameLock) {
                try { cachedImage?.close() } catch (_: Exception) {}
                cachedImage = img
            }
        }, bgHandler)
        virtualDisplay = mp.createVirtualDisplay(
            "quickocr", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, bgHandler
        )
        Log.i(TAG, "QOCR VirtualDisplay created (persistent, frame cache)")
    }

    /**
     * 截屏入口：优先走无障碍 takeScreenshot()（一次授权长期有效），未开启/失败时
     * 回退到 MediaProjection 常驻 VD 缓存帧。
     * 收起系统面板由入口侧负责（磁贴 startActivityAndCollapse / 通知点击启动透明
     * Activity），这里统一延迟 650ms 等过渡动画结束再抓。
     */
    private fun startCaptureInternal() {
        if (captureScheduled) {
            Log.i(TAG, "QOCR capture already scheduled, skip (防抖)")
            return
        }
        if (OcrA11yService.isEnabled(this)) {
            captureScheduled = true
            Log.i(TAG, "QOCR a11y capture scheduled in ${CAPTURE_DELAY_MS}ms")
            main.postDelayed({
                captureScheduled = false
                val started = OcrA11yService.captureIfAvailable { bmp ->
                    main.post {
                        if (bmp != null) {
                            Log.i(TAG, "QOCR a11y frame grabbed")
                            OverlayController.startCrop(applicationContext, bmp)
                        } else {
                            Log.w(TAG, "QOCR a11y failed → projection fallback")
                            captureViaProjection()
                        }
                    }
                }
                if (!started) {
                    Log.w(TAG, "QOCR a11y not bound → projection fallback")
                    captureViaProjection()
                }
            }, CAPTURE_DELAY_MS)
            return
        }
        captureViaProjection()
    }

    /** 原 MediaProjection 链路：常驻 VD + 缓存最近一帧，延迟后取帧。 */
    private fun captureViaProjection() {
        val mp = ProjectionHolder.mediaProjection ?: run {
            Log.w(TAG, "QOCR capture skipped: no a11y and no projection")
            Toast.makeText(applicationContext, "请先开启无障碍权限，或点「录屏权限」授权", Toast.LENGTH_LONG).show()
            return
        }
        if (captureScheduled) {
            Log.i(TAG, "QOCR capture already scheduled, skip (防抖)")
            return
        }
        try {
            ensureVirtualDisplay(mp)
        } catch (e: Exception) {
            Log.e(TAG, "QOCR 建 VirtualDisplay 失败（不自动重授权）", e)
            ProjectionHolder.clearAll()
            registered = false
            callbackProjection = null
            releaseCaptureObjects()
            main.post {
                try { promoteForeground() } catch (_: Exception) {}
                refreshNotification()
                Toast.makeText(applicationContext, "截屏失败，请重新点一次悬浮球", Toast.LENGTH_SHORT).show()
            }
            return
        }

        captureScheduled = true
        Log.i(TAG, "QOCR projection capture scheduled in ${CAPTURE_DELAY_MS}ms")

        main.postDelayed({
            captureScheduled = false
            val img = synchronized(frameLock) { cachedImage }
            if (img == null) {
                Log.w(TAG, "QOCR no cached frame")
                Toast.makeText(applicationContext, "截屏失败，请再点一次", Toast.LENGTH_SHORT).show()
                return@postDelayed
            }
            val bmp = imageToBitmap(img)
            Log.i(TAG, "QOCR frame grabbed bmp=${bmp != null}")
            if (bmp != null) {
                OverlayController.startCrop(applicationContext, bmp)
            } else {
                Toast.makeText(applicationContext, "截屏失败", Toast.LENGTH_SHORT).show()
            }
        }, CAPTURE_DELAY_MS)
    }

    private fun imageToBitmap(img: Image): Bitmap? = try {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val iw = img.width
        val ih = img.height
        val rowBytes = iw * pixelStride
        val bitmap = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
        if (rowStride == rowBytes) {
            buf.rewind()
            bitmap.copyPixelsFromBuffer(buf)
        } else {
            val tmp = ByteBuffer.allocate(rowBytes * ih)
            val dup = buf.duplicate()
            for (row in 0 until ih) {
                dup.position(row * rowStride)
                dup.limit(row * rowStride + rowBytes)
                tmp.put(dup)
            }
            tmp.rewind()
            bitmap.copyPixelsFromBuffer(tmp)
        }
        bitmap
    } catch (e: Exception) {
        Log.e(TAG, "QOCR imageToBitmap failed", e)
        null
    }

    private fun releaseCaptureObjects() {
        Log.i(TAG, "QOCR releaseCaptureObjects vd=${virtualDisplay != null}")
        captureScheduled = false
        synchronized(frameLock) {
            try { cachedImage?.close() } catch (_: Exception) {}
            cachedImage = null
        }
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        virtualDisplay = null
        reader = null
        try { thread?.quitSafely() } catch (_: Exception) {}
        thread = null
    }

    private fun teardown() {
        ProjectionHolder.clearAll()
        registered = false
        callbackProjection = null
        releaseCaptureObjects()
        removeBall()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun applyStates() {
        if (ballEnabled()) showBall() else removeBall()
        refreshNotification()
    }

    /** 走透明中转页：点击通知会让系统收起通知栏，避免截到通知面板。 */
    private fun capturePi(): PendingIntent =
        PendingIntent.getActivity(
            this, 10,
            Intent(this, ConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun servicePi(extra: String): PendingIntent = PendingIntent.getService(
        this, if (extra == EXTRA_STOP) 12 else 11,
        Intent(this, BallService::class.java).putExtra(extra, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "OCR快照入口", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setOngoing(true)
            .setContentIntent(capturePi())
        if (notifEnabled()) {
            builder.setContentTitle("OCR快照 就绪")
                .setContentText("点「截屏框选」→ 拖框 → 识别 → 复制")
                .addAction(0, "截屏框选", capturePi())
                .addAction(0, if (ballView != null) "隐藏悬浮球" else "显示悬浮球",
                    servicePi(EXTRA_TOGGLE_BALL))
                .addAction(0, "退出", servicePi(EXTRA_STOP))
        } else {
            builder.setContentTitle("OCR快照 运行中")
                .setContentText("通知入口已关闭（在应用内可重新开启）")
        }
        return builder.build()
    }

    private fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun showBall() {
        if (ballView != null) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val size = (46 * dp).toInt()

        val v = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xB34F8CFF.toInt())
                setStroke((2 * dp).toInt(), android.graphics.Color.WHITE)
            }
        }

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (24 * dp).toInt()
            y = (320 * dp).toInt()
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!moved && (abs(dx) > 8 * dp || abs(dy) > 8 * dp)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm?.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        Log.i(TAG, "QOCR ball tap hasProj=${ProjectionHolder.hasProjection()}")
                        if (!ProjectionHolder.hasProjection()) {
                            try {
                                startActivity(
                                    Intent(this, ConsentActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (e: Exception) {
                                Toast.makeText(this, "请先打开 OCR快照 完成授权", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            startCaptureInternal()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        wm?.addView(v, params)
        ballView = v
    }

    private fun removeBall() {
        ballView?.let {
            try { wm?.removeView(it) } catch (_: Exception) {}
        }
        ballView = null
    }
}

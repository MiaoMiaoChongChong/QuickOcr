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
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.nio.ByteBuffer

/**
 * 按需前台服务：仅在以下两种场景运行并挂载通知：
 * 1. 用户开启了「通知栏入口」；
 * 2. 当前持有备用 MediaProjection 录屏通道（Android 系统强制要求媒体投影必须挂载前台通知）。
 *
 * 悬浮球展示与无障碍静默截屏已剥离至 EntryCoordinator，关闭通知入口后本服务直接停止并撤销通知。
 *
 * ★Android 14+：一个 MediaProjection 只允许 createVirtualDisplay 一次，故 VD/ImageReader
 * 常驻不释放，并缓存 reader 的最近一帧——画面静止时不产新帧也能截到当前画面。
 */
class BallService : Service() {

    companion object {
        private const val TAG = "QOCR-Svc"
        private const val CHANNEL = "ocr_entry"
        private const val NOTIF_ID = 1002
        private const val CAPTURE_DELAY_MS = 650L
        const val EXTRA_STOP = "stop"
        const val EXTRA_STOP_PROJECTION = "stop_projection"
        const val EXTRA_TOGGLE_BALL = "toggle_ball"
        const val EXTRA_CAPTURE = "capture"
        const val EXTRA_CAPTURE_DELAY = "capture_delay"
        const val EXTRA_FROM_FGS = "from_fgs"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BallService::class.java).putExtra(EXTRA_FROM_FGS, true)
            )
        }

        fun refresh(context: Context) {
            if (!isRunning) return
            try {
                context.startService(Intent(context, BallService::class.java))
            } catch (_: Exception) {
                start(context)
            }
        }

        fun capture(context: Context, delayMs: Long = CAPTURE_DELAY_MS) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BallService::class.java)
                    .putExtra(EXTRA_CAPTURE, true)
                    .putExtra(EXTRA_CAPTURE_DELAY, delayMs)
                    .putExtra(EXTRA_FROM_FGS, true)
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
                    .putExtra(EXTRA_FROM_FGS, true)
            )
        }

        /** 仅停止前台服务并清除通知（不关闭悬浮球，用于用户关闭「通知栏入口」时）。 */
        fun stopServiceOnly(context: Context) {
            try {
                context.stopService(Intent(context, BallService::class.java))
            } catch (_: Exception) {}
        }

        /** 彻底退出（通知栏「退出」按钮用：同时收起悬浮球并释放投影）。 */
        fun stop(context: Context) {
            if (!isRunning) return
            try {
                context.startService(
                    Intent(context, BallService::class.java).putExtra(EXTRA_STOP, true)
                )
            } catch (_: Exception) {
                stopServiceOnly(context)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var vdWidth = 0
    private var vdHeight = 0
    private var registered = false
    private var callbackProjection: MediaProjection? = null

    /** 常驻 VD 的最近一帧；不 close，直到被新帧替换或服务销毁。 */
    private var cachedImage: Image? = null
    private val frameLock = Any()
    private var captureScheduled = false

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
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromFgs = intent?.getBooleanExtra(EXTRA_FROM_FGS, false) == true
        val parcelData = intent?.let {
            IntentCompat.getParcelableExtra(it, "resultData", Intent::class.java)
        }
        val tokenInIntent = intent?.getIntExtra("resultCode", Int.MIN_VALUE)?.let { it != Int.MIN_VALUE } == true &&
            parcelData != null
        Log.i(
            TAG,
            "QOCR cmd startId=$startId capture=${intent?.getBooleanExtra(EXTRA_CAPTURE, false) == true} " +
                "token=$tokenInIntent stop=${intent?.getBooleanExtra(EXTRA_STOP, false) == true} " +
                "stopProj=${intent?.getBooleanExtra(EXTRA_STOP_PROJECTION, false) == true} " +
                "hasProj=${ProjectionHolder.hasProjection()}"
        )

        if (intent?.getBooleanExtra(EXTRA_STOP, false) == true) {
            main.post { teardown() }
            return START_NOT_STICKY
        }

        if (intent?.getBooleanExtra(EXTRA_STOP_PROJECTION, false) == true) {
            ProjectionHolder.clearAll()
            registered = false
            callbackProjection = null
            releaseCaptureObjects()
            EntryCoordinator.sync(applicationContext)
            return START_NOT_STICKY
        }

        if (intent?.getBooleanExtra(EXTRA_TOGGLE_BALL, false) == true) {
            Prefs.setBallEnabled(this, !Prefs.ballEnabled(this))
            EntryCoordinator.sync(applicationContext)
        }

        val notifOn = Prefs.notifEnabled(this)
        val needFgs = notifOn || ProjectionHolder.hasProjection() || tokenInIntent
        if (!needFgs) {
            // 若调用方通过 startForegroundService 拉起，须先满足 FGS 启动契约再立刻移除通知退出
            if (fromFgs) {
                try {
                    promoteForeground(includeMedia = false)
                } catch (_: Exception) {}
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // START_STICKY 会把带一次性 token 的旧 intent 重投递回来，绝不能再用它触发截屏。
        if (tokenInIntent && ProjectionHolder.hasProjection()) {
            Log.w(TAG, "QOCR 忽略重投递的旧 token intent")
            promoteForeground(includeMedia = true)
            main.post { applyStates() }
            return if (notifOn) START_STICKY else START_NOT_STICKY
        }

        try {
            promoteForeground(includeMedia = ProjectionHolder.hasProjection() || tokenInIntent)
        } catch (e: Exception) {
            Log.e(TAG, "QOCR startForeground 失败", e)
            Toast.makeText(this, "入口服务启动受限：${e.message}", Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!ProjectionHolder.hasProjection() && tokenInIntent) {
            try {
                promoteForeground(includeMedia = true)
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val data = parcelData!!
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
                EntryCoordinator.sync(applicationContext)
                return START_NOT_STICKY
            }
        }

        main.post { applyStates() }

        if (intent?.getBooleanExtra(EXTRA_CAPTURE, false) == true) {
            val delayMs = intent.getLongExtra(EXTRA_CAPTURE_DELAY, CAPTURE_DELAY_MS)
            if (OcrA11yService.isEnabled(this)) {
                main.post { EntryCoordinator.triggerCapture(applicationContext, delayMs = delayMs) }
            } else if (ProjectionHolder.hasProjection()) {
                main.post { captureViaProjection(delayMs) }
            }
        }
        return if (Prefs.notifEnabled(this)) START_STICKY else START_NOT_STICKY
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
        val notifOn = Prefs.notifEnabled(this)
        var types = if (notifOn || Build.VERSION.SDK_INT < 30 || !includeMedia) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
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
                    EntryCoordinator.sync(applicationContext)
                }
            }
        }, main)
    }

    private fun currentDisplayMetrics(): Triple<Int, Int, Int> {
        val wmSys = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wmSys.maximumWindowMetrics.bounds
            val dpi = resources.configuration.densityDpi
            Triple(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1), dpi)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wmSys.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1), metrics.densityDpi)
        }
    }

    /** 常驻 VD + 缓存最近一帧；横竖屏切换时原地 resize 并替换 Surface，避免重复 createVirtualDisplay。 */
    private fun ensureVirtualDisplay(mp: MediaProjection) {
        val (w, h, dpi) = currentDisplayMetrics()
        val existingVd = virtualDisplay
        val existingReader = reader
        if (existingVd != null && existingReader != null) {
            if (vdWidth == w && vdHeight == h) return
            val handler = bgHandler ?: Handler(thread!!.looper).also { bgHandler = it }
            val newReader = createImageReader(w, h, handler)
            existingVd.resize(w, h, dpi)
            existingVd.setSurface(newReader.surface)
            synchronized(frameLock) {
                try { cachedImage?.close() } catch (_: Exception) {}
                cachedImage = null
            }
            try { existingReader.close() } catch (_: Exception) {}
            reader = newReader
            vdWidth = w
            vdHeight = h
            Log.i(TAG, "QOCR VirtualDisplay resized to ${w}x$h")
            return
        }

        releaseCaptureObjects()
        val t = HandlerThread("ocr-capture").also { it.start() }
        thread = t
        val handler = Handler(t.looper).also { bgHandler = it }
        val r = createImageReader(w, h, handler)
        reader = r
        virtualDisplay = mp.createVirtualDisplay(
            "quickocr", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
        vdWidth = w
        vdHeight = h
        Log.i(TAG, "QOCR VirtualDisplay created ${w}x$h (persistent, frame cache)")
    }

    private fun createImageReader(w: Int, h: Int, handler: Handler): ImageReader {
        return ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ ir ->
                val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                synchronized(frameLock) {
                    try { cachedImage?.close() } catch (_: Exception) {}
                    cachedImage = img
                }
            }, handler)
        }
    }

    /** 原 MediaProjection 链路：常驻 VD + 缓存最近一帧，延迟后取帧。 */
    private fun captureViaProjection(delayMs: Long = CAPTURE_DELAY_MS) {
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
                EntryCoordinator.sync(applicationContext)
                Toast.makeText(applicationContext, "截屏失败，请重新点一次悬浮球", Toast.LENGTH_SHORT).show()
            }
            return
        }

        captureScheduled = true
        EntryCoordinator.setBallTemporarilyHidden(true)
        Log.i(TAG, "QOCR projection capture scheduled in ${delayMs}ms")

        main.postDelayed({
            captureScheduled = false
            val img = synchronized(frameLock) { cachedImage }
            EntryCoordinator.setBallTemporarilyHidden(false)
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
        }, delayMs)
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
        vdWidth = 0
        vdHeight = 0
        bgHandler = null
        try { thread?.quitSafely() } catch (_: Exception) {}
        thread = null
    }

    private fun teardown() {
        ProjectionHolder.clearAll()
        registered = false
        callbackProjection = null
        releaseCaptureObjects()
        EntryCoordinator.hideBall()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun applyStates() {
        if (!Prefs.notifEnabled(this) && !ProjectionHolder.hasProjection()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
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

    private fun servicePi(extra: String): PendingIntent {
        val reqCode = when (extra) {
            EXTRA_STOP -> 12
            EXTRA_STOP_PROJECTION -> 13
            else -> 11
        }
        return PendingIntent.getService(
            this, reqCode,
            Intent(this, BallService::class.java).putExtra(extra, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

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
        if (Prefs.notifEnabled(this)) {
            builder.setContentTitle("OCR快照 就绪")
                .setContentText("点「截屏框选」→ 拖框 → 识别 → 复制")
                .addAction(0, "截屏框选", capturePi())
                .addAction(
                    0,
                    if (EntryCoordinator.isBallVisible()) "隐藏悬浮球" else "显示悬浮球",
                    servicePi(EXTRA_TOGGLE_BALL)
                )
                .addAction(0, "退出", servicePi(EXTRA_STOP))
        } else {
            // 仅在「关闭通知入口 + 未开无障碍但持有录屏权限」时才会走到这里
            builder.setContentTitle("OCR快照 · 录屏备用通道")
                .setContentText("系统要求录屏保活；开启无障碍权限即可彻底隐藏此通知")
                .addAction(0, "停止录屏通道", servicePi(EXTRA_STOP_PROJECTION))
        }
        return builder.build()
    }

    private fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }
}

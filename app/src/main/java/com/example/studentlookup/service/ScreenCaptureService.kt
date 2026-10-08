package com.example.studentlookup.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.studentlookup.util.Diag

/**
 * 截屏采集服务（**可选**的 OCR 兜底路径，仅用于识别微信会话标题）。
 *
 * 这个类里的每一处都必须是「不可能崩」的：它在 App 进程内运行，一旦抛未捕获异常，
 * 整个进程会死掉，同进程的无障碍服务也会被 ColorOS 判定为「无法运行」并自动关闭开关
 * （用户看到的现象就是：截屏 → App 退出 → 权限被关掉 → 点悬浮球没反应）。
 *
 * Android 14 的三条硬规则（官方文档）：
 * 1. 必须先在清单里声明 FOREGROUND_SERVICE_MEDIA_PROJECTION，并先取得用户授权（consent），
 *    再用 mediaProjection 类型 startForeground()，最后才 getMediaProjection()；
 * 2. 必须先注册 MediaProjection.Callback，否则 createVirtualDisplay() 抛 IllegalStateException；
 * 3. 一个 MediaProjection 令牌只能 createVirtualDisplay() 一次，重复调用抛 SecurityException
 *    （所以这里把 VirtualDisplay 建一次并复用，而不是每次截图都新建）。
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "SLK-Capture"
        private const val CHANNEL_ID = "capture_channel"
        private const val NOTIF_ID = 2

        @Volatile
        var lastResultCode: Int = -1
        @Volatile
        var lastData: Intent? = null

        @Volatile
        var instance: ScreenCaptureService? = null

        /** 本进程内是否已授权过屏幕采集（进程重启后为 false，需重新授权）。 */
        fun hasPermission(): Boolean = lastData != null && lastResultCode != -1

        /**
         * 由**前台界面**（CapturePermissionActivity）在拿到用户授权后调用。
         * 返回是否成功启动；任何失败都只返回 false，绝不抛出。
         */
        fun startWithPermission(context: Context, resultCode: Int, data: Intent): Boolean {
            lastResultCode = resultCode
            lastData = data
            return try {
                context.startForegroundService(Intent(context, ScreenCaptureService::class.java))
                true
            } catch (t: Throwable) {
                Diag.log(context, "Capture", "启动截屏服务失败：${t.javaClass.simpleName} ${t.message}")
                lastData = null
                lastResultCode = -1
                false
            }
        }
    }

    private lateinit var projectionManager: MediaProjectionManager
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 用户在系统里停止共享（状态栏提示条）时走到这里；不要让 App 崩
            Diag.log(this@ScreenCaptureService, "Capture", "系统已停止屏幕采集")
            stopSelf()
        }
    }

    /**
     * 空闲自动收工：屏幕采集在系统状态栏一直是"共享中"，长期挂着既不礼貌、
     * 也容易被 ROM 的隐私/省电策略盯上。这里 90 秒没人用就自动停止。
     * 代价是下次用截图识别需要重新授权一次（本功能本来就是可选兜底）。
     */
    private val idleStop = Runnable {
        if (instance === this) {
            Diag.log(this, "Capture", "截图识别空闲超时，自动停止屏幕采集")
            stopSelf()
        }
    }

    private fun scheduleIdleStop() {
        handler.removeCallbacks(idleStop)
        handler.postDelayed(idleStop, 90_000L)
    }

    override fun onCreate() {
        super.onCreate()
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        createChannel()

        // Android 14：必须先用 mediaProjection 类型 startForeground，之后才能 getMediaProjection
        val started = try {
            startForeground(
                NOTIF_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            )
            true
        } catch (t: Throwable) {
            // 例如被系统限制后台启动前台服务；绝不能让它把整个进程带走
            Diag.log(this, "Capture", "startForeground 失败：${t.javaClass.simpleName} ${t.message}")
            false
        }
        if (!started) {
            stopSelf()
            return
        }

        instance = this
        if (hasPermission()) {
            projection = try {
                projectionManager.getMediaProjection(lastResultCode, lastData!!)
            } catch (t: Throwable) {
                // 令牌是一次性的：复用旧令牌时会抛 SecurityException，这里作废令牌并重来
                Diag.log(this, "Capture", "getMediaProjection 失败，令牌已作废：${t.message}")
                lastData = null
                lastResultCode = -1
                null
            }
            projection?.registerCallback(projectionCallback, handler)
        }
        scheduleIdleStop()
    }

    override fun onDestroy() {
        try { virtualDisplay?.release() } catch (t: Throwable) { Log.w(TAG, "release vd: ${t.message}") }
        try { reader?.close() } catch (t: Throwable) { Log.w(TAG, "close reader: ${t.message}") }
        virtualDisplay = null
        reader = null
        try { projection?.stop() } catch (t: Throwable) { Log.w(TAG, "stop projection: ${t.message}") }
        projection = null
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** VirtualDisplay 只能建一次，建好一直复用。 */
    private fun ensureVirtualDisplay(): Boolean {
        if (virtualDisplay != null) return true
        val proj = projection ?: return false
        val dm = resources.displayMetrics
        return try {
            val r = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
            virtualDisplay = proj.createVirtualDisplay(
                "slk-capture", dm.widthPixels, dm.heightPixels, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
            reader = r
            virtualDisplay != null
        } catch (t: Throwable) {
            Diag.log(this, "Capture", "createVirtualDisplay 失败：${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    /**
     * 采集屏幕顶部 fraction 比例的 Bitmap（用于 OCR 识别备注名）。
     * 失败一律返回 null，不抛异常。
     */
    fun captureTopRegion(fraction: Float = 0.18f): Bitmap? {
        if (!ensureVirtualDisplay()) return null
        val r = reader ?: return null
        val dm = resources.displayMetrics
        return try {
            var image = r.acquireLatestImage()
            var tries = 0
            while (image == null && tries < 12) {
                Thread.sleep(40)
                image = r.acquireLatestImage()
                tries++
            }
            if (image == null) return null
            val bitmap = imageToBitmap(image, dm.widthPixels, dm.heightPixels)
            image.close()
            scheduleIdleStop()
            val cropH = (dm.heightPixels * fraction).toInt().coerceIn(1, dm.heightPixels)
            Bitmap.createBitmap(bitmap, 0, 0, dm.widthPixels, cropH)
        } catch (t: Throwable) {
            Diag.log(this, "Capture", "截图失败：${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    private fun imageToBitmap(image: android.media.Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bmp = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)
        return bmp
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "截屏服务", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("学员速查 · 截屏识别")
            .setContentText("仅在识别微信标题时使用，可随时在系统里停止")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}

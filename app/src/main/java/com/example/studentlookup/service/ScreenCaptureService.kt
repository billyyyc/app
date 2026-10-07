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
import androidx.core.app.NotificationCompat

/**
 * 截屏采集服务（仅用于 OCR 兜底路径）。
 * 通过 MediaProjection 把屏幕渲染到 ImageReader，再裁剪顶部区域返回 Bitmap。
 *
 * 注意：MediaProjection 需要用户一次性授权（见 CapturePermissionActivity）。
 * 授权结果由 companion 保存，服务启动时据此创建 MediaProjection。
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "capture_channel"
        private const val NOTIF_ID = 2

        @Volatile
        var lastResultCode: Int = -1
        @Volatile
        var lastData: Intent? = null

        @Volatile
        var instance: ScreenCaptureService? = null

        fun startWithPermission(context: Context, resultCode: Int, data: Intent) {
            lastResultCode = resultCode
            lastData = data
            val intent = Intent(context, ScreenCaptureService::class.java)
            context.startForegroundService(intent)
        }

        fun hasPermission(): Boolean = lastData != null && lastResultCode != -1
    }

    private lateinit var projectionManager: MediaProjectionManager
    private var projection: MediaProjection? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        createChannel()
        startForeground(NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        instance = this
        if (hasPermission()) {
            projection = projectionManager.getMediaProjection(lastResultCode, lastData!!)
        }
    }

    override fun onDestroy() {
        projection?.stop()
        projection = null
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 采集屏幕顶部 fraction 比例的 Bitmap（用于 OCR 识别备注名）。
     * 失败返回 null（调用方应提示客服手动搜索）。
     */
    fun captureTopRegion(fraction: Float = 0.18f): Bitmap? {
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null
        try {
            virtualDisplay = projection?.createVirtualDisplay(
                "capture", width, height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            ) ?: return null

            // 给一帧时间渲染
            Thread.sleep(150)
            val image = reader.acquireLatestImage() ?: return null
            val bitmap = imageToBitmap(image, width, height)
            image.close()
            val cropH = (height * fraction).toInt().coerceAtMost(height)
            return Bitmap.createBitmap(bitmap, 0, 0, width, cropH)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            virtualDisplay?.release()
            reader.close()
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
            .setContentTitle("学员速查 · 截屏采集")
            .setContentText("用于离线识别微信标题")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}

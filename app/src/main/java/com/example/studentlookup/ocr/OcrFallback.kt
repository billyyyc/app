package com.example.studentlookup.ocr

import android.content.Context
import android.content.Intent
import com.example.studentlookup.service.ScreenCaptureService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognition
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 截图 OCR 兜底：当无障碍读不到微信标题时，截屏顶部区域并离线识别中文，
 * 取第一行非空文本作为疑似备注名返回。
 *
 * 依赖：ScreenCaptureService（需用户已授予屏幕采集权限）。
 */
object OcrFallback {

    private val client by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 识别顶部区域文本。返回最可能的备注名（首行），失败返回 null。
     * 若尚未授权屏幕采集，会拉起授权页；此时本次返回 null，下次再点即可生效。
     */
    suspend fun recognizeTop(context: Context): String? {
        if (!ScreenCaptureService.hasPermission()) {
            // Android 14 对后台启动 Activity 有限制，失败不应拖垮进程
            runCatching {
                context.startActivity(
                    Intent(context, com.example.studentlookup.ui.CapturePermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return null
        }
        val svc = ScreenCaptureService.instance ?: run {
            // 服务未运行则重启；Android 14 对后台启动 mediaProjection 型 FGS 有限制，
            // 失败只放弃本次 OCR，绝不能抛异常导致进程崩溃（会连带杀死无障碍服务）
            runCatching {
                ScreenCaptureService.lastData?.let {
                    ScreenCaptureService.startWithPermission(context, ScreenCaptureService.lastResultCode, it)
                }
            }
            ScreenCaptureService.instance
        } ?: return null

        val bitmap = svc.captureTopRegion(0.18f) ?: return null
        val text = recognize(bitmap) ?: return null
        // 取首行非空文本
        return text.lines().firstOrNull { it.isNotBlank() }?.trim()
    }

    private suspend fun recognize(bitmap: android.graphics.Bitmap): String? =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            client.process(image)
                .addOnSuccessListener { visionText ->
                    cont.resume(visionText.text.takeIf { it.isNotBlank() })
                }
                .addOnFailureListener {
                    cont.resume(null)
                }
        }
}

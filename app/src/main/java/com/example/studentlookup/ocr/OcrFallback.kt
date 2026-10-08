package com.example.studentlookup.ocr

import android.content.Context
import com.example.studentlookup.service.ScreenCaptureService
import com.example.studentlookup.util.Diag
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
     *
     * ⚠️ 这里**绝不**启动截屏服务：Android 12+ 从后台启动 mediaProjection 型前台服务会被
     * 系统拒绝，异常还可能把整个进程带走（进程死 → 无障碍服务被判「无法运行」→ 开关被关掉）。
     * 只使用「用户已在 App 内明确授权并已运行」的截屏服务。
     */
    suspend fun recognizeTop(context: Context): String? {
        val svc = ScreenCaptureService.instance ?: run {
            Diag.log(context, "OCR", "截屏服务未运行，跳过截图识别")
            return null
        }

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

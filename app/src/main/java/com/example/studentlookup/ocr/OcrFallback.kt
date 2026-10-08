package com.example.studentlookup.ocr

import android.content.Context
import android.graphics.Bitmap
import com.example.studentlookup.service.ScreenCaptureService
import com.example.studentlookup.util.Diag
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 截图 OCR 兜底：读取微信会话标题（对方备注名）。
 *
 * 主路径（Android 11+）：由无障碍服务直接截屏（无需录屏授权），裁掉状态栏后只对
 * 顶部标题条做离线中文识别，取最像人名的那一行。
 * 旧路径（Android 10 及以下）：MediaProjection 截屏，需要用户单独授权。
 */
object OcrFallback {

    private val client by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 明显不是人名的文本（状态栏/导航/时间等） */
    private val NOT_NAME = setOf(
        "微信", "通讯录", "发现", "我", "搜索", "取消", "返回", "更多", "＋", "+",
        "聊天信息", "详细资料", "发送", "关闭", "确定", "设置", "文件传输助手",
        "已收款", "已转账", "转账", "微信支付", "红包", "已读", "对方正在输入"
    )
    private val TIME_LIKE = Regex("^[\\d\\s:：.\\-+/%年月日]+$")

    private fun looksLikeName(t: String): Boolean {
        val s = t.trim()
        if (s.isEmpty() || s.length > 24) return false
        if (s in NOT_NAME) return false
        if (TIME_LIKE.matches(s)) return false
        // 纯英文数字（信号/电量/版本号之类）
        if (s.none { it.code > 0x2E80 }) return false
        return true
    }

    /**
     * 对「整屏截图」裁掉顶部状态栏后识别标题行。
     * @param skipTopPx 从该 y 开始裁（通常是状态栏高度）
     * @param maxHeightPx 裁剪高度（标题条大致范围，避免把聊天内容也识别进来）
     */
    suspend fun recognizeTitleLine(
        context: Context,
        full: Bitmap,
        skipTopPx: Int,
        maxHeightPx: Int
    ): String? {
        val top = skipTopPx.coerceIn(0, (full.height - 1).coerceAtLeast(0))
        val h = maxHeightPx.coerceIn(1, full.height - top)
        val crop = try {
            Bitmap.createBitmap(full, 0, top, full.width, h)
        } catch (t: Throwable) {
            android.util.Log.w("SLK-OCR", "裁剪失败：${t.message}")
            return null
        }
        // 1) 放大 2 倍再识别：微信标题字号偏小，放大后识别率明显更好
        val up = try {
            Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
        } catch (t: Throwable) {
            null
        }
        if (up != null) {
            val r = bestLine(context, up, "2x")
            if (r != null) return r
        }
        // 2) 原尺寸兜一次（放大反而糊掉的情况）
        val r2 = bestLine(context, crop, "1x")
        if (up != null) runCatching { up.recycle() }
        return r2
    }

    private suspend fun bestLine(context: Context, bmp: Bitmap, tag: String): String? {
        val text = recognize(bmp) ?: return null
        val lines = text.textBlocks
            .flatMap { it.lines }
            .sortedBy { it.boundingBox?.top ?: 0 }
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
        Diag.log(context, "OCR", "[$tag] 候选行=${lines.joinToString(" / ")}")
        // 含逗号的多段（一个备注里几个孩子）优先
        lines.firstOrNull { (it.contains(',') || it.contains('，')) && it.length >= 4 }?.let { return it }
        return lines.firstOrNull { looksLikeName(it) } ?: lines.firstOrNull()
    }

    /**
     * 旧路径：MediaProjection 截屏（Android 10 及以下）。
     * 这里**绝不**启动截屏服务：从后台启动 mediaProjection 型前台服务会被系统拒绝，
     * 异常还可能拖垮进程。只使用用户已明确授权并已运行的服务。
     */
    suspend fun recognizeTop(context: Context): String? {
        val svc = ScreenCaptureService.instance ?: run {
            Diag.log(context, "OCR", "旧版截屏服务未运行，跳过")
            return null
        }
        val bitmap = svc.captureTopRegion(0.18f) ?: return null
        val text = recognize(bitmap) ?: return null
        return text.textBlocks
            .flatMap { it.lines }
            .sortedBy { it.boundingBox?.top ?: 0 }
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .firstOrNull { looksLikeName(it) }
            ?: text.textBlocks.flatMap { it.lines }
                .firstOrNull { it.text.isNotBlank() }?.text?.trim()
    }

    private suspend fun recognize(bitmap: Bitmap): Text? =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            client.process(image)
                .addOnSuccessListener { visionText -> cont.resume(visionText) }
                .addOnFailureListener { cont.resume(null) }
        }
}

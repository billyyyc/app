package com.example.studentlookup.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * 本地 OCR 引擎（B 方案）：ONNX Runtime + PP-OCRv4 中文识别模型。
 *
 * 只做「单行识别」（PP-OCR 的 rec 模型）：我们本来就只裁了会话标题那一条，
 * 不需要检测网络（det），因此模型只有 10MB、一次推理几十毫秒。
 *
 * 输入预处理与官方一致：高度缩放到 48，宽按比例（上限 320，右侧补 0），
 * 归一化 (v/255-0.5)/0.5；输出 [1,T,C] 做 CTC 贪心解码，字典用 ppocr_keys_v1.txt。
 */
object PaddleOcr {

    private const val TAG = "SLK-PaddleOCR"
    private const val MODEL = "ppocr_rec.onnx"
    private const val KEYS = "ppocr_keys.txt"
    private const val REC_H = 48
    private const val MAX_W = 320

    @Volatile private var ready = false
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var keys: List<String> = emptyList()

    fun isReady(): Boolean = ready

    /** 首次调用时加载模型与字典（约 100~300ms），失败返回 false 由调用方回退到 ML Kit */
    @Synchronized
    fun init(context: Context): Boolean {
        if (ready) return true
        return try {
            val bytes = context.assets.open(MODEL).use { it.readBytes() }
            keys = context.assets.open(KEYS).use { ins ->
                ins.bufferedReader(Charsets.UTF_8).readLines()
            }
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val s = e.createSession(bytes, opts)
            env = e
            session = s
            ready = true
            Log.d(TAG, "模型就绪，字典 ${keys.size} 字，输入 ${s.inputNames}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "初始化失败：${t.message}")
            ready = false
            false
        }
    }

    /** 识别一行文字（传入的应是已经裁好的标题条）。失败返回 null */
    fun recognize(bitmap: Bitmap): String? {
        if (!ready) return null
        val s = session ?: return null
        return try {
            val scale = REC_H.toFloat() / bitmap.height
            var w = (bitmap.width * scale).toInt().coerceAtLeast(8)
            if (w > MAX_W) w = MAX_W
            val scaled = Bitmap.createScaledBitmap(bitmap, w, REC_H, true)

            val pix = IntArray(w * REC_H)
            scaled.getPixels(pix, 0, w, 0, 0, w, REC_H)
            val input = FloatArray(3 * REC_H * MAX_W)
            for (y in 0 until REC_H) {
                for (x in 0 until w) {
                    val c = pix[y * w + x]
                    val r = ((c shr 16) and 0xFF) / 127.5f - 1f
                    val g = ((c shr 8) and 0xFF) / 127.5f - 1f
                    val b = (c and 0xFF) / 127.5f - 1f
                    // CHW：R 全图 → G 全图 → B 全图
                    input[(0 * REC_H + y) * MAX_W + x] = r
                    input[(1 * REC_H + y) * MAX_W + x] = g
                    input[(2 * REC_H + y) * MAX_W + x] = b
                }
            }
            val shape = longArrayOf(1, 3, REC_H.toLong(), MAX_W.toLong())
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)
            val out = s.run(mapOf(s.inputNames.first() to tensor))
            val res = out.use { o ->
                @Suppress("UNCHECKED_CAST")
                val arr = (o.get(0).value as Array<Array<FloatArray>>)[0]  // [T][C]
                ctcDecode(arr)
            }
            tensor.close()
            if (scaled !== bitmap) scaled.recycle()
            res.ifBlank { null }
        } catch (t: Throwable) {
            Log.w(TAG, "识别失败：${t.message}")
            null
        }
    }

    /** CTC 贪心解码：0 为 blank，重复字符合并 */
    private fun ctcDecode(logits: Array<FloatArray>): String {
        val sb = StringBuilder()
        var last = -1
        for (step in logits) {
            var best = 0
            var bestVal = step[0]
            for (i in 1 until step.size) {
                if (step[i] > bestVal) { bestVal = step[i]; best = i }
            }
            if (best != 0 && best != last) {
                val idx = best - 1
                if (idx in keys.indices) sb.append(keys[idx])
            }
            last = best
        }
        return sb.toString().trim()
    }
}

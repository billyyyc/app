package com.example.studentlookup.match

import com.example.studentlookup.data.model.Student
import kotlin.math.abs

/**
 * 学员匹配（按学生姓名）。
 *
 * 分四步，越往后越不可靠，靠不上的就给「候选点选」而不是硬给出一个答案：
 * 1 精确匹配（姓名完全相同，含「陈怡彤2」这种带序号的名字）
 * 2 去称呼后缀后匹配（微信备注「陈怡彤妈」->「陈怡彤」）
 * 3 包含匹配（互相包含，两边都至少 2 个字）—— 近似，需核对
 * 4 都没中：按编辑距离给出最像的少量候选（OCR 认错字时的主要出路），人工点选
 *
 * 注意：第 4 步**不再**把所有距离≤2 的名字都算「命中」——中文三字名只要姓氏相同、
 * 另外两字不同，距离就是 2，那样会一次命中几百人（实测 567 个），毫无意义。
 */
object Matcher {

    /** 一次最多给几个候选 */
    const val MAX_SUGGESTIONS = 8

    // 常见称呼/后缀，用于「去称呼后缀」匹配（先长后短，避免「妈妈」只被剥掉一个字）
    private val SUFFIXES = listOf(
        "的妈妈", "的爸爸", "的妈", "的爸",
        "妈妈", "爸爸", "外婆", "外公", "爷爷", "奶奶", "姥爷", "姥姥",
        "家长", "本人", "先生", "女士", "老师", "阿姨",
        "姑姑", "姑妈", "舅舅", "叔叔", "伯伯", "婶婶", "公公", "婆婆",
        "妈", "爸", "姨", "姑", "舅", "叔", "伯", "婶", "嫂",
        "爷", "奶", "公", "婆", "姐", "哥", "弟", "妹"
    )

    data class MatchResult(
        val found: Boolean,
        /** 近似命中（包含匹配），需要人工核对 */
        val approximate: Boolean,
        /** 查询串原文（卡片上会显示） */
        val query: String,
        /** 姓名 -> 该生在所有学期的记录 */
        val byName: Map<String, List<Student>>,
        /** 未精确命中时的候选姓名（最像的在前） */
        val suggestions: List<String> = emptyList()
    ) {
        val names: List<String> get() = byName.keys.toList()
    }

    fun match(queryRaw: String, all: List<Student>): MatchResult {
        val q = NameNormalizer.normalize(queryRaw)
        if (q.isEmpty()) return MatchResult(false, false, queryRaw, emptyMap())
        val qStripped = stripSuffix(q)
        val keys = listOf(q, qStripped).distinct().filter { it.length >= 2 }

        // 1) 精确
        val exact = all.filter { NameNormalizer.normalize(it.name) == q }
        if (exact.isNotEmpty()) return build(exact, false, queryRaw)

        // 2) 去称呼后缀后精确
        if (qStripped != q) {
            val s = all.filter {
                val n = NameNormalizer.normalize(it.name)
                n == qStripped || stripSuffix(n) == qStripped
            }
            if (s.isNotEmpty()) return build(s, false, queryRaw)
        }

        // 3) 包含（互含）：两边都至少 2 个字，避免单字/短串刷屏
        for (k in keys) {
            val c = all.filter {
                val n = NameNormalizer.normalize(it.name)
                n.length >= 2 && (n.contains(k) || k.contains(n))
            }
            if (c.isNotEmpty()) return build(c, true, queryRaw, rank(c, k))
        }

        // 4) 未命中：给最像的少量候选，人工点选
        val base = if (qStripped.length >= 2) qStripped else q
        return MatchResult(false, false, queryRaw, emptyMap(), candidates(base, all))
    }

    /** 按编辑距离给出候选（先按距离、再按共同字符数、最后按字典序） */
    fun candidates(base: String, all: List<Student>, limit: Int = MAX_SUGGESTIONS): List<String> =
        all.asSequence()
            .map { NameNormalizer.normalize(it.name) }
            .filter { it.length >= 2 && abs(it.length - base.length) <= 1 }
            .distinct()
            .map { it to levenshtein(it, base) }
            .sortedWith(compareBy({ it.second }, { -shared(it.first, base) }, { it.first }))
            .map { it.first }
            .take(limit)
            .toList()

    private fun build(
        rows: List<Student>,
        approximate: Boolean,
        query: String,
        suggestions: List<String> = emptyList()
    ): MatchResult {
        val byName = rows.groupBy { it.name }
        return MatchResult(true, approximate, query, byName, suggestions)
    }

    private fun rank(rows: List<Student>, q: String): List<String> =
        rows.map { it.name }.distinct()
            .sortedWith(
                compareBy(
                    { levenshtein(NameNormalizer.normalize(it), q) },
                    { abs(it.length - q.length) },
                    { it }
                )
            )

    /** 两个字符串的「共同字符数」（按多重集计数，用于打破编辑距离的并列） */
    private fun shared(a: String, b: String): Int {
        val cnt = HashMap<Char, Int>()
        for (c in b) cnt[c] = (cnt[c] ?: 0) + 1
        var hit = 0
        for (c in a) {
            val n = cnt[c] ?: 0
            if (n > 0) {
                hit++
                cnt[c] = n - 1
            }
        }
        return hit
    }

    /** 去掉尾部称呼；剥完太短（<2）就保留原样，避免误伤单字名 */
    private fun stripSuffixOnce(s: String): String {
        for (suf in SUFFIXES) {
            if (s.endsWith(suf)) {
                val t = s.removeSuffix(suf)
                if (t.length >= 2) return t
            }
        }
        return s
    }

    private fun stripSuffix(s: String): String {
        // 允许「陈怡彤妈妈」「陈怡彤的妈妈」这类叠加，最多剥两次
        val once = stripSuffixOnce(s)
        return if (once != s) stripSuffixOnce(once) else s
    }

    /** 标准编辑距离（Levenshtein） */
    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }
}

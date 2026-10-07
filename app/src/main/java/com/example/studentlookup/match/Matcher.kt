package com.example.studentlookup.match

import com.example.studentlookup.data.model.Student

/**
 * 学员匹配（按学生姓名）。
 * 优先级：
 * 1 精确匹配（姓名完全相同）
 * 2 去称呼后缀后匹配（如微信备注「王小宝妈妈」->「王小宝」）
 * 3 包含匹配（互相包含，要求较短一方长度 >= 2）—— 近似，需核对
 * 4 编辑距离容错（错字）—— 近似，需核对
 *
 * 返回按「姓名」聚合的记录集合，便于结果卡片按学期展示该生的全部记录。
 * 多个不同姓名命中时（多为模糊），由调用方决定是列名单还是提示缩小范围。
 */
object Matcher {

    // 常见称呼/后缀，用于「去称呼后缀」匹配
    private val SUFFIXES = listOf(
        "妈妈", "爸爸", "爸", "姐姐", "姐", "哥哥", "哥",
        "叔叔", "阿姨", "爷爷", "奶奶", "姥爷", "姥姥",
        "先生", "女士", "老师", "舅舅", "姑姑", "婶婶", "伯伯"
    )

    data class MatchResult(
        val found: Boolean,
        val approximate: Boolean,
        /** 模糊命中时为真，提示核对 */
        val query: String,
        /** 姓名 -> 该生在所有学期的记录 */
        val byName: Map<String, List<Student>>
    ) {
        val names: List<String> get() = byName.keys.toList()
    }

    fun match(queryRaw: String, all: List<Student>): MatchResult {
        val q = NameNormalizer.normalize(queryRaw)
        if (q.isEmpty()) return MatchResult(false, false, queryRaw, emptyMap())

        // 1) 精确
        val exact = all.filter { NameNormalizer.normalize(it.name) == q }
        if (exact.isNotEmpty()) return build(exact, false, queryRaw)

        // 2) 去称呼后缀
        val qStripped = stripSuffix(q)
        if (qStripped != q) {
            val s = all.filter {
                val n = NameNormalizer.normalize(it.name)
                n == qStripped || stripSuffix(n) == qStripped
            }
            if (s.isNotEmpty()) return build(s, false, queryRaw)
        }

        // 3) 包含（互含）。单字也允许：命中过多时由卡片提示「缩小范围」，不会刷屏
        val contains = all.filter { name ->
            val n = NameNormalizer.normalize(name.name)
            n.contains(q) || q.contains(n)
        }
        if (contains.isNotEmpty()) return build(contains, true, queryRaw)

        // 4) 编辑距离容错
        val th = if (q.length <= 2) 1 else 2
        val lev = all.filter { levenshtein(NameNormalizer.normalize(it.name), q) <= th }
        if (lev.isNotEmpty()) return build(lev, true, queryRaw)

        return MatchResult(false, false, queryRaw, emptyMap())
    }

    private fun build(rows: List<Student>, approximate: Boolean, query: String): MatchResult {
        val byName = rows.groupBy { it.name }
        return MatchResult(true, approximate, query, byName)
    }

    private fun stripSuffix(s: String): String {
        for (suf in SUFFIXES) {
            if (s.endsWith(suf)) return s.removeSuffix(suf)
        }
        return s
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

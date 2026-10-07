package com.example.studentlookup.util

/**
 * 学期清洗 / 排序 / 年龄计算。与「学生查询.html」保持同一套规则，
 * 这样安卓端和网页端对学期的展示顺序、命名一致。
 */
object TermUtils {

    private val SEASON_MAP = linkedMapOf(
        "寒假" to 1,
        "春季" to 2,
        "暑期" to 3,
        "秋季" to 4
    )

    /** 清洗学期名：去掉「考勤信息」后缀，保留 年份+季节，如 2026暑期考勤信息 -> 2026暑期 */
    fun clean(term: String): String {
        val stripped = term.replace("考勤信息", "").trim()
        val year = "\\d{4}".toRegex().find(stripped)?.value ?: ""
        val season = SEASON_MAP.keys.firstOrNull { stripped.contains(it) } ?: ""
        return if (year.isNotEmpty() && season.isNotEmpty()) "$year$season" else stripped
    }

    /** 排序键：年份-季节序，如 2026暑期 -> 2026-3。数值越大越新。 */
    fun sortKey(term: String): String {
        val yearMatch = "\\d{4}".toRegex().find(term)
        val year = yearMatch?.value ?: "0"
        var order = 9
        for ((season, idx) in SEASON_MAP) {
            if (term.contains(season)) { order = idx; break }
        }
        return "$year-$order"
    }

    /** 从出生年月（支持 2012.5.9 / 2012-5-9 / 2012.5 等）算年龄，失败返回 null */
    fun ageFromBirth(birth: String?): String? {
        if (birth.isNullOrBlank()) return null
        val b = birth.replace("\n", "").trim()
        val parts = if (b.contains(".")) b.split(".") else b.split("-")
        if (parts.isEmpty()) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = (parts.getOrNull(1)?.toIntOrNull() ?: 1)
        val day = (parts.getOrNull(2)?.toIntOrNull() ?: 1)
        val now = java.util.Calendar.getInstance()
        var age = now.get(java.util.Calendar.YEAR) - year
        if (now.get(java.util.Calendar.MONTH) + 1 < month ||
            (now.get(java.util.Calendar.MONTH) + 1 == month && now.get(java.util.Calendar.DAY_OF_MONTH) < day)
        ) {
            age -= 1
        }
        return if (age in 0..120) "${age}岁" else null
    }

    /** 年龄（整数），失败返回 -1 */
    fun ageInt(birth: String?): Int {
        if (birth.isNullOrBlank()) return -1
        val b = birth.replace("\n", "").trim()
        val parts = if (b.contains(".")) b.split(".") else b.split("-")
        val year = parts[0].toIntOrNull() ?: return -1
        val month = (parts.getOrNull(1)?.toIntOrNull() ?: 1)
        val day = (parts.getOrNull(2)?.toIntOrNull() ?: 1)
        val now = java.util.Calendar.getInstance()
        var age = now.get(java.util.Calendar.YEAR) - year
        if (now.get(java.util.Calendar.MONTH) + 1 < month ||
            (now.get(java.util.Calendar.MONTH) + 1 == month && now.get(java.util.Calendar.DAY_OF_MONTH) < day)
        ) age -= 1
        return if (age in 0..120) age else -1
    }
}

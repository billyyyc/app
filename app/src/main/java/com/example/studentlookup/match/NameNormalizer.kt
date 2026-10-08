package com.example.studentlookup.match

/**
 * 备注名归一化。
 *
 * 微信备注里经常混进各种标记：百分号（打折标记「肖子祺%」）、括号备注（「曾子乐(不在读)」）、
 * 空格、emoji、书名号等。这些都不是姓名的一部分，查询前要清掉，否则永远匹配不上。
 * 规则：
 * 1. 去掉成对括号及其内容（中英文括号都算）
 * 2. 全角转半角
 * 3. 只保留「字母 / 数字 / 汉字」（汉字、数字序号如「陈怡彤2」会被保留）
 */
object NameNormalizer {

    private val BRACKETS = Regex("[\\(（\\[【{<][^)）\\]】}>]*[\\)）\\]】}>]")

    fun normalize(s: String): String {
        val noBracket = BRACKETS.replace(s, "")
        return noBracket.trim()
            .replace("\\s+".toRegex(), "")
            .map { c ->
                // 全角字符（FF01~FF5E）对应半角（21~7E）
                if (c.code in 0xFF01..0xFF5E) (c.code - 0xFEE0).toChar() else c
            }
            .filter { it.isLetterOrDigit() }
            .joinToString("")
    }
}

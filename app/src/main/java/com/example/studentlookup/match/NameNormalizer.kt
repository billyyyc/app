package com.example.studentlookup.match

/**
 * 备注名归一化：去空格、全角转半角（ASCII 可视字符范围）。
 * 仅做安全、可逆的转换，避免误伤正常姓名中的符号。
 */
object NameNormalizer {
    fun normalize(s: String): String {
        return s.trim()
            .replace("\\s+".toRegex(), "")
            .map { c ->
                // 全角字符（FF01~FF5E）对应半角（21~7E）
                if (c.code in 0xFF01..0xFF5E) (c.code - 0xFEE0).toChar() else c
            }
            .joinToString("")
    }
}

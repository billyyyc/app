package com.example.studentlookup.data.import

import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 极简 xlsx 读取器（零依赖，不引入 POI，保持安装包精简）。
 * 只支持读取「一个单元格 = 一段文本」的普通表格，覆盖教务维护的学员表场景。
 *
 * 关键：返回每一个 sheet 的数据，sheet 名即「期」。
 */
object XlsxReader {

    data class SheetData(
        val name: String,
        /** 行 -> 列 -> 单元格文本（可空） */
        val rows: List<List<String?>>
    )

    fun read(input: InputStream): List<SheetData> {
        // 1) 先把整个 zip 读进内存，便于按名随机访问各 part
        val entries = mutableMapOf<String, ByteArray>()
        val zis = ZipInputStream(input)
        var entry = zis.nextEntry
        while (entry != null) {
            entries[entry.name] = zis.readBytes()
            entry = zis.nextEntry
        }

        val sharedStrings = entries["xl/sharedStrings.xml"]
            ?.let { parseSharedStrings(it) } ?: emptyList()

        val rels = entries["xl/_rels/workbook.xml.rels"]
            ?.let { parseRels(it) } ?: emptyMap()

        val workbookSheets = entries["xl/workbook.xml"]
            ?.let { parseWorkbook(it) } ?: emptyList() // name -> rId

        val result = mutableListOf<SheetData>()
        for ((name, rId) in workbookSheets) {
            val target = rels[rId] ?: continue
            val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
            val bytes = entries[path] ?: continue
            val rows = parseWorksheet(bytes, sharedStrings)
            if (rows.isNotEmpty()) result.add(SheetData(name, rows))
        }
        return result
    }

    // ---------- 内部解析 ----------

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val list = mutableListOf<String>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var event = parser.eventType
        val sb = StringBuilder()
        var inSi = false
        var inT = false
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "si" -> { inSi = true; sb.setLength(0) }
                        "t" -> inT = true
                    }
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> {
                    if (inT) sb.append(parser.text)
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "t" -> inT = false
                        "si" -> { list.add(sb.toString()); inSi = false }
                    }
                }
            }
            event = parser.next()
        }
        return list
    }

    private fun parseWorkbook(bytes: ByteArray): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var event = parser.eventType
        val relNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "sheet") {
                val name = parser.getAttributeValue(null, "name")
                val rId = parser.getAttributeValue(relNs, "id")
                if (name != null && rId != null) out.add(name to rId)
            }
            event = parser.next()
        }
        return out
    }

    private fun parseRels(bytes: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                if (id != null && target != null) map[id] = target
            }
            event = parser.next()
        }
        return map
    }

    private fun parseWorksheet(bytes: ByteArray, shared: List<String>): List<List<String?>> {
        val rows = mutableListOf<List<String?>>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var event = parser.eventType

        var inCell = false
        var cellText = StringBuilder()
        var maxCol = -1
        var currentMap: MutableMap<Int, String>? = null

        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> { currentMap = mutableMapOf() }
                        "c" -> {
                            inCell = true
                            cellText = StringBuilder()
                            // 记录当前单元格列索引（从 ref 如 "B3" 解析）
                            val ref = parser.getAttributeValue(null, "r") ?: ""
                            val col = colIndex(ref)
                            if (col >= 0 && col > maxCol) maxCol = col
                            currentCol = col
                            cellType = parser.getAttributeValue(null, "t")
                        }
                    }
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> {
                    if (inCell) cellText.append(parser.text)
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "c" -> {
                            inCell = false
                            val raw = cellText.toString().trim()
                            val value = when (cellType) {
                                "s" -> { // 共享字符串索引
                                    val idx = raw.toIntOrNull()
                                    if (idx != null && idx in shared.indices) shared[idx] else raw
                                }
                                "inlineStr" -> raw
                                else -> raw // 数字、普通文本
                            }
                            if (value.isNotEmpty() && currentCol >= 0) currentMap?.set(currentCol, value)
                        }
                        "row" -> {
                            if (currentMap != null) {
                                val list = MutableList(maxCol + 1) { null as String? }
                                for ((col, v) in currentMap!!) list[col] = v
                                rows.add(list)
                            }
                            currentMap = null
                            maxCol = -1
                        }
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    // 解析单元格引用列字母 -> 0 基索引（A->0, B->1, AA->26）
    private var currentCol = 0
    private var cellType: String? = null

    private fun colIndex(ref: String): Int {
        var idx = 0
        for (ch in ref) {
            if (ch.isLetter()) idx = idx * 26 + (ch.uppercaseChar() - 'A' + 1)
            else break
        }
        return idx - 1
    }
}

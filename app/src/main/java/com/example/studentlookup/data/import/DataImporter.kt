package com.example.studentlookup.data.import

import android.content.Context
import android.net.Uri
import com.example.studentlookup.App
import com.example.studentlookup.data.model.Student
import com.example.studentlookup.util.TermUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把读取到的 xlsx 数据写入本地库（对应真实「考勤记录.xlsx」）。
 *
 * 规则：
 * - 每个 sheet = 单独一期（term 取 sheet 名，如 2026暑期考勤信息）。
 * - 表头按「清洗后列名」映射，带别名容错：
 *   姓名(学生姓名/名字)、年级、学校、手机(手机号码/电话)、
 *   出生年月(出生日期)、班次(班级)、老师(教师)。
 *   「出生\n年月」这种带换行的表头会被清洗成「出生年月」。
 * - 一行 = 一名学生一期；同一学生多期会有多行。不做多孩子拆分（真实数据单名单行）。
 * - 默认整表覆盖（clear + insertAll）。
 */
object DataImporter {

    data class ImportResult(
        val inserted: Int,
        val studentCount: Int,
        val skipped: Int,
        val terms: List<String>,
        val errors: List<String>
    )

    // 目标字段 -> 候选表头（按顺序命中第一个）
    private val ALIASES = mapOf(
        "name" to listOf("姓名", "学生姓名", "名字"),
        "grade" to listOf("年级"),
        "school" to listOf("学校"),
        "phone" to listOf("手机", "手机号码", "电话"),
        "birth" to listOf("出生年月", "出生日期"),
        "classSession" to listOf("班次", "班级"),
        "teacher" to listOf("老师", "教师")
    )

    suspend fun import(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val db = (context.applicationContext as App).database

        val sheets = context.contentResolver.openInputStream(uri)?.use { XlsxReader.read(it) }
            ?: return@withContext ImportResult(0, 0, 0, emptyList(), listOf("无法读取文件，请检查文件是否损坏或权限。"))

        val students = mutableListOf<Student>()
        val errors = mutableListOf<String>()
        val terms = mutableListOf<String>()

        for (sheet in sheets) {
            val term = sheet.name.ifBlank { "默认期" }
            terms.add(term)

            val header = sheet.rows.firstOrNull()
            if (header == null) {
                errors.add("【$term】空表，已跳过")
                continue
            }
            val headerMap = mapHeaders(header)
            val nameIdx = resolveColumn(headerMap, "name")
            if (nameIdx == null) {
                errors.add("【$term】缺少「姓名」列，已跳过（表头：${header.filterNotNull().joinToString(",")}）")
                continue
            }
            val gradeIdx = resolveColumn(headerMap, "grade")
            val schoolIdx = resolveColumn(headerMap, "school")
            val phoneIdx = resolveColumn(headerMap, "phone")
            val birthIdx = resolveColumn(headerMap, "birth")
            val classIdx = resolveColumn(headerMap, "classSession")
            val teacherIdx = resolveColumn(headerMap, "teacher")

            for (r in 1 until sheet.rows.size) {
                val row = sheet.rows[r] ?: continue
                val name = cell(row, nameIdx)?.trim()
                if (name.isNullOrBlank()) continue // 空行跳过，不计错误
                val termClean = TermUtils.clean(term)
                students.add(
                    Student(
                        term = term,
                        termClean = termClean,
                        name = name,
                        grade = cell(row, gradeIdx)?.trim()?.takeIf { it.isNotBlank() },
                        school = cell(row, schoolIdx)?.trim()?.takeIf { it.isNotBlank() },
                        phone = cell(row, phoneIdx)?.trim()?.takeIf { it.isNotBlank() },
                        birth = cell(row, birthIdx)?.trim()?.takeIf { it.isNotBlank() },
                        classSession = cell(row, classIdx)?.trim()?.takeIf { it.isNotBlank() },
                        teacher = cell(row, teacherIdx)?.trim()?.takeIf { it.isNotBlank() }
                    )
                )
            }
        }

        db.studentDao().clear()
        db.studentDao().insertAll(students)

        val distinctNames = students.map { it.name }.toSet()
        ImportResult(
            inserted = students.size,
            studentCount = distinctNames.size,
            skipped = errors.size,
            terms = terms.distinct(),
            errors = errors
        )
    }

    /** 归一化表头：去所有空白（含换行）后做 key，便于容错 */
    private fun mapHeaders(header: List<String?>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        header.forEachIndexed { i, v ->
            val key = v?.replace("\\s+".toRegex(), "") ?: return@forEachIndexed
            if (key.isNotBlank()) map[key] = i
        }
        return map
    }

    /** 在 headerMap 中按别名顺序找第一个存在的列索引 */
    private fun resolveColumn(headerMap: Map<String, Int>, field: String): Int? {
        for (alias in (ALIASES[field] ?: emptyList())) {
            val idx = headerMap[alias]
            if (idx != null) return idx
        }
        return null
    }

    private fun cell(row: List<String?>, idx: Int?): String? =
        if (idx != null && idx in row.indices) row[idx] else null
}

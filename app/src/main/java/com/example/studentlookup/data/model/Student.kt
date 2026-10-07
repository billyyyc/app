package com.example.studentlookup.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条学员考勤记录（对应真实「考勤记录.xlsx」）。
 * - term / termClean：来自导入 xlsx 的 sheet 名（每一期一个 sheet），如「2026暑期考勤信息」。
 * - name：查询主键（学生姓名）。
 * 一行 = 一名学生在一个学期的一条记录。同一学生在多个学期会出现多行。
 */
@Entity(tableName = "students")
data class Student(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 学期原始名（sheet 名，如 2026暑期考勤信息） */
    @ColumnInfo(name = "term") val term: String,
    /** 学期清洗名（用于展示/排序，如 2026暑期） */
    @ColumnInfo(name = "term_clean") val termClean: String,
    /** 学生姓名（查询主键） */
    @ColumnInfo(name = "name") val name: String,
    /** 年级 */
    @ColumnInfo(name = "grade") val grade: String?,
    /** 学校 */
    @ColumnInfo(name = "school") val school: String?,
    /** 手机 */
    @ColumnInfo(name = "phone") val phone: String?,
    /** 出生年月 */
    @ColumnInfo(name = "birth") val birth: String?,
    /** 班次 */
    @ColumnInfo(name = "class_session") val classSession: String?,
    /** 老师 */
    @ColumnInfo(name = "teacher") val teacher: String?
)

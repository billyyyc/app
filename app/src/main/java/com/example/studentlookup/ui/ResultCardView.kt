package com.example.studentlookup.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import com.example.studentlookup.R
import com.example.studentlookup.data.model.Student
import com.example.studentlookup.match.Matcher
import com.example.studentlookup.util.TermUtils

/**
 * 结果卡片与菜单的浮层管理。所有浮层都是 TYPE_APPLICATION_OVERLAY，
 * 不跳转、不切应用，点空白处关闭。
 */
object ResultCardView {

    private const val TAG = "SLK-Card"

    /** 表格一行：某个学期 + 该学期该生的记录（没有记录则为空行，灰色显示） */
    data class GridRow(val termClean: String, val seq: Int?, val student: Student?)

    /** 列顺序：客服最常问的「哪个班、谁带」放前面 */
    private val COLUMNS = listOf("期数", "学期", "班次", "老师", "年级", "学校", "手机")

    private var current: View? = null
    private var currentMenu: View? = null

    fun show(
        context: Context,
        result: Matcher.MatchResult,
        onManualSearch: (String) -> Unit,
        /** 查询串（OCR 识别到的原文）预填进搜索框，方便直接改了再搜 */
        prefill: String = "",
        /** 同一家长的其他孩子：姓名 -> 「2026暑期 五晚D 徐铮」 */
        siblings: List<Pair<String, String>> = emptyList(),
        /** 点选候选学生（用于记住「识别文字 -> 正确姓名」） */
        onPick: ((String) -> Unit)? = null,
        /** 没认准的识别片段，点一下进纠正 */
        unresolved: List<String> = emptyList(),
        onCorrect: ((String) -> Unit)? = null,
        /** 上一步（点候选进来的），非空时显示「返回」 */
        onBack: (() -> Unit)? = null,
        grid: List<GridRow> = emptyList()
    ) {
        dismiss()
        val root = View.inflate(context, R.layout.result_card, null) as LinearLayout

        val tvTitle = root.findViewById<TextView>(R.id.tv_title)
        val container = root.findViewById<LinearLayout>(R.id.container_students)
        val tvStatus = root.findViewById<TextView>(R.id.tv_status)
        val etSearch = root.findViewById<EditText>(R.id.et_search)
        val btnSearch = root.findViewById<Button>(R.id.btn_search)
        val btnCopy = root.findViewById<Button>(R.id.btn_copy)
        val btnClose = root.findViewById<Button>(R.id.btn_close)
        val btnBack = root.findViewById<Button>(R.id.btn_back)

        tvTitle.text = "查询：${result.query}"
        if (prefill.isNotEmpty()) {
            etSearch.setText(prefill)
            etSearch.setSelection(prefill.length)
        }
        if (onBack != null) {
            btnBack.visibility = View.VISIBLE
            btnBack.setOnClickListener { onBack.invoke() }
        }

        if (result.found) {
            when {
                result.byName.size == 1 -> {
                    val (name, recs) = result.byName.entries.first()
                    tvStatus.text = if (result.approximate) "⚠ 近似匹配，请核对" else ""
                    tvStatus.setTextColor(context.getColor(R.color.warn))
                    container.addView(buildSummary(context, name, recs))
                    container.addView(buildTable(context, grid, recs))
                    if (siblings.isNotEmpty()) {
                        container.addView(hint(context, "同一家长的其他孩子（点名字切换）："))
                        for ((n, info) in siblings) {
                            container.addView(chip(context, "▸ $n    $info", 15f) { onManualSearch(n) })
                        }
                    }
                    addUnresolved(context, container, unresolved, onCorrect)
                    btnCopy.visibility = View.VISIBLE
                    btnCopy.setOnClickListener {
                        copy(context, buildCopyText(name, recs))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                }
                result.byName.size <= 12 -> {
                    tvStatus.text = "命中 ${result.byName.size} 个，点名字查看："
                    tvStatus.setTextColor(context.getColor(R.color.text_secondary))
                    val names = result.suggestions.ifEmpty { result.names.sorted() }
                    for (n in names) {
                        container.addView(chip(context, "▸ $n", 16f) { onManualSearch(n) })
                    }
                    addUnresolved(context, container, unresolved, onCorrect)
                    btnCopy.visibility = View.GONE
                }
                else -> {
                    tvStatus.text = "命中 ${result.byName.size} 个，请把姓名写全一点"
                    tvStatus.setTextColor(context.getColor(R.color.warn))
                    btnCopy.visibility = View.GONE
                }
            }
        } else if (result.suggestions.isNotEmpty()) {
            tvStatus.text = "没精确命中，请点选最像的："
            tvStatus.setTextColor(context.getColor(R.color.warn))
            for (n in result.suggestions) {
                container.addView(chip(context, "▸ $n", 16f) { (onPick ?: onManualSearch)(n) })
            }
            addUnresolved(context, container, unresolved, onCorrect)
            btnCopy.visibility = View.GONE
        } else {
            tvStatus.text = "未找到对应记录，改一下上面的搜索词试试"
            tvStatus.setTextColor(context.getColor(R.color.warn))
            btnCopy.visibility = View.GONE
            addUnresolved(context, container, unresolved, onCorrect)
        }

        btnSearch.setOnClickListener {
            val q = etSearch.text.toString().trim()
            if (q.isNotEmpty()) onManualSearch(q)
        }
        btnClose.setOnClickListener { dismiss() }

        addOverlay(context, root)
        current = root
    }

    // ---------- 表格 ----------

    private fun buildTable(context: Context, grid: List<GridRow>, recs: List<Student>): View {
        val rows = if (grid.isNotEmpty()) grid else fallbackGrid(recs)
        val latest = rows.firstOrNull { it.student != null }?.student?.let { TermUtils.sortKey(it.term) }
        val h = HorizontalScrollView(context).apply { isFillViewport = true }
        val table = TableLayout(context)
        table.addView(row(context, COLUMNS, Style.HEADER))
        for (r in rows) {
            val s = r.student
            if (s == null) {
                table.addView(row(context, listOf("", r.termClean, "", "", "", "", ""), Style.EMPTY))
            } else {
                val isLatest = TermUtils.sortKey(s.term) == latest
                table.addView(
                    row(
                        context,
                        listOf(
                            if (r.seq != null) "第${r.seq}期" else "",
                            r.termClean,
                            s.classSession ?: "",
                            s.teacher ?: "",
                            s.grade ?: "",
                            s.school ?: "",
                            s.phone ?: ""
                        ),
                        if (isLatest) Style.LATEST else Style.NORMAL
                    )
                )
            }
        }
        h.addView(table)
        return h
    }

    /** 没有传入完整学期表时的兜底：只列该生有记录的学期（倒序） */
    private fun fallbackGrid(recs: List<Student>): List<GridRow> =
        recs.sortedByDescending { TermUtils.sortKey(it.term) }
            .mapIndexed { i, s -> GridRow(TermUtils.clean(s.term), recs.size - i, s) }

    private enum class Style { HEADER, NORMAL, EMPTY, LATEST }

    private fun row(context: Context, cols: List<String>, style: Style): TableRow {
        val row = TableRow(context)
        for (c in cols) {
            row.addView(TextView(context).apply {
                text = c
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7))
                setLineSpacing(dp(context, 2).toFloat(), 1.05f)
                when (style) {
                    Style.HEADER -> {
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(context.getColor(R.color.text_primary))
                        setBackgroundResource(R.drawable.cell_header)
                    }
                    Style.EMPTY -> {
                        setTextColor(0xFF9AA0A6.toInt())
                        setBackgroundResource(R.drawable.cell_empty)
                    }
                    Style.LATEST -> {
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(context.getColor(R.color.text_primary))
                        setBackgroundResource(R.drawable.cell_latest)
                    }
                    Style.NORMAL -> {
                        setTextColor(context.getColor(R.color.text_primary))
                        setBackgroundResource(R.drawable.cell)
                    }
                }
            })
        }
        return row
    }

    private fun buildSummary(context: Context, name: String, recs: List<Student>): View {
        val age = recs.firstNotNullOfOrNull { TermUtils.ageFromBirth(it.birth) } ?: "未知"
        // 期数按「学期」去重：同一学期里可能有两行（同一学生报了不同班次）
        val termCount = recs.map { it.term }.distinct().size
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 2), dp(context, 6), 0, dp(context, 8))
            addView(TextView(context).apply {
                text = "👨‍🎓 $name"
                textSize = 18f
                setTextColor(context.getColor(R.color.text_primary))
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = "年龄：$age      就读：$termCount 期"
                textSize = 14f
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(0, dp(context, 4), 0, 0)
            })
        }
    }

    private fun hint(context: Context, text: String) = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(context.getColor(R.color.text_primary))
        setPadding(0, dp(context, 10), 0, 0)
    }

    private fun chip(context: Context, text: String, size: Float, onClick: () -> Unit) =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(context.getColor(R.color.purple_700))
            setPadding(dp(context, 4), dp(context, 9), 0, dp(context, 3))
            setOnClickListener { onClick() }
        }

    private fun addUnresolved(
        context: Context,
        container: LinearLayout,
        unresolved: List<String>,
        onCorrect: ((String) -> Unit)?
    ) {
        if (unresolved.isEmpty() || onCorrect == null) return
        container.addView(hint(context, "以下片段没认准，点一下纠正（改一次就会记住）："))
        for (seg in unresolved) {
            container.addView(chip(context, "▸ $seg", 15f) { onCorrect(seg) })
        }
    }

    /** 卡片高度按屏幕算：中间表格滚，底部按钮永远可见 */

    /** 纯提示卡片：用于「无障碍没连上 / 不在微信 / 识别失败」等场景，永远看得见 */
    fun showNotice(
        context: Context,
        title: String,
        message: String,
        actions: List<Pair<String, () -> Unit>>
    ) {
        dismiss()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x66000000)
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(context, 64), 0, 0)
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.card_bg))
            val pad = dp(context, 16)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(context, 16), 0, dp(context, 16), 0) }
            setOnClickListener { }
        }
        card.addView(TextView(context).apply {
            text = title
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(context.getColor(R.color.text_primary))
        })
        card.addView(TextView(context).apply {
            text = message
            textSize = 14f
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, dp(context, 8), 0, 0)
        })
        for ((label, action) in actions) {
            card.addView(Button(context).apply {
                text = label
                setOnClickListener { dismiss(); action() }
            })
        }
        root.addView(card)
        addOverlay(context, root)
        current = root
    }

    /** 长按菜单 */
    fun showMenu(context: Context, onAction: (MenuAction) -> Unit) {
        currentMenu?.let { remove(context, it) }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(android.R.color.white))
            val pad = dp(context, 12)
            setPadding(pad, pad, pad, pad)
        }
        val actions = listOf(
            "手动搜索" to MenuAction.MANUAL,
            "刷新数据" to MenuAction.REFRESH,
            "隐藏悬浮球" to MenuAction.HIDE,
            "设置/权限" to MenuAction.SETTINGS
        )
        for ((label, action) in actions) {
            root.addView(Button(context).apply {
                text = label
                setOnClickListener { onAction(action); remove(context, root); currentMenu = null }
            })
        }
        val params = WindowManager.LayoutParams(
            dp(context, 180), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        try {
            wm.addView(root, params)
        } catch (t: Throwable) {
            Log.w(TAG, "菜单 addView 失败: ${t.message}")
            Toast.makeText(context, "菜单无法显示（悬浮窗权限可能被系统关闭）", Toast.LENGTH_LONG).show()
            return
        }
        currentMenu = root
    }

    fun showManualSearch(context: Context, initialText: String = "", onSearch: (String) -> Unit) {
        dismiss()
        val root = View.inflate(context, R.layout.result_card, null) as LinearLayout
        val tvTitle = root.findViewById<TextView>(R.id.tv_title)
        val container = root.findViewById<LinearLayout>(R.id.container_students)
        val etSearch = root.findViewById<EditText>(R.id.et_search)
        val btnSearch = root.findViewById<Button>(R.id.btn_search)
        val btnClose = root.findViewById<Button>(R.id.btn_close)
        val btnBack = root.findViewById<Button>(R.id.btn_back)
        btnBack.visibility = View.GONE
        root.findViewById<Button>(R.id.btn_copy).visibility = View.GONE
        root.findViewById<TextView>(R.id.tv_status).visibility = View.GONE
        tvTitle.text = "手动搜索"
        container.visibility = View.GONE
        btnSearch.text = "搜索"
        if (initialText.isNotEmpty()) {
            etSearch.setText(initialText)
            etSearch.setSelection(initialText.length)
        }
        btnSearch.setOnClickListener {
            val q = etSearch.text.toString().trim()
            if (q.isNotEmpty()) onSearch(q)
        }
        btnClose.setOnClickListener { dismiss() }
        addOverlay(context, root)
        current = root
    }

    fun dismiss() {
        current?.let { v ->
            try {
                (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            } catch (t: Throwable) {
                Log.w(TAG, "removeView 失败: ${t.message}")
            }
        }
        current = null
    }

    private fun remove(context: Context, v: View) {
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (t: Throwable) {
            Log.w(TAG, "removeView 失败: ${t.message}")
        }
    }

    private fun addOverlay(context: Context, root: View) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        root.setOnClickListener { dismiss() }
        val card = root.findViewById<View>(R.id.card_root)
        card?.setOnClickListener { /* 阻止关闭 */ }
        // 默认把焦点给卡片本身、不落到输入框：否则每次弹卡片都会自动跳键盘挡住输入框
        card?.isFocusableInTouchMode = true
        card?.requestFocus()
        root.findViewById<EditText>(R.id.et_search)?.clearFocus()
        // 键盘弹起时让窗口跟着缩（配合 weight 布局，输入框与按钮不被挡住）
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        // 浮层加不上（例如悬浮窗权限被系统收回）绝不能崩掉整个进程
        try {
            wm.addView(root, params)
        } catch (t: Throwable) {
            Log.w(TAG, "addView 失败: ${t.message}")
            Toast.makeText(context, "浮层无法显示（悬浮窗权限可能被系统关闭）", Toast.LENGTH_LONG).show()
        }
    }

    private fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("student", text))
    }

    private fun buildCopyText(name: String, recs: List<Student>): String = buildString {
        append(name).append("\n")
        recs.sortedByDescending { TermUtils.sortKey(it.term) }.forEach { s ->
            append("${TermUtils.clean(s.term)}｜${s.classSession ?: ""} ${s.teacher ?: ""}｜")
            append("${s.grade ?: ""} ${s.school ?: ""}｜${s.phone ?: ""}\n")
        }
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()
}

enum class MenuAction { MANUAL, REFRESH, HIDE, SETTINGS }

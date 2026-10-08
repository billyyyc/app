package com.example.studentlookup.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
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
 * 不跳转、不切应用，点空白处关闭（对应 PRD 零打扰原则）。
 */
object ResultCardView {

    private var current: View? = null
    private var currentMenu: View? = null

    fun show(
        context: Context,
        result: Matcher.MatchResult,
        onManualSearch: (String) -> Unit,
        onDump: (() -> String?)? = null
    ) {
        dismiss()
        val root = View.inflate(context, R.layout.result_card, null) as LinearLayout
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val tvTitle = root.findViewById<TextView>(R.id.tv_title)
        val container = root.findViewById<LinearLayout>(R.id.container_students)
        val tvStatus = root.findViewById<TextView>(R.id.tv_status)
        val etSearch = root.findViewById<EditText>(R.id.et_search)
        val btnSearch = root.findViewById<Button>(R.id.btn_search)
        val btnCopy = root.findViewById<Button>(R.id.btn_copy)
        val btnClose = root.findViewById<Button>(R.id.btn_close)
        val btnDump = root.findViewById<Button>(R.id.btn_dump)

        tvTitle.text = "查询：${result.query}"

        if (result.found) {
            when {
                result.byName.size == 1 -> {
                    val (name, recs) = result.byName.entries.first()
                    if (result.approximate) {
                        tvStatus.text = "（近似匹配，请核对）"
                        tvStatus.setTextColor(context.getColor(R.color.warn))
                    } else {
                        tvStatus.text = ""
                    }
                    container.addView(buildSummary(context, name, recs))
                    container.addView(buildStudentTable(context, name, recs))
                    btnCopy.visibility = View.VISIBLE
                    btnCopy.setOnClickListener {
                        copy(context, buildCopyText(name, recs))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                }
                result.byName.size <= 12 -> {
                    tvStatus.text = "命中 ${result.byName.size} 个姓名，点选查看："
                    tvStatus.setTextColor(context.getColor(R.color.text_secondary))
                    val names = result.names.sorted()
                    for (n in names) {
                        val tv = TextView(context).apply {
                            text = "▸ $n"
                            textSize = 15f
                            setTextColor(context.getColor(R.color.text_primary))
                            setPadding(0, 8, 0, 2)
                            setOnClickListener { onManualSearch(n) }
                        }
                        container.addView(tv)
                    }
                    btnCopy.visibility = View.GONE
                }
                else -> {
                    tvStatus.text = "命中 ${result.byName.size} 个学生，请缩小范围"
                    tvStatus.setTextColor(context.getColor(R.color.warn))
                    btnCopy.visibility = View.GONE
                }
            }
        } else {
            tvStatus.text = "未找到对应记录"
            tvStatus.setTextColor(context.getColor(R.color.warn))
            btnCopy.visibility = View.GONE
        }

        btnSearch.setOnClickListener {
            val q = etSearch.text.toString().trim()
            if (q.isNotEmpty()) onManualSearch(q)
        }
        if (onDump == null) {
            btnDump.visibility = View.GONE
        } else {
            btnDump.setOnClickListener {
                val text = onDump.invoke()
                Toast.makeText(
                    context,
                    if (text.isNullOrBlank()) "没有节点信息（无障碍未连接？）"
                    else "已复制节点信息，可发给技术支持校准标题",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        btnClose.setOnClickListener { dismiss() }

        addOverlay(context, root)
        current = root
    }

    /**
     * 纯提示卡片：用于「无障碍没连上 / 不在微信 / 识别失败」等场景。
     * 与结果卡不同，它一定会显示出来 —— 点悬浮球“没反应”这种体验永远不该再出现。
     */
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
            // 有监听器才会消费点击，避免误触卡片把浮层关掉
            setOnClickListener { }
        }
        card.addView(TextView(context).apply {
            text = title
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
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

    private fun buildSummary(context: Context, name: String, recs: List<Student>): View {
        val age = recs.firstNotNullOfOrNull { TermUtils.ageFromBirth(it.birth) } ?: "未知"
        val ll = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 8)
        }
        ll.addView(TextView(context).apply {
            text = "👨‍🎓 $name"
            textSize = 17f
            setTextColor(context.getColor(R.color.text_primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        ll.addView(TextView(context).apply {
            text = "📅 年龄：$age    📚 就读：${recs.size} 期"
            textSize = 13f
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, 4, 0, 0)
        })
        return ll
    }

    private fun buildStudentTable(context: Context, name: String, recs: List<Student>): View {
        val sorted = recs.sortedBy { TermUtils.sortKey(it.term) }
        val maxH = (context.resources.displayMetrics.heightPixels * 0.55).toInt()
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxH
            ).apply { setMargins(0, 4, 0, 0) }
        }
        val table = TableLayout(context).apply {
            setBackgroundColor(context.getColor(R.color.card_bg))
        }
        // 表头
        table.addView(headerRow(context, listOf("期数", "学期", "年级", "学校", "手机", "班次", "老师")))
        sorted.forEachIndexed { i, s ->
            table.addView(dataRow(context, listOf(
                "第${i + 1}期", s.termClean, s.grade ?: "", s.school ?: "",
                s.phone ?: "", s.classSession ?: "", s.teacher ?: ""
            )))
        }
        scroll.addView(table)
        return scroll
    }

    private fun headerRow(context: Context, cols: List<String>): TableRow {
        val row = TableRow(context)
        for (c in cols) {
            row.addView(TextView(context).apply {
                text = c
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(context.getColor(R.color.text_primary))
                setPadding(4, 4, 4, 4)
            })
        }
        return row
    }

    private fun dataRow(context: Context, cols: List<String>): TableRow {
        val row = TableRow(context)
        for (c in cols) {
            row.addView(TextView(context).apply {
                text = c
                textSize = 11f
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(4, 4, 4, 4)
            })
        }
        return row
    }

    private fun buildCopyText(name: String, recs: List<Student>): String = buildString {
        append(name).append("\n")
        recs.sortedBy { TermUtils.sortKey(it.term) }.forEachIndexed { i, s ->
            append("第${i + 1}期 ${s.termClean}｜${s.grade ?: ""} ${s.school ?: ""}｜${s.phone ?: ""}｜${s.classSession ?: ""} ${s.teacher ?: ""}\n")
        }
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
            val b = Button(context).apply {
                text = label
                setOnClickListener { onAction(action); remove(context, root); currentMenu = null }
            }
            root.addView(b)
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
            Log.w("SLK-Card", "菜单 addView 失败: ${t.message}")
            Toast.makeText(context, "菜单无法显示（悬浮窗权限可能被系统关闭）", Toast.LENGTH_LONG).show()
            return
        }
        currentMenu = root
    }

    fun showManualSearch(context: Context, onSearch: (String) -> Unit) {
        dismiss()
        val root = View.inflate(context, R.layout.result_card, null) as LinearLayout
        val tvTitle = root.findViewById<TextView>(R.id.tv_title)
        val container = root.findViewById<LinearLayout>(R.id.container_students)
        val etSearch = root.findViewById<EditText>(R.id.et_search)
        val btnSearch = root.findViewById<Button>(R.id.btn_search)
        val btnClose = root.findViewById<Button>(R.id.btn_close)
        root.findViewById<Button>(R.id.btn_copy).visibility = View.GONE
        root.findViewById<TextView>(R.id.tv_status).visibility = View.GONE
        tvTitle.text = "手动搜索"
        container.visibility = View.GONE
        btnSearch.text = "搜索"
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
                Log.w("SLK-Card", "removeView 失败: ${t.message}")
            }
        }
        current = null
    }

    private fun remove(context: Context, v: View) {
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (t: Throwable) {
            Log.w("SLK-Card", "removeView 失败: ${t.message}")
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
        // 浮层加不上（例如悬浮窗权限被系统收回）绝不能崩掉整个进程
        try {
            wm.addView(root, params)
        } catch (t: Throwable) {
            Log.w("SLK-Card", "addView 失败: ${t.message}")
            Toast.makeText(context, "浮层无法显示（悬浮窗权限可能被系统关闭）", Toast.LENGTH_LONG).show()
        }
    }

    private fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("student", text))
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()
}

enum class MenuAction { MANUAL, REFRESH, HIDE, SETTINGS }

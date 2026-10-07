package com.example.studentlookup.util

import android.os.Build

/**
 * 国产 ROM 识别与引导文案（对应 PRD 第八章）。
 * 仅用于给用户展示「去哪开开关」，真正的开启仍需用户在系统设置手动操作。
 */
object RomUtils {

    enum class Rom { XIAOMI, HUAWEI, OPPO, VIVO, SAMSUNG, OTHER }

    fun getRom(): Rom {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return when {
            manufacturer.contains("xiaomi") || brand.contains("xiaomi") ||
                manufacturer.contains("redmi") -> Rom.XIAOMI
            manufacturer.contains("huawei") || brand.contains("huawei") ||
                manufacturer.contains("honor") -> Rom.HUAWEI
            manufacturer.contains("oppo") || brand.contains("oppo") ||
                manufacturer.contains("oneplus") -> Rom.OPPO
            manufacturer.contains("vivo") || brand.contains("vivo") ||
                manufacturer.contains("iqoo") -> Rom.VIVO
            manufacturer.contains("samsung") -> Rom.SAMSUNG
            else -> Rom.OTHER
        }
    }

    /** 返回该机型需要额外放行的开关说明 */
    fun guidance(): String = when (getRom()) {
        Rom.XIAOMI -> "小米/红米：设置 → 应用设置 → 权限 → 悬浮窗/后台弹出界面/自启动；电池与性能 → 省电策略设为「无限制」。"
        Rom.HUAWEI -> "华为/荣耀：设置 → 应用 → 应用启动管理 → 关闭自动管理并允许自启动/后台运行；权限 → 悬浮窗。"
        Rom.OPPO -> "OPPO/一加：设置 → 应用管理 → 悬浮窗/自启动；电池 → 允许后台高耗电。"
        Rom.VIVO -> "vivo/iQOO：设置 → 应用与权限 → 悬浮窗/后台弹出界面/自启动；电池 → 后台高耗电允许。"
        Rom.SAMSUNG -> "三星：设置 → 电池 → 电池优化 → 将该应用设为「不优化」。"
        Rom.OTHER -> "请在该机型设置中放行：悬浮窗、自启动、后台运行、电池优化白名单。"
    }
}

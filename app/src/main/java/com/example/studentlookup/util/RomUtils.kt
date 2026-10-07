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

    /**
     * 返回该机型需要额外放行的后台/悬浮窗开关说明（与无障碍服务无关）。
     */
    fun guidance(): String = when (getRom()) {
        Rom.XIAOMI -> "小米/红米：设置 → 应用设置 → 权限 → 悬浮窗/后台弹出界面/自启动；电池与性能 → 省电策略设为「无限制」。"
        Rom.HUAWEI -> "华为/荣耀：设置 → 应用 → 应用启动管理 → 关闭自动管理并允许自启动/后台运行；权限 → 悬浮窗。"
        Rom.OPPO -> "OPPO/一加：设置 → 应用管理 → 悬浮窗/自启动；电池 → 允许后台高耗电。"
        Rom.VIVO -> "vivo/iQOO：设置 → 应用与权限 → 悬浮窗/后台弹出界面/自启动；电池 → 后台高耗电允许。"
        Rom.SAMSUNG -> "三星：设置 → 电池 → 电池优化 → 将该应用设为「不优化」。"
        Rom.OTHER -> "请在该机型设置中放行：悬浮窗、自启动、后台运行、电池优化白名单。"
    }

    /**
     * 返回「去哪开启本应用的无障碍服务」的分步指引。
     * 关键点：第三方无障碍服务不在无障碍首页，而藏在「已下载的服务 / 已安装的服务」子菜单里。
     */
    fun accessibilityGuidance(): String = when (getRom()) {
        Rom.XIAOMI -> "① 点上方「开启无障碍」按钮 → ② 设置里选「更多设置」→「无障碍」→「已下载的应用」→ 找到「学员速查」→ ③ 开启开关，弹窗点「允许 / 确定」。\n提示：MIUI/HyperOS 若找不到，先回桌面再进设置；开启后勿在Recent里划掉本应用。"
        Rom.HUAWEI -> "① 点上方「开启无障碍」按钮 → ② 设置里选「辅助功能」→「无障碍」→「已下载的服务」→ 找到「学员速查」→ ③ 开启开关，弹窗点「允许」。\n提示：荣耀 MagicOS 路径为 设置 → 辅助功能 → 无障碍。"
        Rom.OPPO -> "① 点上方「开启无障碍」按钮 → ② 设置里选「其他设置」(或「系统设置」) →「无障碍」→「已下载的服务」→ 找到「学员速查」→ ③ 开启开关，弹窗点「允许」。\n提示：ColorOS 把第三方服务都收在「已下载的服务」里，首页看不到；开启后别强制停止本应用，否则服务会被自动关闭。"
        Rom.VIVO -> "① 点上方「开启无障碍」按钮 → ② 设置里选「更多设置」→「辅助功能」(或「无障碍」) →「已下载的服务」→ 找到「学员速查」→ ③ 开启开关，弹窗点「允许」。"
        Rom.SAMSUNG -> "① 点上方「开启无障碍」按钮 → ② 设置里选「无障碍」→「已安装的服务」→ 找到「学员速查」→ ③ 开启开关。\n提示：One UI 可能在「无障碍」→「已安装的服务」或「屏幕阅读器」同级菜单。"
        Rom.OTHER -> "① 点上方「开启无障碍」按钮 → ② 在系统设置里找到「无障碍 / Accessibility」→「已下载的服务 / Downloaded services」→ 找到「学员速查」→ ③ 开启开关，弹窗点「允许」。\n通用提示：第三方服务通常不在无障碍首页，需进「已下载的服务」子菜单。"
    }
}

package com.mediahub.model

/**
 * 播放专业信息面板偏好（P1 专业/精简开关）。
 *
 * - [expertMode] = true（产品默认）：信息面板展示完整三段
 *   （源参数 / 配置 / 运行观测）的全部行；
 * - false（精简）：同三段结构，仅保留每段的关键行，降低信息密度。
 *
 * 约束：该开关**只控制信息密度**——不重建播放器、不切换内核、不改画质；
 * 面板数据严格区分"源参数 / 配置 / 运行观测"三类，未知一律写
 * [ProfessionalInfoPreferences.UNKNOWN]（"—"），绝不编造（例：设置=硬解
 * 不代表实际已硬解；引擎未暴露实际解码器时就显示未知）。
 */
data class ProfessionalInfoPreferences(
    val expertMode: Boolean = true,
) {
    companion object {
        /** 未知值的统一表达（"—"）；面板映射函数对缺失字段一律输出该值。 */
        const val UNKNOWN: String = "—"

        /** 当前产品默认值；供恢复默认和测试使用。 */
        val Default: ProfessionalInfoPreferences
            get() = ProfessionalInfoPreferences()
    }
}

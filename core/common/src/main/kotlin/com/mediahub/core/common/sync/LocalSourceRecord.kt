package com.mediahub.core.common.sync

import com.mediahub.model.ServerType

/**
 * 本机已有一条来源记录的"规划视图"，对应 Windows LocalSourceRecord。
 *
 * 只放规划器需要的字段：不读凭据、不读显示时间戳。与 Windows 的差异：
 * - type 可空（Android 的 ServerType 没有 Unknown 值，null 表示"没有类型"）；
 * - syncSourceId 可空（pre-W6 的旧记录没有同步身份，正是 ADOPT 规则要处理的情形）。
 */
data class LocalSourceRecord(
    val serverId: String = "",
    val syncSourceId: String? = null,
    val type: ServerType? = null,
    val url: String? = null,
    val accountScope: String? = null,
    val displayName: String? = null,
    val recordRevision: Long = 0L,
)

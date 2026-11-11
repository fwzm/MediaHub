package com.mediahub.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 媒体源（服务器/云盘/本地）配置表。敏感信息（Token 等）绝不在此存储。 */
@Entity(
    tableName = "servers",
    indices = [Index(value = ["sync_source_id"], unique = true)],
)
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** [com.mediahub.model.ServerType].name */
    val type: String,
    val username: String? = null,
    val note: String? = null,
    val icon: String? = null,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
    val createdAtEpochMs: Long,
    val lastConnectedAtEpochMs: Long? = null,
    val lastError: String? = null,
    /**
     * 跨设备来源同步身份（ADR-S001 / W6-B）。null 表示这条来源还没有同步身份
     * （pre-W6 的旧记录，正是规划器 ADOPT 规则要处理的情形）。
     *
     * 三列用 snake_case 列名：与协议报告的字段命名（sync_source_id / sync_record_revision /
     * sync_source_type_raw）保持一致，便于跨端排查；历史列仍是 Room 默认的 camelCase，不做改名。
     */
    @ColumnInfo(name = "sync_source_id") val syncSourceId: String? = null,
    /** 该来源最后一次已知的记录修订号。合并次序只认它，禁止墙上时间 LWW（ADR-S001 D5）。 */
    // defaultValue 与 MIGRATION_4_5 的 DEFAULT 0 逐字一致：KSP 导出的 schemas/5.json 才是 Room 打开库时
    // 用来校验的实际结构，导出与迁移两处不一致会让"迁移成功但打开即崩"这种问题只在真机上暴露。
    @ColumnInfo(name = "sync_record_revision", defaultValue = "0") val syncRecordRevision: Long = 0L,
    /**
     * 原始 wire 类型值逐字保留。type 列只存得下 ServerType 枚举名，表达不了
     * "quantum-nas" 这类本端还不认识的同步值；丢在这里，将来版本可以接管（D3）。
     */
    @ColumnInfo(name = "sync_source_type_raw") val syncSourceTypeRaw: String? = null,
)

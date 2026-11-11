package com.mediahub.core.common.sync

/**
 * 一个 sourceType 值，保留发送端的原始写法（ADR-S001 D3）。
 *
 * raw 逐字保留（不 trim、不改大小写），因此 "Emby" 与 "emby" 是同类但不同值——相等性包含原文，
 * 与 Windows SyncSourceTypeValue 一致，避免把两种拼写静默合并。只有 wire 才做 trim + 小写，
 * 用于与合同词汇比较。
 */
class SyncSourceTypeValue private constructor(private val rawValue: String) {

    /** 原始写法，写出协议时用它（不是 wire）。 */
    val raw: String get() = rawValue

    /** 规范化后的比较形态：trim + 小写；空串表示"没有值"。 */
    val wire: String get() = rawValue.trim().lowercase()

    /** 是否属于合同词汇；空值返回 false。 */
    val isKnown: Boolean get() = SyncSourceTypes.isKnown(rawValue)

    /** 是否真的带了一个值（空白视为没有）。 */
    val isPresent: Boolean get() = wire.isNotEmpty()

    override fun equals(other: Any?): Boolean = other is SyncSourceTypeValue && other.rawValue == rawValue

    override fun hashCode(): Int = rawValue.hashCode()

    override fun toString(): String = rawValue

    companion object {
        /** null 合法（表示没有值），返回空值而不是抛异常。 */
        fun parse(raw: String?): SyncSourceTypeValue = SyncSourceTypeValue(raw ?: "")
    }
}

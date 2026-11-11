package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.WireText

/** 已配对设备清单里的一行。 */
data class PairedDeviceRow(
    /** 页面「忘记这台」唯一需要的值；不携带指纹、地址或任何凭据。 */
    val deviceId: String,
    val displayName: String = "",
    val fingerprintLabel: String,
    val pairedLabel: String,
    val lastSeenLabel: String,
    val sourceLabel: String,
    val summaryLine: String,
    val detailLine: String,
    val forgetLabel: String,
    val lastSeenEpochMs: Long,
)

/** 整页的只读视图模型：行 + 空态/豆腐块/容量提示 + 计数。 */
data class PairedDeviceListView(
    val rows: List<PairedDeviceRow>,
    val countLabel: String,
    val emptyLabel: String,
    val tofuLabel: String,
    val capacityLabel: String,
    val capacity: Int,
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    /** 容量提示非空即已满：界面据此禁用「登记新设备」之类动作。 */
    val isFull: Boolean get() = capacityLabel.isNotEmpty()

    val count: Int get() = rows.size
}

/**
 * 已配对设备清单的展示层。
 *
 * 只做格式化，不读写存储、不联网。所有文案与 Windows `MediaHub.Sync.PairedDeviceListPresenter` 逐字相同，
 * 这样两端截图能对得上，用户也不会因为换设备看到两套说法。
 */
object PairedDeviceListPresenter {

    /** 没有名字时退回标识的前缀长度。 */
    const val FALLBACK_NAME_LENGTH: Int = 8

    /** 指纹只显示尾部的长度：完整指纹不落界面，避免被拍照抄走。 */
    const val FINGERPRINT_SUFFIX_LENGTH: Int = 8

    /** 空清单文案。 */
    const val EMPTY_TEXT: String = "还没有配对过的设备。"

    /** 豆腐块：说清「配对过 ≠ 可信」，避免用户误以为已建立安全保障。 */
    const val TOFU_TEXT: String = "首次配对时无法验证对方身份：指纹只用来识别「上次那台是不是同一台」，不代表对方可信。"

    /** 容量文案前缀。 */
    const val CAPACITY_PREFIX: String = "已登记 "

    /**
     * 生成整页视图模型。[devices] 为 null 当作空清单；[capacity] 非正数退回默认 32；
     * **保持传入顺序**（排序由 registry 负责），与 Windows `Build` 一致。
     */
    fun build(
        devices: List<PairedDevice>?,
        capacity: Int = PairedDeviceRegistry.DEFAULT_CAPACITY,
        nowEpochMs: Long = 0L,
    ): PairedDeviceListView {
        // nowEpochMs 保留在签名里与 Windows Build 同形；当前文案没有相对措辞，故不参与计算。
        @Suppress("UNUSED_PARAMETER")
        val unusedNow = nowEpochMs
        val limit = if (capacity > 0) capacity else PairedDeviceRegistry.DEFAULT_CAPACITY
        val rows = devices.orEmpty().map { device -> buildRow(device) }
        return PairedDeviceListView(
            rows = rows,
            countLabel = countText(rows.size),
            emptyLabel = EMPTY_TEXT,
            tofuLabel = TOFU_TEXT,
            capacityLabel = if (rows.size >= limit) capacityText(limit) else "",
            capacity = limit,
        )
    }

    /** 单行；两个时间戳都用 [PairedDevice.formatEpoch]，与 Windows 的本地时间格式一致。 */
    fun buildRow(device: PairedDevice): PairedDeviceRow {
        val name = resolveName(device)
        val fingerprint = fingerprintText(device.fingerprint)
        val paired = "首次配对 " + PairedDevice.formatEpoch(device.pairedAtEpochMs)
        val lastSeen = "最后相见 " + PairedDevice.formatEpoch(device.lastSeenEpochMs)
        val sources = sourceText(device.sourceCount)
        return PairedDeviceRow(
            deviceId = device.deviceId,
            // 与 Windows PairedDeviceRow.DisplayName 一致：这里是**解析后**的名字（无名字时退回标识前缀），
            // 不是对端给的原始 displayName。
            displayName = name,
            fingerprintLabel = fingerprint,
            pairedLabel = paired,
            lastSeenLabel = lastSeen,
            sourceLabel = sources,
            summaryLine = listOf(name, fingerprint, paired, lastSeen, sources).joinToString(" · "),
            detailLine = listOf(fingerprint, paired, lastSeen, sources).joinToString(" · "),
            forgetLabel = "忘记 " + name,
            lastSeenEpochMs = device.lastSeenEpochMs,
        )
    }

    /** 计数文案。 */
    fun countText(count: Int): String = "已配对 " + count + " 台"

    /** 满容量文案。 */
    fun capacityText(capacity: Int): String =
        CAPACITY_PREFIX + capacity + " 台，已达上限：新的设备不会被登记，请先忘记一台不再使用的设备。"

    /** 有名字用名字，没名字退回标识前 8 个字符（短标识整个保留）。 */
    private fun resolveName(device: PairedDevice): String {
        if (!WireText.isBlank(device.displayName)) {
            return WireText.trim(device.displayName)
        }
        return if (device.deviceId.length <= FALLBACK_NAME_LENGTH) {
            device.deviceId
        } else {
            device.deviceId.substring(0, FALLBACK_NAME_LENGTH)
        }
    }

    /** 只显示指纹尾部；没有指纹就说「无指纹」，绝不显示完整指纹。 */
    private fun fingerprintText(fingerprint: String?): String {
        val value = WireText.trim(fingerprint ?: "")
        if (value.isEmpty()) {
            return "无指纹"
        }
        val suffix = if (value.length <= FINGERPRINT_SUFFIX_LENGTH) {
            value
        } else {
            value.substring(value.length - FINGERPRINT_SUFFIX_LENGTH)
        }
        return "指纹 …" + suffix
    }

    /** 来源数：负数当 0，永远不显示「-1 个来源」。 */
    private fun sourceText(sourceCount: Int): String =
        (if (sourceCount < 0) 0 else sourceCount).toString() + " 个来源"
}

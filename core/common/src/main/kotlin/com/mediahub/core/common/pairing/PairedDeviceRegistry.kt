package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.WireText
import java.io.IOException

/** 一次核对里「对方这次出示的指纹」和「本机钉住的指纹」的关系。 */
enum class PairedFingerprintState {
    /** 本机没有这个设备的记录。 */
    NEW,

    /** 有一条记录，且出示的指纹与它一致。 */
    MATCH,

    /** 有一条记录，但出示的指纹与它不一致（或这次没带可用指纹）。 */
    MISMATCH,
}

/**
 * 已配对设备登记的原因码。
 *
 * 这 12 个字符串是与 Windows `PairedDeviceReasons` 的对外契约：同名、同拼写，两端逐字相同。
 * [ALL] 的顺序也与 Windows 相同（注意它与下面的声明顺序不同：FingerprintNew 排在最前）。
 */
object PairedDeviceReasons {
    const val FINGERPRINT_MISMATCH: String = "device-fingerprint-mismatch"
    const val FINGERPRINT_NEW: String = "device-fingerprint-new"
    const val FINGERPRINT_MATCH: String = "device-fingerprint-match"
    const val MISSING_DEVICE_ID: String = "device-identity-missing"
    const val MISSING_FINGERPRINT: String = "device-fingerprint-missing"
    const val REGISTERED: String = "paired-device-registered"
    const val UPDATED: String = "paired-device-updated"
    const val UNCHANGED: String = "paired-device-unchanged"
    const val LIMIT_REACHED: String = PairedDeviceStoreReasons.LIMIT_REACHED
    const val STORE_UNREADABLE: String = PairedDeviceStoreReasons.STORE_UNREADABLE
    const val FORGOTTEN: String = "paired-device-forgotten"
    const val UNKNOWN_DEVICE: String = "paired-device-unknown"

    /** 与 Windows `PairedDeviceReasons.All` 同序。 */
    val ALL: List<String> = listOf(
        FINGERPRINT_NEW,
        FINGERPRINT_MATCH,
        FINGERPRINT_MISMATCH,
        MISSING_DEVICE_ID,
        MISSING_FINGERPRINT,
        REGISTERED,
        UPDATED,
        UNCHANGED,
        LIMIT_REACHED,
        STORE_UNREADABLE,
        FORGOTTEN,
        UNKNOWN_DEVICE,
    )
}

/** 一次「想登记某台设备」的输入（对端出示的标识、指纹、名字与来源数）。 */
data class PairedDeviceObservation(
    val deviceId: String,
    val fingerprint: String,
    val displayName: String = "",
    val sourceCount: Int = 0,
    val nowEpochMs: Long,
)

/** [PairedDeviceRegistry.verify] 的结果：状态 + 稳定原因码 + 双方指纹（用于界面说明）。 */
data class PairedDeviceVerification(
    val deviceId: String,
    val state: PairedFingerprintState,
    val reason: String = "",
    val presentedFingerprint: String = "",
    val pinnedFingerprint: String? = null,
) {
    val isMatch: Boolean get() = state == PairedFingerprintState.MATCH
    val isNew: Boolean get() = state == PairedFingerprintState.NEW
    val isMismatch: Boolean get() = state == PairedFingerprintState.MISMATCH

    /** 只有「新设备」与「一致」两种情况允许把指纹钉下去。 */
    val canPin: Boolean get() = state == PairedFingerprintState.NEW || state == PairedFingerprintState.MATCH
}

/** [PairedDeviceRegistry.register] 的结果：是否被接受、是否新增、改动后的记录与稳定原因码。 */
data class PairedDeviceRegistration(
    val device: PairedDevice?,
    val state: PairedFingerprintState,
    val accepted: Boolean,
    val added: Boolean,
    val reason: String = "",
) {
    val isMismatch: Boolean get() = state == PairedFingerprintState.MISMATCH

    companion object {
        fun rejected(state: PairedFingerprintState, reason: String, device: PairedDevice? = null): PairedDeviceRegistration =
            PairedDeviceRegistration(device = device, state = state, accepted = false, added = false, reason = reason)
    }
}

/**
 * 已配对设备登记簿。
 *
 * 职责只有三件：核对（[verify]）、登记（[register]）、忘记（[forget]）。
 * 关键安全不变式：**已经钉住的指纹绝不被覆盖** —— 同一个 deviceId 换了指纹一律判 Mismatch 并拒绝登记，
 * 只有先 [forget] 掉再重新配对才能换指纹。这与 Windows `PairedDeviceRegistry` 逐条对应。
 */
class PairedDeviceRegistry @JvmOverloads constructor(
    val store: PairedDeviceStore,
    val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "容量必须为正数。" }
    }

    /** 最近一次从存储加载时看到的问题（没有则为 null）。 */
    val loadError: String? get() = store.loadError

    /** 清单（已排好序）。 */
    fun getAll(): List<PairedDevice> = store.getAll().sortedWith { left, right ->
        val byName = WireText.compareAsciiIgnoreCase(left.deviceId, right.deviceId)
        if (byName != 0) byName else left.deviceId.compareTo(right.deviceId)
    }

    val count: Int get() = store.getAll().size

    val isFull: Boolean get() = count >= capacity

    /** 按 deviceId 找记录；空白视为没给，直接 null。大小写不敏感。 */
    fun find(deviceId: String?): PairedDevice? {
        val key = WireText.trim(deviceId ?: "")
        if (key.isEmpty()) {
            return null
        }
        for (device in store.getAll()) {
            if (WireText.equalsAsciiIgnoreCase(device.deviceId, key)) {
                return device
            }
        }
        return null
    }

    /** 只核对，不写盘。 */
    fun verify(deviceId: String?, fingerprint: String?): PairedDeviceVerification {
        val key = WireText.trim(deviceId ?: "")
        if (key.isEmpty()) {
            return PairedDeviceVerification(
                deviceId = "",
                state = PairedFingerprintState.NEW,
                reason = PairedDeviceReasons.MISSING_DEVICE_ID,
            )
        }
        val presented = PairingFingerprint.tryParse(fingerprint)
        val existing = find(key)
        if (existing == null) {
            return PairedDeviceVerification(
                deviceId = key,
                state = PairedFingerprintState.NEW,
                reason = if (presented != null) PairedDeviceReasons.FINGERPRINT_NEW else PairedDeviceReasons.MISSING_FINGERPRINT,
                presentedFingerprint = presented?.hex ?: "",
            )
        }
        if (presented == null) {
            return PairedDeviceVerification(
                deviceId = key,
                state = PairedFingerprintState.MISMATCH,
                reason = PairedDeviceReasons.MISSING_FINGERPRINT,
                pinnedFingerprint = existing.fingerprint,
            )
        }
        val pinned = PairingFingerprint.tryParse(existing.fingerprint)
        val same = pinned != null && pinned.matches(presented)
        return PairedDeviceVerification(
            deviceId = existing.deviceId,
            state = if (same) PairedFingerprintState.MATCH else PairedFingerprintState.MISMATCH,
            reason = if (same) PairedDeviceReasons.FINGERPRINT_MATCH else PairedDeviceReasons.FINGERPRINT_MISMATCH,
            presentedFingerprint = presented.hex,
            pinnedFingerprint = existing.fingerprint,
        )
    }

    /**
     * 登记一次观察。新设备按首次配对写入；已存在的设备只刷新「最近相见 / 名字 / 来源数」，
     * 指纹一旦不一致就拒绝（绝不覆盖），存储写失败也折成稳定原因码而不抛。
     */
    fun register(observation: PairedDeviceObservation): PairedDeviceRegistration {
        val key = WireText.trim(observation.deviceId)
        if (key.isEmpty()) {
            return PairedDeviceRegistration.rejected(PairedFingerprintState.NEW, PairedDeviceReasons.MISSING_DEVICE_ID)
        }
        val presented = PairingFingerprint.tryParse(observation.fingerprint)
            ?: return PairedDeviceRegistration.rejected(PairedFingerprintState.NEW, PairedDeviceReasons.MISSING_FINGERPRINT)
        val now = observation.nowEpochMs
        val existing = find(key)
        if (existing != null) {
            val verification = verify(existing.deviceId, presented.hex)
            if (verification.isMismatch) {
                return PairedDeviceRegistration.rejected(PairedFingerprintState.MISMATCH, verification.reason, existing)
            }
            val updated = existing.copy(
                displayName = if (WireText.isBlank(observation.displayName)) {
                    existing.displayName
                } else {
                    truncateName(WireText.trim(observation.displayName))
                },
                fingerprint = presented.hex,
                pairedAtEpochMs = if (existing.pairedAtEpochMs > 0) existing.pairedAtEpochMs else now,
                lastSeenEpochMs = maxOf(existing.lastSeenEpochMs, now),
                sourceCount = if (observation.sourceCount < 0) 0 else observation.sourceCount,
            )
            if (updated == existing) {
                return PairedDeviceRegistration(
                    device = existing,
                    state = PairedFingerprintState.MATCH,
                    accepted = true,
                    added = false,
                    reason = PairedDeviceReasons.UNCHANGED,
                )
            }
            try {
                store.save(updated)
            } catch (exception: IOException) {
                return PairedDeviceRegistration.rejected(PairedFingerprintState.MATCH, describeStoreFailure(exception), existing)
            }
            return PairedDeviceRegistration(
                device = updated,
                state = PairedFingerprintState.MATCH,
                accepted = true,
                added = false,
                reason = PairedDeviceReasons.UPDATED,
            )
        }
        if (store.getAll().size >= capacity) {
            return PairedDeviceRegistration.rejected(PairedFingerprintState.NEW, PairedDeviceReasons.LIMIT_REACHED)
        }
        val created = PairedDevice.create(
            deviceId = key,
            fingerprint = presented.hex,
            displayName = observation.displayName,
            nowEpochMs = now,
            sourceCount = observation.sourceCount,
        )
        try {
            store.save(created)
        } catch (exception: IOException) {
            return PairedDeviceRegistration.rejected(PairedFingerprintState.NEW, describeStoreFailure(exception))
        }
        return PairedDeviceRegistration(
            device = created,
            state = PairedFingerprintState.NEW,
            accepted = true,
            added = true,
            reason = PairedDeviceReasons.REGISTERED,
        )
    }

    /** 忘掉一台设备（解绑）：写盘失败只返回 false，不抛。 */
    fun forget(deviceId: String?): Boolean {
        val key = WireText.trim(deviceId ?: "")
        if (key.isEmpty()) {
            return false
        }
        return try {
            store.forget(key)
        } catch (exception: IOException) {
            false
        }
    }

    private fun describeStoreFailure(exception: IOException): String {
        val message = exception.message
        if (exception is PairedDeviceStoreException) {
            return if (exception.reason == PairedDeviceStoreReasons.LIMIT_REACHED) {
                PairedDeviceReasons.LIMIT_REACHED
            } else {
                PairedDeviceReasons.STORE_UNREADABLE
            }
        }
        return if (message != null && message.contains(PairedDeviceStoreReasons.LIMIT_REACHED)) {
            PairedDeviceReasons.LIMIT_REACHED
        } else {
            PairedDeviceReasons.STORE_UNREADABLE
        }
    }

    private fun truncateName(value: String): String =
        if (value.length > PairedDevice.MAXIMUM_DISPLAY_NAME_LENGTH) {
            value.substring(0, PairedDevice.MAXIMUM_DISPLAY_NAME_LENGTH)
        } else {
            value
        }

    companion object {
        /** 默认容量（与 Windows 相同）。 */
        const val DEFAULT_CAPACITY: Int = FilePairedDeviceStore.DEFAULT_CAPACITY

        /** 状态的中文说法：一致 / 不一致 / 新设备。 */
        fun describeState(state: PairedFingerprintState): String = when (state) {
            PairedFingerprintState.MATCH -> "指纹一致"
            PairedFingerprintState.MISMATCH -> "指纹不一致"
            PairedFingerprintState.NEW -> "新设备"
        }

        /**
         * 12 条原因码的中文说法（与 Windows `PairedDeviceReasons.DescribeReason` 逐字相同）。
         * 未知的非空原因原样返回，空原因说「未说明原因。」——这样界面永远不会显示空行。
         */
        fun describeReason(reason: String?): String = when {
            reason == PairedDeviceReasons.FINGERPRINT_MATCH -> "指纹与本机记录一致。"
            reason == PairedDeviceReasons.FINGERPRINT_NEW -> "本机第一次见到这个设备，将按首次配对记住它的指纹。"
            reason == PairedDeviceReasons.FINGERPRINT_MISMATCH ->
                "该设备这次出示的指纹与本机记录不一致，配对已中止；如需重新信任，请先在已配对设备里移除它。"
            reason == PairedDeviceReasons.MISSING_DEVICE_ID -> "对端没有提供设备标识，无法登记。"
            reason == PairedDeviceReasons.MISSING_FINGERPRINT -> "对端没有提供可用的设备指纹，无法登记。"
            reason == PairedDeviceReasons.REGISTERED -> "已记住该设备（配对成功不等于已同步）。"
            reason == PairedDeviceReasons.UPDATED -> "已更新该设备的最近配对时间。"
            reason == PairedDeviceReasons.UNCHANGED -> "该设备没有变化。"
            reason == PairedDeviceReasons.LIMIT_REACHED -> "已配对设备数量已达上限，新设备不会被登记；请先移除一台。"
            reason == PairedDeviceReasons.STORE_UNREADABLE -> "已配对设备清单当前不可写，这次配对没有被记住。"
            reason == PairedDeviceReasons.FORGOTTEN -> "已移除该设备。"
            reason == PairedDeviceReasons.UNKNOWN_DEVICE -> "本机没有这个设备的记录。"
            !WireText.isBlank(reason) -> reason!!
            else -> "未说明原因。"
        }

        /** 一台设备的一行摘要（与 Windows `PairedDevice.Describe` 同形）。 */
        fun describe(device: PairedDevice, nowEpochMs: Long? = null): String = device.describe(nowEpochMs)
    }
}

package com.mediahub.core.common.pairing

/**
 * 配对设备文档可能被拒绝、隔离或封顶的稳定原因码。
 *
 * 与 Windows 侧 MediaHub.Sync.PairedDeviceStoreReasons 逐字同名同序：这些码会写进日志与文档，
 * 两端对同一份坏文档必须给出同一个码。
 */
object PairedDeviceStoreReasons {
    /** 文件存在，但不是本构建认识的文档。 */
    const val MALFORMED_JSON = "paired-malformed-json"

    /** 文档由更新的构建写出；逐字段猜会丢设备。 */
    const val UNSUPPORTED_VERSION = "paired-unsupported-version"

    /** 文件存在但根本读不出来，于是本次会话拒绝覆盖它。 */
    const val STORE_UNREADABLE = "paired-store-unreadable"

    /** 清单已满：新设备被拒绝，而不是静默淘汰一台旧设备。 */
    const val LIMIT_REACHED = "paired-device-limit-reached"

    /** 一次写入开始但没写完；内存里的清单已回滚。 */
    const val WRITE_FAILED = "paired-store-write-failed"

    /** 上面全部原因码，按稳定顺序排列，供测试与文档枚举。 */
    val ALL: List<String> = listOf(
        MALFORMED_JSON,
        UNSUPPORTED_VERSION,
        STORE_UNREADABLE,
        LIMIT_REACHED,
        WRITE_FAILED,
    )
}

/**
 * 已配对设备的持久清单，供 [PairedDeviceRegistry] 使用。
 *
 * 存储从不联网，也不发明身份：它只记住 registry 交给它的东西。读必须永不抛——损坏的文件要降级成空清单，
 * 应用才能启动；而损坏本身必须通过 [loadError] 可见，不能被藏起来。
 *
 * 与 Windows 侧 MediaHub.Sync.IPairedDeviceStore 对应。
 */
interface PairedDeviceStore {
    /** 全部已记住的设备。顺序是存储自己的顺序；registry 会为展示重新排序。 */
    fun getAll(): List<PairedDevice>

    /** 按 [PairedDevice.deviceId] 插入或替换一台设备。 */
    @Throws(PairedDeviceStoreException::class)
    fun save(device: PairedDevice)

    /** 按标识移除一台设备；本来就没记住时返回 false。 */
    @Throws(PairedDeviceStoreException::class)
    fun forget(deviceId: String): Boolean

    /** 上次加载降级的原因；读得干净时为 null。 */
    val loadError: String?
}

/**
 * 存储写不进去时抛出的异常。
 *
 * 继承 [java.io.IOException]，与 Windows 侧「写入失败一律是 IOException / UnauthorizedAccessException」等价：
 * 上层只要捕获 IOException 就能把「什么都没写进去」与「写成功了」区分开。
 */
class PairedDeviceStoreException(
    val reason: String,
    message: String? = null,
    cause: Throwable? = null,
) : java.io.IOException(message ?: reason, cause)

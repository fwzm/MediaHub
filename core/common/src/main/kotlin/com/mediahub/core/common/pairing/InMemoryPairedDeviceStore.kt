package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.WireText

/**
 * 只活在内存里的 [PairedDeviceStore]，供测试与「还没有档案的设备」使用。
 *
 * 它刻意与文件实现遵守同一套规则：覆盖写保留原位置、完全相同的写入不计数、空标识被拒绝而不是存成空键。
 * 对应 Windows 侧 MediaHub.Sync.InMemoryPairedDeviceStore。
 */
class InMemoryPairedDeviceStore(
    /** 让测试模拟「读不出来」的存储；值原样透过 [loadError] 暴露。 */
    override var loadError: String? = null,
    /** 可选上限，对应文件实现里的兜底；0 表示不限。 */
    val capacity: Int = 0,
) : PairedDeviceStore {

    private val gate = Any()
    private val devices = mutableListOf<PairedDevice>()

    /** 真正改变了清单的写入次数。 */
    var writeCount: Int = 0
        private set

    /** 当前记住的标识，按存储顺序。 */
    val knownDeviceIds: List<String>
        get() = synchronized(gate) { devices.map { device -> device.deviceId } }

    override fun getAll(): List<PairedDevice> = synchronized(gate) { devices.toList() }

    override fun save(device: PairedDevice) {
        require(!WireText.isBlank(device.deviceId)) { "设备标识不能为空。" }
        synchronized(gate) {
            val index = indexOf(device.deviceId)
            if (index >= 0) {
                if (devices[index] == device) {
                    return
                }
                devices[index] = device
                writeCount++
                return
            }
            if (capacity > 0 && devices.size >= capacity) {
                throw PairedDeviceStoreException(PairedDeviceStoreReasons.LIMIT_REACHED)
            }
            devices.add(device)
            writeCount++
        }
    }

    override fun forget(deviceId: String): Boolean {
        val key = WireText.trim(deviceId)
        if (key.isEmpty()) {
            return false
        }
        synchronized(gate) {
            val index = indexOf(key)
            if (index < 0) {
                return false
            }
            devices.removeAt(index)
            writeCount++
            return true
        }
    }

    private fun indexOf(deviceId: String): Int {
        val key = WireText.trim(deviceId)
        for (index in devices.indices) {
            if (WireText.equalsAsciiIgnoreCase(devices[index].deviceId, key)) {
                return index
            }
        }
        return -1
    }
}

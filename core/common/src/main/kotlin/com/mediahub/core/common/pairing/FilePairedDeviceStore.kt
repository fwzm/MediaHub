package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.WireText
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 配对设备清单的文件存储。
 *
 * 目录由调用方注入（Android 侧是 filesDir 下的子目录），本类不依赖 android.content.Context，
 * 所以核心契约可以在 JVM 单测里直接跑。磁盘格式与 Windows MediaHub.Sync.FilePairedDeviceStore 逐字节相同，
 * 两端读写同一个 paired-devices.json：`version: 1` + `devices` 数组、CRLF、2 空格缩进、缺省不写尾随换行。
 *
 * 读永不抛：文档读不懂就隔离（改成 paired-devices.corrupt-<时间戳>.json）并降级为空清单，
 * 原因留在 [loadError]；此时 [save] / [forget] 会抛 [PairedDeviceStoreException]（原因沿用 loadError）。
 */
class FilePairedDeviceStore @JvmOverloads constructor(
    directory: File,
    val capacity: Int = DEFAULT_CAPACITY,
) : PairedDeviceStore {

    private val gate = Any()
    private val devices = mutableListOf<PairedDevice>()
    private var loaded = false
    private var writable = true

    /** 最近一次加载看到的问题（没有则为 null）。只读快照，供界面解释「为什么清单是空的」。 */
    override var loadError: String? = null
        private set

    /** 被隔离文件的路径；没有隔离过则为 null。 */
    var lastQuarantinedPath: String? = null
        private set

    /** 真正落盘的次数（内容相同的保存不计数）。 */
    var writeCount: Int = 0
        private set

    val directoryPath: String = directory.absolutePath

    val filePath: String = File(directory, FILE_NAME).absolutePath

    val backupPath: String
        get() = filePath + ".bak"

    override fun getAll(): List<PairedDevice> = synchronized(gate) {
        ensureLoadedLocked()
        // 排序与 Windows 一致：先 ASCII 大小写不敏感、再逐码元（不能用 String.CASE_INSENSITIVE_ORDER，
        // Java 会把 U+017F/U+212A 折成 ASCII）。
        devices.sortedWith { left, right ->
            val byName = WireText.compareAsciiIgnoreCase(left.deviceId, right.deviceId)
            if (byName != 0) byName else left.deviceId.compareTo(right.deviceId)
        }
    }

    override fun save(device: PairedDevice) = synchronized(gate) {
        require(!WireText.isBlank(device.deviceId)) { "设备标识不能为空。" }
        ensureLoadedLocked()
        ensureWritableLocked()
        val index = indexOfLocked(device.deviceId)
        if (index >= 0) {
            val previous = devices[index]
            if (previous == device) {
                return@synchronized
            }
            devices[index] = device
            try {
                persistLocked()
            } catch (exception: IOException) {
                devices[index] = previous
                throw exception
            } catch (exception: RuntimeException) {
                devices[index] = previous
                throw exception
            }
            writeCount += 1
            return@synchronized
        }
        if (capacity > 0 && devices.size >= capacity) {
            throw IOException(PairedDeviceStoreReasons.LIMIT_REACHED)
        }
        devices.add(device)
        try {
            persistLocked()
        } catch (exception: IOException) {
            devices.removeAt(devices.size - 1)
            throw exception
        } catch (exception: RuntimeException) {
            devices.removeAt(devices.size - 1)
            throw exception
        }
        writeCount += 1
    }

    override fun forget(deviceId: String): Boolean = synchronized(gate) {
        val key = WireText.trim(deviceId)
        if (key.isEmpty()) {
            return@synchronized false
        }
        ensureLoadedLocked()
        ensureWritableLocked()
        val index = indexOfLocked(key)
        if (index < 0) {
            return@synchronized false
        }
        val removed = devices.removeAt(index)
        try {
            persistLocked()
        } catch (exception: IOException) {
            devices.add(index, removed)
            throw exception
        } catch (exception: RuntimeException) {
            devices.add(index, removed)
            throw exception
        }
        writeCount += 1
        true
    }

    private fun ensureLoadedLocked() {
        if (loaded) {
            return
        }
        loaded = true
        loadLocked()
    }

    private fun ensureWritableLocked() {
        if (!writable) {
            throw IOException(loadError ?: PairedDeviceStoreReasons.STORE_UNREADABLE)
        }
    }

    private fun loadLocked() {
        val file = File(filePath)
        if (!file.exists()) {
            return
        }
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (exception: IOException) {
            markUnreadable()
            return
        } catch (exception: SecurityException) {
            markUnreadable()
            return
        }
        when (val decoded = PairedDeviceDocument.decode(text)) {
            is PairedDeviceDocument.Decoded.Loaded -> devices.addAll(decoded.devices)
            is PairedDeviceDocument.Decoded.Rejected -> quarantineLocked(decoded.reason)
        }
    }

    private fun markUnreadable() {
        loadError = PairedDeviceStoreReasons.STORE_UNREADABLE
        writable = false
        devices.clear()
    }

    /**
     * 把读不懂的文件原样挪到 paired-devices.corrupt-<yyyyMMddHHmmss>.json，然后从空清单继续写。
     * 挪不动就退回只读（writable=false），绝不覆盖原文件。
     */
    private fun quarantineLocked(reason: String) {
        loadError = reason
        writable = false
        devices.clear()
        val source = File(filePath)
        if (!source.exists()) {
            writable = true
            return
        }
        val stamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss", java.util.Locale.ROOT))
        var target = File(source.parentFile, CORRUPT_PREFIX + stamp + ".json")
        var attempt = 1
        while (target.exists() && attempt <= 100) {
            target = File(source.parentFile, CORRUPT_PREFIX + stamp + "-" + attempt + ".json")
            attempt += 1
        }
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (exception: IOException) {
            return
        } catch (exception: SecurityException) {
            return
        }
        lastQuarantinedPath = target.absolutePath
        writable = true
    }

    private fun persistLocked() {
        val directory = File(directoryPath)
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw IOException(PairedDeviceStoreReasons.WRITE_FAILED)
        }
        val text = PairedDeviceDocument.render(devices)
        val temporary = File(filePath + ".tmp")
        try {
            temporary.writeText(text, Charsets.UTF_8)
            val target = File(filePath)
            if (target.exists()) {
                Files.copy(target.toPath(), File(backupPath).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun indexOfLocked(deviceId: String): Int {
        val key = WireText.trim(deviceId)
        for (index in devices.indices) {
            if (WireText.equalsAsciiIgnoreCase(devices[index].deviceId, key)) {
                return index
            }
        }
        return -1
    }

    companion object {
        /** 清单文件名（与 Windows 相同）。 */
        const val FILE_NAME: String = "paired-devices.json"

        /** 隔离文件前缀（与 Windows 相同）。 */
        const val CORRUPT_PREFIX: String = "paired-devices.corrupt-"

        /** 默认容量（与 Windows 相同）。 */
        const val DEFAULT_CAPACITY: Int = 32
    }
}

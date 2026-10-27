package com.mediahub.core.common.sync

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 文件版基线存储：一份文档装下所有对端的基线，按对端 deviceId 分键（对齐 Windows FileSourceSyncBaselineStore）。
 *
 * 写是原子的——先在同目录写临时文件并刷盘，再换进正式名——所以进程在保存中途被杀，留下的要么是旧文档、
 * 要么是新文档，绝不会是各半。上一版另存为 sync-baseline.json.bak：基线重建不贵但也不免费，
 * 坏文档的恢复路径不该是「全部重同步」。
 *
 * 读不懂的文档（解析失败、更新版本写出）绝不删除、绝不原地覆盖：改名成
 * sync-baseline.corrupt-yyyyMMddHHmmss.json，本存储随后表现得像什么都不知道。那会退化成全量快照——
 * 吵一点，但对端会把记录重发一遍，而不是用户丢掉它们。只有因 I/O 原因读不出来（文件被锁）时才拒绝写，
 * 因为那时字节还完好，覆盖掉就销毁了唯一副本。
 */
class FileSourceSyncBaselineStore(directory: File) : ISourceSyncBaselineStore {

    private val gate = Any()
    private val peers = LinkedHashMap<String, SourceSyncBaseline>()
    private var loaded = false
    private var writable = true

    /** 最近一次加载为什么没有拿到可用数据：编解码器原因码，或 [SourceSyncBaselineStoreReasons.STORE_UNREADABLE]；文档可读（或不存在）时为 null。 */
    var loadError: String? = null
        private set

    /** 坏文档被移到哪里（隔离成功时）。 */
    var lastQuarantinedPath: String? = null
        private set

    /** 真正重写文档的保存次数。 */
    var writeCount: Int = 0
        private set

    val directoryPath: String = directory.absolutePath

    val filePath: String = File(directory, FILE_NAME).absolutePath

    val backupPath: String
        get() = filePath + ".bak"

    override fun load(peerDeviceId: String): SourceSyncBaseline {
        if (WireText.isBlank(peerDeviceId)) {
            return SourceSyncBaseline.EMPTY
        }
        synchronized(gate) {
            ensureLoadedLocked()
            val key = WireText.trim(peerDeviceId)
            return peers.entries.firstOrNull { WireText.equalsAsciiIgnoreCase(it.key, key) }?.value
                ?: SourceSyncBaseline.EMPTY
        }
    }

    override fun save(peerDeviceId: String, baseline: SourceSyncBaseline): SourceSyncBaselineSaveResult {
        require(!WireText.isBlank(peerDeviceId)) { "A peer device id is required." }
        synchronized(gate) {
            ensureLoadedLocked()
            ensureWritableLocked()
            val key = WireText.trim(peerDeviceId)
            val entry = peers.entries.firstOrNull { WireText.equalsAsciiIgnoreCase(it.key, key) }
            val existing = entry?.value
            val merge = SourceSyncBaselineMerge.merge(existing, baseline)
            if (!merge.changed) {
                return if (merge.rejectedSyncSourceIds.isEmpty()) {
                    SourceSyncBaselineSaveResult.NO_CHANGE
                } else {
                    SourceSyncBaselineSaveResult(
                        unchanged = true,
                        rejectedSyncSourceIds = merge.rejectedSyncSourceIds,
                        reason = merge.reason,
                    )
                }
            }
            val storedKey = entry?.key ?: key
            peers[storedKey] = merge.merged
            try {
                persistLocked()
            } catch (exception: Throwable) {
                if (entry != null) peers[storedKey] = existing!! else peers.remove(storedKey)
                throw exception
            }
            writeCount += 1
            return SourceSyncBaselineSaveResult.written(merge.rejectedSyncSourceIds, merge.reason)
        }
    }

    /** 文档认识的对端 deviceId，按文档写出的同一顺序排序。读取本属性会加载文档。 */
    val peerDeviceIds: List<String>
        get() = synchronized(gate) {
            ensureLoadedLocked()
            peers.keys.sortedWith { left, right ->
                val byName = WireText.compareAsciiIgnoreCase(left, right)
                if (byName != 0) byName else left.compareTo(right)
            }
        }

    /** 忘掉文档里关于某个对端的一切；真的删了并重写返回 true，该对端不存在返回 false。 */
    fun forgetPeer(peerDeviceId: String): Boolean {
        require(!WireText.isBlank(peerDeviceId)) { "A peer device id is required." }
        synchronized(gate) {
            ensureLoadedLocked()
            ensureWritableLocked()
            val key = WireText.trim(peerDeviceId)
            val entry = peers.entries.firstOrNull { WireText.equalsAsciiIgnoreCase(it.key, key) }
                ?: return false
            val existing = entry.value
            peers.remove(entry.key)
            try {
                persistLocked()
            } catch (exception: Throwable) {
                peers[entry.key] = existing
                throw exception
            }
            writeCount += 1
            return true
        }
    }

    /** 忘掉某个对端基线里的一条来源；没有匹配返回 false。删掉最后一条时整个对端节点一起消失。 */
    fun forgetEntry(peerDeviceId: String, syncSourceId: String): Boolean {
        require(!WireText.isBlank(peerDeviceId)) { "A peer device id is required." }
        require(!WireText.isBlank(syncSourceId)) { "A sync source id is required." }
        synchronized(gate) {
            ensureLoadedLocked()
            ensureWritableLocked()
            val key = WireText.trim(peerDeviceId)
            val peerEntry = peers.entries.firstOrNull { WireText.equalsAsciiIgnoreCase(it.key, key) }
                ?: return false
            val existing = peerEntry.value
            val wanted = WireText.trim(syncSourceId)
            val remaining = ArrayList<SourceSyncBaselineEntry>()
            var removed = false
            for (item in existing.entries) {
                if (WireText.equalsAsciiIgnoreCase(WireText.trim(item.syncSourceId), wanted)) {
                    removed = true
                    continue
                }
                remaining.add(item)
            }
            if (!removed) return false
            if (remaining.isEmpty()) {
                peers.remove(peerEntry.key)
            } else {
                peers[peerEntry.key] = existing.copy(entries = remaining)
            }
            try {
                persistLocked()
            } catch (exception: Throwable) {
                peers[peerEntry.key] = existing
                throw exception
            }
            writeCount += 1
            return true
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val file = File(filePath)
        if (!file.exists()) return
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (exception: IOException) {
            markUnreadable()
            return
        } catch (exception: SecurityException) {
            markUnreadable()
            return
        }
        try {
            readDocumentLocked(text)
        } catch (exception: SourceSyncContractException) {
            quarantineLocked(exception.reason)
        }
    }

    private fun markUnreadable() {
        writable = false
        loadError = SourceSyncBaselineStoreReasons.STORE_UNREADABLE
    }

    private fun ensureWritableLocked() {
        if (!writable) {
            throw IOException(
                "The stored sync baseline could not be read (" + (loadError ?: "unknown") + "); refusing to overwrite it.",
            )
        }
    }

    private fun readDocumentLocked(text: String) {
        if (WireText.isBlank(text)) {
            throw SourceSyncContractException(SourceSyncBaselineCodecReasons.MALFORMED_JSON, "The sync baseline document is empty.")
        }
        if (text.length > MAX_DOCUMENT_CHARS) {
            throw SourceSyncContractException(SourceSyncBaselineCodecReasons.TOO_LARGE, "The sync baseline document is too large to read.")
        }
        // 深度先查：与 Windows JsonDocumentOptions.MaxDepth=16 同义，超深文档在任何解析器里都不必再试。
        if (WireJson.exceedsDepth(text, SourceSyncBaselineCodec.MAX_DEPTH)) {
            throw SourceSyncContractException(SourceSyncBaselineCodecReasons.TOO_DEEP, "The sync baseline document is nested too deeply.")
        }
        val root = try {
            kotlinx.serialization.json.Json.parseToJsonElement(text)
        } catch (exception: Exception) {
            throw SourceSyncContractException(SourceSyncBaselineCodecReasons.MALFORMED_JSON, "The sync baseline document is not valid JSON.")
        }
        if (root !is kotlinx.serialization.json.JsonObject) {
            throw SourceSyncContractException(SourceSyncBaselineCodecReasons.MALFORMED_JSON, "The sync baseline document root must be an object.")
        }

        val versionElement = root["version"]
            ?: throw SourceSyncContractException("missing-field:version", "The sync baseline document has no version.")
        val version = numberTextOrNull(versionElement)?.toIntOrNull()
            ?: throw SourceSyncContractException("invalid-field:version", "The document version must be an integer.")
        if (version != CURRENT_DOCUMENT_VERSION) {
            throw SourceSyncContractException(
                "unsupported-schema-version:" + version.toString(),
                "The sync baseline document uses version " + version.toString() + ", which this build does not read.",
            )
        }

        val peersElement = root["peers"]
        if (peersElement == null || peersElement is kotlinx.serialization.json.JsonNull) {
            throw SourceSyncContractException("missing-field:peers", "The sync baseline document has no peers array.")
        }
        if (peersElement !is kotlinx.serialization.json.JsonArray) {
            throw SourceSyncContractException("invalid-field:peers", "The peers field must be an array.")
        }
        if (peersElement.size > MAX_PEERS) {
            throw SourceSyncContractException(SourceSyncBaselineCodecReasons.TOO_LARGE, "The sync baseline document names too many peers.")
        }
        for (element in peersElement) {
            readPeerLocked(element)
        }
    }

    private fun readPeerLocked(element: kotlinx.serialization.json.JsonElement) {
        if (element !is kotlinx.serialization.json.JsonObject) {
            throw SourceSyncContractException("invalid-field:peers", "Every peer entry must be an object.")
        }
        val idElement = element["deviceId"]
        if (idElement == null || idElement !is kotlinx.serialization.json.JsonPrimitive || !idElement.isString ||
            WireText.isBlank(idElement.content)
        ) {
            throw SourceSyncContractException("invalid-field:deviceId", "Every peer entry needs a device id.")
        }
        val baselineElement = element["baseline"]
        if (baselineElement == null || baselineElement !is kotlinx.serialization.json.JsonObject) {
            throw SourceSyncContractException("invalid-field:baseline", "Every peer entry needs a baseline object.")
        }
        val baseline = SourceSyncBaselineCodec.deserialize(baselineElement.toString())
        peers[WireText.trim(idElement.content)] = baseline
    }

    private fun numberTextOrNull(element: kotlinx.serialization.json.JsonElement): String? {
        if (element is kotlinx.serialization.json.JsonNull) return null
        if (element !is kotlinx.serialization.json.JsonPrimitive || element.isString) return null
        return element.content
    }

    /**
     * 把读不懂的文件原样挪到 sync-baseline.corrupt-<yyyyMMddHHmmss>.json，然后从空清单继续写。
     * 挪不动就退回只读（writable=false），绝不覆盖原文件。
     */
    private fun quarantineLocked(reason: String?) {
        peers.clear()
        loadError = reason ?: SourceSyncBaselineCodecReasons.MALFORMED_JSON
        writable = false
        val source = File(filePath)
        if (!source.exists()) {
            writable = true
            return
        }
        val stamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT))
        var target = File(source.parentFile, CORRUPT_PREFIX + stamp + ".json")
        var suffix = 1
        while (target.exists()) {
            target = File(source.parentFile, CORRUPT_PREFIX + stamp + "-" + suffix.toString() + ".json")
            suffix += 1
        }
        try {
            Files.move(source.toPath(), target.toPath())
        } catch (exception: IOException) {
            return
        } catch (exception: SecurityException) {
            return
        }
        lastQuarantinedPath = target.absolutePath
        // 坏字节已经原样存到别的名字下，所以现在可以写一份新的。
        writable = true
    }

    private fun persistLocked() {
        val directory = File(directoryPath)
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw IOException(SourceSyncBaselineStoreReasons.WRITE_FAILED)
        }
        val bytes = serializeDocumentLocked()
        val temporary = File(filePath + ".tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            val target = File(filePath)
            if (target.exists()) {
                Files.copy(target.toPath(), File(backupPath).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun serializeDocumentLocked(): ByteArray {
        val writer = WireJsonTextWriter(true)
        writer.startObject()
        writer.name("version").value(CURRENT_DOCUMENT_VERSION)
        writer.name("peers").startArray()
        val ordered = peers.entries.sortedWith { left, right ->
            val byName = WireText.compareAsciiIgnoreCase(left.key, right.key)
            if (byName != 0) byName else left.key.compareTo(right.key)
        }
        for ((deviceId, baseline) in ordered) {
            writer.startObject()
            writer.name("deviceId").value(deviceId)
            writer.name("baseline")
            SourceSyncBaselineCodec.writeBaseline(writer, baseline)
            writer.endObject()
        }
        writer.endArray()
        writer.endObject()
        return writer.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /** 根目录里的文件名（与 Windows 相同）。 */
        const val FILE_NAME: String = "sync-baseline.json"

        /** 被隔离文档的前缀，后面接时间戳与 .json（与 Windows 相同）。 */
        const val CORRUPT_PREFIX: String = "sync-baseline.corrupt-"

        /** 本构建写出、也是唯一读取的文档版本。 */
        const val CURRENT_DOCUMENT_VERSION: Int = 1

        /** 单文档对端上限，在解析条目表之前就查。 */
        const val MAX_PEERS: Int = 64

        /** 文档长度上限；更大的文件当成损坏，不当成数据。 */
        const val MAX_DOCUMENT_CHARS: Long = 16L * 1024 * 1024
    }
}

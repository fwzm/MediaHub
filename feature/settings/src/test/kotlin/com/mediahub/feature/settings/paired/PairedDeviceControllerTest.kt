package com.mediahub.feature.settings.paired

import com.mediahub.core.common.pairing.FilePairedDeviceStore
import com.mediahub.core.common.pairing.InMemoryPairedDeviceStore
import com.mediahub.core.common.pairing.PairedDevice
import com.mediahub.core.common.pairing.PairedDeviceReasons
import com.mediahub.core.common.pairing.PairedDeviceRegistry
import com.mediahub.core.common.pairing.PairedDeviceStore
import com.mediahub.core.common.pairing.PairedDeviceStoreException
import com.mediahub.core.common.pairing.PairedDeviceStoreReasons
import com.mediahub.core.common.sync.ISourceSyncBaselineStore
import com.mediahub.core.common.sync.InMemorySourceSyncBaselineStore
import com.mediahub.core.common.sync.SourceSyncBaseline
import com.mediahub.core.common.sync.SourceSyncBaselineEntry
import com.mediahub.core.common.sync.SourceSyncBaselineSaveResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * W6-70 界面侧「已配对设备」状态机（纯 JVM，用内存存储与固定时钟）。
 *
 * 判定本身在 core:common 的 PairedDeviceRegistry 里由契约用例钉住；这里只钉这一页自己的四件事：
 * 读不到存储时如实报不可用、读得到时把清单端上来、忘记必须两段（先问再删）、取消本身也是回执。
 */
class PairedDeviceControllerTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val now = 1_792_800_000_000L

    @Test
    fun refreshWithoutAStoragePortReportsUnavailable() = runTest {
        val controller = controller(storeFactory = null, io = StandardTestDispatcher(testScheduler))

        val refreshed = controller.refresh()

        assertFalse(refreshed)
        val state = controller.state.value.list as PairedDeviceListState.Unavailable
        assertEquals(PairedDeviceController.STORAGE_UNAVAILABLE, state.reason)
    }

    @Test
    fun refreshListsTheDevicesTheStoreAlreadyKnows() = runTest {
        val store = InMemoryPairedDeviceStore()
        store.save(device("device-b", "客厅 NAS", FINGERPRINT_A))
        store.save(device("device-a", "书房", FINGERPRINT_B))
        val controller = controller(storeFactory = { store }, io = StandardTestDispatcher(testScheduler))

        assertTrue(controller.refresh())

        val ready = controller.state.value.list as PairedDeviceListState.Ready
        assertEquals(2, ready.view.rows.size)
        assertEquals("已配对 2 台", ready.view.countLabel)
        // 清单顺序由 registry.getAll 排好（deviceId 大小写不敏感升序），控制器不重排。
        assertEquals(listOf("device-a", "device-b"), ready.view.rows.map { row -> row.deviceId })
        assertNull(ready.loadError)
    }

    @Test
    fun refreshCarriesTheStoreLoadErrorIntoThePage() = runTest {
        val store = InMemoryPairedDeviceStore(loadError = PairedDeviceStoreReasons.MALFORMED_JSON)
        val controller = controller(storeFactory = { store }, io = StandardTestDispatcher(testScheduler))

        assertTrue(controller.refresh())

        val ready = controller.state.value.list as PairedDeviceListState.Ready
        assertEquals(PairedDeviceStoreReasons.MALFORMED_JSON, ready.loadError)
        assertTrue(ready.view.isEmpty)
        // 界面把码翻成人话，且不吞掉原始码之外的信息。
        assertTrue(PairedDeviceCopy.warningOf(PairedDeviceStoreReasons.MALFORMED_JSON).contains("隔离"))
    }

    @Test
    fun refreshTurnsAThrowingStoreIntoUnavailable() = runTest {
        val broken = object : PairedDeviceStore {
            override val loadError: String? get() = null
            override fun getAll(): List<PairedDevice> = throw IOException("磁盘掉了")
            override fun save(device: PairedDevice) = throw IOException("磁盘掉了")
            override fun forget(deviceId: String): Boolean = throw IOException("磁盘掉了")
        }
        val controller = controller(storeFactory = { broken }, io = StandardTestDispatcher(testScheduler))

        assertFalse(controller.refresh())

        val state = controller.state.value.list as PairedDeviceListState.Unavailable
        assertEquals(PairedDeviceController.STORAGE_UNAVAILABLE, state.reason)
        assertEquals("磁盘掉了", state.message)
    }

    @Test
    fun askForgetIgnoresBlankAndUnknownIds() = runTest {
        val store = InMemoryPairedDeviceStore()
        store.save(device("device-a", "客厅 NAS", FINGERPRINT_A))
        val controller = controller(storeFactory = { store }, io = StandardTestDispatcher(testScheduler))
        controller.refresh()

        assertFalse(controller.askForget(null))
        assertFalse(controller.askForget("   "))
        assertFalse(controller.askForget("device-zzz"))
        assertNull(controller.state.value.prompt)
    }

    @Test
    fun askForgetBuildsTheSamePromptAsTheWindowsSide() = runTest {
        val store = InMemoryPairedDeviceStore()
        store.save(device("device-a", "客厅 NAS", FINGERPRINT_A))
        val controller = controller(storeFactory = { store }, io = StandardTestDispatcher(testScheduler))
        controller.refresh()

        assertTrue(controller.askForget("device-a"))

        val prompt = controller.state.value.prompt!!
        assertEquals("device-a", prompt.deviceId)
        assertEquals("客厅 NAS", prompt.displayName)
        assertEquals("指纹 …" + FINGERPRINT_A.takeLast(8), prompt.fingerprintLabel)
        assertEquals(
            "将忘记「客厅 NAS」（指纹 …" + FINGERPRINT_A.takeLast(8) + "）。" + PairedDeviceCopy.FORGET_KEEPS_NOTE,
            prompt.text,
        )
        // 只读动作：问一次不等于写一次。
        assertEquals(1, store.writeCount)
    }

    @Test
    fun cancelForgetIsItselfAReceipt() = runTest {
        val store = InMemoryPairedDeviceStore()
        store.save(device("device-a", "客厅 NAS", FINGERPRINT_A))
        val controller = controller(storeFactory = { store }, io = StandardTestDispatcher(testScheduler))
        controller.refresh()
        controller.askForget("device-a")

        val outcome = controller.cancelForget()

        assertFalse(outcome.forgotten)
        assertEquals("取消", outcome.title)
        assertEquals(PairedDeviceCopy.FORGET_CANCELLED, outcome.message)
        assertNull(controller.state.value.prompt)
        assertEquals(1, store.writeCount)
        // 没问就取消也要有回执，不能默默什么都不说。
        val second = controller.cancelForget()
        assertEquals(PairedDeviceCopy.FORGET_NOTHING_PENDING, second.message)
    }

    @Test
    fun confirmForgetWithoutAPromptSaysNothingPending() = runTest {
        val store = InMemoryPairedDeviceStore()
        val controller = controller(storeFactory = { store }, io = StandardTestDispatcher(testScheduler))

        val outcome = controller.confirmForget()

        assertFalse(outcome.forgotten)
        assertEquals(PairedDeviceCopy.FORGET_UNKNOWN_TITLE, outcome.title)
        assertEquals(PairedDeviceCopy.FORGET_NOTHING_PENDING, outcome.message)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun confirmForgetRemovesTheDeviceAndReloadsTheList() = runTest {
        val store = InMemoryPairedDeviceStore()
        store.save(device("device-a", "客厅 NAS", FINGERPRINT_A))
        store.save(device("device-b", "书房", FINGERPRINT_B))
        val controller = controller(storeFactory = { store }, io = StandardTestDispatcher(testScheduler))
        controller.refresh()
        controller.askForget("device-a")

        val outcome = controller.confirmForget()

        assertTrue(outcome.forgotten)
        assertEquals(PairedDeviceCopy.FORGET_DONE_TITLE, outcome.title)
        assertEquals(PairedDeviceReasons.FORGOTTEN, outcome.reason)
        assertNull(controller.state.value.prompt)
        assertFalse(controller.state.value.forgetting)
        val ready = controller.state.value.list as PairedDeviceListState.Ready
        assertEquals(listOf("device-b"), ready.view.rows.map { row -> row.deviceId })
        assertEquals(listOf("device-b"), store.knownDeviceIds)
    }

    @Test
    fun confirmForgetReportsUnknownWhenTheStoreCannotDropIt() = runTest {
        val kept = device("device-a", "客厅 NAS", FINGERPRINT_A)
        val stubborn = object : PairedDeviceStore {
            override val loadError: String? get() = null
            override fun getAll(): List<PairedDevice> = listOf(kept)
            override fun save(device: PairedDevice) = Unit
            override fun forget(deviceId: String): Boolean = false
        }
        val controller = controller(storeFactory = { stubborn }, io = StandardTestDispatcher(testScheduler))
        controller.refresh()
        controller.askForget("device-a")

        val outcome = controller.confirmForget()

        assertFalse(outcome.forgotten)
        assertEquals(PairedDeviceCopy.FORGET_UNKNOWN_TITLE, outcome.title)
        assertEquals(PairedDeviceReasons.UNKNOWN_DEVICE, outcome.reason)
        assertEquals(
            PairedDeviceRegistry.describeReason(PairedDeviceReasons.UNKNOWN_DEVICE),
            outcome.message,
        )
        // 删不掉就如实说删不掉，清单里那一行仍然在。
        val ready = controller.state.value.list as PairedDeviceListState.Ready
        assertEquals(1, ready.view.rows.size)
    }

    @Test
    fun aStoreThatCannotBeWrittenMakesForgetFailInsteadOfThrowing() = runTest {
        val unwritable = object : PairedDeviceStore {
            override val loadError: String? get() = PairedDeviceStoreReasons.STORE_UNREADABLE
            override fun getAll(): List<PairedDevice> = listOf(device("device-a", "客厅 NAS", FINGERPRINT_A))
            override fun save(device: PairedDevice) = Unit
            override fun forget(deviceId: String): Boolean =
                throw PairedDeviceStoreException(PairedDeviceStoreReasons.STORE_UNREADABLE)
        }
        val controller = controller(storeFactory = { unwritable }, io = StandardTestDispatcher(testScheduler))
        controller.refresh()
        controller.askForget("device-a")

        val outcome = controller.confirmForget()

        assertFalse(outcome.forgotten)
        assertEquals(PairedDeviceReasons.UNKNOWN_DEVICE, outcome.reason)
        assertFalse(controller.state.value.forgetting)
    }

    @Test
    fun forgetDropsThePeerBaselineAndSaysSo() = runTest {
        val store = InMemoryPairedDeviceStore()
        store.save(device("device-a", "客厅 NAS", FINGERPRINT_A))
        store.save(device("device-b", "书房", FINGERPRINT_B))
        val baselines = seededBaselines("device-a", "device-b")
        val controller = controller(
            storeFactory = { store },
            io = StandardTestDispatcher(testScheduler),
            baselineStore = baselines,
        )
        controller.refresh()
        controller.askForget("device-a")

        val outcome = controller.confirmForget()

        assertTrue(outcome.forgotten)
        assertTrue(outcome.baselineForgotten)
        assertEquals(PairedDeviceCopy.FORGET_DONE_MESSAGE, outcome.message)
        // 解绑同时是「忘掉知识」：同一 deviceId 重新配对后必须重新按首轮处理，而不是拿旧基线跳过真实变更。
        assertEquals(listOf("device-b"), baselines.knownPeerDeviceIds)
        assertTrue(baselines.load("device-a").isEmpty)
        assertEquals(1, baselines.load("device-b").entries.size)
    }

    @Test
    fun forgetWithoutABaselineStoreSaysTheBaselineStayed() = runTest {
        val store = InMemoryPairedDeviceStore()
        store.save(device("device-a", "客厅 NAS", FINGERPRINT_A))
        val controller = controller(storeFactory = { store }, io = StandardTestDispatcher(testScheduler))
        controller.refresh()
        controller.askForget("device-a")

        val outcome = controller.confirmForget()

        assertTrue(outcome.forgotten)
        assertFalse(outcome.baselineForgotten)
        assertEquals(PairedDeviceCopy.FORGET_DONE_MESSAGE_WITHOUT_BASELINE, outcome.message)
        assertTrue(store.knownDeviceIds.isEmpty())
    }

    @Test
    fun aThrowingBaselineStoreStillForgetsTheDevice() = runTest {
        val store = InMemoryPairedDeviceStore()
        store.save(device("device-a", "客厅 NAS", FINGERPRINT_A))
        var forgetCalls = 0
        val broken = object : ISourceSyncBaselineStore {
            override fun load(peerDeviceId: String): SourceSyncBaseline = SourceSyncBaseline.EMPTY
            override fun save(
                peerDeviceId: String,
                baseline: SourceSyncBaseline,
            ): SourceSyncBaselineSaveResult = throw IOException("基线盘掉了")
            override fun forget(peerDeviceId: String): Boolean {
                forgetCalls += 1
                throw IOException("基线盘掉了")
            }
        }
        val controller = controller(
            storeFactory = { store },
            io = StandardTestDispatcher(testScheduler),
            baselineStore = broken,
        )
        controller.refresh()
        controller.askForget("device-a")

        val outcome = controller.confirmForget()

        // 基线清不掉只损失知识，不把已经删掉的登记撤回；回执要如实说基线可能还在。
        assertTrue(outcome.forgotten)
        assertFalse(outcome.baselineForgotten)
        assertEquals(PairedDeviceCopy.FORGET_DONE_MESSAGE_WITHOUT_BASELINE, outcome.message)
        assertEquals(1, forgetCalls)
        assertTrue(store.knownDeviceIds.isEmpty())
    }

    @Test
    fun anUnknownDeviceNeverTouchesItsBaseline() = runTest {
        val kept = device("device-a", "客厅 NAS", FINGERPRINT_A)
        val stubborn = object : PairedDeviceStore {
            override val loadError: String? get() = null
            override fun getAll(): List<PairedDevice> = listOf(kept)
            override fun save(device: PairedDevice) = Unit
            override fun forget(deviceId: String): Boolean = false
        }
        val baselines = seededBaselines("device-a")
        val controller = controller(
            storeFactory = { stubborn },
            io = StandardTestDispatcher(testScheduler),
            baselineStore = baselines,
        )
        controller.refresh()
        controller.askForget("device-a")
        val writesBefore = baselines.writeCount

        val outcome = controller.confirmForget()

        assertFalse(outcome.forgotten)
        assertFalse(outcome.baselineForgotten)
        assertEquals(PairedDeviceReasons.UNKNOWN_DEVICE, outcome.reason)
        // 先生效的是「登记删掉了没」：删不掉时清基线会留下相反状态（知识没了、设备还配着）。
        assertEquals(writesBefore, baselines.writeCount)
        assertEquals(listOf("device-a"), baselines.knownPeerDeviceIds)
        assertEquals(1, baselines.load("device-a").entries.size)
    }

    @Test
    fun dismissAndClearDropThePreviousConclusions() = runTest {
        val store = InMemoryPairedDeviceStore()
        store.save(device("device-a", "客厅 NAS", FINGERPRINT_A))
        val controller = controller(storeFactory = { store }, io = StandardTestDispatcher(testScheduler))
        controller.refresh()
        controller.askForget("device-a")
        controller.cancelForget()

        controller.dismissOutcome()
        assertNull(controller.state.value.outcome)

        controller.clear()
        assertTrue(controller.state.value.list is PairedDeviceListState.Loading)
        assertNull(controller.state.value.prompt)
        assertNull(controller.state.value.outcome)
    }

    @Test
    fun theProductionLayoutPutsTheFileNextToAFileStoreItCanRead() = runTest {
        // 生产路径：filesDir/paired_devices/paired-devices.json，文件名与 Windows 逐字相同。
        val filesDir = temporary.newFolder("files")
        val store = PairedDeviceStorage.storeIn(filesDir) as FilePairedDeviceStore
        assertEquals(
            File(PairedDeviceStorage.directoryIn(filesDir), FilePairedDeviceStore.FILE_NAME).absolutePath,
            store.filePath,
        )
        store.save(device("device-a", "客厅 NAS", FINGERPRINT_A))
        assertTrue(File(store.filePath).isFile)

        val controller = controller(
            storeFactory = { PairedDeviceStorage.storeIn(filesDir) },
            io = StandardTestDispatcher(testScheduler),
        )
        assertTrue(controller.refresh())
        val ready = controller.state.value.list as PairedDeviceListState.Ready
        assertEquals(1, ready.view.rows.size)

        controller.askForget("device-a")
        assertTrue(controller.confirmForget().forgotten)
        // 解绑是写盘：重开一个存储读回来，登记真的没了。
        assertTrue(FilePairedDeviceStore(PairedDeviceStorage.directoryIn(filesDir)).getAll().isEmpty())
    }

    private fun controller(
        storeFactory: (() -> PairedDeviceStore)?,
        io: kotlinx.coroutines.CoroutineDispatcher,
        baselineStore: ISourceSyncBaselineStore? = null,
    ): PairedDeviceController = PairedDeviceController(
        storeFactory = storeFactory,
        now = { now },
        io = io,
        baselineStore = baselineStore,
    )

    private fun seededBaselines(vararg peers: String): InMemorySourceSyncBaselineStore {
        val store = InMemorySourceSyncBaselineStore()
        peers.forEach { peer ->
            assertTrue(store.save(peer, baseline("sync-1", 3)).saved)
        }
        return store
    }

    private fun baseline(syncSourceId: String, revision: Long): SourceSyncBaseline = SourceSyncBaseline(
        deviceId = "device-a",
        snapshotId = "snapshot-1",
        entries = listOf(
            SourceSyncBaselineEntry(
                syncSourceId = syncSourceId,
                recordRevision = revision,
                contentHash = "hash",
                fieldHashes = mapOf("displayName" to "h-hash"),
            ),
        ),
    )

    private fun device(deviceId: String, displayName: String, fingerprint: String): PairedDevice =
        PairedDevice.create(
            deviceId = deviceId,
            fingerprint = fingerprint,
            displayName = displayName,
            nowEpochMs = now,
            sourceCount = 2,
        )

    private companion object {
        const val FINGERPRINT_A: String =
            "3f2a9c1d4b6e8f0a2c4e6b8d0f1a3c5e7b9d1f3a5c7e9b0d2f4a6c8e0b1d3f5a"
        const val FINGERPRINT_B: String =
            "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9"
    }
}

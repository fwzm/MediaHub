package com.mediahub.app.backup

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.mediahub.app.MainActivity
import com.mediahub.app.di.AppModule
import com.mediahub.core.common.backup.BackupSerializer
import com.mediahub.core.database.entity.PlaybackProgressEntity
import com.mediahub.core.database.entity.ServerEntity
import com.mediahub.core.database.prefs.UserPreferencesStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import java.util.regex.Pattern

/**
 * R2 (Agent C) - NON-OVERWRITING device subset for the production Settings -> sync-and-backup chain.
 *
 * This SUPERSEDES BackupUserFlowHarmlessTest. The old name was misleading: the old test inserted and
 * deleted rows in the live database without registering that write, and "did not click the confirmed
 * replace button" was used as if it proved the whole path performed no business writes. It does not.
 *
 * DATA CONTRACT - every write this test performs, explicitly registered:
 *   1. INSERT one ServerEntity whose id is agent-c-nonoverwriting-<suffix>. It never reuses an
 *      existing id, so no existing row can be replaced.
 *   2. INSERT one PlaybackProgressEntity owned by that id.
 *   3. Create at most one file in the user-chosen Downloads folder, named with the same suffix.
 * It NEVER writes preferences, NEVER writes credentials/session state, and NEVER restores/overwrites
 * existing servers or progress. It manipulates no existing row.
 *
 * LABEL: PRODUCTION_FLOW_WITH_TEST_DATA. The real BackupViewModel / BackupRepository / serializer /
 * crypto / SAF file chain runs unmodified, but the data set is not an isolated instance: the test
 * inserts its own rows into the live app database. This must not be reported as acceptance of the
 * original user data instance, and no fake exporter is used.
 *
 * PREFLIGHT (read-only, before any page operation): proves there is no pending / corrupt / uncertain
 * restore journal and no orphan snapshot. It deliberately does NOT call recoverInterruptedRestore -
 * that would EXECUTE recovery, not check for it. If the preflight cannot be satisfied the test stops
 * before touching the UI and reports BLOCKED; it never clears the journal or deletes snapshots.
 *
 * Run (physical device is opt-in and named explicitly):
 *   adb -s <serial> shell am instrument -w -r \
 *     -e backupAcceptance isolated -e allowPhysicalDevice <Build.MODEL> \
 *     -e class com.mediahub.app.backup.BackupUserFlowNonOverwritingDeviceTest \
 *     com.mediahub.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class BackupUserFlowNonOverwritingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val appPackage = context.packageName
    private val documentPackages = Pattern.compile("com\\.(android|google\\.android)\\.documentsui")
    private val logger: Logger = object : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }

    @Test
    fun exportCancelSaveWrongPasswordAndUnconfirmedReplaceAreNonOverwriting() = runBlocking {
        assertEquals("isolated", InstrumentationRegistry.getArguments().getString("backupAcceptance"))
        assertEquals("必须在运行参数中显式命名本机型号", Build.MODEL,
            InstrumentationRegistry.getArguments().getString("allowPhysicalDevice"))

        // ---- preflight: read-only, and it must pass BEFORE any page operation ----
        val preflight = preflightState()
        assertTrue(
            "BLOCKED: 恢复日志/快照前置条件不成立，禁止页面操作（不自动清理）：$preflight",
            preflight == "active=false,xmlActive=false,bak=false,orphanSnapshots=0",
        )

        val suffix = System.currentTimeMillis().toString()
        val ownedId = "agent-c-nonoverwriting-$suffix"
        val fileName = "MediaHub-agent-c-nonoverwriting-$suffix.mhb"
        val cancelledName = "MediaHub-agent-c-nonoverwriting-$suffix-cancelled.mhb"
        // A fresh, run-unique secret that exists only in memory. Never a fixed repository password,
        // never logged, never persisted.
        val password = randomPassword()
        val db = AppModule.provideAppDatabase(context)
        val tokens: TokenStore = AppModule.provideTokenStore(AppModule.provideSecretStorage(context, logger))
        val preferences = UserPreferencesStore(context)

        val before = snapshot(db, preferences, tokens)
        assertNull("测试夹具 id 不能与既有行冲突", db.serverDao().getById(ownedId))
        var fileCreated = false
        try {
            db.serverDao().upsert(ServerEntity(ownedId, "Agent C task-only local media", "LOCAL", createdAtEpochMs = 1))
            db.playbackProgressDao().upsert(
                PlaybackProgressEntity(ownedId, "task-only-item", 12000, 60000, true, 2, itemTitle = "Task-only progress"),
            )

            device.executeShellCommand("am start -n " + appPackage + "/.MainActivity")
            assertTrue("MainActivity 必须处于前台（本机拒绝后台启动 Activity，故经 shell 启动）",
                device.wait(Until.hasObject(By.pkg(appPackage)), TIMEOUT))
            requireNode(By.desc("设置")).click()
            clickText("同步与备份")
            requireNode(By.text("导出备份"))

            // 1) Cancel the SAF picker: must create no file and write nothing.
            enterExportPasswords(password)
            clickText("导出备份")
            waitForDocumentsUi()
            selectDownloadsDirectory()
            requireNode(By.clazz("android.widget.EditText")).text = cancelledName
            cancelDocumentsUi()
            assertEquals("取消SAF后未创建文件", "missing", existsInDownloads(cancelledName))

            // 2) Real save with the run-unique password.
            enterExportPasswords(password)
            clickText("导出备份")
            waitForDocumentsUi()
            selectDownloadsDirectory()
            requireNode(By.clazz("android.widget.EditText")).text = fileName
            clickSave()
            assertTrue(device.wait(Until.hasObject(By.pkg(appPackage)), TIMEOUT))
            scrollTo(By.text("备份已导出"))
            fileCreated = true
            // Do not infer the location from the on-screen breadcrumb: verify the real file.
            assertEquals("保存后必须在所选 Downloads 目录找到实际文件", "present", existsInDownloads(fileName))
            val bytes = device.executeShellCommand("cat /sdcard/Download/$fileName").toByteArray(Charsets.UTF_8)
            assertTrue("导出不是空文件", bytes.isNotEmpty())
            val decoded = BackupSerializer.import(bytes, password)
            assertTrue("UI导出文件被自身导入规则接受", decoded is BackupSerializer.ImportResult.Ok)
            decoded as BackupSerializer.ImportResult.Ok
            assertTrue(decoded.payload.servers.any { it.backupId == ownedId })
            assertEquals("备份必须携带真实安装版本",
                context.packageManager.getPackageInfo(appPackage, 0).versionName, decoded.payload.manifest.appVersion)
            clickText("完成")

            // 3) Wrong password and the preview must both write nothing.
            clickText("选择备份文件")
            waitForDocumentsUi()
            selectDownloadsDirectory()
            scrollTo(By.text(fileName)).click()
            assertTrue(device.wait(Until.hasObject(By.pkg(appPackage)), TIMEOUT))
            val wrong = randomPassword()
            try {
                enterRestorePassword(wrong)
                clickText("解密并预览")
                waitForDecryption()
                scrollTo(By.text("重新输入备份密码"))
            } finally { wrong.fill('\u0000') }
            assertUserDataUnchanged(before, snapshot(db, preferences, tokens), ownedId, "错误密码")
            clickText("重新输入备份密码")
            enterRestorePassword(password)
            clickText("解密并预览")
            waitForDecryption()
            scrollTo(By.text("恢复预览"))
            assertUserDataUnchanged(before, snapshot(db, preferences, tokens), ownedId, "预览")

            // 4) The unconfirmed replace action stays disabled and writes nothing.
            scrollTo(By.text("替换所选数据（覆盖已有）"))
            val radios = device.findObjects(By.clazz("android.widget.RadioButton"))
            assertTrue("替换策略radio可见", radios.isNotEmpty())
            radios.last().click()
            val replaceButton = actionForText("替换恢复")
            assertFalse("未确认替换按钮必须禁用", replaceButton.isEnabled)
            replaceButton.click()
            assertFalse("点击禁用按钮不能替用户确认", requireNode(By.clazz("android.widget.CheckBox")).isChecked)
            assertUserDataUnchanged(before, snapshot(db, preferences, tokens), ownedId, "未确认替换")
            // INTENTIONAL STOP: the confirmed replace is destructive and stays NOT_RUN_POLICY.
        } catch (failure: Throwable) {
            runCatching {
                val evidence = File(context.getExternalFilesDir(null), "backup-ui-nonoverwriting-failure.xml")
                device.dumpWindowHierarchy(evidence)
            }
            throw failure
        } finally {
            try {
                db.playbackProgressDao().deleteByServer(ownedId)
                db.serverDao().deleteById(ownedId)
                // Cleanup touches ONLY the exact names this run created; no wildcard deletion.
                val exact = if (fileCreated) listOf(fileName) else listOf(fileName, cancelledName)
                device.executeShellCommand(exact.joinToString(" ") { "rm -f /sdcard/Download/$it" })
                password.fill('\u0000')
            } finally {
                db.close()
                device.pressHome()
            }
        }
    }

    // ---- read-only preflight -------------------------------------------------------------

    private fun preflightState(): String {
        val prefs = context.getSharedPreferences("mediahub_restore_journal", Context.MODE_PRIVATE)
        val active = prefs.getString("active_restore", null)
        val xml = File(context.dataDir, "shared_prefs/mediahub_restore_journal.xml")
        val bak = File(context.dataDir, "shared_prefs/mediahub_restore_journal.xml.bak")
        val xmlActive = xml.takeIf { it.isFile }?.readText()?.contains("name=\"active_restore\"") ?: false
        val snapshots = File(context.noBackupFilesDir, "restore-snapshots")
        val orphans = snapshots.listFiles()?.size ?: 0
        return "active=" + (active != null) + ",xmlActive=" + xmlActive +
            ",bak=" + bak.exists() + ",orphanSnapshots=" + orphans
    }

    // ---- full business + credential state, compared in memory only -----------------------

    private class Snapshot(
        val servers: Map<String, ServerEntity>,
        val progress: Map<String, PlaybackProgressEntity>,
        val preferences: String,
        val credentialKeySets: Map<String, Set<String>>,
        val tokenPresent: Boolean,
    )

    private suspend fun snapshot(db: com.mediahub.core.database.AppDatabase,
                                 prefs: UserPreferencesStore, tokens: TokenStore): Snapshot {
        val secret = context.getSharedPreferences("mediahub_secret_store", Context.MODE_PRIVATE)
        val emby = context.getSharedPreferences("mediahub_emby_sessions", Context.MODE_PRIVATE)
        val jelly = context.getSharedPreferences("mediahub_jellyfin_sessions", Context.MODE_PRIVATE)
        // Key NAMES only: credential values never leave this process and never reach a report.
        return Snapshot(
            servers = db.serverDao().observeAll().first().associateBy { it.id },
            progress = db.playbackProgressDao().getAll().associateBy { it.serverId + "|" + it.itemId },
            preferences = prefs.flow.first().toString(),
            credentialKeySets = mapOf(
                "secret" to secret.all.keys.toSet(),
                "emby" to emby.all.keys.toSet(),
                "jellyfin" to jelly.all.keys.toSet(),
            ),
            tokenPresent = tokens.readTokens("source") != null,
        )
    }

    /**
     * Compares the FULL relevant business and credential state, excluding only the rows this run owns.
     * A difference in user-owned data is reported as INCONCLUSIVE (a concurrent writer may be present);
     * it is NEVER papered over by writing an older snapshot back.
     */
    private fun assertUserDataUnchanged(before: Snapshot, after: Snapshot, ownedId: String, stage: String) {
        val bServers = before.servers.filterKeys { it != ownedId }
        val aServers = after.servers.filterKeys { it != ownedId }
        val bProgress = before.progress.filterKeys { !it.startsWith(ownedId + "|") }
        val aProgress = after.progress.filterKeys { !it.startsWith(ownedId + "|") }
        val parts = mutableListOf<String>()
        if (bServers != aServers) parts += "servers"
        if (bProgress != aProgress) parts += "progress"
        if (before.preferences != after.preferences) parts += "preferences"
        if (before.credentialKeySets != after.credentialKeySets) parts += "credentialKeys"
        if (before.tokenPresent != after.tokenPresent) parts += "tokenPresence"
        assertTrue(
            "INCONCLUSIVE[$stage]: 本机业务/凭据状态发生变化（" + parts.joinToString("+") +
                "）；可能存在并发写入，本测试不会用旧快照覆盖回去以制造\"状态相同\"",
            parts.isEmpty(),
        )
    }

    private fun randomPassword(): CharArray {
        val alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return CharArray(32) { alphabet[random.nextInt(alphabet.length)] }
    }

    // ---- UI helpers ----------------------------------------------------------------------

    private fun requireNode(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), TIMEOUT)) { "未找到UI节点：$selector" }

    private fun clickText(text: String) {
        scrollTo(By.text(text))
        device.findObjects(By.text(text)).last().click()
    }

    private fun actionForText(text: String): UiObject2 {
        var node = scrollTo(By.text(text))
        repeat(8) {
            if (node.isClickable) return node
            node = checkNotNull(node.parent) { "没有点击语义的按钮：$text" }
        }
        error("未找到受限深度内的按钮：$text")
    }

    private fun scrollTo(selector: BySelector): UiObject2 {
        device.wait(Until.findObject(selector), 1000)?.let { return it }
        repeat(8) {
            val scroll = device.findObject(By.scrollable(true)) ?: return@repeat
            scroll.scroll(Direction.DOWN, 0.7f)
            device.findObject(selector)?.let { return it }
        }
        repeat(8) {
            val scroll = device.findObject(By.scrollable(true)) ?: return@repeat
            scroll.scroll(Direction.UP, 0.7f)
            device.findObject(selector)?.let { return it }
        }
        return requireNode(selector)
    }

    private fun enterExportPasswords(password: CharArray) {
        scrollTo(By.text("备份密码"))
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        assertTrue("两个导出密码输入", fields.size >= 2)
        fields[0].click(); fields[0].text = String(password)
        val confirmation = device.findObjects(By.clazz("android.widget.EditText"))[1]
        confirmation.click(); confirmation.text = String(password)
        device.pressBack()
    }

    private fun enterRestorePassword(password: CharArray) {
        scrollTo(By.text("解密并预览"))
        val field = device.findObjects(By.clazz("android.widget.EditText")).last()
        field.click(); field.text = String(password)
        device.pressBack()
    }

    private fun waitForDocumentsUi() {
        assertTrue("必须实际启动系统SAF", device.wait(Until.hasObject(By.pkg(documentPackages)), CRYPTO_TIMEOUT))
    }

    private fun waitForDecryption() {
        val working = By.text("正在解密并验证…")
        if (device.wait(Until.hasObject(working), 1000)) {
            assertTrue("解密必须在有限观察窗口完成", device.wait(Until.gone(working), CRYPTO_TIMEOUT))
        }
    }

    /**
     * OEM-aware directory selection. The adapter keys off what is actually on screen: the picker
     * header/breadcrumb first, then the roots drawer, then any list container that exposes a
     * Downloads entry. It never requires the AOSP-only roots_list id and never blind-taps coordinates.
     */
    private fun selectDownloadsDirectory() {
        val downloadsText = Pattern.compile("(?i)^(downloads|下载内容|下载)$")
        device.waitForIdle(TIMEOUT)
        if (headerIsDownloads()) return
        device.findObject(By.desc(Pattern.compile("(?i)show roots|显示根目录|显示位置")))?.let {
            it.click()
            device.waitForIdle(TIMEOUT)
        }
        for (container in listOf("roots_list", "dir_list")) {
            val scoped = By.text(downloadsText).hasAncestor(By.res(Pattern.compile(".*:id/" + container)))
            device.wait(Until.findObject(scoped), 2_000)?.let { node ->
                node.click(); device.waitForIdle(TIMEOUT); return
            }
        }
        val plain = device.findObjects(By.text(downloadsText))
        assertTrue("无法确定 Downloads 目录（不盲猜坐标）：header=" + headerText(), plain.isNotEmpty())
        plain.first().click()
        device.waitForIdle(TIMEOUT)
    }

    private fun headerText(): String =
        device.findObject(By.res(Pattern.compile(".*:id/header_title")))?.text
            ?: device.findObject(By.res(Pattern.compile(".*:id/breadcrumb_text")))?.text.orEmpty()

    private fun headerIsDownloads(): Boolean {
        val header = headerText()
        return header.contains("下载") || header.contains("Download", ignoreCase = true)
    }

    private fun clickSave() {
        requireNode(By.text(Pattern.compile("(?i)^(save|保存|保存文档)$"))).click()
        device.waitForIdle(TIMEOUT)
    }

    private fun cancelDocumentsUi() {
        repeat(3) {
            if (device.hasObject(By.pkg(appPackage))) return
            device.pressBack()
            if (device.wait(Until.hasObject(By.pkg(appPackage)), 1000)) return
        }
        assertTrue(device.wait(Until.hasObject(By.pkg(appPackage)), TIMEOUT))
    }

    private fun existsInDownloads(fileName: String): String {
        assertEquals("Downloads目录必须存在", "/sdcard/Download",
            device.executeShellCommand("ls -d /sdcard/Download").trim())
        val entries = device.executeShellCommand("ls -1 /sdcard/Download")
            .lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
        assertFalse("Downloads目录列表不能有读取错误", entries.any { it.startsWith("ls:") })
        return if (fileName in entries) "present" else "missing"
    }

    private companion object {
        const val TIMEOUT = 15_000L
        const val CRYPTO_TIMEOUT = 60_000L
    }
}

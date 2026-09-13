package com.mediahub.app.backup

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.mediahub.app.di.AppModule
import com.mediahub.core.common.backup.BackupSerializer
import com.mediahub.core.database.entity.PlaybackProgressEntity
import com.mediahub.core.database.entity.ServerEntity
import com.mediahub.core.database.prefs.UserPreferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * Agent C, C3 NON-DESTRUCTIVE subset (run with the user is explicit consent).
 *
 * Deliberately stops BEFORE the confirmed REPLACE_SELECTED restore: it never overwrites the
 * existing servers, playback progress or preferences of the installation under test, never
 * kills the process and never injects a corrupt journal. Verified behaviour is limited to:
 *   - SAF cancel leaves no file;
 *   - a real save produces a non-empty, self-decryptable file carrying the real appVersion;
 *   - a wrong password performs zero business writes;
 *   - the preview performs zero business writes;
 *   - the unconfirmed replace action stays disabled and writes nothing.
 *
 * Unlike BackupUserFlowTest this does NOT require an emulator, because the device is an
 * explicitly named physical device; the opt-in is -e allowPhysicalDevice <Build.MODEL>.
 * Existing preferences are read-only here: the test never writes them.
 *
 * Run:
 *   adb -s <serial> shell am instrument -w -r -e backupAcceptance isolated \
 *     -e allowPhysicalDevice <MODEL> \
 *     -e class com.mediahub.app.backup.BackupUserFlowHarmlessTest \
 *     com.mediahub.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class BackupUserFlowHarmlessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val appPackage = context.packageName
    private val documentPackages = Pattern.compile("com\\.(android|google\\.android)\\.documentsui")

    @Test
    fun settingsSafExportCancelSaveWrongPasswordPreviewAndUnconfirmedReplaceGate() = runBlocking {
        assertEquals("isolated", InstrumentationRegistry.getArguments().getString("backupAcceptance"))
        val allowPhysical = InstrumentationRegistry.getArguments().getString("allowPhysicalDevice")
        assertEquals("必须在运行参数中显式命名本机型号", Build.MODEL, allowPhysical)
        // Destructive probes stay explicitly out of scope for this class.
        val suffix = System.currentTimeMillis().toString()
        val sourceId = "agent-c-ui-$suffix"
        val fileName = "MediaHub-agent-c-ui-$suffix.mhb"
        val cancelledName = "MediaHub-agent-c-ui-$suffix-cancelled.mhb"
        val password = "AgentC-harmless-acceptance-password"
        val db = AppModule.provideAppDatabase(context)
        val preferences = UserPreferencesStore(context)
        val originalPreferences = preferences.flow.first()
        val source = ServerEntity(sourceId, "Agent C task-only local media", "LOCAL", createdAtEpochMs = 1)
        val progress = PlaybackProgressEntity(sourceId, "task-only-item", 12000, 60000, true, 2, itemTitle = "Task-only progress")
        try {
            assertEquals(null, db.serverDao().getById(sourceId))
            db.serverDao().upsert(source)
            db.playbackProgressDao().upsert(progress)
            // NOTE: preferences are never modified by this test.

            // This OEM build aborts background activity starts issued by the app uid
            // ("Abort background activity starts from <uid>"). UiDevice.executeShellCommand runs
            // through UiAutomation as the shell uid, which is exempt, so the activity is started here.
            // A pre-launch before `am instrument` does not survive: instrumentation restarts the process.
            device.executeShellCommand("am start -n " + appPackage + "/.MainActivity")
            assertTrue(
                "MainActivity 必须处于前台（本机拒绝后台启动 Activity，故经 shell 启动）",
                device.wait(Until.hasObject(By.pkg(appPackage)), TIMEOUT),
            )
            requireNode(By.desc("设置")).click()
            clickText("同步与备份")
            requireNode(By.text("导出备份"))

            enterExportPasswords(password)
            clickText("导出备份")
            waitForDocumentsUi()
            chooseDownloads()
            requireNode(By.clazz("android.widget.EditText")).text = cancelledName
            cancelDocumentsUi()
            assertEquals("取消SAF后未创建文件", "missing", existsInDownloads(cancelledName))
            assertEquals(source, db.serverDao().getById(sourceId))
            assertEquals(progress, db.playbackProgressDao().get(sourceId, progress.itemId))

            enterExportPasswords(password)
            clickText("导出备份")
            waitForDocumentsUi()
            chooseDownloads()
            requireNode(By.clazz("android.widget.EditText")).text = fileName
            requireNode(By.text(Pattern.compile("(?i)^(save|保存)$"))).click()
            assertTrue(device.wait(Until.hasObject(By.pkg(appPackage)), TIMEOUT))
            scrollTo(By.text("备份已导出"))
            assertEquals("保存后必须有实际文件", "present", existsInDownloads(fileName))
            val bytes = device.executeShellCommand("cat /sdcard/Download/$fileName").toByteArray(Charsets.UTF_8)
            assertTrue("导出不是空文件", bytes.isNotEmpty())
            val decoded = BackupSerializer.import(bytes, password.toCharArray())
            assertTrue("UI导出文件被自身导入规则接受", decoded is BackupSerializer.ImportResult.Ok)
            decoded as BackupSerializer.ImportResult.Ok
            assertTrue(decoded.payload.servers.any { it.backupId == sourceId && it.endpoints.isEmpty() })
            assertTrue(decoded.payload.progress.any { it.serverBackupId == sourceId && it.positionMs == progress.positionMs })
            assertEquals(context.packageManager.getPackageInfo(appPackage, 0).versionName, decoded.payload.manifest.appVersion)
            clickText("完成")

            val beforePreviewPreferences = preferences.flow.first()
            clickText("选择备份文件")
            waitForDocumentsUi()
            chooseDownloads()
            scrollTo(By.text(fileName)).click()
            assertTrue(device.wait(Until.hasObject(By.pkg(appPackage)), TIMEOUT))
            enterRestorePassword("incorrect-task-only-password")
            clickText("解密并预览")
            waitForDecryption()
            scrollTo(By.text("重新输入备份密码"))
            assertEquals("认证失败零业务写入", source, db.serverDao().getById(sourceId))
            assertEquals(progress, db.playbackProgressDao().get(sourceId, progress.itemId))
            assertEquals("认证失败不得改写偏好", beforePreviewPreferences, preferences.flow.first())
            clickText("重新输入备份密码")
            enterRestorePassword(password)
            clickText("解密并预览")
            waitForDecryption()
            scrollTo(By.text("恢复预览"))
            assertEquals("预览零业务写入", source, db.serverDao().getById(sourceId))
            assertEquals(progress, db.playbackProgressDao().get(sourceId, progress.itemId))
            assertEquals("预览不得改写偏好", beforePreviewPreferences, preferences.flow.first())

            // Gate check only: switch to the replace strategy and confirm it cannot run unconfirmed.
            scrollTo(By.text("替换所选数据（覆盖已有）"))
            val radios = device.findObjects(By.clazz("android.widget.RadioButton"))
            assertTrue("替换策略radio可见", radios.isNotEmpty())
            radios.last().click()
            val replaceButton = actionForText("替换恢复")
            assertFalse("未确认替换按钮必须禁用", replaceButton.isEnabled)
            replaceButton.click()
            assertFalse("点击禁用按钮不能替用户确认", requireNode(By.clazz("android.widget.CheckBox")).isChecked)
            assertEquals("未确认替换零业务写入", source, db.serverDao().getById(sourceId))
            assertEquals(progress, db.playbackProgressDao().get(sourceId, progress.itemId))
            assertEquals("未确认替换不得改写偏好", beforePreviewPreferences, preferences.flow.first())
            // INTENTIONAL STOP: the confirmed replace is destructive and is out of scope here.
        } catch (failure: Throwable) {
            runCatching {
                val evidence = File(context.getExternalFilesDir(null), "backup-ui-harmless-failure.xml")
                device.dumpWindowHierarchy(evidence)
            }
            throw failure
        } finally {
            try {
                db.playbackProgressDao().deleteByServer(sourceId)
                db.serverDao().deleteById(sourceId)
                assertEquals("本测试不得留下偏好改动", originalPreferences, preferences.flow.first())
            } finally {
                db.close()
                device.executeShellCommand("rm -f /sdcard/Download/$fileName /sdcard/Download/$cancelledName")
                device.pressHome()
            }
        }
    }

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

    private fun enterExportPasswords(password: String) {
        scrollTo(By.text("备份密码"))
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        assertTrue("两个导出密码输入", fields.size >= 2)
        fields[0].click()
        fields[0].text = password
        val confirmation = device.findObjects(By.clazz("android.widget.EditText"))[1]
        confirmation.click()
        confirmation.text = password
        device.pressBack()
    }

    private fun enterRestorePassword(password: String) {
        scrollTo(By.text("解密并预览"))
        val field = device.findObjects(By.clazz("android.widget.EditText")).last()
        field.click()
        field.text = password
        device.pressBack()
    }

    private fun waitForDocumentsUi() {
        val started = android.os.SystemClock.elapsedRealtime()
        var appeared = device.wait(Until.hasObject(By.pkg(documentPackages)), TIMEOUT)
        if (!appeared) {
            val stacks = Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, frames) ->
                "${thread.name} ${thread.state}\n${frames.joinToString("\n") { "  at $it" }}"
            }
            File(context.getExternalFilesDir(null), "backup-timing-stacks.txt").writeText(stacks)
            val pulse = java.util.concurrent.CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post { pulse.countDown() }
            val responsive = pulse.await(1, java.util.concurrent.TimeUnit.SECONDS)
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("stream", "\nKDF observation at 15000ms; mainResponsive=$responsive\n")
            })
            assertTrue("后台加密期间主线程必须仍可响应", responsive)
            appeared = device.wait(Until.hasObject(By.pkg(documentPackages)),
                (CRYPTO_TIMEOUT - (android.os.SystemClock.elapsedRealtime() - started)).coerceAtLeast(1))
        }
        instrumentation.sendStatus(2, android.os.Bundle().apply {
            putString("stream", "\nSAF appeared=$appeared elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}\n")
        })
        assertTrue("必须实际启动系统SAF", appeared)
    }

    private fun waitForDecryption() {
        val working = By.text("正在解密并验证…")
        if (device.wait(Until.hasObject(working), 1000)) {
            assertTrue("解密必须在有限观察窗口完成", device.wait(Until.gone(working), CRYPTO_TIMEOUT))
        }
    }

    private fun chooseDownloads() {
        val roots = By.res(Pattern.compile(".*:id/roots_list"))
        device.waitForIdle(TIMEOUT)
        if (!device.hasObject(roots)) {
            requireNode(By.desc(Pattern.compile("(?i)show roots|显示根目录|显示位置"))).click()
        }
        assertTrue("SAF位置抽屉已打开", device.wait(Until.hasObject(roots), TIMEOUT))
        device.waitForIdle(TIMEOUT)
        val downloads = By.text(Pattern.compile("(?i)^(downloads|下载)$")).hasAncestor(roots)
        requireNode(downloads).click()
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

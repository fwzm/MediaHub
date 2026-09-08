package com.mediahub.app.backup

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
 * 正式入口 + 真实 DocumentsUI；仅允许显式授权的隔离模拟器。
 * 运行参数：-e backupAcceptance isolated。只清理本测试创建的行/文件，恢复原偏好。
 * 断言包括取消无文件、磁盘导出可解密、密码失败/预览零业务写入和替换确认门控。
 */
@RunWith(AndroidJUnit4::class)
class BackupUserFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val appPackage = context.packageName
    private val documentPackages = Pattern.compile("com\\.(android|google\\.android)\\.documentsui")

    @Test
    fun settingsSafExportCancelSaveImportPasswordRetryAndConfirmedReplace() = runBlocking {
        assertEquals("isolated", InstrumentationRegistry.getArguments().getString("backupAcceptance"))
        assertTrue("此测试只允许隔离模拟器", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val suffix = System.currentTimeMillis().toString()
        val sourceId = "agent-b-ui-$suffix"
        val fileName = "MediaHub-agent-b-ui-$suffix.mhb"
        val cancelledName = "MediaHub-agent-b-ui-$suffix-cancelled.mhb"
        val password = "AgentB-acceptance-password"
        val db = AppModule.provideAppDatabase(context)
        val preferences = UserPreferencesStore(context)
        val originalPreferences = preferences.flow.first()
        val source = ServerEntity(sourceId, "Agent B task-only local media", "LOCAL", createdAtEpochMs = 1)
        val progress = PlaybackProgressEntity(sourceId, "task-only-item", 12000, 60000, true, 2, itemTitle = "Task-only progress")
        try {
            assertEquals(null, db.serverDao().getById(sourceId))
            db.serverDao().upsert(source)
            db.playbackProgressDao().upsert(progress)
            preferences.update { it.copy(defaultPlaybackSpeed = 1.25f) }
            val exportedPreferences = preferences.flow.first()

            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
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

            // 改变本地数据后再预览，验证无确认阶段保留这些值；确认后恢复导出值。
            val changedSource = source.copy(name = "Agent B local change before preview")
            db.serverDao().upsert(changedSource)
            preferences.update { it.copy(defaultPlaybackSpeed = 1.5f) }
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
            assertEquals("认证失败零业务写入", changedSource, db.serverDao().getById(sourceId))
            assertEquals(progress, db.playbackProgressDao().get(sourceId, progress.itemId))
            assertEquals(beforePreviewPreferences, preferences.flow.first())
            clickText("重新输入备份密码")
            enterRestorePassword(password)
            clickText("解密并预览")
            waitForDecryption()
            scrollTo(By.text("恢复预览"))
            assertEquals("预览零业务写入", changedSource, db.serverDao().getById(sourceId))
            assertEquals(progress, db.playbackProgressDao().get(sourceId, progress.itemId))
            assertEquals(beforePreviewPreferences, preferences.flow.first())

            // RadioButton 与文字不是同一个点击目标，点击实际 radio 控件。
            scrollTo(By.text("替换所选数据（覆盖已有）"))
            val radios = device.findObjects(By.clazz("android.widget.RadioButton"))
            assertTrue("替换策略radio可见", radios.isNotEmpty())
            radios.last().click()
            val replaceButton = actionForText("替换恢复")
            assertFalse("未确认替换按钮必须禁用", replaceButton.isEnabled)
            replaceButton.click()
            assertFalse("点击禁用按钮不能替用户确认", requireNode(By.clazz("android.widget.CheckBox")).isChecked)
            assertEquals(changedSource, db.serverDao().getById(sourceId))
            assertEquals(progress, db.playbackProgressDao().get(sourceId, progress.itemId))
            assertEquals(beforePreviewPreferences, preferences.flow.first())
            scrollTo(By.text("我确认要替换所选数据"))
            requireNode(By.clazz("android.widget.CheckBox")).click()
            val confirmedAction = actionForText("替换恢复")
            assertTrue("明确确认后替换按钮启用", confirmedAction.isEnabled)
            confirmedAction.click()
            scrollTo(By.text("恢复完成"))
            val restored = db.serverDao().getById(sourceId)!!
            assertEquals(source.name, restored.name)
            assertEquals(source.type, restored.type)
            assertEquals(progress.positionMs, db.playbackProgressDao().get(sourceId, progress.itemId)!!.positionMs)
            assertEquals(exportedPreferences, preferences.flow.first())
        } catch (failure: Throwable) {
            // 保存失败发生时的任务专用界面，避免 finally 返回桌面后丢失选择器证据。
            runCatching {
                val evidence = File(context.getExternalFilesDir(null), "backup-ui-failure.xml")
                device.dumpWindowHierarchy(evidence)
            }
            throw failure
        } finally {
            try {
                db.playbackProgressDao().deleteByServer(sourceId)
                db.serverDao().deleteById(sourceId)
                preferences.update { originalPreferences }
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
        // Compose 的 TextView/装饰 Button 子节点可 enabled=true，真正点击语义在祖先 View。
        // 以具备点击语义的实际 action 检查禁用门控，不能只看文案节点。
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
        // API 36 的实际失败栈确认生产 600k PBKDF2 在 IO worker 运行约 19s。
        // 普通 UI 仍限 15s；只给真实密码计算独立的有限观察窗口，不重试操作。
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
        // 等待抽屉动画结束，再在roots内重新查询；不能命中动画前页面的Downloads标题。
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
        // executeShellCommand不解释 if/then；Android FUSE也可能不枚举点目录。
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

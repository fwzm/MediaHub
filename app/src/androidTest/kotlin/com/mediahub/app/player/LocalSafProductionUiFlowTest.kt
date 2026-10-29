package com.mediahub.app.player

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mediahub.app.MainActivity
import com.mediahub.app.di.AppModule
import com.mediahub.core.database.prefs.UserPreferencesStore
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.model.PlaybackEngineMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Real directory picker, retained grant, provider and Media3; synthetic files, isolated emulator. */
@RunWith(AndroidJUnit4::class)
class LocalSafProductionUiFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test(timeout = 180_000)
    fun selectedDirectorySurvivesNavigationPlaysAndReauthorizesTheSameSource() = runBlocking {
        assertEquals("isolated", InstrumentationRegistry.getArguments().getString("backupAcceptance"))
        assertTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(instrumentation)
        val db = AppModule.provideAppDatabase(context)
        val repository = ServerRepository(db)
        val prefs = UserPreferencesStore(context)
        val original = prefs.flow.first()
        val originalGrants = resolver.persistedUriPermissions.map { it.uri }.toSet()
        val label = "MediaHub-SAF-${System.nanoTime()}"
        val mediaName = "电影 a+b%.mp4"
        val created = mutableListOf<android.net.Uri>()
        var sourceId: String? = null
        var scenario: ActivityScenario<MainActivity>? = null

        fun waitNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
            // FilterChip merges its label into the clickable parent; the unmerged label
            // cannot satisfy hasText AND hasClickAction on a single semantics node.
            compose.waitUntil(15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
            return compose.onAllNodes(matcher).onFirst()
        }
        fun addFile(name: String, mime: String, bytes: ByteArray) {
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$label/嵌套")
            }))
            created += uri
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        }
        fun chooseTree() {
            waitNode(hasText("选择本地目录", substring = true)).performScrollTo().performClick()
            val documents = By.pkg(Pattern.compile("com\\.(android|google\\.android)\\.documentsui"))
            assertTrue(device.wait(Until.hasObject(documents), 10_000))
            // The prior backup picker may already leave DocumentsUI in Downloads, with
            // no roots drawer button. Use the same observed-location adapter as backup.
            val downloads = Pattern.compile("(?i)^(downloads?|下载内容|下载)$")
            fun header() = device.findObject(By.res(Pattern.compile(".*:id/header_title")))?.text
                ?: device.findObject(By.res(Pattern.compile(".*:id/breadcrumb_text")))?.text.orEmpty()
            device.waitForIdle(10_000)
            if (!header().contains("Download", ignoreCase = true) && !header().contains("下载")) {
                device.findObject(By.desc(Pattern.compile("(?i)show roots|显示根目录|显示位置")))?.let {
                    it.click(); device.waitForIdle(10_000)
                }
                var selectedDownloads = false
                for (container in listOf("roots_list", "dir_list")) {
                    val entry = device.wait(Until.findObject(By.text(downloads).hasAncestor(
                        By.res(Pattern.compile(".*:id/" + container)))), 2_000)
                    if (entry != null) { entry.click(); selectedDownloads = true; break }
                }
                if (!selectedDownloads) {
                    val visible = device.findObjects(By.text(downloads))
                    assertTrue("Downloads location must be visible", visible.isNotEmpty())
                    visible.first().click()
                }
                device.waitForIdle(10_000)
            }
            checkNotNull(device.wait(Until.findObject(By.text(label)), 10_000)).click()
            checkNotNull(device.wait(Until.findObject(By.res(Pattern.compile(".*:id/action_menu_select"))), 10_000)).click()
            device.wait(Until.findObject(By.res("android:id/button1")), 3_000)?.click()
            waitNode(hasText("已选择目录"))
        }
        fun leavePlayer() {
            val close = hasContentDescription(context.getString(com.mediahub.feature.player.R.string.player_close))
            if (compose.onAllNodes(close).fetchSemanticsNodes().isEmpty()) compose.onRoot().performTouchInput { click(center) }
            waitNode(close).performClick()
            waitNode(hasText("播放", substring = false))
        }

        try {
            addFile(mediaName, "video/mp4", instrumentation.context.assets.open("a4-synthetic.mp4").use { it.readBytes() })
            addFile("电影 a+b%.zh.srt", "application/x-subrip", "1\n00:00:00,000 --> 00:00:59,000\nSAF synthetic subtitle\n".toByteArray())
            prefs.update { it.copy(playbackEngineMode = PlaybackEngineMode.MEDIA3, autoLandscape = false,
                immersiveBars = false, playerVisualEffects = it.playerVisualEffects.copy(enabled = false)) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            waitNode(hasContentDescription("添加媒体库")).performClick()
            waitNode(hasText("本地存储") and hasClickAction()).performClick()
            chooseTree()
            waitNode(hasSetTextAction() and hasText("本地存储")).performTextReplacement(label)
            waitNode(hasText("保存目录")).performScrollTo().performClick()
            waitNode(hasContentDescription("添加媒体库"))
            waitNode(hasText(label)).performClick()
            waitNode(hasText("嵌套")).performClick()
            waitNode(hasText("电影 a+b%", substring = false)).performClick()
            waitNode(hasText("播放", substring = false)).performClick()
            val info = hasContentDescription(context.getString(com.mediahub.feature.player.R.string.player_info_entry))
            if (compose.onAllNodes(info).fetchSemanticsNodes().isEmpty()) compose.onRoot().performTouchInput { click(center) }
            waitNode(info).performClick()
            waitNode(hasText("320×180", substring = true))
            assertTrue(device.takeScreenshot(java.io.File(context.filesDir, "saf-media3-info.png")))
            device.pressBack()
            val subtitles = hasContentDescription(context.getString(com.mediahub.feature.player.R.string.player_subtitles))
            if (compose.onAllNodes(subtitles).fetchSemanticsNodes().isEmpty()) compose.onRoot().performTouchInput { click(center) }
            waitNode(subtitles).performClick()
            waitNode(hasText("电影 a+b%.zh", substring = true)).performScrollTo().performClick()
            waitNode(hasText(context.getString(com.mediahub.feature.player.R.string.player_subtitle_external_active)))
            device.pressBack()
            leavePlayer()
            sourceId = db.serverDao().observeAll().first().single { it.name == label }.id
            val selected = checkNotNull(repository.getServer(checkNotNull(sourceId))).baseUrl
            assertTrue(selected.startsWith("content://"))
            assertTrue(resolver.persistedUriPermissions.any { it.uri.toString() == selected && it.isReadPermission })
            // The picker grant is real; only this fixture's new grant is revoked.
            resolver.persistedUriPermissions.filter { it.uri !in originalGrants }.forEach {
                resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            device.pressBack()
            waitNode(hasContentDescription("刷新")).performClick()
            waitNode(hasText("重新授权目录")).performClick()
            chooseTree()
            waitNode(hasText("保存授权")).performScrollTo().performClick()
            waitNode(hasText("电影 a+b%", substring = false))
            assertEquals(1, db.serverDao().observeAll().first().count { it.name == label })
            assertEquals(sourceId, db.serverDao().observeAll().first().single { it.name == label }.id)
        } catch (failure: Throwable) {
            runCatching {
                val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?.let { java.io.File(it) } ?: context.filesDir
                check(directory.isDirectory || directory.mkdirs())
                device.takeScreenshot(java.io.File(directory, "saf-ui-failure.png"))
                device.dumpWindowHierarchy(java.io.File(directory, "saf-ui-failure.xml"))
            }
            throw failure
        } finally {
            scenario?.close()
            sourceId?.let { repository.deleteServer(it) }
            // An early assertion may fail before sourceId was assigned.
            db.serverDao().observeAll().first().filter { it.name == label }.forEach { repository.deleteServer(it.id) }
            resolver.persistedUriPermissions.filter { it.uri !in originalGrants }.forEach {
                resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            created.forEach { resolver.delete(it, null, null) }
            prefs.update { original }
        }
    }
}

package com.mediahub.app.player

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.mediahub.app.MainActivity
import com.mediahub.app.di.AppModule
import com.mediahub.core.database.prefs.UserPreferencesStore
import com.mediahub.model.PlaybackEngineMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Actual MainActivity/NavHost/provider/Media3. Synthetic loopback media; isolated emulator only. */
@RunWith(AndroidJUnit4::class)
class WebDavProductionUiFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test(timeout = 120_000)
    fun addConnectBrowseDetailPlayDiscoverAndReturnThroughProductionNavigation() = runBlocking {
        assertEquals("isolated", InstrumentationRegistry.getArguments().getString("backupAcceptance"))
        assertTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val db = AppModule.provideAppDatabase(context)
        val prefs = UserPreferencesStore(context)
        val original = prefs.flow.first()
        val name = "A4 UI ${System.nanoTime()}"
        val username = "fixture-${System.nanoTime()}"
        val password = "fixture-${System.nanoTime()}"
        val auth = Credentials.basic(username, password)
        val unknown = AtomicInteger()
        val mediaGets = AtomicInteger()
        val propfinds = AtomicInteger()
        val bytes = instrumentation.context.assets.open("a4-synthetic.mp4").use { it.readBytes() }
        val movie = "/dav/%E7%94%B5%E5%BD%B1%20a%2Bb%25.mp4"
        val subtitle = "/dav/%E7%94%B5%E5%BD%B1%20a%2Bb%25.zh.srt"
        fun row(path: String, label: String, collection: Boolean = false) =
            "<D:response><D:href>$path</D:href><D:propstat><D:prop>" +
                "<D:displayname>$label</D:displayname><D:resourcetype>" +
                (if (collection) "<D:collection/>" else "") + "</D:resourcetype>" +
                (if (path == movie) "<D:getcontentlength>${bytes.size}</D:getcontentlength><D:getcontenttype>video/mp4</D:getcontenttype>" else "") +
                "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
        fun xml(body: String) = MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml")
            .setBody("<D:multistatus xmlns:D=\"DAV:\">$body</D:multistatus>")
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != auth) {
                    unknown.incrementAndGet(); return MockResponse().setResponseCode(401)
                }
                val path = request.requestUrl?.encodedPath
                if (request.method == "PROPFIND") {
                    propfinds.incrementAndGet()
                    return when (path to request.getHeader("Depth")) {
                        "/dav/" to "0" -> xml(row("/dav/", "root", true))
                        "/dav/" to "1" -> xml(row("/dav/", "root", true) + row(movie, "电影 a+b%.mp4") + row(subtitle, "电影 a+b%.zh.srt"))
                        movie to "0" -> xml(row(movie, "电影 a+b%.mp4"))
                        else -> { unknown.incrementAndGet(); MockResponse().setResponseCode(500) }
                    }
                }
                if (request.method == "GET" && path == subtitle) return MockResponse().setBody("1\n00:00:00,000 --> 00:00:59,000\nA4 synthetic subtitle\n")
                if (request.method == "GET" && path == movie) {
                    mediaGets.incrementAndGet()
                    val range = request.getHeader("Range")
                    val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    if (start !in bytes.indices) return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${bytes.size}")
                    val response = MockResponse().setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
                        .setBody(Buffer().write(bytes, start, bytes.size - start))
                    if (range != null) response.setResponseCode(206).setHeader("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
                    return response
                }
                unknown.incrementAndGet(); return MockResponse().setResponseCode(500)
            }
        }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            prefs.update { it.copy(playbackEngineMode = PlaybackEngineMode.MEDIA3, autoLandscape = false, immersiveBars = false,
                playerVisualEffects = it.playerVisualEffects.copy(enabled = false)) }
            server.start()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            waitNode(hasContentDescription("添加媒体库")).performClick()
            waitNode(hasText("WebDAV")).performClick()
            fun field(label: String, value: String) = compose.onNode(hasSetTextAction() and hasText(label))
                .performScrollTo().performTextReplacement(value)
            field("服务器地址", server.url("/dav/").toString())
            field("名称", name)
            field("用户名（可选）", username)
            field("密码", password)
            device.pressBack() // dismiss IME
            compose.onNodeWithText("测试连接").performScrollTo().performClick()
            compose.waitUntil(20_000) { propfinds.get() > 0 && compose.onAllNodesWithText("测试连接").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("登录并添加").performScrollTo().performClick()
            waitNode(hasText(name)).performClick()
            waitNode(hasText("电影 a+b%.mp4")).performClick()
            waitNode(hasText("播放", substring = false)).performClick()
            waitNode(hasContentDescription("信息")).performClick()
            waitNode(hasText("实际内核"))
            compose.onAllNodesWithText("Media3", useUnmergedTree = true).onFirst().assertExists()
            waitNode(hasText("320×180", substring = true))
            assertTrue(device.takeScreenshot(java.io.File(context.filesDir, "a4-webdav-player-info.png")))
            device.pressBack()
            waitNode(hasContentDescription("字幕")).performClick()
            waitNode(hasText("电影 a+b%.zh", substring = true))
            assertTrue(device.takeScreenshot(java.io.File(context.filesDir, "a4-webdav-subtitle-discovery.png")))
            // Discovery is reached by the actual player, not a test-only screen.
            device.pressBack()
            device.pressBack()
            waitNode(hasText("播放", substring = false))
            device.pressBack()
            waitNode(hasText("电影 a+b%.mp4"))
            device.pressBack()
            waitNode(hasText(name))
            assertTrue("actual engine must request real MP4", mediaGets.get() > 0)
            assertTrue("auth/browse/detail/discovery must reach wire", propfinds.get() >= 4)
        } finally {
            scenario?.close()
            compose.waitForIdle()
            val created = db.serverDao().observeAll().first().filter { it.name == name }
            for (source in created) {
                AppModule.provideCredentialVault(AppModule.provideSecretStorage(context, com.mediahub.core.logging.StdoutLogger())).clear(source.id)
                db.serverDao().deleteById(source.id)
            }
            prefs.update { original }
            db.close()
            server.shutdown() // all actual requests finished before checking unknown requests
        }
        assertEquals("unknown requests counted after Activity and server shutdown", 0, unknown.get())
    }

    private fun waitNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(20_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodes(matcher).onFirst()
    }
}

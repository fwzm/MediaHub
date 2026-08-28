package com.mediahub.provider.local

import com.mediahub.model.MediaServer
import com.mediahub.model.PageRequest
import com.mediahub.model.ServerType
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * LocalProvider 分页（Phase 1C 评审修复）测试：
 * nextOffset 必须按累计位置推进，锁定 600/401/空目录/越界/连续翻页不重复五类真实场景。
 */
class LocalProviderPaginationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MediaServer(
        id = "srv-local", name = "本地", type = ServerType.LOCAL,
        baseUrl = "file:///local", createdAtEpochMs = 0,
    )

    private fun providerWith(vararg roots: File): LocalProvider =
        LocalProvider(server, object : LocalRootProvider {
            override fun rootDirectories(): List<File> = roots.toList()
        })

    /** 在目录里建 count 个零填充命名的普通文件（保证字典序稳定）。 */
    private fun makeFiles(dir: File, count: Int): List<File> =
        (0 until count).map { i -> File(dir, "f%04d.mp4".format(i)).apply { writeText("x") } }

    // ---- 600 项 / limit 200：三页走完，nextOffset 严格推进，无重复 ----

    @Test
    fun `six hundred items paginate with strictly advancing nextOffset and no repeats`() = runBlocking {
        val dir = tmp.newFolder("root600")
        val files = makeFiles(dir, 600)
        val provider = providerWith(dir)

        val page0 = provider.listFolder(null, PageRequest(offset = 0, limit = 200))
        assertEquals(600, page0.totalCount)
        assertTrue(page0.hasMore)
        assertEquals(200, page0.nextOffset)

        val page1 = provider.listFolder(null, PageRequest(offset = page0.nextOffset!!, limit = 200))
        assertTrue(page1.hasMore)
        // 回归锁：修复前 nextOffset 恒为 items.size=200，第二页被反复抓取
        assertEquals(400, page1.nextOffset)

        val page2 = provider.listFolder(null, PageRequest(offset = page1.nextOffset!!, limit = 200))
        assertFalse(page2.hasMore)
        assertNull(page2.nextOffset)

        val all = page0.items + page1.items + page2.items
        assertEquals(600, all.size)
        assertEquals(600, all.map { it.id }.distinct().size)
        // 连续分页不得重复第二页
        assertTrue(page1.items.map { it.id }.intersect(page2.items.map { it.id }.toSet()).isEmpty())
        // 顺序即目录排序顺序
        assertEquals(files.map { it.absolutePath }, all.map { it.id })
    }

    // ---- 401 项：最后一页不足 limit ----

    @Test
    fun `four hundred one items end with partial page and null nextOffset`() = runBlocking {
        val dir = tmp.newFolder("root401")
        makeFiles(dir, 401)
        val provider = providerWith(dir)

        val page0 = provider.listFolder(null, PageRequest(offset = 0, limit = 200))
        assertEquals(200, page0.items.size)
        assertEquals(200, page0.nextOffset)

        val page1 = provider.listFolder(null, PageRequest(offset = 200, limit = 200))
        assertEquals(200, page1.items.size)
        assertEquals(400, page1.nextOffset)

        val page2 = provider.listFolder(null, PageRequest(offset = 400, limit = 200))
        assertEquals(1, page2.items.size)
        assertFalse(page2.hasMore)
        assertNull(page2.nextOffset)
    }

    // ---- 空目录 ----

    @Test
    fun `empty directory yields empty page without more`() = runBlocking {
        val dir = tmp.newFolder("rootEmpty")
        val provider = providerWith(dir)

        val page = provider.listFolder(null, PageRequest())

        assertTrue(page.items.isEmpty())
        assertEquals(0, page.totalCount)
        assertFalse(page.hasMore)
        assertNull(page.nextOffset)
    }

    // ---- offset 越界 ----

    @Test
    fun `offset beyond total yields empty page and terminates paging`() = runBlocking {
        val dir = tmp.newFolder("root50")
        makeFiles(dir, 50)
        val provider = providerWith(dir)

        val page = provider.listFolder(null, PageRequest(offset = 1000, limit = 200))

        assertTrue(page.items.isEmpty())
        assertEquals(50, page.totalCount)
        assertFalse(page.hasMore)
        assertNull(page.nextOffset)
    }

    // ---- 精确边界：offset == total ----

    @Test
    fun `offset exactly at total yields empty page without more`() = runBlocking {
        val dir = tmp.newFolder("root10")
        makeFiles(dir, 10)
        val provider = providerWith(dir)

        val page = provider.listFolder(null, PageRequest(offset = 10, limit = 200))

        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
        assertNull(page.nextOffset)
    }

    // ---- 目录与文件混排：目录优先的既有排序策略不被分页破坏 ----

    @Test
    fun `directories sort before files across paginated pages`() = runBlocking {
        val dir = tmp.newFolder("rootMixed")
        makeFiles(dir, 3)
        for (i in 0 until 2) File(dir, "d$i").mkdir()
        val provider = providerWith(dir)

        val page = provider.listFolder(null, PageRequest(offset = 0, limit = 200))

        assertEquals(5, page.items.size)
        assertTrue(page.items.take(2).all { it.type == com.mediahub.model.MediaType.FOLDER })
        assertTrue(page.items.drop(2).all { it.type == com.mediahub.model.MediaType.VIDEO })
    }
}

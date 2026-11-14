package com.mediahub.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件浏览器的独立 SAF 目录栈（收尾项 2）。
 *
 * 栈必须能进出子目录、保留完整路径，并且能编码进 SavedStateHandle
 * 让进程重建/旋转屏幕后回到同一路径。
 */
class SafPathTest {

    private val root = SafPathNode(uri = "content://tree/primary%3AMovies", name = "Movies")
    private val sub = SafPathNode(
        uri = "content://tree/primary%3AMovies/document/primary%3AMovies%2F2024",
        name = "2024",
    )
    private val deep = SafPathNode(
        uri = "content://tree/primary%3AMovies/document/primary%3AMovies%2F2024%2FExtras",
        name = "Extras",
    )

    @Test
    fun `push and pop keep the chosen root`() {
        var stack = SafPath.push(emptyList(), root)
        stack = SafPath.push(stack, sub)
        assertEquals(listOf(root, sub), stack)
        assertEquals("Movies / 2024", SafPath.breadcrumb(stack))
        stack = SafPath.pop(stack)
        assertEquals(listOf(root), stack)
        // 根目录不能被弹出，否则会失去 SAF 授权入口。
        assertEquals(listOf(root), SafPath.pop(stack))
        assertEquals("未选择目录", SafPath.breadcrumb(emptyList()))
    }

    @Test
    fun `stack survives state restore`() {
        val stack = listOf(root, sub, deep)
        assertEquals(stack, SafPath.decode(SafPath.encode(stack)))
        assertEquals("Movies / 2024 / Extras", SafPath.breadcrumb(SafPath.decode(SafPath.encode(stack))))
        assertTrue(SafPath.decode(null).isEmpty())
        assertTrue(SafPath.decode("").isEmpty())
    }

    @Test
    fun `current returns the deepest node`() {
        assertNull(SafPath.current(emptyList()))
        assertEquals(root, SafPath.current(listOf(root)))
        assertEquals(deep, SafPath.current(listOf(root, sub, deep)))
    }

    @Test
    fun `re-entering a sibling does not duplicate the root`() {
        val sibling = SafPathNode(uri = "content://tree/primary%3AMovies/document/primary%3AMovies%2F2023", name = "2023")
        var stack = SafPath.push(SafPath.push(emptyList(), root), sub)
        stack = SafPath.pop(stack)
        stack = SafPath.push(stack, sibling)
        assertEquals(listOf(root, sibling), stack)
    }
}

package com.mediahub.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 收藏开关纯状态迁移（task-25 / A2）：全部断言不依赖 Compose 运行时。 */
class FavoriteToggleTest {

    @Test
    fun `fromServerState mirrors the server UserData value`() {
        val favorited = FavoriteToggle.fromServerState("item-1", isFavorite = true)
        val plain = FavoriteToggle.fromServerState("item-1", isFavorite = false)

        assertEquals("item-1", favorited.itemId)
        assertTrue(favorited.isFavorite)
        assertFalse(favorited.isUpdating)
        assertNull(favorited.error)
        assertFalse(plain.isFavorite)
    }

    @Test
    fun `pending keeps the current icon and clears a stale error`() {
        val failed = FavoriteToggleState("item-1", isFavorite = false, error = "网络错误")

        val state = FavoriteToggle.pending(failed)

        assertFalse("请求在途不得预翻转图标", state.isFavorite)
        assertTrue(state.isUpdating)
        assertNull("重试应清掉旧错误", state.error)
    }

    @Test
    fun `confirmed renders the server readback instead of the local inverse`() {
        // 服务器忽略写入（回读仍为 false）时，界面必须停在 false，而不是本地点成 true
        val afterClick = FavoriteToggle.pending(FavoriteToggleState("item-1", isFavorite = false))

        val ignored = FavoriteToggle.confirmed(afterClick, serverValue = false)
        val accepted = FavoriteToggle.confirmed(afterClick, serverValue = true)

        assertFalse(ignored.isFavorite)
        assertFalse(ignored.isUpdating)
        assertTrue(accepted.isFavorite)
    }

    @Test
    fun `failed keeps the previous state and surfaces a readable message`() {
        val before = FavoriteToggleState("item-1", isFavorite = true)

        val state = FavoriteToggle.failed(before, "服务器未响应，请稍后重试")

        assertTrue("失败不得把已收藏切成未收藏", state.isFavorite)
        assertFalse(state.isUpdating)
        assertEquals("服务器未响应，请稍后重试", state.error)
    }
}

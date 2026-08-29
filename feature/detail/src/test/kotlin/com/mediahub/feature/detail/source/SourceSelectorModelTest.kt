package com.mediahub.feature.detail.source

import com.mediahub.model.ExternalIds
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source selector 纯投影（1F C2 / ADR-038）：gate 条件与副本标注。 */
class SourceSelectorModelTest {

    private fun item(serverId: String, id: String) = MediaItem(
        serverId = serverId, id = id, type = MediaType.MOVIE,
        title = "Fargo", year = 2014, externalIds = ExternalIds(tmdb = "1"),
    )

    private fun occ(serverId: String, serverName: String, id: String, active: Boolean = false) =
        CanonicalSourceOccurrence(
            serverId = serverId, serverName = serverName,
            item = item(serverId, id), isActive = active,
        )

    // ---- gate：distinct serverId ≥ 2 才显示（继承 1E） ----

    @Test
    fun `selector hidden when fewer than two distinct servers`() {
        assertFalse(shouldShowSourceSelector(emptyList()))
        assertFalse(
            shouldShowSourceSelector(
                listOf(occ("s1", "予初", "a1"), occ("s1", "予初", "a1-dup")),
            ),
        )
        assertTrue(
            shouldShowSourceSelector(
                listOf(occ("s1", "予初", "a1", active = true), occ("s2", "墨云阁", "b1")),
            ),
        )
    }

    // ---- 标签：单副本 = 服务器名 ----

    @Test
    fun `single copy per server uses plain server name`() {
        val rows = sourceRows(
            listOf(
                occ("s1", "予初", "a1", active = true),
                occ("s2", "墨云阁", "b1"),
            ),
        )
        assertEquals(listOf("予初", "墨云阁"), rows.map { it.label })
        assertEquals(listOf(true, false), rows.map { it.isActive })
        assertEquals(listOf("a1", "b1"), rows.map { it.itemId })
    }

    // ---- 标签：同服务器多副本 = "服务器名 · 副本N"（禁用 MediaVersion 语义） ----

    @Test
    fun `same server duplicates are labeled as copies with 1 based index`() {
        val rows = sourceRows(
            listOf(
                occ("s1", "予初", "a1", active = true),
                occ("s1", "予初", "a1-dup"),
                occ("s2", "墨云阁", "b1"),
            ),
        )
        assertEquals(
            listOf("予初 · 副本1", "予初 · 副本2", "墨云阁"),
            rows.map { it.label },
        )
        // 行携带各自 itemId/serverId，切换路由可直达目标 occurrence
        assertEquals(listOf("a1", "a1-dup", "b1"), rows.map { it.itemId })
        assertEquals(listOf("s1", "s1", "s2"), rows.map { it.serverId })
    }
}

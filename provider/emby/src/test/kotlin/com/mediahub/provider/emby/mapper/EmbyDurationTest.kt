package com.mediahub.provider.emby.mapper

import com.mediahub.provider.emby.api.EmbyBaseItemDto
import com.mediahub.provider.emby.api.EmbyMediaSourceInfoDto
import com.mediahub.provider.emby.api.EmbyUserItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 时长识别兼容（用户条目 79 / handoff 条目 11）：fnOS 等兼容服可能返回
 * 毫秒或秒尺度的 RunTimeTicks，标准换算会得到 <1s 或荒谬值。
 */
class EmbyDurationTest {

    @Test
    fun `standard ticks convert to milliseconds`() {
        // 2 小时：72_000_000_000 ticks（100ns）→ 7_200_000 ms
        assertEquals(7_200_000L, EmbyDuration.fromRunTimeTicks(72_000_000_000L))
    }

    @Test
    fun `exactly one second of ticks stays on the standard scale`() {
        assertEquals(1_000L, EmbyDuration.fromRunTimeTicks(10_000_000L))
    }

    @Test
    fun `millisecond scale values pass through unchanged`() {
        assertEquals(5_400_000L, EmbyDuration.fromRunTimeTicks(5_400_000L))
        assertEquals(10_000L, EmbyDuration.fromRunTimeTicks(10_000L))
    }

    @Test
    fun `second scale values are multiplied by one thousand`() {
        assertEquals(5_400_000L, EmbyDuration.fromRunTimeTicks(5_400L))
        assertEquals(1_000L, EmbyDuration.fromRunTimeTicks(1L))
        assertEquals(9_999_000L, EmbyDuration.fromRunTimeTicks(9_999L))
    }

    @Test
    fun `null zero and negative values yield null`() {
        assertNull(EmbyDuration.fromRunTimeTicks(null))
        assertNull(EmbyDuration.fromRunTimeTicks(0L))
        assertNull(EmbyDuration.fromRunTimeTicks(-1L))
        assertNull(EmbyDuration.fromRunTimeTicks(-72_000_000_000L))
    }

    @Test
    fun `results beyond seven days yield null`() {
        // 604_800_001 ms → 需要 6_048_000_010_000 标准 ticks
        assertNull(EmbyDuration.fromRunTimeTicks(6_048_000_010_000L))
    }

    @Test
    fun `exactly seven days is still accepted`() {
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        assertEquals(sevenDaysMs, EmbyDuration.fromRunTimeTicks(sevenDaysMs * 10_000L))
    }
}

/** 列表/浏览条目映射使用同一时长兜底（禁止两套换算）。 */
class EmbyMediaItemRuntimeCompatTest {

    @Test
    fun `millisecond scale runtime maps through the compat rule`() {
        val item = EmbyMediaItemMapper.map(
            EmbyBaseItemDto(id = "m1", name = "电影A", type = "Movie", runTimeTicks = 5_400_000L),
            "srv-1",
        )!!
        assertEquals(5_400_000L, item.runtimeMs)
    }

    @Test
    fun `missing runtime maps to null instead of a bogus value`() {
        val item = EmbyMediaItemMapper.map(
            EmbyBaseItemDto(id = "m1", name = "电影A", type = "Movie", runTimeTicks = 0L),
            "srv-1",
        )!!
        assertNull(item.runtimeMs)
    }
}

/** 详情映射：条目 RunTimeTicks 缺失/无效时回退 MediaSources[0]。 */
class EmbyDetailDurationFallbackTest {

    @Test
    fun `detail falls back to first media source runtime when item runtime is missing`() {
        val dto = EmbyUserItemDto(
            id = "m1",
            name = "电影A",
            type = "Movie",
            runTimeTicks = null,
            mediaSources = listOf(EmbyMediaSourceInfoDto(id = "src1", runTimeTicks = 54_000_000_000L)),
        )
        val detail = EmbyDetailMapper.mapDetail(dto, "srv-1")!!
        assertEquals(5_400_000L, detail.item.runtimeMs)
    }

    @Test
    fun `detail prefers item runtime when it is usable`() {
        val dto = EmbyUserItemDto(
            id = "m1",
            name = "电影A",
            type = "Movie",
            runTimeTicks = 72_000_000_000L,
            mediaSources = listOf(EmbyMediaSourceInfoDto(id = "src1", runTimeTicks = 5_400_000_000L)),
        )
        val detail = EmbyDetailMapper.mapDetail(dto, "srv-1")!!
        assertEquals(7_200_000L, detail.item.runtimeMs)
    }
}

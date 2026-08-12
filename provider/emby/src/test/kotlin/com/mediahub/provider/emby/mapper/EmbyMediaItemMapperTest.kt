package com.mediahub.provider.emby.mapper

import com.mediahub.provider.emby.api.EmbyBaseItemDto
import org.junit.Assert.assertNull
import org.junit.Test

/** preflight 加固（Phase 1B-2）：Id 缺失/空白的条目禁止进入领域层。 */
class EmbyMediaItemMapperTest {
    @Test
    fun `items without usable id map to null`() {
        assertNull(EmbyMediaItemMapper.map(EmbyBaseItemDto(id = null, name = "无名"), "srv-1"))
        assertNull(EmbyMediaItemMapper.map(EmbyBaseItemDto(id = "  ", name = "空id"), "srv-1"))
    }
}

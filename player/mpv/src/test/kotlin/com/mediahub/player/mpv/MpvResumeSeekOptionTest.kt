package com.mediahub.player.mpv

import com.mediahub.model.ResumeSeekMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** 条目 124：mpv 精确续播 → `hr-seek` 选项值。 */
class MpvResumeSeekOptionTest {

    @Test
    fun `keyframe resume disables high resolution seeking`() {
        assertEquals("hr-seek", MpvResumeSeekOption.NAME)
        assertEquals("no", MpvResumeSeekOption.value(ResumeSeekMode.KEYFRAME))
    }

    @Test
    fun `exact resume enables high resolution seeking`() {
        assertEquals("yes", MpvResumeSeekOption.value(ResumeSeekMode.EXACT))
    }
}

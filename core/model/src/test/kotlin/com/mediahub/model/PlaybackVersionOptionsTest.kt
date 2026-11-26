package com.mediahub.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 多版本闭环：PlaybackOptions / PlaybackLaunchSnapshot 必须能携带并默认省略 versionId。 */
class PlaybackVersionOptionsTest {
    @Test
    fun `playback options default to no version`() {
        assertNull(PlaybackOptions().versionId)
        assertEquals("ms-2", PlaybackOptions(versionId = "ms-2").versionId)
    }

    @Test
    fun `launch snapshot defaults to no version and carries explicit one`() {
        assertNull(PlaybackLaunchSnapshot(itemId = "m1").versionId)
        assertEquals(
            "ms-2",
            PlaybackLaunchSnapshot(itemId = "m1", type = MediaType.MOVIE, versionId = "ms-2").versionId,
        )
    }
}

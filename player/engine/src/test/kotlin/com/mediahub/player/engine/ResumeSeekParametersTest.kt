package com.mediahub.player.engine

import androidx.media3.exoplayer.SeekParameters
import com.mediahub.model.ResumeSeekMode
import org.junit.Assert.assertSame
import org.junit.Test

/** 条目 124：EXO 精确续播 → Media3 SeekParameters 的真实映射。 */
class ResumeSeekParametersTest {

    @Test
    fun `keyframe resume uses closest sync for fast startup`() {
        assertSame(
            SeekParameters.CLOSEST_SYNC,
            ResumeSeekParameters.forMode(ResumeSeekMode.KEYFRAME),
        )
    }

    @Test
    fun `exact resume uses exact seek parameters`() {
        assertSame(
            SeekParameters.EXACT,
            ResumeSeekParameters.forMode(ResumeSeekMode.EXACT),
        )
    }
}

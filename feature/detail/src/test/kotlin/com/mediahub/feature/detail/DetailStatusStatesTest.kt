package com.mediahub.feature.detail

import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视觉审计 D2/D3：详情页加载态与错误态必须复用 core/ui 的 MediaHubStatus（与首页/媒体库/搜索同一套三态组件），
 * 而不是裸 CircularProgressIndicator / 裸 Text + TextButton。
 * 该映射是纯数据，可在 JVM 单测里逐条断言，无需 Robolectric。
 */
class DetailStatusStatesTest {

    private fun sampleDetail(): MediaDetail = MediaDetail(
        item = MediaItem(
            serverId = "s1",
            id = "x",
            type = MediaType.MOVIE,
            title = "T",
        ),
    )

    @Test
    fun loadingStateAsksTheSharedStatusForASpinner() {
        val spec = detailStatusSpec(DetailUiState.Loading)
        assertNotNull(spec)
        assertTrue(spec!!.loading)
        assertEquals(DETAIL_STATUS_LOADING_TITLE, spec.title)
        assertNull(spec.message)
        assertNull(spec.retryLabel)
    }

    @Test
    fun errorStateCarriesTheFailureMessageAndARetryAffordance() {
        val spec = detailStatusSpec(DetailUiState.Error("网络超时"))
        assertNotNull(spec)
        assertFalse(spec!!.loading)
        assertEquals(DETAIL_STATUS_ERROR_TITLE, spec.title)
        assertEquals("网络超时", spec.message)
        assertEquals(DETAIL_STATUS_RETRY_LABEL, spec.retryLabel)
    }

    @Test
    fun errorStateFallsBackWhenTheServerGivesNoUsableMessage() {
        val blank = detailStatusSpec(DetailUiState.Error("   "))
        assertEquals(DETAIL_STATUS_ERROR_FALLBACK_MESSAGE, blank!!.message)
        assertEquals(DETAIL_STATUS_RETRY_LABEL, blank.retryLabel)
    }

    @Test
    fun contentStateRendersNoStatusAtAll() {
        assertNull(detailStatusSpec(DetailUiState.Content(sampleDetail())))
    }

    @Test
    fun statusCopyStaysReadableAndDistinctPerState() {
        assertTrue(DETAIL_STATUS_LOADING_TITLE.isNotBlank())
        assertTrue(DETAIL_STATUS_ERROR_TITLE.isNotBlank())
        assertTrue(DETAIL_STATUS_LOADING_TITLE != DETAIL_STATUS_ERROR_TITLE)
        assertEquals("重试", DETAIL_STATUS_RETRY_LABEL)
    }
}

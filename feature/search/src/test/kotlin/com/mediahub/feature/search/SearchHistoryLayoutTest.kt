package com.mediahub.feature.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryLayoutTest {

    @Test
    fun `history is fixed to two rows`() {
        assertEquals(2, SEARCH_HISTORY_ROWS)
    }

    @Test
    fun `columns scale with width but stay within bounds`() {
        assertEquals(2, searchHistoryColumns(200f))
        assertEquals(4, searchHistoryColumns(400f))
        assertEquals(6, searchHistoryColumns(1200f))
    }

    @Test
    fun `visible count equals columns times two rows`() {
        assertEquals(8, visibleSearchHistoryCount(400f))
        assertEquals(12, visibleSearchHistoryCount(1200f))
    }
}

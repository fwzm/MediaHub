package com.mediahub.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** 审计 #97：文件浏览网格在平板上必须按可用宽度自适应列数。 */
class LibraryGridPolicyTest {

    @Test
    fun `phones keep three columns below six hundred dp`() {
        assertEquals(3, LibraryGridPolicy.columnsForWidth(320))
        assertEquals(3, LibraryGridPolicy.columnsForWidth(599))
    }

    @Test
    fun `tablets use five columns from six hundred dp`() {
        assertEquals(5, LibraryGridPolicy.columnsForWidth(600))
        assertEquals(5, LibraryGridPolicy.columnsForWidth(899))
    }

    @Test
    fun `large tablets use six columns from nine hundred dp`() {
        assertEquals(6, LibraryGridPolicy.columnsForWidth(900))
        assertEquals(6, LibraryGridPolicy.columnsForWidth(1200))
    }

    @Test
    fun `invalid widths stay at the phone grid`() {
        assertEquals(3, LibraryGridPolicy.columnsForWidth(0))
        assertEquals(3, LibraryGridPolicy.columnsForWidth(-10))
    }
}

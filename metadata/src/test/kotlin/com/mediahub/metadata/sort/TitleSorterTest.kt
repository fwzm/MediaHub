package com.mediahub.metadata.sort

import com.mediahub.metadata.MetadataResult
import com.mediahub.metadata.MetadataSource
import org.junit.Assert.assertEquals
import org.junit.Test

class TitleSorterTest {

    private val sorter = TitleSorter()

    @Test
    fun `ignores leading english articles`() {
        val sorted = sorter.sort(
            listOf(result("1", "Mission: Impossible"), result("2", "The Matrix"), result("3", "A Quiet Place")),
        )

        assertEquals(listOf("The Matrix", "Mission: Impossible", "A Quiet Place"), sorted.map { it.title })
    }

    @Test
    fun `orders chinese titles by pinyin`() {
        val sorted = sorter.sort(
            listOf(result("1", "变形金刚"), result("2", "阿凡达"), result("3", "长津湖")),
        )

        assertEquals(listOf("阿凡达", "变形金刚", "长津湖"), sorted.map { it.title })
    }

    @Test
    fun `orders embedded numbers naturally`() {
        val sorted = sorter.sort(
            listOf(result("1", "Spider-Man 10"), result("2", "Spider-Man 2")),
        )

        assertEquals(listOf("Spider-Man 2", "Spider-Man 10"), sorted.map { it.title })
    }

    @Test
    fun `folds full width and case before comparing`() {
        val sorted = sorter.sort(
            listOf(result("1", "ＦＲＩＥＮＤＳ"), result("2", "Fargo")),
        )

        assertEquals(listOf("Fargo", "ＦＲＩＥＮＤＳ"), sorted.map { it.title })
    }

    @Test
    fun `blank titles are placed last`() {
        val sorted = sorter.sort(
            listOf(result("1", "   "), result("2", "Fargo"), result("3", "1923")),
        )

        assertEquals(listOf("1923", "Fargo", "   "), sorted.map { it.title })
    }

    @Test
    fun `equal keys fall back to stable id order`() {
        val sorted = sorter.sort(
            listOf(result("b", "The Matrix"), result("a", "Matrix")),
        )

        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }

    @Test
    fun `uses original title when display title is blank`() {
        val sorted = sorter.sort(
            listOf(
                result("1", "  ", originalTitle = "Zulu"),
                result("2", "Amelie"),
            ),
        )

        assertEquals(listOf("2", "1"), sorted.map { it.id })
    }

    @Test
    fun `keeps year-like titles instead of stripping them`() {
        val sorted = sorter.sort(
            listOf(result("1", "1917"), result("2", "Alien")),
        )

        assertEquals(listOf("1917", "Alien"), sorted.map { it.title })
    }

    @Test
    fun `generic overload orders any title bearing list`() {
        data class Row(val id: String, val name: String)

        val sorted = sorter.sortedByTitle(
            items = listOf(Row("1", "变形金刚"), Row("2", "阿凡达"), Row("3", "长津湖")),
            titleOf = { it.name },
            idOf = { it.id },
        )

        assertEquals(listOf("阿凡达", "变形金刚", "长津湖"), sorted.map { it.name })
    }

    @Test
    fun `generic overload keeps blank titles last and ids stable`() {
        data class Row(val id: String, val name: String)

        val sorted = sorter.sortedByTitle(
            items = listOf(Row("b", "  "), Row("a", "1917"), Row("c", "Amelie")),
            titleOf = { it.name },
            idOf = { it.id },
        )

        assertEquals(listOf("a", "c", "b"), sorted.map { it.id })
    }

    private fun result(
        id: String,
        title: String,
        originalTitle: String? = null,
    ) = MetadataResult(
        source = MetadataSource.TMDB,
        id = id,
        title = title,
        originalTitle = originalTitle,
    )
}

package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The order the reader asked for, applied to one folder's files.
 *
 * The database brings a folder's rows back in an order of its own, which is stable and nobody's
 * choice, so the screen's order is put on afterwards. The tie-break is the part worth testing:
 * two files of the same size or written in the same millisecond have to come back in the same
 * order every time, or a rescan would shuffle a grid that did not change.
 *
 * Plain JUnit, no Robolectric: nothing here touches Android.
 */
class RefOrderTest {

    private fun ref(name: String, size: Long = 0, mtime: Long = 0) = LibraryRef(
        id = 0,
        uri = "content://test.fixtures/$name",
        name = name,
        ext = name.substringAfterLast('.', "").lowercase(),
        mime = null,
        size = size,
        mtime = mtime,
        origin = RefOrigin.PICK,
        sourceNode = null,
        addedAt = 0,
        coverState = Covers.NONE,
        coverKey = null
    )

    private val files = listOf(
        ref("beta.txt", size = 300, mtime = 2000),
        ref("Alpha.txt", size = 100, mtime = 3000),
        ref("gamma.txt", size = 200, mtime = 1000)
    )

    private fun order(order: Settings.SortOrder) =
        orderRefs(files, order).map { it.name }

    @Test
    fun byNameIgnoresCase() {
        assertThat(order(Settings.SortOrder.NAME_AZ))
            .containsExactly("Alpha.txt", "beta.txt", "gamma.txt").inOrder()
        assertThat(order(Settings.SortOrder.NAME_ZA))
            .containsExactly("gamma.txt", "beta.txt", "Alpha.txt").inOrder()
    }

    @Test
    fun bySize() {
        assertThat(order(Settings.SortOrder.LARGEST))
            .containsExactly("beta.txt", "gamma.txt", "Alpha.txt").inOrder()
        assertThat(order(Settings.SortOrder.SMALLEST))
            .containsExactly("Alpha.txt", "gamma.txt", "beta.txt").inOrder()
    }

    @Test
    fun byDate() {
        assertThat(order(Settings.SortOrder.NEWEST))
            .containsExactly("Alpha.txt", "beta.txt", "gamma.txt").inOrder()
        assertThat(order(Settings.SortOrder.OLDEST))
            .containsExactly("gamma.txt", "beta.txt", "Alpha.txt").inOrder()
    }

    /** Equal sizes come back in name order rather than in whatever order they went in. */
    @Test
    fun tiesFallBackToTheName() {
        val sameSize = listOf(
            ref("c.txt", size = 10),
            ref("a.txt", size = 10),
            ref("b.txt", size = 10)
        )
        assertThat(orderRefs(sameSize, Settings.SortOrder.SMALLEST).map { it.name })
            .containsExactly("a.txt", "b.txt", "c.txt").inOrder()
    }

    @Test
    fun anEmptyFolderIsStillEmpty() {
        assertThat(orderRefs(emptyList(), Settings.SortOrder.LARGEST)).isEmpty()
    }
}

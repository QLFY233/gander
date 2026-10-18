package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Where a card dragged by its grip may be dropped.
 *
 * The rows a drag passes over are headings, cards and lines of text, and only some of the
 * headings are places a file can be filed: this is that decision, on its own, because it is the
 * one part of the gesture that can be asked without a finger.
 *
 * Plain JUnit, no Robolectric: nothing here touches Android.
 */
class DropTargetTest {

    private fun node(id: Long, name: String, imported: Boolean = false) = LibraryNode(
        id = id,
        kind = if (imported) NodeKind.IMPORTED else NodeKind.VIRTUAL,
        name = name,
        treeUri = if (imported) "content://test.tree/$id" else null,
        docId = if (imported) "root-$id" else null,
        colorIndex = 0,
        position = id.toInt(),
        scannedAt = null,
        createdAt = 0
    )

    private fun section(id: Long, name: String, imported: Boolean = false) =
        HomeRow.Section(node(id, name, imported), count = 0, collapsed = false)

    private val card = HomeRow.Card(
        LibraryRef(
            id = 1,
            uri = "content://test.fixtures/zeta.txt",
            name = "zeta.txt",
            ext = "txt",
            mime = "text/plain",
            size = 10,
            mtime = 20,
            origin = RefOrigin.PICK,
            sourceNode = null,
            addedAt = 0,
            coverState = Covers.NONE,
            coverKey = null
        ),
        nodeId = 7
    )

    @Test
    fun anotherFoldersHeadingIsAPlaceToDrop() {
        assertThat(dropTargetFor(section(8, "Receipts"), from = 7)!!.name).isEqualTo("Receipts")
    }

    /** The folder the card came out of is not: filing it there adds a place it already has. */
    @Test
    fun theFolderItCameFromIsNot() {
        assertThat(dropTargetFor(section(7, "Documents"), from = 7)).isNull()
    }

    /**
     * An imported folder is a real directory on the phone rather than a list of its own, and
     * putting a file in it would move the file rather than add a place in the index.
     */
    @Test
    fun anImportedFolderIsNot() {
        assertThat(dropTargetFor(section(9, "Downloads", imported = true), from = 7)).isNull()
    }

    @Test
    fun onlyHeadingsArePlacesToDrop() {
        assertThat(dropTargetFor(card, from = 7)).isNull()
        assertThat(dropTargetFor(HomeRow.Hint("Nothing here yet"), from = 7)).isNull()
        assertThat(dropTargetFor(null, from = 7)).isNull()
    }

    /** A folder dragged from a card with no folder of its own is still not the one it is in. */
    @Test
    fun theHeadingOfEveryOtherFolderIsAPlaceToDrop() {
        val nodes = (1L..3L).map { section(it, "Folder $it") }
        val drops = nodes.map { dropTargetFor(it, from = 2)?.id }
        assertThat(drops).containsExactly(1L, null, 3L).inOrder()
    }
}

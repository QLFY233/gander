package com.arjun.gander

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The library's own rules: what a folder is, what deleting one means, and what a file can
 * belong to.
 *
 * Everything here is about the index. The one thing that must never happen is a change here
 * touching the reader's actual files, and the way that is enforced is that nothing in the
 * library has a write grant to give: it only ever gives grants back.
 */
@RunWith(AndroidJUnit4::class)
class LibraryNodesTest {

    private lateinit var context: Context
    private lateinit var provider: FixtureProvider
    private lateinit var library: Library

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = FixtureProvider.install()
        library = (context as GanderApp).library
        library.io = directExecutor()
    }

    @After
    fun tearDown() {
        library.close()
    }

    private fun grantedTree(vararg children: ChildDoc): Uri {
        FakeDocumentsProvider.install().folder("root", "Docs", *children)
        return FakeDocumentsProvider.treeUri("root")
    }

    private fun picked(name: String): Uri = provider.add(name, Fixtures.sized(name, 16))

    // ---------------------------------------------------------------
    // Nodes
    // ---------------------------------------------------------------

    @Test
    fun importingATreeRecordsTheGrantAndNothingElse() {
        val id = library.importTree(grantedTree(ChildDoc("a", "a.pdf", "application/pdf", 1, 1)))
        val node = library.node(id)!!

        assertThat(node.imported).isTrue()
        assertThat(node.name).isEqualTo("Docs")
        assertThat(node.scannedAt).isNull()
        assertThat(library.refsFor(node)).isEmpty()
        assertThat(context.contentResolver.persistedUriPermissions.map { it.uri })
            .contains(node.treeUri!!.toUri())
    }

    @Test
    fun theSameTreeIsImportedOnlyOnce() {
        val tree = grantedTree(ChildDoc("a", "a.pdf", "application/pdf", 1, 1))
        val first = library.importTree(tree)
        val second = library.importTree(tree)
        assertThat(second).isEqualTo(first)
        assertThat(library.nodes()).hasSize(1)
    }

    @Test
    fun expandingAnImportedFolderFillsTheIndex() {
        val id = library.importTree(
            grantedTree(ChildDoc("a", "a.pdf", "application/pdf", 1, 1))
        )
        val truncated = library.ensureScanned(id)
        assertThat(truncated).isFalse()
        assertThat(library.refsFor(library.node(id)!!).map { it.name }).containsExactly("a.pdf")
    }

    @Test
    fun scanningAnImportedFolderTwiceDoesNothingTheSecondTime() {
        val id = library.importTree(
            grantedTree(ChildDoc("a", "a.pdf", "application/pdf", 1, 1))
        )
        library.ensureScanned(id)
        assertThat(library.ensureScanned(id)).isFalse()
        assertThat(library.refsFor(library.node(id)!!)).hasSize(1)
    }

    @Test
    fun aVirtualFolderIsMadeEmptyAndGetsAColour() {
        val id = library.createVirtualFolder("Receipts")
        val node = library.node(id)!!
        assertThat(node.imported).isFalse()
        assertThat(node.colorIndex).isAtLeast(0)
        assertThat(node.colorIndex).isLessThan(FOLDER_COLOR_COUNT)
        assertThat(library.refsFor(node)).isEmpty()
    }

    /** The colour comes from the row's id, so a rename cannot move it. */
    @Test
    fun renamingAFolderKeepsItsColour() {
        val id = library.createVirtualFolder("Receipts")
        val before = library.node(id)!!.colorIndex
        library.renameNode(id, "Tax")
        assertThat(library.node(id)!!.colorIndex).isEqualTo(before)
        assertThat(library.node(id)!!.name).isEqualTo("Tax")
    }

    @Test
    fun foldersAreListedInTheOrderTheyWereMade() {
        library.createVirtualFolder("One")
        library.createVirtualFolder("Two")
        library.createVirtualFolder("Three")
        assertThat(library.nodes().map { it.name }).containsExactly("One", "Two", "Three").inOrder()
    }

    // ---------------------------------------------------------------
    // Picked files
    // ---------------------------------------------------------------

    /**
     * A picked file goes into a folder named after the real one it came from, and here there
     * is no real one to name: the fixture provider is not a filesystem provider, which is
     * the same shape a cloud provider has. So it lands in the fallback.
     */
    @Test
    fun aPickedFileLandsInTheFallbackFolder() {
        library.importPicked(listOf(picked("a.pdf")))
        val node = library.nodes().single()
        assertThat(node.name).isEqualTo(context.getString(R.string.imported_files))
        assertThat(library.refsFor(node).map { it.name }).containsExactly("a.pdf")
    }

    @Test
    fun aSecondImportFromTheSamePlaceReusesTheFolder() {
        library.importPicked(listOf(picked("a.pdf")))
        library.importPicked(listOf(picked("b.pdf")))
        assertThat(library.nodes()).hasSize(1)
        assertThat(library.refsFor(library.nodes().single()).map { it.name })
            .containsExactly("a.pdf", "b.pdf")
    }

    @Test
    fun importingTheSameFileTwiceDoesNotDuplicateIt() {
        library.importPicked(listOf(picked("a.pdf")))
        library.importPicked(listOf(picked("a.pdf")))
        assertThat(library.refsFor(library.nodes().single())).hasSize(1)
    }

    @Test
    fun aPickedFileCarriesTheProvidersDescriptionOfIt() {
        library.importPicked(listOf(picked("a.pdf")))
        val ref = library.refsFor(library.nodes().single()).single()
        assertThat(ref.ext).isEqualTo("pdf")
        assertThat(ref.origin).isEqualTo(RefOrigin.PICK)
        assertThat(ref.size).isEqualTo(16)
    }

    // ---------------------------------------------------------------
    // Membership
    // ---------------------------------------------------------------

    @Test
    fun aTreeFileCanBeFiledInSeveralMadeUpFolders() {
        val tree = library.importTree(
            grantedTree(ChildDoc("a", "a.pdf", "application/pdf", 1, 1))
        )
        library.ensureScanned(tree)
        val ref = library.refsFor(library.node(tree)!!).single()
        val one = library.createVirtualFolder("One")
        val two = library.createVirtualFolder("Two")

        library.moveRef(ref.id, one)
        library.moveRef(ref.id, two)

        assertThat(library.refsFor(library.node(one)!!).map { it.id }).containsExactly(ref.id)
        assertThat(library.refsFor(library.node(two)!!).map { it.id }).containsExactly(ref.id)
        // And it is still in the folder it came from: membership adds a place, it does not move one.
        assertThat(library.refsFor(library.node(tree)!!).map { it.id }).containsExactly(ref.id)
    }

    @Test
    fun removingARefTakesItOutOfEveryFolder() {
        val one = library.createVirtualFolder("One")
        val two = library.createVirtualFolder("Two")
        val refId = library.importPicked(listOf(picked("a.pdf"))).single()
        library.moveRef(refId, one)
        library.moveRef(refId, two)

        library.removeRef(refId)

        assertThat(library.refsFor(library.node(one)!!)).isEmpty()
        assertThat(library.refsFor(library.node(two)!!)).isEmpty()
        assertThat(library.ref(refId)).isNull()
    }

    // ---------------------------------------------------------------
    // Deleting
    // ---------------------------------------------------------------

    /** A tree file is not Gander's to delete, so it stays where it came from. */
    @Test
    fun deletingAMadeUpFolderKeepsTheTreeFile() {
        val tree = library.importTree(
            grantedTree(ChildDoc("a", "a.pdf", "application/pdf", 1, 1))
        )
        library.ensureScanned(tree)
        val ref = library.refsFor(library.node(tree)!!).single()
        val made = library.createVirtualFolder("One")
        library.moveRef(ref.id, made)

        library.deleteNode(made)

        assertThat(library.node(made)).isNull()
        assertThat(library.ref(ref.id)).isNotNull()
        assertThat(library.refsFor(library.node(tree)!!).map { it.id }).containsExactly(ref.id)
    }

    /**
     * A picked file left in no folder at all is an invisible grant: the only way back to it
     * is the system picker, so it goes with the last folder it was in.
     */
    @Test
    fun deletingTheLastFolderHoldingAPickedFileTakesItAndItsGrant() {
        val uri = picked("a.pdf")
        val refId = library.importPicked(listOf(uri)).single()
        val node = library.nodes().single()

        library.deleteNode(node.id)

        assertThat(library.ref(refId)).isNull()
        assertThat(context.contentResolver.persistedUriPermissions.map { it.uri }).doesNotContain(uri)
    }

    @Test
    fun deletingAnImportedFolderGivesBackTheGrantAndItsFiles() {
        val tree = grantedTree(ChildDoc("a", "a.pdf", "application/pdf", 1, 1))
        val id = library.importTree(tree)
        library.ensureScanned(id)
        val refId = library.refsFor(library.node(id)!!).single().id

        library.deleteNode(id)

        assertThat(library.node(id)).isNull()
        assertThat(library.ref(refId)).isNull()
        assertThat(context.contentResolver.persistedUriPermissions.map { it.uri }).doesNotContain(tree)
    }

    @Test
    fun aTreeFileFiledInAMadeUpFolderGoesWhenTheTreeDoes() {
        val tree = library.importTree(
            grantedTree(ChildDoc("a", "a.pdf", "application/pdf", 1, 1))
        )
        library.ensureScanned(tree)
        val ref = library.refsFor(library.node(tree)!!).single()
        val made = library.createVirtualFolder("One")
        library.moveRef(ref.id, made)

        library.deleteNode(tree)

        assertThat(library.refsFor(library.node(made)!!)).isEmpty()
        assertThat(library.ref(ref.id)).isNull()
    }

    // ---------------------------------------------------------------
    // Rescanning
    // ---------------------------------------------------------------

    @Test
    fun aRescanPicksUpANewFileAndDropsOneThatHasGone() {
        val id = library.importTree(
            grantedTree(ChildDoc("a", "a.pdf", "application/pdf", 1, 1))
        )
        library.refresh(RefreshReason.PULL)
        shadowOf(Looper.getMainLooper()).idle()

        FakeDocumentsProvider.install()
            .folder("root", "Docs", ChildDoc("b", "b.pdf", "application/pdf", 2, 2))
        library.refresh(RefreshReason.PULL)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(library.refsFor(library.node(id)!!).map { it.name }).containsExactly("b.pdf")
    }

    /**
     * A file whose size and modification time have not moved is left alone, which is what
     * keeps a rescan of an untouched library down to a read. The cover is the visible proof:
     * a row that was rewritten would have lost it.
     */
    @Test
    fun anUnchangedFileIsNotRewritten() {
        val id = library.importTree(
            grantedTree(ChildDoc("a", "a.pdf", "application/pdf", 1, 1))
        )
        library.ensureScanned(id)
        val ref = library.refsFor(library.node(id)!!).single()
        library.markCover(ref.id, Covers.READY, "a-cover-key")

        library.refresh(RefreshReason.PULL)
        shadowOf(Looper.getMainLooper()).idle()

        val after = library.ref(library.refsFor(library.node(id)!!).single().id)!!
        assertThat(after.coverState).isEqualTo(Covers.READY)
        assertThat(after.coverKey).isEqualTo("a-cover-key")
    }

    /** A file that was rewritten has a cover of something that is no longer there. */
    @Test
    fun aChangedFileHasItsCoverDropped() {
        val id = library.importTree(
            grantedTree(ChildDoc("a", "a.pdf", "application/pdf", 1, 1))
        )
        library.ensureScanned(id)
        val ref = library.refsFor(library.node(id)!!).single()
        library.markCover(ref.id, Covers.READY, "a-cover-key")

        FakeDocumentsProvider.install()
            .folder("root", "Docs", ChildDoc("a", "a.pdf", "application/pdf", 99, 99))
        library.refresh(RefreshReason.PULL)
        shadowOf(Looper.getMainLooper()).idle()

        val after = library.ref(library.refsFor(library.node(id)!!).single().id)!!
        assertThat(after.coverState).isEqualTo(Covers.NONE)
        assertThat(after.coverKey).isNull()
        assertThat(after.size).isEqualTo(99)
    }

    // ---------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------

    @Test
    fun searchMatchesAnyPartOfANameWithoutCase() {
        library.importPicked(listOf(picked("Quarterly Report.pdf"), picked("notes.txt")))
        assertThat(library.search("report").map { it.name }).containsExactly("Quarterly Report.pdf")
        assertThat(library.search("QUARTERLY").map { it.name }).containsExactly("Quarterly Report.pdf")
    }

    @Test
    fun searchIsEmptyForAnEmptyQuery() {
        library.importPicked(listOf(picked("a.pdf")))
        assertThat(library.search("  ")).isEmpty()
    }

    /**
     * A name with a wildcard in it is a name, not a pattern.
     *
     * The percent sign has to survive two things on the way in. The URI, where it is an escape
     * character rather than a name — which is why [FixtureProvider.add] encodes it — and the
     * LIKE pattern, where it is a wildcard rather than a name, which is what this asserts.
     */
    @Test
    fun searchTreatsWildcardsAsText() {
        library.importPicked(listOf(picked("100% done.pdf"), picked("notes.txt")))
        assertThat(library.search("100%").map { it.name }).containsExactly("100% done.pdf")
        // And the underscore, the other one. "_" would match any character, so a search for it
        // would otherwise return every file whose name has one character where the underscore
        // is — axb.pdf as much as a_b.pdf.
        library.importPicked(listOf(picked("a_b.pdf"), picked("axb.pdf")))
        assertThat(library.search("a_b").map { it.name }).containsExactly("a_b.pdf")
    }

    /** Only the index is searched: a folder nobody has opened holds nothing to find. */
    @Test
    fun searchCannotSeeInsideAFolderThatHasNotBeenOpened() {
        library.importTree(grantedTree(ChildDoc("a", "a.pdf", "application/pdf", 1, 1)))
        assertThat(library.search("a.pdf")).isEmpty()
    }
}

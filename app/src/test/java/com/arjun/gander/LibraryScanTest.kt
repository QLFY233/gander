package com.arjun.gander

import android.content.ContentResolver
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reading a granted tree into the flat list the index keeps.
 *
 * The two things worth pinning are what "flat" means — every level walked, no directory
 * coming back as an entry of its own — and that the ceiling stops the walk rather than
 * failing it.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScanTest {

    private lateinit var resolver: ContentResolver
    private lateinit var provider: FakeDocumentsProvider

    @Before
    fun setUp() {
        resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        provider = FakeDocumentsProvider.install()
    }

    private fun scan(max: Int = LibraryScan.MAX_FILES) =
        LibraryScan.scan(resolver, FakeDocumentsProvider.treeUri("root"), max)

    @Test
    fun everyLevelIsFlattenedAndOnlyFilesComeBack() {
        provider
            .folder(
                "root", "Docs",
                ChildDoc("a", "a.pdf", "application/pdf", 10, 100),
                ChildDoc("sub", "sub", MIME_DIR, 0, 200)
            )
            .folder(
                "sub", "sub",
                ChildDoc("b", "b.txt", "text/plain", 20, 300),
                ChildDoc("deep", "deep", MIME_DIR, 0, 400)
            )
            .folder("deep", "deep", ChildDoc("c", "c.md", "text/markdown", 30, 500))

        assertThat(scan().files.map { it.name }).containsExactly("a.pdf", "b.txt", "c.md")
    }

    /** The child directory is a route to more files, never an entry of its own. */
    @Test
    fun aSubdirectoryIsNotAnEntry() {
        provider
            .folder("root", "Docs", ChildDoc("sub", "sub", MIME_DIR, 0, 0))
            .folder("sub", "sub", ChildDoc("a", "a.pdf", "application/pdf", 1, 1))
        assertThat(scan().files.map { it.name }).containsExactly("a.pdf")
    }

    @Test
    fun dotfilesAreLeftOut() {
        provider.folder(
            "root", "Docs",
            ChildDoc("a", "a.pdf", "application/pdf", 10, 100),
            ChildDoc("h", ".hidden.pdf", "application/pdf", 10, 100),
            ChildDoc("d", ".git", MIME_DIR, 0, 0)
        )
        assertThat(scan().files.map { it.name }).containsExactly("a.pdf")
    }

    @Test
    fun theProviderDescribesEachFile() {
        provider.folder("root", "Docs", ChildDoc("a", "a.pdf", "application/pdf", 1234, 5678))
        val found = scan().files.single()
        assertThat(found.ext).isEqualTo("pdf")
        assertThat(found.mime).isEqualTo("application/pdf")
        assertThat(found.size).isEqualTo(1234)
        assertThat(found.mtime).isEqualTo(5678)
    }

    /** The URI is derived from the tree, because that is the only one a tree file can have. */
    @Test
    fun eachFileIsAddressableThroughTheTree() {
        provider.folder("root", "Docs", ChildDoc("a", "a.pdf", "application/pdf", 1, 1))
        val found = scan().files.single()
        assertThat(found.uri).contains(FakeDocumentsProvider.AUTHORITY)
        assertThat(found.uri).contains("a")
    }

    @Test
    fun theCeilingStopsTheWalkAndSaysSo() {
        provider.folder(
            "root", "Docs",
            ChildDoc("a", "a.pdf", "application/pdf", 1, 1),
            ChildDoc("b", "b.pdf", "application/pdf", 1, 1),
            ChildDoc("c", "c.pdf", "application/pdf", 1, 1)
        )
        val result = scan(max = 2)
        assertThat(result.files).hasSize(2)
        assertThat(result.truncated).isTrue()
    }

    @Test
    fun aTreeThatHoldsNothingReadsAsEmptyRatherThanTruncated() {
        provider.folder("root", "Docs")
        val result = scan()
        assertThat(result.files).isEmpty()
        assertThat(result.truncated).isFalse()
    }

    /**
     * A provider that answers a directory with itself would otherwise walk for ever. The
     * seen-set is the only guard there is, so it is worth a test.
     */
    @Test
    fun aTreeThatLoopsBackOnItselfTerminates() {
        provider
            .folder("root", "Docs", ChildDoc("sub", "sub", MIME_DIR, 0, 0))
            .folder("sub", "sub", ChildDoc("root", "root", MIME_DIR, 0, 0))
        assertThat(scan().files).isEmpty()
    }

    /** A tree whose document id no provider knows is empty, not an error. */
    @Test
    fun anUnknownTreeReadsAsEmpty() {
        val result = LibraryScan.scan(resolver, FakeDocumentsProvider.treeUri("nowhere"))
        assertThat(result.files).isEmpty()
    }
}

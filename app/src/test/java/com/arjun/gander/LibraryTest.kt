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
 * The reading history, and the index's first run.
 *
 * This replaces the old `RecentsTest`, and the difference between the two is the whole
 * point of the change: the old list was filtered by Android's record of which URIs the app
 * still held a read grant for, which meant a file opened from inside a granted folder could
 * never appear in it. The question asked now is whether the file can be opened, which is
 * both cheaper to explain and true for the way a reader actually opens things.
 */
@RunWith(AndroidJUnit4::class)
class LibraryTest {

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

    /**
     * A URI that really opens.
     *
     * The history asks whether a file can be opened rather than whether a grant is held, so
     * a test that wants an entry to survive has to hand over something a read will succeed
     * on. `sized` makes them on the spot, which is cheaper than committing fifteen fixtures
     * to test a cap of ten.
     */
    private fun fixture(name: String): Uri = provider.add(name, Fixtures.sized(name, 16))

    private fun opened(name: String) {
        library.recordOpened(fixture(name), name)
    }

    /** A URI nothing answers, which is what a file whose grant has gone looks like. */
    private fun missing(name: String): Uri =
        "content://${FixtureProvider.AUTHORITY}/$name".toUri()

    private fun names() = library.recents().map { it.name }

    // ---------------------------------------------------------------
    // The history
    // ---------------------------------------------------------------

    @Test
    fun aFreshInstallHasNothing() {
        assertThat(library.isEmpty()).isTrue()
        assertThat(library.recents()).isEmpty()
        assertThat(library.nodes()).isEmpty()
    }

    @Test
    fun theNewestOpenedFileComesFirst() {
        opened("a.pdf")
        opened("b.pdf")
        opened("c.pdf")
        assertThat(names()).containsExactly("c.pdf", "b.pdf", "a.pdf").inOrder()
    }

    @Test
    fun reopeningAFileMovesItUpWithoutDuplicating() {
        val uri = fixture("a.pdf")
        library.recordOpened(uri, "a.pdf")
        opened("b.pdf")
        library.recordOpened(uri, "a.pdf")
        assertThat(names()).containsExactly("a.pdf", "b.pdf").inOrder()
    }

    /** The name is refreshed on the way, in case the provider renamed the file. */
    @Test
    fun reopeningPicksUpANewDisplayName() {
        val uri = fixture("a.pdf")
        library.recordOpened(uri, "Old name")
        library.recordOpened(uri, "New name")
        assertThat(names()).containsExactly("New name")
    }

    @Test
    fun theHistoryStopsAtTen() {
        (1..15).forEach { opened("f$it.pdf") }
        val all = library.recents()
        assertThat(all).hasSize(Library.MAX_OPENED)
        assertThat(all.first().name).isEqualTo("f15.pdf")
        assertThat(all.last().name).isEqualTo("f6.pdf")
    }

    @Test
    fun forgettingAnEntryTakesItOut() {
        opened("a.pdf")
        opened("b.pdf")
        library.forgetOpened(fixture("a.pdf").toString())
        assertThat(names()).containsExactly("b.pdf")
    }

    /**
     * A file that cannot be opened is hidden rather than deleted, so a grant that comes back
     * brings the entry back with it. This is what the old list did with a lost grant, and the
     * behaviour is worth keeping: the alternative is losing the history every time a provider
     * is briefly unavailable.
     */
    @Test
    fun anEntryThatCannotBeOpenedIsHidden() {
        opened("a.pdf")
        library.recordOpened(missing("gone.pdf"), "Gone")
        assertThat(names()).containsExactly("a.pdf")
    }

    @Test
    fun aHiddenEntryReturnsWhenTheFileDoes() {
        library.recordOpened(missing("later.pdf"), "Later")
        assertThat(names()).isEmpty()
        library.recordOpened(fixture("later.pdf"), "Later")
        assertThat(names()).containsExactly("Later")
    }

    /** A history entry that is not a document URI at all is simply not shown. */
    @Test
    fun aUriThatIsNotADocumentIsHidden() {
        library.recordOpened(Uri.parse("not-a-uri"), "Nothing")
        assertThat(names()).isEmpty()
    }

    // ---------------------------------------------------------------
    // The migration
    // ---------------------------------------------------------------

    /**
     * The list the previous build kept in SharedPreferences is carried into `opened`.
     *
     * It goes to `opened` and not to `ref`, because those entries were "opened" rather than
     * "filed", and the database is not opened until the first read, which is what leaves a
     * window for a test to write the old store first.
     */
    @Test
    fun theOldRecentsListIsCarriedOver() {
        val a = fixture("old-a.pdf")
        val b = fixture("old-b.pdf")
        context.getSharedPreferences("recents", Context.MODE_PRIVATE).edit()
            .putString(
                "items",
                """[{"uri":"$a","name":"a.pdf","time":2000},""" +
                    """{"uri":"$b","name":"b.pdf","time":1000}]"""
            )
            .commit()

        assertThat(names()).containsExactly("a.pdf", "b.pdf").inOrder()
        assertThat(library.isEmpty()).isFalse()
    }

    @Test
    fun aCorruptOldStoreIsIgnoredRatherThanThrowing() {
        context.getSharedPreferences("recents", Context.MODE_PRIVATE).edit()
            .putString("items", "not json at all")
            .commit()
        assertThat(library.recents()).isEmpty()
        assertThat(library.isEmpty()).isTrue()
    }

    // ---------------------------------------------------------------
    // Refreshing
    // ---------------------------------------------------------------

    private fun recording(): Pair<MutableList<RefreshState>, LibraryListener> {
        val states = mutableListOf<RefreshState>()
        val listener = object : LibraryListener {
            override fun onLibraryChanged(nodes: Set<Long>?) = Unit
            override fun onRefresh(state: RefreshState) {
                states += state
            }
        }
        library.addListener(listener)
        return states to listener
    }

    private fun importedFolder(vararg children: ChildDoc): Long {
        FakeDocumentsProvider.install().folder("root", "Docs", *children)
        return library.importTree(FakeDocumentsProvider.treeUri("root"))
    }

    @Test
    fun aRefreshFillsInAnImportedFolder() {
        val id = importedFolder(
            ChildDoc("a", "a.pdf", "application/pdf", 10, 100),
            ChildDoc("b", "b.txt", "text/plain", 20, 200)
        )
        val (states, listener) = recording()

        library.refresh(RefreshReason.PULL)
        shadowOf(Looper.getMainLooper()).idle()

        val node = library.node(id)!!
        assertThat(library.refsFor(node).map { it.name }).containsExactly("a.pdf", "b.txt")
        assertThat(node.scannedAt).isNotNull()
        assertThat(states.last().running).isFalse()
        assertThat(states.any { it.running }).isTrue()
        library.removeListener(listener)
    }

    /**
     * Opening and closing the app ten times is not ten reasons to walk a photo library, so a
     * cold start inside the quiet period is skipped. The stamp is in the database, which is
     * what makes it survive the process that wrote it.
     */
    @Test
    fun aSecondColdStartInsideTheQuietPeriodIsSkipped() {
        importedFolder(ChildDoc("a", "a.pdf", "application/pdf", 10, 100))
        val (states, listener) = recording()

        library.refresh(RefreshReason.COLD_START)
        shadowOf(Looper.getMainLooper()).idle()
        val afterFirst = states.size

        library.refresh(RefreshReason.COLD_START)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(states.size).isEqualTo(afterFirst)

        // A pull is never throttled: the reader asked for it.
        library.refresh(RefreshReason.PULL)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(states.size).isGreaterThan(afterFirst)
        library.removeListener(listener)
    }

    @Test
    fun cancellingSaysTheRefreshIsNotRunning() {
        importedFolder(ChildDoc("a", "a.pdf", "application/pdf", 10, 100))
        library.refresh(RefreshReason.PULL)
        shadowOf(Looper.getMainLooper()).idle()

        library.cancelRefresh()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(library.currentRefreshState().running).isFalse()
    }

    @Test
    fun aListenerThatHasGoneIsDroppedRatherThanKeptAlive() {
        var listener: LibraryListener? = object : LibraryListener {
            override fun onLibraryChanged(nodes: Set<Long>?) = Unit
            override fun onRefresh(state: RefreshState) = Unit
        }
        library.addListener(listener!!)
        listener = null
        System.gc()

        // The assertion is that publishing after the only strong reference is gone neither
        // throws nor resurrects it; a weak reference cannot be observed directly.
        library.refresh(RefreshReason.PULL)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(library.currentRefreshState().running).isFalse()
    }
}

package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The home screen: the sidebar, the content column, and the first-run explainer.
 *
 * The screen reads its index and its folders on a background thread, which would make every
 * assertion here a race. Both executors are replaced with ones that run inline — the screen's
 * own loader and the library's refresh thread — so a test asserts on a screen that has finished
 * drawing.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    private lateinit var context: Context
    private lateinit var provider: FixtureProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = FixtureProvider.install()
        Thumbs.resetForTests()
        Covers.resetForTests()
        // The library is the app's, and the app is a new one for every test, so the index starts
        // empty. Its refresh thread is not: a rescan is a background walk of every imported
        // folder, and a test wants it to have finished by the time it looks.
        (context as GanderApp).library.io = directExecutor()
        context.contentResolver.persistedUriPermissions.forEach {
            context.contentResolver.releasePersistableUriPermission(
                it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    /**
     * Lets any Undo bar run out. Material queues bars in one object for the whole process, and a
     * bar left up at the end of a test would hold the next test's bar back until it had gone.
     */
    @After
    fun tearDown() {
        shadowOf(context.mainLooper).idleFor(Duration.ofSeconds(10))
    }

    private val library: Library get() = (context as GanderApp).library

    private fun home(state: Bundle? = null): ActivityController<MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().loader = directExecutor()
        return controller.create(state).start().resume().visible().also {
            shadowOf(context.mainLooper).idle()
        }
    }

    private fun ActivityController<MainActivity>.toolbar(): MaterialToolbar =
        get().findViewById(R.id.toolbar)

    private fun ActivityController<MainActivity>.sidebar(): View = get().findViewById(R.id.sidebar)
    private fun ActivityController<MainActivity>.scrim(): View = get().findViewById(R.id.scrim)
    private fun ActivityController<MainActivity>.welcome(): View = get().findViewById(R.id.welcome)
    private fun ActivityController<MainActivity>.grid(): RecyclerView =
        get().findViewById(R.id.contentList)

    private fun ActivityController<MainActivity>.sidebarList(): RecyclerView =
        get().findViewById(R.id.sidebarList)

    /** Every bound row's text under [id], in order, whichever adapter is asked. */
    private fun texts(list: RecyclerView, id: Int): List<String> =
        (0 until list.adapter!!.itemCount).mapNotNull { position ->
            val adapter = list.adapter!!
            val holder = adapter.createViewHolder(list, adapter.getItemViewType(position))
            adapter.bindViewHolder(holder, position)
            holder.itemView.findViewById<TextView>(id)?.text?.toString()
        }

    private fun ActivityController<MainActivity>.sectionTitles() =
        texts(grid(), R.id.sectionTitle)

    private fun ActivityController<MainActivity>.cardTitles() = texts(grid(), R.id.cardTitle)

    private fun ActivityController<MainActivity>.sidebarTitles() =
        texts(sidebarList(), R.id.sidebarTitle)

    private fun ActivityController<MainActivity>.sidebarHeaders() =
        texts(sidebarList(), R.id.sidebarHeaderText)

    private fun ActivityController<MainActivity>.hints() = texts(grid(), R.id.hintText)

    /** Clicks the row whose [id] text is [title], in [list]. */
    private fun clickRow(list: RecyclerView, id: Int, title: String) {
        val adapter = list.adapter!!
        val position = (0 until adapter.itemCount).first { position ->
            val holder = adapter.createViewHolder(list, adapter.getItemViewType(position))
            adapter.bindViewHolder(holder, position)
            holder.itemView.findViewById<TextView>(id)?.text?.toString() == title
        }
        val holder = adapter.createViewHolder(list, adapter.getItemViewType(position))
        adapter.bindViewHolder(holder, position)
        holder.itemView.performClick()
        shadowOf(context.mainLooper).idle()
    }

    private fun ActivityController<MainActivity>.clickCard(title: String) =
        clickRow(grid(), R.id.cardTitle, title)

    private fun ActivityController<MainActivity>.clickSection(title: String) =
        clickRow(grid(), R.id.sectionTitle, title)

    /** A granted folder holding one subfolder and four files, one of them hidden. */
    private fun grantedFolder(name: String = "Documents"): Uri {
        val id = "root-${name.lowercase()}"
        FakeDocumentsProvider.install().folder(
            id,
            name,
            ChildDoc("sub", "Leases", MIME_DIR, 0, 0),
            ChildDoc("f1", "zeta.txt", "text/plain", 2048, 0),
            ChildDoc("f2", "alpha.txt", "text/plain", 1024, 0),
            ChildDoc("f3", ".hidden.txt", "text/plain", 512, 0),
            ChildDoc("f4", "mid.txt", "text/plain", 1536, 0)
        )
        return FakeDocumentsProvider.treeUri(id)
    }

    /** Imports a granted folder into the library, which is what a granted folder now is. */
    private fun imported(name: String = "Documents"): Long = library.importTree(grantedFolder(name))

    /** A file from outside Gander, picked rather than granted as a tree. */
    private fun picked(name: String): Uri {
        val uri = provider.add(name, Fixtures.sized(name, 16))
        library.importPicked(listOf(uri))
        return uri
    }

    private fun latestDialog(): AlertDialog? = ShadowDialog.getLatestDialog() as? AlertDialog

    /**
     * A dialog's title, which AlertDialog has no getter for.
     *
     * The id is AppCompat's own, which is what the dialog's own layout gives its title view.
     */
    private fun AlertDialog.title(): String =
        findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()

    private fun AlertDialog.titles(): List<String> =
        (0 until listView.adapter.count).map { listView.adapter.getItem(it).toString() }

    private fun answer(which: Int) {
        latestDialog()!!.getButton(which).performClick()
        shadowOf(context.mainLooper).idle()
    }

    private fun persistedUris(): List<String> =
        context.contentResolver.persistedUriPermissions.map { it.uri.toString() }

    private fun ActivityController<MainActivity>.undo(): View? =
        get().findViewById(com.google.android.material.R.id.snackbar_action)

    // ---------------------------------------------------------------
    // First run
    // ---------------------------------------------------------------

    @Test
    fun aFirstRunShowsTheExplainerInsteadOfAnEmptyGrid() {
        val controller = home()
        assertThat(controller.welcome().visibility).isEqualTo(View.VISIBLE)
        assertThat(controller.grid().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun theExplainerShowsEveryKindGanderOpens() {
        val grid = home().get().findViewById<ViewGroup>(R.id.formatGrid)
        assertThat(grid.childCount).isEqualTo(WELCOME_BADGES.size)
        val labels = (0 until grid.childCount)
            .map { (grid.getChildAt(it) as TextView).text.toString() }
        assertThat(labels).containsExactlyElementsIn(WELCOME_BADGES.map { it.first }).inOrder()
    }

    /** TalkBack reads one sentence for the grid rather than walking nine meaningless tiles. */
    @Test
    fun theExplainerGridIsOneStopForAScreenReader() {
        val grid = home().get().findViewById<ViewGroup>(R.id.formatGrid)
        assertThat(grid.contentDescription.toString())
            .isEqualTo(context.getString(R.string.welcome_formats_spoken))
    }

    @Test
    fun theExplainerGoesOnceAFolderHasBeenImported() {
        imported()
        val controller = home()
        assertThat(controller.welcome().visibility).isEqualTo(View.GONE)
        assertThat(controller.grid().visibility).isEqualTo(View.VISIBLE)
    }

    // ---------------------------------------------------------------
    // The two columns
    // ---------------------------------------------------------------

    @Test
    fun aFolderBecomesASectionAndItsFilesBecomeCards() {
        imported()
        val controller = home()

        assertThat(controller.sectionTitles()).containsExactly("Documents")
        assertThat(controller.cardTitles())
            .containsExactly("zeta.txt", "alpha.txt", "mid.txt")
    }

    /** A granted tree is flattened: a subdirectory is a route to files, never a card. */
    @Test
    fun aSubdirectoryIsNotASectionOrACard() {
        imported()
        val controller = home()
        assertThat(controller.cardTitles()).doesNotContain("Leases")
        assertThat(controller.sectionTitles()).doesNotContain("Leases")
    }

    @Test
    fun dotfilesAreNotShown() {
        imported()
        assertThat(home().cardTitles()).doesNotContain(".hidden.txt")
    }

    @Test
    fun theSidebarListsTheFolderToo() {
        imported()
        val controller = home()
        assertThat(controller.sidebarHeaders())
            .containsExactly(
                context.getString(R.string.recent_files),
                context.getString(R.string.folders)
            ).inOrder()
        assertThat(controller.sidebarTitles()).contains("Documents")
    }

    /** The reading history is its own list, and it is not the index. */
    @Test
    fun anOpenedFileAppearsInTheSidebarHistory() {
        val uri = picked("Alder Court.pdf")
        library.recordOpened(uri, "Alder Court.pdf")
        assertThat(home().sidebarTitles()).contains("Alder Court.pdf")
    }

    // ---------------------------------------------------------------
    // The sidebar's two arrangements
    // ---------------------------------------------------------------

    @Test
    fun aPhoneStartsWithTheSidebarAway() {
        val controller = home()
        assertThat(controller.sidebar().visibility).isEqualTo(View.GONE)
        assertThat(controller.scrim().visibility).isEqualTo(View.GONE)
        // And the way to open it is at the left of the content column's title bar, because a
        // button that closes a panel is useless underneath it.
        assertThat(controller.toolbar().navigationIcon).isNotNull()
    }

    @Test
    @Config(qualifiers = "sw600dp-land")
    fun aLandscapeTabletStartsWithTheSidebarOut() {
        val controller = home()
        assertThat(controller.sidebar().visibility).isEqualTo(View.VISIBLE)
        // Side by side, so there is nothing to dim.
        assertThat(controller.scrim().visibility).isEqualTo(View.GONE)
        assertThat(controller.toolbar().navigationIcon).isNull()
    }

    @Test
    fun theToggleOpensAndClosesTheFloatingSidebar() {
        val controller = home()

        controller.get().toggleSidebar()
        shadowOf(context.mainLooper).idle()
        assertThat(controller.sidebar().visibility).isEqualTo(View.VISIBLE)
        assertThat(controller.scrim().visibility).isEqualTo(View.VISIBLE)
        // While it is out the icon would close the panel it sits under, so it goes.
        assertThat(controller.toolbar().navigationIcon).isNull()

        controller.get().toggleSidebar()
        shadowOf(context.mainLooper).idle()
        assertThat(controller.sidebar().visibility).isEqualTo(View.GONE)
        assertThat(controller.scrim().visibility).isEqualTo(View.GONE)
        assertThat(controller.toolbar().navigationIcon).isNotNull()
    }

    /** A tap on the dimmed content column puts the sidebar away, as does the button in it. */
    @Test
    fun theScrimAndTheSidebarsOwnButtonBothCloseIt() {
        val controller = home()
        controller.get().toggleSidebar()
        controller.scrim().performClick()
        shadowOf(context.mainLooper).idle()
        assertThat(controller.sidebar().visibility).isEqualTo(View.GONE)

        controller.get().toggleSidebar()
        controller.get().findViewById<View>(R.id.sidebarToggle).performClick()
        shadowOf(context.mainLooper).idle()
        assertThat(controller.sidebar().visibility).isEqualTo(View.GONE)
    }

    /** What the reader answered is remembered, so the next screen starts where they left it. */
    @Test
    fun theSidebarsStateIsRemembered() {
        home().get().toggleSidebar()
        assertThat(Settings.sidebarOpen(context)).isTrue()
        assertThat(home().sidebar().visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun theBackGestureClosesAFloatingSidebar() {
        val controller = home()
        controller.get().toggleSidebar()
        assertThat(controller.sidebar().visibility).isEqualTo(View.VISIBLE)

        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(context.mainLooper).idle()

        assertThat(controller.sidebar().visibility).isEqualTo(View.GONE)
        assertThat(controller.get().isFinishing).isFalse()
    }

    @Test
    @Config(qualifiers = "sw600dp-land")
    fun theBackGestureLeavesAFixedSidebarAlone() {
        val controller = home()
        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(context.mainLooper).idle()
        assertThat(controller.sidebar().visibility).isEqualTo(View.VISIBLE)
    }

    // ---------------------------------------------------------------
    // Sections
    // ---------------------------------------------------------------

    @Test
    fun theFirstSectionOpensAndTheRestFold() {
        imported("First")
        imported("Second")
        val controller = home()

        assertThat(controller.sectionTitles()).containsExactly("First", "Second").inOrder()
        // Only the first folder's files are cards: the rest are one row each until asked for.
        assertThat(controller.cardTitles()).containsExactly("zeta.txt", "alpha.txt", "mid.txt")
    }

    @Test
    fun tappingASectionHeadingFoldsItAndRemembers() {
        val id = imported()
        val controller = home()

        controller.clickSection("Documents")
        assertThat(controller.cardTitles()).isEmpty()

        // A new screen draws it folded, because the reader said so.
        val second = home()
        assertThat(second.sectionTitles()).containsExactly("Documents")
        assertThat(second.cardTitles()).isEmpty()
        assertThat(Settings.sectionCollapsed(context, "node.$id", true)).isTrue()
    }

    @Test
    fun tappingACardOpensItInTheViewer() {
        imported()
        val controller = home()

        controller.clickCard("zeta.txt")

        val started = shadowOf(controller.get()).nextStartedActivity
        assertThat(started.component!!.className).isEqualTo(ViewerActivity.INTERNAL_VIEWER)
        assertThat(started.data.toString()).contains("f1")
        assertThat(started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    }

    /**
     * A back swipe shows this screen as it was last drawn on its way out, so a tap still fading
     * when a document opened came back lit. The press has to be gone by the pause.
     */
    @Test
    fun aTapHighlightIsGoneBeforeTheScreenLeaves() {
        imported()
        val controller = home()
        val list = controller.grid()
        val rows = (0 until list.childCount).map { list.getChildAt(it) }.filter { it.isClickable }
        assertThat(rows).isNotEmpty()
        rows.forEach { it.isPressed = true }

        controller.pause()

        assertThat(rows.filter { it.isPressed }).isEmpty()
    }

    // ---------------------------------------------------------------
    // Sorting and filtering
    // ---------------------------------------------------------------

    @Test
    fun sortAppliesToEverySection() {
        imported()
        val controller = home()
        controller.toolbar().menu.performIdentifierAction(R.id.action_sort, 0)
        shadowOf(context.mainLooper).idle()

        val which = Settings.SortOrder.LARGEST.ordinal
        latestDialog()!!.listView.performItemClick(null, which, which.toLong())
        shadowOf(context.mainLooper).idle()

        assertThat(Settings.sortOrder(context)).isEqualTo(Settings.SortOrder.LARGEST)
        // zeta is 2048 bytes, mid 1536, alpha 1024.
        assertThat(controller.cardTitles())
            .containsExactly("zeta.txt", "mid.txt", "alpha.txt").inOrder()
    }

    @Test
    fun filterHidesWhatDoesNotMatchAndSaysSoInTheSubtitle() {
        imported()
        val controller = home()
        controller.toolbar().menu.performIdentifierAction(R.id.action_filter, 0)
        shadowOf(context.mainLooper).idle()

        val dialog = latestDialog()!!
        val container = dialog.findViewById<ViewGroup>(R.id.filterItemsContainer)!!
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i)
            if (row.findViewById<TextView>(R.id.filterBadge).text == "TXT") row.performClick()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(context.mainLooper).idle()

        assertThat(controller.cardTitles()).hasSize(3)
        assertThat(controller.toolbar().subtitle?.toString())
            .isEqualTo(context.getString(R.string.filter_subtitle, "TXT"))
    }

    @Test
    fun filterDropsASectionThatHoldsNothingMatching() {
        imported()
        val controller = home()
        controller.toolbar().menu.performIdentifierAction(R.id.action_filter, 0)
        shadowOf(context.mainLooper).idle()

        val dialog = latestDialog()!!
        val container = dialog.findViewById<ViewGroup>(R.id.filterItemsContainer)!!
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i)
            if (row.findViewById<TextView>(R.id.filterBadge).text == "PDF") row.performClick()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(context.mainLooper).idle()

        // Nothing here is a PDF, so the folder's heading goes with its cards.
        assertThat(controller.sectionTitles()).isEmpty()
        assertThat(controller.cardTitles()).isEmpty()
    }

    @Test
    fun aFilterSurvivesARotation() {
        imported()
        val controller = home()
        controller.toolbar().menu.performIdentifierAction(R.id.action_filter, 0)
        shadowOf(context.mainLooper).idle()
        val dialog = latestDialog()!!
        val container = dialog.findViewById<ViewGroup>(R.id.filterItemsContainer)!!
        container.getChildAt(0).performClick()
        val badge = (container.getChildAt(0).findViewById<TextView>(R.id.filterBadge)).text.toString()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(context.mainLooper).idle()

        val state = Bundle()
        controller.saveInstanceState(state)
        assertThat(state.getStringArrayList("active_filters")).containsExactly(badge)

        val second = home(state)
        assertThat(second.toolbar().subtitle?.toString())
            .isEqualTo(context.getString(R.string.filter_subtitle, badge))
    }

    // ---------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------

    private fun ActivityController<MainActivity>.search(text: String) {
        val view = toolbar().menu.findItem(R.id.action_search).actionView as SearchView
        view.setQuery(text, false)
        shadowOf(context.mainLooper).idle()
    }

    @Test
    fun searchHidesTheCardsThatDoNotMatch() {
        imported()
        val controller = home()
        controller.search("zeta")
        assertThat(controller.cardTitles()).containsExactly("zeta.txt")
    }

    /**
     * A search that finds nothing says so, and says why: the index is a snapshot, so a file in a
     * folder nobody has opened is not there to be found. "Nothing found" without that would be a
     * lie of omission.
     */
    @Test
    fun anEmptySearchExplainsItself() {
        imported()
        val controller = home()
        controller.search("nothing like this")
        assertThat(controller.cardTitles()).isEmpty()
        assertThat(controller.hints()).contains(context.getString(R.string.search_not_scanned))
    }

    // ---------------------------------------------------------------
    // Refresh
    // ---------------------------------------------------------------

    @Test
    fun aPullRescansEveryImportedFolder() {
        val id = imported()
        val controller = home()

        FakeDocumentsProvider.install().folder(
            "root", "Documents", ChildDoc("n", "new.txt", "text/plain", 10, 10)
        )
        controller.get().pullToRefresh()
        shadowOf(context.mainLooper).idle()

        assertThat(controller.cardTitles()).containsExactly("new.txt")
        assertThat(library.node(id)!!.scannedAt).isNotNull()
    }

    // ---------------------------------------------------------------
    // Removal
    // ---------------------------------------------------------------

    private fun ActivityController<MainActivity>.longPressCard(title: String) {
        val list = grid()
        val adapter = list.adapter!!
        val position = (0 until adapter.itemCount).first { position ->
            val holder = adapter.createViewHolder(list, adapter.getItemViewType(position))
            adapter.bindViewHolder(holder, position)
            holder.itemView.findViewById<TextView>(R.id.cardTitle)?.text?.toString() == title
        }
        val holder = adapter.createViewHolder(list, adapter.getItemViewType(position))
        adapter.bindViewHolder(holder, position)
        holder.itemView.performLongClick()
        shadowOf(context.mainLooper).idle()
    }

    @Test
    fun aCardsMenuOffersTheFileActions() {
        imported()
        val controller = home()
        controller.longPressCard("zeta.txt")

        assertThat(latestDialog()!!.titles()).containsExactly(
            context.getString(R.string.open),
            context.getString(R.string.move_to),
            context.getString(R.string.remove_from_library),
            context.getString(R.string.share_file)
        ).inOrder()
    }

    /**
     * Removed, the card goes at once, but the file's grant stays until the Undo bar has gone,
     * since Android cannot give one back.
     */
    @Test
    fun removingAPickedFileKeepsItsGrantUntilTheUndoBarGoes() {
        val uri = picked("statement.txt")
        val controller = home()

        controller.longPressCard("statement.txt")
        latestDialog()!!.listView.performItemClick(null, 2, 2L)
        shadowOf(context.mainLooper).idle()
        answer(AlertDialog.BUTTON_POSITIVE)

        assertThat(controller.cardTitles()).doesNotContain("statement.txt")
        assertThat(controller.undo()?.isShown).isTrue()
        assertThat(persistedUris()).contains(uri.toString())

        shadowOf(context.mainLooper).idleFor(Duration.ofSeconds(6))

        assertThat(persistedUris()).doesNotContain(uri.toString())
        assertThat(library.search("statement")).isEmpty()
    }

    @Test
    fun undoPutsTheCardBackWithItsGrant() {
        val uri = picked("statement.txt")
        val controller = home()

        controller.longPressCard("statement.txt")
        latestDialog()!!.listView.performItemClick(null, 2, 2L)
        shadowOf(context.mainLooper).idle()
        answer(AlertDialog.BUTTON_POSITIVE)

        controller.undo()!!.performClick()
        shadowOf(context.mainLooper).idle()
        shadowOf(context.mainLooper).idleFor(Duration.ofSeconds(6))

        assertThat(controller.cardTitles()).contains("statement.txt")
        assertThat(persistedUris()).contains(uri.toString())
    }

    /** A bar nobody is looking at can no longer be answered, so the removal goes through. */
    @Test
    fun leavingTheScreenCarriesARemovalOut() {
        val uri = picked("statement.txt")
        val controller = home()

        controller.longPressCard("statement.txt")
        latestDialog()!!.listView.performItemClick(null, 2, 2L)
        shadowOf(context.mainLooper).idle()
        answer(AlertDialog.BUTTON_POSITIVE)

        controller.pause().stop()

        assertThat(persistedUris()).doesNotContain(uri.toString())
    }

    /**
     * Gander's own primary is a burnt red, so tinting only the destructive button left the two
     * within dE 4.6 of each other on the light palette: near enough to the 2.3 a person can
     * notice that the colour marked nothing and made Cancel look dangerous too. Both roles are
     * asserted, because the bug was the pair being alike rather than either one being wrong.
     */
    @Test
    fun theDestructiveButtonDoesNotLookLikeTheDismissiveOne() {
        picked("statement.txt")
        val controller = home()

        controller.longPressCard("statement.txt")
        latestDialog()!!.listView.performItemClick(null, 2, 2L)
        shadowOf(context.mainLooper).idle()
        val dialog = latestDialog()!!

        val remove = dialog.getButton(AlertDialog.BUTTON_POSITIVE).currentTextColor
        val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE).currentTextColor
        assertThat(remove).isEqualTo(ContextCompat.getColor(context, R.color.gander_error))
        assertThat(cancel)
            .isEqualTo(ContextCompat.getColor(context, R.color.gander_on_surface_variant))
        assertThat(remove).isNotEqualTo(cancel)
    }

    // ---------------------------------------------------------------
    // Deleting a folder
    // ---------------------------------------------------------------

    @Test
    fun deletingAnImportedFolderGivesBackTheGrant() {
        val tree = grantedFolder()
        library.importTree(tree)
        val controller = home()

        controller.longPressSection("Documents")

        assertThat(latestDialog()!!.titles()).containsExactly(
            context.getString(R.string.rename), context.getString(R.string.delete)
        ).inOrder()
        latestDialog()!!.listView.performItemClick(null, 1, 1L)
        shadowOf(context.mainLooper).idle()
        assertThat(latestDialog()!!.title())
            .isEqualTo(context.getString(R.string.remove_folder_title))
        answer(AlertDialog.BUTTON_POSITIVE)

        assertThat(persistedUris()).doesNotContain(tree.toString())
        assertThat(controller.sectionTitles()).isEmpty()
        assertThat(controller.welcome().visibility).isEqualTo(View.VISIBLE)
    }

    private fun ActivityController<MainActivity>.longPressSection(title: String) {
        val list = grid()
        val adapter = list.adapter!!
        val position = (0 until adapter.itemCount).first { position ->
            val holder = adapter.createViewHolder(list, adapter.getItemViewType(position))
            adapter.bindViewHolder(holder, position)
            holder.itemView.findViewById<TextView>(R.id.sectionTitle)?.text?.toString() == title
        }
        val holder = adapter.createViewHolder(list, adapter.getItemViewType(position))
        adapter.bindViewHolder(holder, position)
        holder.itemView.performLongClick()
        shadowOf(context.mainLooper).idle()
    }

    @Test
    fun renamingAFolderKeepsItsFilesAndItsColour() {
        val id = imported()
        val before = library.node(id)!!.colorIndex
        val controller = home()

        controller.longPressSection("Documents")
        latestDialog()!!.listView.performItemClick(null, 0, 0L)
        shadowOf(context.mainLooper).idle()
        val field = latestDialog()!!.findViewById<android.widget.EditText>(R.id.textField)!!
        field.setText("Leases")
        answer(AlertDialog.BUTTON_POSITIVE)

        assertThat(library.node(id)!!.name).isEqualTo("Leases")
        assertThat(library.node(id)!!.colorIndex).isEqualTo(before)
        assertThat(controller.sectionTitles()).containsExactly("Leases")
        assertThat(controller.cardTitles()).hasSize(3)
    }

    // ---------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------

    /**
     * The three points the design names, as arithmetic: a 360dp phone, an 800dp tablet held
     * upright, and the 964dp a 1280dp tablet has left beside a 300dp sidebar.
     */
    @Test
    fun theColumnCountFollowsTheRoomTheContentColumnGot() {
        assertThat(cardColumns(344, 168, 2, 6)).isEqualTo(2)
        assertThat(cardColumns(784, 168, 2, 6)).isEqualTo(4)
        assertThat(cardColumns(964, 168, 2, 6)).isEqualTo(5)
    }

    @Test
    fun aCardIsOneCellAndAHeadingIsTheWholeWidth() {
        imported()
        val controller = home()
        val manager = controller.grid().layoutManager as GridLayoutManager
        assertThat(manager.spanSizeLookup.getSpanSize(0)).isEqualTo(manager.spanCount)
        assertThat(manager.spanSizeLookup.getSpanSize(1)).isEqualTo(1)
        // Out of range answers full span: the layout manager asks about positions mid-update,
        // and a heading-shaped guess reflows where a cell-shaped one would throw.
        assertThat(manager.spanSizeLookup.getSpanSize(9999)).isEqualTo(manager.spanCount)
    }

    // ---------------------------------------------------------------
    // The import entry points
    // ---------------------------------------------------------------

    @Test
    fun theImportActionOffersBothKinds() {
        val controller = home()
        controller.toolbar().menu.performIdentifierAction(R.id.action_import, 0)
        shadowOf(context.mainLooper).idle()

        assertThat(latestDialog()!!.titles()).containsExactly(
            context.getString(R.string.import_files),
            context.getString(R.string.import_folder)
        ).inOrder()
    }

    @Test
    fun newFolderAsksForANameAndThenShowsIt() {
        val controller = home()
        controller.toolbar().menu.performIdentifierAction(R.id.action_new_folder, 0)
        shadowOf(context.mainLooper).idle()

        val field = latestDialog()!!.findViewById<android.widget.EditText>(R.id.textField)!!
        field.setText("Receipts")
        answer(AlertDialog.BUTTON_POSITIVE)

        assertThat(library.nodes().map { it.name }).containsExactly("Receipts")
        assertThat(controller.sectionTitles()).containsExactly("Receipts")
    }

    // ---------------------------------------------------------------
    // About
    // ---------------------------------------------------------------

    /**
     * The About screen asks Android what the app requests and prints the answer, rather than
     * printing a claim. This is the assertion that the answer is still "none".
     */
    @Test
    fun aboutReportsNoPermissionsBecauseThereAreNone() {
        val controller = home()
        controller.toolbar().menu.performIdentifierAction(R.id.action_about, 0)
        shadowOf(context.mainLooper).idle()

        val dialog = ShadowDialog.getLatestDialog()
        assertThat(dialog).isNotNull()
        val text = dialog.findViewById<TextView>(R.id.aboutPermissions).text.toString()
        assertThat(text).isEqualTo(context.getString(R.string.about_permissions_none))
    }

    // ---------------------------------------------------------------
    // Google Play
    // ---------------------------------------------------------------

    private fun ActivityController<MainActivity>.playItem() =
        toolbar().menu.findItem(R.id.action_play)

    /**
     * A copy installed from an APK is offered the switch to Play, whose listing updates it in
     * place, and is never asked to rate, since Play takes ratings only from its own copies.
     */
    @Test
    fun aCopyFromGitHubIsOfferedTheSwitchToPlay() {
        shadowOf(context.packageManager).setInstallSourceInfo(
            context.packageName, "com.android.chrome", "com.google.android.packageinstaller"
        )
        val item = home().playItem()

        assertThat(item.isVisible).isTrue()
        assertThat(item.title.toString()).isEqualTo(context.getString(R.string.switch_to_play))
    }

    /** Google Play's own copy keeps Rate in the same place. */
    @Test
    fun playsOwnCopyIsAskedToRate() {
        shadowOf(context.packageManager).setInstallSourceInfo(
            context.packageName, "com.android.vending", "com.android.vending"
        )
        val item = home().playItem()

        assertThat(item.isVisible).isTrue()
        assertThat(item.title.toString()).isEqualTo(context.getString(R.string.rate_app))
    }

    /** The switch opens Gander's listing in the Play Store app, where Update does the move. */
    @Test
    fun theSwitchOpensGandersListingInThePlayStore() {
        val controller = home()
        controller.toolbar().menu.performIdentifierAction(R.id.action_play, 0)

        val started = shadowOf(controller.get()).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(started.data.toString()).isEqualTo("market://details?id=${context.packageName}")
        assertThat(started.`package`).isEqualTo("com.android.vending")
    }

    /** Sort and filter are global now, so neither is hidden: there is no folder to be inside. */
    @Test
    fun sortAndFilterAreAlwaysInTheMenu() {
        val controller = home()
        assertThat(controller.toolbar().menu.findItem(R.id.action_sort).isVisible).isTrue()
        assertThat(controller.toolbar().menu.findItem(R.id.action_filter).isVisible).isTrue()
        assertThat(controller.toolbar().menu.findItem(R.id.action_share_app).isVisible).isTrue()
        assertThat(controller.toolbar().menu.findItem(R.id.action_about).isVisible).isTrue()
    }
}

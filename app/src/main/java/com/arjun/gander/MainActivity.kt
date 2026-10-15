package com.arjun.gander

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.util.concurrent.Executors

/**
 * The home screen: a library in two columns.
 *
 * The left column is the reading history and the folders; the right is every folder at once,
 * each one a section of cards. They are two views of one index — [Library] — and neither owns
 * anything: a folder here is a row in a database Gander keeps, not a directory on the phone.
 * That is what makes rename, delete and move honest operations rather than promises: they move
 * an index and never a file.
 *
 * There is no navigation stack and no going into a folder. A folder's files are already in the
 * content column, flattened to whatever depth the tree went, so the two columns are the whole
 * of the screen: the back gesture closes the sidebar, or leaves.
 *
 * Everything that reads or writes goes through [loader]. Reading a granted folder is a query to
 * another app's DocumentsProvider and reading the index is SQLite; either one on the main
 * thread is an ANR waiting for a slow provider or a large folder.
 */
class MainActivity : AppCompatActivity() {

    /**
     * Where the screen's own work is done: building both columns, importing, renaming.
     *
     * Single threaded on purpose. One screen is being drawn at a time, and it keeps the
     * library's reads on one thread while its refresh thread writes.
     */
    @androidx.annotation.VisibleForTesting
    internal var loader: java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor()

    private val main = Handler(Looper.getMainLooper())

    private val library: Library get() = (application as GanderApp).library

    private lateinit var toolbar: MaterialToolbar
    private lateinit var sidebar: View
    private lateinit var scrim: View
    private lateinit var sidebarList: RecyclerView
    private lateinit var contentList: RecyclerView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var progress: LinearProgressIndicator
    private lateinit var welcome: View
    private lateinit var grid: GridLayoutManager

    private val activeFilters = mutableSetOf<String>()

    /** What the search field holds. Empty when there is no search. */
    private var query = ""

    /** Which folders have their files showing in the sidebar. Per process, not persisted. */
    private val expandedFolders = mutableSetOf<Long>()

    private var sidebarOpen = false
    private var sidebarOverlays = false

    /**
     * A row taken off the screen whose removal waits for its Undo bar to go.
     *
     * Nothing is given back until then, because a released grant has no inverse: Android takes
     * a grant only from the picker, so an Undo made after it would have nothing to restore. The
     * card is only hidden meanwhile.
     */
    private var pending: Pending? = null
    private var undoBar: Snackbar? = null

    /** The row's URI, which is what hides it, and what removing it does. */
    private class Pending(val uri: String, val commit: () -> Unit)

    /**
     * Bumped by every render. A load that finishes after another has been asked for is dropped
     * rather than drawn.
     */
    private var renderToken = 0

    /** Set while a reload is already queued, so a burst of changes draws once. */
    private var reloadQueued = false

    /** Folders whose over-large import has already been mentioned, so it is said once. */
    private val truncationMentioned = mutableSetOf<Long>()

    private val homeAdapter = HomeAdapter(
        onOpen = { ref -> openInViewer(ref.uri.toUri()) },
        onCardMenu = { ref -> showCardMenu(ref) },
        onToggleSection = { section -> toggleSection(section) },
        onSectionMenu = { node -> showFolderMenu(node) },
        onCoverState = { ref, state -> recordCover(ref, state) }
    )

    private val sidebarAdapter = SidebarAdapter(
        onHeader = { key, collapsed -> setSidebarSection(key, collapsed) },
        onFolder = { node -> toggleFolder(node) },
        onFolderMenu = { node -> showFolderMenu(node) },
        onFile = { uri -> openInViewer(uri.toUri()) }
    )

    /**
     * The library's answers, held as a private object rather than implemented by the activity.
     *
     * [LibraryListener] is internal, and a public class may not name an internal type in its
     * supertype list. Nothing outside this screen subscribes anyway: the library is the
     * process's and this is the only thing that draws from it.
     */
    private val listener = object : LibraryListener {

        /**
         * The index changed under us.
         *
         * The whole screen is rebuilt rather than patched. The two columns are two views of one
         * index and a change to it — a folder renamed, a file removed, a rescan that found a new
         * one — can move rows in both at once, so patching one of them would leave the other
         * describing a library that no longer exists. The rebuild is coalesced, so a refresh that
         * reports each folder as it finishes draws once rather than once per folder.
         */
        override fun onLibraryChanged(nodes: Set<Long>?) {
            if (reloadQueued) return
            reloadQueued = true
            main.post {
                reloadQueued = false
                if (!isDestroyed) render()
            }
        }

        /**
         * How far a refresh has got.
         *
         * The spinner stops as soon as the first folder is in, and the bar at the top of the
         * content column carries the rest. A pull on a folder of thirty thousand files takes
         * seconds, and a spinner that turns for all of them reads as a screen that has stopped
         * responding rather than one that is working.
         */
        override fun onRefresh(state: RefreshState) {
            if (state.running) {
                progress.isIndeterminate = state.total == 0
                if (state.total > 0) {
                    progress.setProgressCompat(state.scanned * 100 / state.total, true)
                }
                progress.visibility = View.VISIBLE
                if (state.scanned > 0) refresh.isRefreshing = false
            } else {
                progress.visibility = View.GONE
                refresh.isRefreshing = false
            }
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showSidebar(false, persist = true)
        }
    }

    private val openDocuments =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) mutate { library.importPicked(uris) }
        }

    private val openTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) mutate { library.importTree(uri) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        toolbar = findViewById(R.id.toolbar)
        sidebar = findViewById(R.id.sidebar)
        scrim = findViewById(R.id.scrim)
        sidebarList = findViewById(R.id.sidebarList)
        contentList = findViewById(R.id.contentList)
        refresh = findViewById(R.id.refresh)
        progress = findViewById(R.id.loadProgress)
        welcome = findViewById(R.id.welcome)

        sidebarOverlays = resources.getBoolean(R.bool.sidebar_overlays_content)

        setUpToolbar()
        setUpColumns()
        setUpSidebar()
        setUpRefresh()

        // Built once here rather than on every render: the nine kinds Gander opens do not
        // change while it is running, and the block itself is shown or hidden, not rebuilt.
        fillFormatGrid(findViewById(R.id.formatGrid))

        // The two buttons in the empty state are the two actions the title bar offers, said at
        // length where a first run has no folders to put anything into yet.
        findViewById<View>(R.id.importFilesButton).setOnClickListener { importFiles() }
        findViewById<View>(R.id.importFolderButton).setOnClickListener { importFolder() }

        restoreFilters(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backCallback)
        showSidebar(Settings.sidebarOpen(this), persist = false)
        library.addListener(listener)
    }

    // ------------------------------------------------------------------
    // Setting up
    // ------------------------------------------------------------------

    private fun setUpToolbar() {
        toolbar.inflateMenu(R.menu.main_menu)
        // The icon at the left of the title bar is the way into a sidebar that is away. While
        // the sidebar is out the same action is its own button at the top, because a button that
        // closes a panel is useless underneath it.
        toolbar.setNavigationOnClickListener { toggleSidebar() }
        // Set once, like the inflate: the installer cannot change while this process lives,
        // because any reinstall or update kills the process first.
        if (installedFromPlay()) {
            toolbar.menu.findItem(R.id.action_play).setTitle(R.string.rate_app)
        }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import -> { showImportDialog(); true }
                R.id.action_new_folder -> { showNewFolderDialog(); true }
                R.id.action_sort -> { showSortDialog(); true }
                R.id.action_filter -> { showFilterDialog(); true }
                R.id.action_play -> { openPlayListing(); true }
                R.id.action_share_app -> { shareGander(); true }
                R.id.action_about -> { showAbout(); true }
                else -> false
            }
        }

        // Search expands into the title bar rather than opening a screen of its own, because
        // what it filters is the grid underneath: the thing being searched has to stay where it
        // is. Collapsing the field clears the query, so the grid is never left filtered by
        // something nobody can see.
        val search = toolbar.menu.findItem(R.id.action_search)
        (search.actionView as? SearchView)?.let { view ->
            view.queryHint = getString(R.string.search_files)
            view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(text: String?): Boolean = false
                override fun onQueryTextChange(text: String?): Boolean {
                    query = text.orEmpty()
                    render()
                    return true
                }
            })
            search.setOnActionExpandListener(object : android.view.MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: android.view.MenuItem): Boolean = true
                override fun onMenuItemActionCollapse(item: android.view.MenuItem): Boolean {
                    if (query.isNotEmpty()) {
                        query = ""
                        render()
                    }
                    return true
                }
            })
        }
    }

    /**
     * The card grid is as wide as it fits rather than a fixed number of columns, so the count
     * has to be worked out from the room the content column actually got — which is not known
     * until it is laid out, and changes when the sidebar opens on a tablet.
     */
    private fun setUpColumns() {
        grid = GridLayoutManager(this, resources.getInteger(R.integer.home_card_min_columns)).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) =
                    if (homeAdapter.isFullSpan(position)) grid.spanCount else 1
            }.apply {
                // Uncached, getSpanIndex walks getSpanSize from zero for every item, so laying
                // out a large folder is quadratic in its length.
                isSpanIndexCacheEnabled = true
                isSpanGroupIndexCacheEnabled = true
            }
        }
        contentList.layoutManager = grid
        contentList.adapter = homeAdapter
        // Off, because both columns are rebuilt from the index rather than patched: nothing in
        // them moves, and the default animator would slide every card out and back in on a
        // search keystroke or a rescan.
        contentList.itemAnimator = null
        contentList.addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
            applyColumns(right - left - view.paddingStart - view.paddingEnd)
        }
    }

    private fun applyColumns(widthPx: Int) {
        if (widthPx <= 0) return
        val columns = cardColumns(
            widthPx,
            resources.getDimensionPixelSize(R.dimen.home_card_min_width),
            resources.getInteger(R.integer.home_card_min_columns),
            resources.getInteger(R.integer.home_card_max_columns)
        )
        if (grid.spanCount != columns) grid.spanCount = columns
    }

    private fun setUpSidebar() {
        sidebarList.layoutManager = LinearLayoutManager(this)
        sidebarList.adapter = sidebarAdapter
        sidebarList.setHasFixedSize(false)
        sidebarList.itemAnimator = null
        findViewById<View>(R.id.sidebarToggle).setOnClickListener { toggleSidebar() }
        // The reader's own preferences. They used to hang off the title bar's overflow button;
        // they are here now because the title bar belongs to the content column and this is the
        // column of everything that is not a file.
        findViewById<View>(R.id.settingsButton).setOnClickListener { toolbar.showOverflowMenu() }
        scrim.setOnClickListener { showSidebar(false, persist = true) }
    }

    private fun setUpRefresh() {
        refresh.setOnRefreshListener { pullToRefresh() }
    }

    /**
     * A pull on the content column: a full rescan of every imported folder.
     *
     * The one thing that moves the index. There is no refresh button and no automatic rescan
     * when the screen comes back, because neither is a moment the reader asked for: a pull is,
     * and so is a cold start.
     */
    internal fun pullToRefresh() {
        library.refresh(RefreshReason.PULL)
    }

    private fun restoreFilters(state: Bundle?) {
        val saved = state?.getStringArrayList(STATE_ACTIVE_FILTERS) ?: return
        activeFilters.clear()
        activeFilters.addAll(saved)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_ACTIVE_FILTERS, ArrayList(activeFilters))
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * The index is read on every resume, and the toolbar's filter subtitle with it.
     *
     * Nothing here rescans anything: a cold start is the only automatic refresh and it happens
     * once per process, in [onStart]. Coming back from the viewer, a rotation and a return from
     * the background all redraw what the index already holds, which is what makes them instant.
     */
    override fun onResume() {
        super.onResume()
        render()
    }

    /**
     * A cold start rescans every imported folder, which is the one moment the reader has asked
     * for fresh data without saying so. The library throttles it: opening and closing the app
     * ten times is not ten reasons to walk a photo library.
     */
    override fun onStart() {
        super.onStart()
        library.refresh(RefreshReason.COLD_START)
    }

    /**
     * Takes any tap highlight off before this screen leaves.
     *
     * A tap that opens a document starts a ripple, and the viewer covers this screen while it is
     * still fading. A back swipe then shows this screen as it was last drawn on the way out, so
     * the card that was tapped came back lit for a moment.
     */
    override fun onPause() {
        super.onPause()
        window.decorView.isPressed = false
        window.decorView.jumpDrawablesToCurrentState()
    }

    /** A removal waiting on its Undo bar goes through, since nobody is here to answer it. */
    override fun onStop() {
        super.onStop()
        finishRemoving()
        undoBar?.dismiss()
        // A rescan walks provider queries and writes rows; nobody is looking at the result.
        library.cancelRefresh()
    }

    override fun onDestroy() {
        library.removeListener(listener)
        // Anything already queued still runs to completion and the thread ends with it, rather
        // than outliving the activity it was drawing.
        loader.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /**
     * Builds both columns from the index.
     *
     * The toolbar changes at once and the columns follow, because the title is known here and
     * the rows are not: they come from a database and, for a folder nobody has opened yet, from
     * a provider that may be slow. Nothing is cleared in the meantime, so returning from a
     * document leaves the old grid on screen until the new one is ready rather than blinking
     * through empty.
     */
    private fun render() {
        val token = ++renderToken
        val hidden = pending?.uri
        val filters = activeFilters.toSet()
        val searching = query.trim()
        val expanded = expandedFolders.toSet()
        val order = Settings.sortOrder(this)
        val recentCollapsed = Settings.sectionCollapsed(this, SIDEBAR_RECENT, false)
        val foldersCollapsed = Settings.sectionCollapsed(this, SIDEBAR_FOLDERS, false)
        val filtering = filters.isNotEmpty() || searching.isNotEmpty()

        val announce = Runnable {
            if (token == renderToken && !isDestroyed) progress.visibility = View.VISIBLE
        }
        main.postDelayed(announce, RENDER_PROGRESS_DELAY_MS)

        loader.execute {
            if (token != renderToken) return@execute

            val nodes = library.nodes()
            var truncated = 0L
            val home = mutableListOf<HomeRow>()
            val side = mutableListOf<SidebarRow>()

            nodes.forEachIndexed { index, listed ->
                var node = listed
                // A folder nobody has opened has nothing in the index yet, and the first time it
                // is drawn is the moment to read it. A search is not: looking for something
                // should not go and walk every folder on the phone.
                val collapsed = !filtering &&
                    Settings.sectionCollapsed(this, sectionKey(node.id), index != 0)
                if (!collapsed && node.unscanned) {
                    if (library.ensureScanned(node.id)) truncated = node.id
                    node = library.node(node.id) ?: node
                }
                // A row whose Undo bar is up is off both columns until the bar has gone: the
                // file is on its way out, and a card that stayed behind the question would
                // make the removal look like it had failed.
                val held = if (collapsed) {
                    emptyList()
                } else {
                    orderRefs(library.refsFor(node).filter { it.uri != hidden }, order)
                }
                val shown = held.filter { matches(it, filters, searching) }
                // A search or a filter hides a section that holds nothing that matches, rather
                // than leaving a heading over an empty grid.
                if (filtering && shown.isEmpty()) return@forEachIndexed

                home += HomeRow.Section(node, if (collapsed) library.countFor(node) else held.size, collapsed)
                when {
                    collapsed -> Unit
                    shown.isEmpty() -> home += HomeRow.Hint(
                        getString(
                            if (node.unscanned) R.string.unscanned_hint else R.string.empty_folder
                        )
                    )
                    else -> shown.forEach { home += HomeRow.Card(it) }
                }
            }

            // The reading history, which is not the index: a file opened once and never filed
            // is still worth offering again.
            side += SidebarRow.Header(SIDEBAR_RECENT, getString(R.string.recent_files), recentCollapsed)
            if (!recentCollapsed) {
                val recents = library.recents().filter { it.uri != hidden }
                if (recents.isEmpty()) {
                    side += SidebarRow.Hint(getString(R.string.no_recents_hint))
                } else {
                    recents.forEach {
                        side += SidebarRow.File(
                            it.uri, it.name, it.name.substringAfterLast('.', "").lowercase(), null
                        )
                    }
                }
            }

            side += SidebarRow.Header(SIDEBAR_FOLDERS, getString(R.string.folders), foldersCollapsed)
            if (!foldersCollapsed) {
                if (nodes.isEmpty()) {
                    side += SidebarRow.Hint(getString(R.string.no_folders_hint))
                } else {
                    nodes.forEach { node ->
                        side += SidebarRow.Folder(node, node.id in expanded)
                        if (node.id in expanded) {
                            val refs = orderRefs(
                                library.refsFor(node).filter { it.uri != hidden }, order
                            )
                            if (refs.isEmpty()) {
                                side += SidebarRow.Hint(getString(R.string.empty_folder))
                            } else {
                                refs.forEach {
                                    side += SidebarRow.File(it.uri, it.name, it.ext, it.mime)
                                }
                            }
                        }
                    }
                }
            }

            if (searching.isNotEmpty() && home.isEmpty() && nodes.isNotEmpty()) {
                home += HomeRow.Hint(getString(R.string.search_no_results, searching))
                home += HomeRow.Hint(getString(R.string.search_not_scanned))
            }

            val firstRun = library.isEmpty()
            val filterSummary = SUPPORTED_FILTER_TYPES
                .map { it.badge }
                .filter { it in activeFilters }
                .joinToString(", ")
                .takeIf { it.isNotEmpty() }

            main.post {
                main.removeCallbacks(announce)
                if (token != renderToken || isDestroyed) return@post
                progress.visibility = View.GONE
                welcome.visibility = if (firstRun) View.VISIBLE else View.GONE
                contentList.visibility = if (firstRun) View.GONE else View.VISIBLE
                toolbar.subtitle =
                    filterSummary?.let { getString(R.string.filter_subtitle, it) }
                homeAdapter.submit(home)
                sidebarAdapter.submit(side)
                if (truncated != 0L && truncationMentioned.add(truncated)) {
                    Snackbar.make(
                        contentList,
                        resources.getQuantityString(
                            R.plurals.import_truncated,
                            LibraryScan.MAX_FILES,
                            LibraryScan.MAX_FILES
                        ),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Whether a file survives the current filter and search. */
    private fun matches(ref: LibraryRef, filters: Set<String>, searching: String): Boolean {
        if (filters.isNotEmpty() && badgeFor(ref.name, ref.mime).first !in filters) return false
        if (searching.isNotEmpty() && !ref.name.contains(searching, ignoreCase = true)) return false
        return true
    }

    /**
     * Keeps what a cover did, so a failure is not tried again on every scroll.
     *
     * Written only when the answer differs from what the index already holds, because this is
     * called once per card per bind and most binds find the cover already drawn.
     */
    private fun recordCover(ref: LibraryRef, state: Int) {
        if (ref.coverState == state) return
        loader.execute {
            library.markCover(ref.id, state, if (state == Covers.READY) Covers.key(ref) else null)
        }
    }

    // ------------------------------------------------------------------
    // The sidebar
    // ------------------------------------------------------------------

    /**
     * Shows or hides the sidebar, and remembers the reader's answer.
     *
     * The toggle is in one of two places depending on this: at the top of the sidebar while it
     * is out, and at the left of the content column's title bar while it is away. A button that
     * closes a panel is useless underneath it.
     *
     * Internal rather than private so a test can open the panel without going through the
     * title bar's icon, which has no click path Robolectric can drive.
     */
    internal fun toggleSidebar() {
        showSidebar(!sidebarOpen, persist = true)
    }

    private fun showSidebar(open: Boolean, persist: Boolean) {
        sidebarOpen = open
        sidebar.visibility = if (open) View.VISIBLE else View.GONE
        scrim.visibility = if (open && sidebarOverlays) View.VISIBLE else View.GONE
        toolbar.navigationIcon = if (open) null else {
            androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_panel)
        }
        toolbar.navigationContentDescription = getString(R.string.open_sidebar)
        // The back gesture closes a floating panel and does nothing to a fixed one: a tablet
        // held sideways has room for both columns and a panel that is always there is not
        // something to dismiss.
        backCallback.isEnabled = open && sidebarOverlays
        if (persist) Settings.setSidebarOpen(this, open)
    }

    private fun setSidebarSection(key: String, collapsed: Boolean) {
        Settings.setSectionCollapsed(this, key, collapsed)
        render()
    }

    /**
     * Opens or folds a folder in the sidebar.
     *
     * Opening one is also the moment it is first read, if it never has been: the index holds
     * nothing for a folder nobody has looked inside, and the reader has just said they want to.
     */
    private fun toggleFolder(node: LibraryNode) {
        if (!expandedFolders.remove(node.id)) {
            expandedFolders += node.id
            loader.execute {
                library.ensureScanned(node.id)
                main.post { render() }
            }
        } else {
            render()
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private fun toggleSection(section: HomeRow.Section) {
        Settings.setSectionCollapsed(this, sectionKey(section.node.id), !section.collapsed)
        render()
    }

    /** Runs a change to the library on the loader, then redraws. */
    private fun mutate(work: () -> Unit) {
        loader.execute {
            work()
            main.post { if (!isDestroyed) render() }
        }
    }

    private fun showImportDialog() {
        val items = arrayOf(getString(R.string.import_files), getString(R.string.import_folder))
        MaterialAlertDialogBuilder(this)
            .setItems(items) { _, which ->
                if (which == 0) importFiles() else importFolder()
            }
            .show()
    }

    private fun importFiles() = openDocuments.launch(arrayOf("*/*"))

    private fun importFolder() = openTree.launch(null)

    /**
     * The long-press menu of a folder: rename, or delete.
     *
     * What deleting means depends on what the folder is, and the two questions say different
     * things. A made-up folder is an index entry and nothing else. An imported one is a grant,
     * and giving it back cannot be undone from here.
     */
    private fun showFolderMenu(node: LibraryNode) {
        val items = arrayOf(getString(R.string.rename), getString(R.string.delete))
        MaterialAlertDialogBuilder(this)
            .setTitle(node.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRenameDialog(node)
                    else -> askToRemove(
                        if (node.imported) R.string.remove_folder_title
                        else R.string.delete_virtual_title,
                        getString(
                            if (node.imported) R.string.remove_folder_message
                            else R.string.delete_virtual_message,
                            node.name
                        ),
                        kept = {},
                        remove = { mutate { library.deleteNode(node.id) } }
                    )
                }
            }
            .show()
    }

    /** The long-press menu of a card. */
    private fun showCardMenu(ref: LibraryRef) {
        val items = arrayOf(
            getString(R.string.open),
            getString(R.string.move_to),
            getString(R.string.remove_from_library),
            getString(R.string.share_file)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(ref.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openInViewer(ref.uri.toUri())
                    1 -> showMoveDialog(ref)
                    2 -> askToRemove(
                        R.string.remove_file_title,
                        getString(R.string.remove_file_message, ref.name),
                        kept = {}
                    ) {
                        removeLater(ref.uri) { mutate { library.removeRef(ref.id) } }
                    }
                    else -> shareFile(ref)
                }
            }
            .show()
    }

    /**
     * Moves a file into one of the reader's own folders.
     *
     * A move here adds a place rather than taking one away: the file stays in the folder it came
     * from, because that folder is a real directory Gander does not own and this one is a list
     * it keeps.
     */
    private fun showMoveDialog(ref: LibraryRef) {
        loader.execute {
            val targets = library.nodes().filter { !it.imported }
            main.post {
                if (isDestroyed) return@post
                val labels = targets.map { it.name } + getString(R.string.new_folder)
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.move_to)
                    .setItems(labels.toTypedArray()) { _, which ->
                        if (which < targets.size) {
                            mutate { library.moveRef(ref.id, targets[which].id) }
                        } else {
                            showNewFolderDialog { name ->
                                mutate {
                                    val id = library.createVirtualFolder(name)
                                    library.moveRef(ref.id, id)
                                }
                            }
                        }
                    }
                    .show()
            }
        }
    }

    private fun showNewFolderDialog(onCreated: (String) -> Unit = { name ->
        mutate { library.createVirtualFolder(name) }
    }) {
        askForName(R.string.new_folder, "", onCreated)
    }

    private fun showRenameDialog(node: LibraryNode) {
        askForName(R.string.rename, node.name) { name ->
            mutate { library.renameNode(node.id, name) }
        }
    }

    /** One field, one name. Used by a new folder and by a rename. */
    private fun askForName(title: Int, current: String, onName: (String) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_text_field, null)
        view.findViewById<TextInputLayout>(R.id.textFieldBox).hint = getString(R.string.folder_name)
        val field = view.findViewById<TextInputEditText>(R.id.textField)
        field.setText(current)
        field.setSelection(current.length)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = field.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openInViewer(uri: Uri) {
        // By the viewer's internal name, which is what lets it trust the URI and record it in
        // the reading history. See ViewerActivity.INTERNAL_VIEWER.
        startActivity(
            Intent()
                .setClassName(this, ViewerActivity.INTERNAL_VIEWER)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun shareFile(ref: LibraryRef) {
        val uri = ref.uri.toUri()
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: "*/*"
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.share_file))) }
            .onFailure { Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show() }
    }

    /**
     * Asks before a row goes, and calls [kept] if the answer is no. Removing a file or a folder
     * gives back Gander's access to it, and Android has no inverse for that, so once the Undo
     * bar has gone the way back is the system picker.
     */
    private fun askToRemove(title: Int, message: String, kept: () -> Unit, remove: () -> Unit) {
        var removing = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.remove) { _, _ ->
                removing = true
                remove()
            }
            .setNegativeButton(android.R.string.cancel, null)
            // Cancel, Back and a tap outside alike
            .setOnDismissListener { if (!removing) kept() }
            .create()
        dialog.show()
        // Tinted after show(): getButton returns null until the dialog is laid out. Both buttons
        // are set rather than only the destructive one, because Gander's own primary is a burnt
        // red: an error-coloured Remove beside an untouched Cancel measures dE 4.6 on the light
        // palette, near enough to the 2.3 a person can notice that it marks nothing and only
        // makes Cancel look dangerous too.
        listOf(
            AlertDialog.BUTTON_POSITIVE to com.google.android.material.R.attr.colorError,
            AlertDialog.BUTTON_NEGATIVE to com.google.android.material.R.attr.colorOnSurfaceVariant
        ).forEach { (which, attr) ->
            dialog.getButton(which).let {
                it.setTextColor(MaterialColors.getColor(it, attr))
            }
        }
    }

    /**
     * Hides the row for [uri] and offers Undo, doing [commit] only once the offer has gone.
     * See [pending].
     */
    private fun removeLater(uri: String, commit: () -> Unit) {
        // One at a time: the bar for this one takes the last one's place
        finishRemoving()
        val removal = Pending(uri, commit)
        pending = removal
        render()
        undoBar = Snackbar.make(contentList, R.string.removed, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                if (pending === removal) {
                    pending = null
                    render()
                }
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(bar: Snackbar, event: Int) {
                    if (event != DISMISS_EVENT_ACTION && pending === removal) {
                        finishRemoving()
                        render()
                    }
                }
            })
            .also { it.show() }
    }

    /** Carries out the removal waiting on an Undo bar, if one is. */
    private fun finishRemoving() {
        val removal = pending ?: return
        pending = null
        removal.commit()
    }

    // ------------------------------------------------------------------
    // The empty state
    // ------------------------------------------------------------------

    /**
     * Draws the nine tiles of the welcome grid: eight of the pairs [badgeFor] returns, and ETC
     * for the kinds that have no tile.
     *
     * The grid is filled here rather than declared nine times in the layout so that the first
     * screen cannot end up naming a different set of things from the cards underneath it.
     */
    private fun fillFormatGrid(grid: ViewGroup) {
        WELCOME_BADGES.forEach { (label, color) ->
            val tile = layoutInflater.inflate(R.layout.view_welcome_tile, grid, false) as TextView
            tile.text = label
            tile.background.mutate().setTint(color)
            grid.addView(tile)
        }
        grid.contentDescription = getString(R.string.welcome_formats_spoken)
        ViewCompat.setScreenReaderFocusable(grid, true)
    }

    // ------------------------------------------------------------------
    // About, Play and sharing the app
    // ------------------------------------------------------------------

    private fun showAbout() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull().orEmpty()
        view.findViewById<TextView>(R.id.aboutVersion).text =
            getString(R.string.about_version, version)

        val permissions = requestedPermissions()
        val field = view.findViewById<TextView>(R.id.aboutPermissions)
        when {
            // Only when the package manager refused to answer. Printing "none" for a question we
            // could not ask would be the one dishonest thing this dialog could do, so it says
            // nothing at all instead.
            permissions == null ->
                view.findViewById<View>(R.id.aboutPermissionsCard).visibility = View.GONE
            permissions.isEmpty() -> field.setText(R.string.about_permissions_none)
            // Never expected: assembleRelease fails before a build can get here. Shown rather
            // than swallowed, because a broken promise is the thing a reader of this dialog most
            // needs to know.
            else -> {
                field.text = permissions.joinToString("\n")
                field.setTextColor(
                    MaterialColors.getColor(field, com.google.android.material.R.attr.colorError)
                )
            }
        }

        view.findViewById<View>(R.id.aboutAuthor)
            .setOnClickListener { openUrl(getString(R.string.url_author)) }
        view.findViewById<View>(R.id.aboutSource)
            .setOnClickListener { openUrl(getString(R.string.url_source)) }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_gander)
            .setView(view)
            .setPositiveButton(R.string.about_close, null)
            .show()

        view.findViewById<View>(R.id.aboutLicences).setOnClickListener {
            dialog.dismiss()
            openLicences()
        }
    }

    /**
     * What Android says this install asks for, or null if it would not say.
     *
     * androidx.core declares DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION under our own package name
     * so libraries can registerReceiver safely. It is signature level, self-granted and never
     * shown to a user, which is why the permission check in build.gradle.kts allowlists it as
     * well. Anything else carrying our package prefix is ours on the same reasoning, so drop
     * those and report what is left.
     */
    private fun requestedPermissions(): List<String>? = runCatching {
        packageManager
            .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .filterNot { it.startsWith("$packageName.") }
    }.getOrNull()

    /**
     * Gander shows its own licences. The asset is copied into the cache and handed to the viewer
     * as a plain path, so the bundled Markdown renderer draws it and there is no second document
     * surface to keep alive.
     */
    private fun openLicences() {
        val file = File(cacheDir, getString(R.string.licences_file_name))
        val opened = runCatching {
            assets.open(LICENCES_ASSET).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            startActivity(
                Intent()
                    .setClassName(this, ViewerActivity.INTERNAL_VIEWER)
                    .putExtra(ViewerActivity.EXTRA_PATH, file.absolutePath)
            )
        }.isSuccess
        if (!opened) Toast.makeText(this, R.string.licences_failed, Toast.LENGTH_SHORT).show()
    }

    /**
     * Hands a URL to whichever browser the user has. Gander never fetches anything itself, and
     * without the INTERNET permission it could not.
     */
    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show() }
    }

    /**
     * Whether Google Play is this install's installer of record, the only case where Rate can go
     * anywhere.
     */
    private fun installedFromPlay(): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
        installer == PLAY_STORE
    }.getOrDefault(false)

    /**
     * Gander's listing, sent to the Play Store app by name so another store that also answers
     * market:// links cannot catch it, and to the browser if Play will not take it.
     */
    private fun openPlayListing() {
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
            .setPackage(PLAY_STORE)
        runCatching { startActivity(market) }
            .onFailure { openUrl(getString(R.string.url_play_listing, packageName)) }
    }

    /**
     * A line and a link through the system share sheet, as the viewer shares a file. Gander
     * leaves itself out of the sheet.
     */
    private fun shareGander() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
            .putExtra(
                Intent.EXTRA_TEXT,
                getString(R.string.share_app_text, getString(R.string.url_site))
            )
        val chooser = Intent.createChooser(send, getString(R.string.share_app))
            .putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(this, ViewerActivity::class.java))
            )
        runCatching { startActivity(chooser) }
    }

    // ------------------------------------------------------------------
    // Sort and filter
    // ------------------------------------------------------------------

    private fun showSortDialog() {
        val orders = Settings.SortOrder.values()
        val current = Settings.sortOrder(this)
        val items = orders.map { getString(it.labelRes) }.toTypedArray()
        val checkedItem = orders.indexOf(current).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_by)
            .setSingleChoiceItems(items, checkedItem) { dialog, which ->
                Settings.setSortOrder(this, orders[which])
                render()
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Which kinds of file the grid shows.
     *
     * A filter applies to every folder at once, because there is no folder to be inside: the
     * content column is all of them, and the filter is a property of the screen rather than of
     * a place. It survives a rotation, and it is shown in the title bar's subtitle so that a
     * short list is never a mystery.
     */
    private fun showFilterDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_filter, null)
        val container = view.findViewById<ViewGroup>(R.id.filterItemsContainer)
        val resetBtn = view.findViewById<View>(R.id.resetFilterButton)

        val tempSelected = activeFilters.toMutableSet()
        val checkBoxes = mutableListOf<MaterialCheckBox>()

        SUPPORTED_FILTER_TYPES.forEach { item ->
            val row = layoutInflater.inflate(R.layout.dialog_filter_item, container, false)
            val badge = row.findViewById<TextView>(R.id.filterBadge)
            val title = row.findViewById<TextView>(R.id.filterTitle)
            val checkbox = row.findViewById<MaterialCheckBox>(R.id.filterCheckbox)

            badge.text = item.badge
            badge.background.mutate().setTint(item.color)
            title.setText(item.labelRes)
            checkbox.isChecked = item.badge in tempSelected
            checkBoxes += checkbox

            row.setOnClickListener {
                checkbox.isChecked = !checkbox.isChecked
                if (checkbox.isChecked) {
                    tempSelected += item.badge
                } else {
                    tempSelected -= item.badge
                }
            }

            container.addView(row)
        }

        resetBtn.setOnClickListener {
            tempSelected.clear()
            checkBoxes.forEach { it.isChecked = false }
        }

        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                activeFilters.clear()
                activeFilters.addAll(tempSelected)
                render()
            }
            .show()
    }

    private companion object {
        const val LICENCES_ASSET = "licences.md"

        /** Google Play's package: the installer Rate depends on, and the app it opens. */
        const val PLAY_STORE = "com.android.vending"

        /** How long a folder may take to read before the screen says anything about it. */
        const val RENDER_PROGRESS_DELAY_MS = 150L

        /** The two sidebar headings, as keys in Settings' record of what is folded. */
        const val SIDEBAR_RECENT = "sidebar.recent"
        const val SIDEBAR_FOLDERS = "sidebar.folders"

        /** What the reader's choices are filed under, per folder. */
        fun sectionKey(nodeId: Long) = "node.$nodeId"

        /** Where the reader's filter was, kept across a configuration change. */
        const val STATE_ACTIVE_FILTERS = "active_filters"
    }
}

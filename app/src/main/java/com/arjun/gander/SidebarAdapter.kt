package com.arjun.gander

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * The sidebar: the reading history, then the folders, and the files inside a folder the reader
 * has opened.
 *
 * The two lists fold, and so does each folder, which is the whole of the navigation this
 * screen has: there is no going into a folder and coming back out, because a folder's files
 * are already in the content column, flattened. What the tree here is for is finding a folder
 * when the content column has scrolled a long way.
 *
 * A file row carries its type icon and nothing else — no thumbnail. A sidebar row is 56dp and
 * the cover it could hold would be a second, worse copy of the card the content column is
 * already showing.
 */
internal sealed interface SidebarRow {

    /** A foldable heading: the reading history, or the folders. */
    data class Header(val key: String, val title: String, val collapsed: Boolean) : SidebarRow

    /** A folder, and whether its files are showing underneath it. */
    data class Folder(val node: LibraryNode, val expanded: Boolean) : SidebarRow

    /**
     * One file: a recent one, or one inside an expanded folder.
     *
     * [ext] and [mime] are what the type icon is chosen by. A recent entry knows only the name
     * it was opened under, which is enough: the extension is the first thing the kind is read
     * from anyway.
     */
    data class File(val uri: String, val name: String, val ext: String, val mime: String?) : SidebarRow

    /** A line of text in place of rows: an empty history, or a folder with nothing in it. */
    data class Hint(val text: String) : SidebarRow
}

internal class SidebarAdapter(
    private val onHeader: (String, Boolean) -> Unit,
    private val onFolder: (LibraryNode) -> Unit,
    private val onFolderMenu: (LibraryNode) -> Unit,
    private val onFile: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<SidebarRow>()

    /**
     * Replaces the rows, as [HomeAdapter.submit] does: the same positions holding different
     * things, rather than a structure that changed.
     */
    fun submit(newRows: List<SidebarRow>) {
        val old = rows.size
        rows.clear()
        rows.addAll(newRows)
        val shared = minOf(old, rows.size)
        if (shared > 0) notifyItemRangeChanged(0, shared)
        if (old > rows.size) notifyItemRangeRemoved(rows.size, old - rows.size)
        if (rows.size > old) notifyItemRangeInserted(old, rows.size - old)
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is SidebarRow.Header -> 0
        is SidebarRow.Folder -> 1
        is SidebarRow.File -> 2
        is SidebarRow.Hint -> 3
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            0 -> R.layout.item_sidebar_header
            3 -> R.layout.row_hint
            else -> R.layout.item_sidebar_row
        }
        return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is SidebarRow.Header -> bindHeader(holder, row)
            is SidebarRow.Folder -> bindFolder(holder, row)
            is SidebarRow.File -> bindFile(holder, row)
            is SidebarRow.Hint ->
                holder.itemView.findViewById<TextView>(R.id.hintText).text = row.text
        }
    }

    private fun bindHeader(holder: RecyclerView.ViewHolder, row: SidebarRow.Header) {
        val view = holder.itemView
        view.findViewById<TextView>(R.id.sidebarHeaderText).text = row.title
        view.findViewById<ImageView>(R.id.sidebarHeaderChevron).rotation =
            if (row.collapsed) -90f else 0f
        view.contentDescription = row.title
        view.setOnClickListener { onHeader(row.key, !row.collapsed) }
    }

    private fun bindFolder(holder: RecyclerView.ViewHolder, row: SidebarRow.Folder) {
        val view = holder.itemView
        val context = view.context
        // A holder is reused, so the inset is set rather than added to: a folder row that
        // followed a file row would otherwise keep that row's indent for good.
        view.setPaddingRelative(inset(view), view.paddingTop, view.paddingEnd, view.paddingBottom)
        // The flat folder here and the two-layer one on a section heading are the same colour:
        // at 24dp the back layer of the coloured drawable is a smudge, and a row is not the
        // place to show depth.
        setIcon(
            view.findViewById(R.id.sidebarIcon),
            R.drawable.ic_kind_folder,
            ContextCompat.getColor(context, folderColorRes(row.node.colorIndex))
        )
        view.findViewById<TextView>(R.id.sidebarTitle).text = row.node.name
        // One lookup, two properties: the arrow is the same object in both states and only its
        // angle changes, so asking for it twice was a cut-and-paste waiting to happen.
        val chevron = view.findViewById<ImageView>(R.id.sidebarChevron)
        chevron.visibility = View.VISIBLE
        chevron.rotation = if (row.expanded) 0f else -90f
        view.contentDescription = row.node.name
        view.setOnClickListener { onFolder(row.node) }
        view.setOnLongClickListener { onFolderMenu(row.node); true }
    }

    private fun bindFile(holder: RecyclerView.ViewHolder, row: SidebarRow.File) {
        val view = holder.itemView
        setIcon(
            view.findViewById(R.id.sidebarIcon),
            iconFor(row.name, row.mime),
            badgeFor(row.name, row.mime).second
        )
        view.findViewById<TextView>(R.id.sidebarTitle).text = row.name
        view.findViewById<ImageView>(R.id.sidebarChevron).visibility = View.GONE
        // Pushed in under the folder it came from, so an expanded folder reads as one block.
        view.setPaddingRelative(
            inset(view) + view.resources.getDimensionPixelSize(R.dimen.sidebar_indent),
            view.paddingTop, view.paddingEnd, view.paddingBottom
        )
        view.contentDescription = row.name
        view.setOnClickListener { onFile(row.uri) }
        view.setOnLongClickListener(null)
    }

    /** The inset a row starts at, from the layout, so the two cannot disagree. */
    private fun inset(view: View): Int =
        view.resources.getDimensionPixelSize(R.dimen.sidebar_row_inset)
}

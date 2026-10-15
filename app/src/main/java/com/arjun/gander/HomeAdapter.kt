package com.arjun.gander

import android.content.res.ColorStateList
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * The content column: every folder, one section each, and each section a grid of cards.
 *
 * One adapter for the whole column, sections and cards together, rather than a nested
 * RecyclerView per folder. The sections are a handful and the cards are the scroll: nesting
 * would give every folder its own scrolling surface and its own view pool, and a grid inside a
 * vertical list cannot size itself without measuring all of its children.
 *
 * A card is one cell and a heading is the whole width, which is what [isFullSpan] answers.
 */
internal sealed interface HomeRow {

    /** A folder's heading, with how many files are in it. Full span. */
    data class Section(val node: LibraryNode, val count: Int, val collapsed: Boolean) : HomeRow

    /** One file. One cell of the grid. */
    data class Card(val ref: LibraryRef) : HomeRow

    /** A line of text in place of cards: a folder with nothing in it, or a search with no hits. */
    data class Hint(val text: String) : HomeRow
}

/**
 * How many cards fit across [widthPx], never fewer than [min] or more than [max].
 *
 * A plain function rather than arithmetic inside the layout listener because the answer is the
 * one thing about the grid worth testing without a screen: the phone and tablet examples in the
 * design are three points on this line, and it is easy to be off by one at the edges.
 */
internal fun cardColumns(widthPx: Int, minCardWidthPx: Int, min: Int, max: Int): Int =
    if (minCardWidthPx <= 0) min else (widthPx / minCardWidthPx).coerceIn(min, max)

internal class HomeAdapter(
    private val onOpen: (LibraryRef) -> Unit,
    private val onCardMenu: (LibraryRef) -> Unit,
    private val onToggleSection: (HomeRow.Section) -> Unit,
    private val onSectionMenu: (LibraryNode) -> Unit,
    private val onCoverState: (LibraryRef, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<HomeRow>()

    /**
     * Replaces the rows.
     *
     * Told as a range rather than as a structure change, and the grid's item animator is off
     * for the same reason: nothing here moves. The screen rebuilds both columns from the index
     * on every change, so what RecyclerView is being told is that the same positions hold
     * different things — which is the truth, and costs a rebind rather than a re-layout with
     * every view animating in and out.
     */
    fun submit(newRows: List<HomeRow>) {
        val old = rows.size
        rows.clear()
        rows.addAll(newRows)
        val shared = minOf(old, rows.size)
        if (shared > 0) notifyItemRangeChanged(0, shared)
        if (old > rows.size) notifyItemRangeRemoved(rows.size, old - rows.size)
        if (rows.size > old) notifyItemRangeInserted(old, rows.size - old)
    }

    /** Whether the row at [position] takes the whole width rather than one cell. */
    fun isFullSpan(position: Int): Boolean =
        position !in rows.indices || rows[position] !is HomeRow.Card

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is HomeRow.Section -> 0
        is HomeRow.Card -> 1
        is HomeRow.Hint -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            0 -> R.layout.item_home_section
            1 -> R.layout.item_home_card
            else -> R.layout.row_hint
        }
        return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HomeRow.Section -> bindSection(holder, row)
            is HomeRow.Card -> bindCard(holder, row)
            is HomeRow.Hint ->
                holder.itemView.findViewById<TextView>(R.id.hintText).text = row.text
        }
    }

    private fun bindSection(holder: RecyclerView.ViewHolder, row: HomeRow.Section) {
        val view = holder.itemView
        view.findViewById<ImageView>(R.id.sectionIcon)
            .setImageResource(folderDrawable(row.node.colorIndex))
        view.findViewById<TextView>(R.id.sectionTitle).text = row.node.name
        val count = view.findViewById<TextView>(R.id.sectionCount)
        count.text = if (row.node.unscanned) {
            null
        } else {
            view.context.resources.getQuantityString(
                R.plurals.section_files, row.count, row.count
            )
        }
        // Down while it is open and right while it is shut: the arrow says which way the
        // section will move, so it is turned rather than swapped for a second drawable.
        view.findViewById<ImageView>(R.id.sectionChevron).rotation = if (row.collapsed) -90f else 0f
        view.contentDescription = listOfNotNull(
            row.node.name,
            count.text?.toString()
        ).joinToString(", ")
        view.setOnClickListener { onToggleSection(row) }
        view.setOnLongClickListener { onSectionMenu(row.node); true }
    }

    private fun bindCard(holder: RecyclerView.ViewHolder, row: HomeRow.Card) {
        val view = holder.itemView
        val ref = row.ref
        val (badgeText, badgeColor) = badgeFor(ref.name, ref.mime)
        val icon = view.findViewById<ImageView>(R.id.cardIcon)
        val cover = view.findViewById<ImageView>(R.id.cardCover)

        // The kind of file, in its colour, under the cover. It is what stands in for a photo
        // that has not been decoded yet, a document with no cheap first page, and a file
        // whose cover failed to draw once and will not be tried again.
        setIcon(icon, iconFor(ref.name, ref.mime), badgeColor)
        icon.visibility = View.VISIBLE
        cover.visibility = View.GONE
        cover.setImageDrawable(null)
        cover.tag = null

        val kind = FileKind.detect(ref.ext, ref.mime)
        when {
            // Nothing to draw, or something that has already failed to draw once. A cover is
            // tried once per file and not once per scroll: an encrypted PDF fails every time.
            !Covers.supported(kind, ref.ext) -> Unit
            ref.coverState == Covers.FAILED -> Unit
            else -> Covers.load(view.context, ref, cover, icon) { state ->
                onCoverState(ref, state)
            }
        }

        view.findViewById<TextView>(R.id.cardTitle).text = ref.name
        val subtitle = view.findViewById<TextView>(R.id.cardSubtitle)
        subtitle.text = subtitleFor(view, ref, badgeText)
        view.contentDescription = listOfNotNull(ref.name, subtitle.text?.toString())
            .joinToString(", ")
        view.setOnClickListener { onOpen(ref) }
        view.setOnLongClickListener { onCardMenu(ref); true }
    }

    /**
     * The line under a filename: how big it is, then what it is.
     *
     * "248 MB · PPT" — the size first because that is what a reader scanning a folder of
     * near-identical names is looking for, and the type as the same three letters the badge
     * shows, so the two cannot disagree about what kind of file this is. A provider that will
     * not say how big a file is leaves the size out rather than saying "0 B".
     */
    private fun subtitleFor(view: View, ref: LibraryRef, badgeText: String): String =
        listOfNotNull(
            if (ref.size > 0) Formatter.formatShortFileSize(view.context, ref.size) else null,
            badgeText
        ).joinToString(" · ")
}

/**
 * Draws [res] into [icon] in [color].
 *
 * Every type icon is drawn in one flat colour so that it can be tinted here: a card's icon
 * carries the same colour its badge would have, and a sidebar row's carries the same one
 * again, which is what makes the two columns read as one library rather than two lists.
 */
internal fun setIcon(icon: ImageView, res: Int, color: Int) {
    icon.setImageResource(res)
    icon.imageTintList = ColorStateList.valueOf(color)
}

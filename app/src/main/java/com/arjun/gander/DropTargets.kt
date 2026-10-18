package com.arjun.gander

/**
 * The folder a card dragged out of [from] may be dropped on, given the row under it, or null.
 *
 * Only a folder is a place to drop, and only some folders are: a card is a file, and a file is
 * not a folder. The folder the card is already in is out, since filing it there would add a
 * place it already has; an imported folder is out, because that is a real directory Gander does
 * not own and putting a file in it would mean moving the file on the phone rather than adding a
 * place in the index. That leaves the reader's own folders, which is exactly what the menu's
 * Move to offers.
 *
 * Two rows carry a folder, in the two columns: a section heading in the content column, and a
 * folder row in the sidebar. Both are the same answer, so both are asked here.
 */
internal fun dropTargetFor(row: HomeRow?, from: Long): LibraryNode? = when (row) {
    is HomeRow.Section -> row.node.takeUnless { it.imported || it.id == from }
    else -> null
}

/** The same for the sidebar's rows, of which only a folder's is a place to drop. */
internal fun dropTargetFor(row: SidebarRow?, from: Long): LibraryNode? = when (row) {
    is SidebarRow.Folder -> row.node.takeUnless { it.imported || it.id == from }
    else -> null
}

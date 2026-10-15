package com.arjun.gander

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Reads an imported tree into a flat list of files.
 *
 * "Imported" means a folder the reader granted through `OpenDocumentTree`, and
 * "flat" is the whole point: the design has no nesting, so every level of the
 * tree is walked and only the files come back. A subdirectory is a route to more
 * files, never an entry of its own.
 *
 * The walk queries every directory it can reach. There is a cheaper heuristic —
 * a directory's `COLUMN_LAST_MODIFIED` moves when its direct children change —
 * and it is deliberately not used to skip descending: a file rewritten in place
 * three levels down changes neither of its ancestors' timestamps, so skipping on
 * it would leave a stale size and a stale cover key in the index. The caller may
 * use the timestamps to avoid *writing* rows that did not change, which is safe,
 * because there the comparison is against the same file's own record.
 *
 * [MAX_FILES] is a ceiling rather than a target. Flattening a directory of
 * thirty thousand files would put thirty thousand rows in front of the first
 * frame and thirty thousand covers in a queue, and no screen is improved by it,
 * so the walk stops and says it stopped. The caller asks before that happens.
 */
internal object LibraryScan {

    /** How many files one import will take before asking. See §7.7 of the design. */
    const val MAX_FILES = 5000

    /**
     * One file found in a tree.
     *
     * [uri] is derived from the tree with `buildDocumentUriUsingTree`, which is the only
     * URI that can be built for a file nobody picked: it carries the tree's own grant, and
     * it dies with the tree, which is exactly the lifetime a tree ref has.
     */
    internal data class Found(
        val uri: String,
        val name: String,
        val ext: String,
        val mime: String?,
        val size: Long,
        val mtime: Long
    )

    /** What a walk produced, and whether the ceiling cut it short. */
    internal data class Result(val files: List<Found>, val truncated: Boolean)

    private val COLUMNS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    fun scan(
        resolver: ContentResolver,
        treeUri: Uri,
        max: Int = MAX_FILES
    ): Result {
        val root = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return Result(emptyList(), truncated = false)

        val found = mutableListOf<Found>()
        // Breadth first, because a wide tree stops sooner than a deep one and the ceiling
        // should fall on the shallowest files it can.
        val queue = ArrayDeque<String>()
        // A provider that answers a directory with itself, or with a parent, would otherwise
        // walk forever. Cheap to keep and the only guard there is against that.
        val seen = mutableSetOf<String>()
        queue.addLast(root)
        seen.add(root)
        var truncated = false

        while (queue.isNotEmpty()) {
            val docId = queue.removeFirst()
            val children = childrenOf(resolver, treeUri, docId)
            for (child in children) {
                if (child.mime == MIME_DIR) {
                    if (seen.add(child.docId)) queue.addLast(child.docId)
                    continue
                }
                // Nothing Gander opens is hidden by convention, and a folder of dotfiles
                // reads as noise. The listing has always dropped them; so does the index,
                // or the two would disagree about what a folder holds.
                if (child.name.startsWith(".")) continue
                if (found.size >= max) {
                    truncated = true
                    break
                }
                found += Found(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId)
                        .toString(),
                    name = child.name,
                    ext = child.name.substringAfterLast('.', "").lowercase(),
                    mime = child.mime.ifEmpty { null },
                    size = child.size,
                    mtime = child.modified
                )
            }
            if (truncated) break
        }

        return Result(found, truncated)
    }

    /**
     * One directory's entries.
     *
     * The provider is another process, so this is a Binder call and it belongs on a
     * background thread. A provider that throws — uninstalled, permission revoked, or
     * simply in a bad state — reads as an empty directory rather than failing the whole
     * import: one unreachable folder should not cost the reader the other thirty.
     */
    private fun childrenOf(
        resolver: ContentResolver,
        treeUri: Uri,
        docId: String
    ): List<ChildDoc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val children = mutableListOf<ChildDoc>()
        runCatching {
            resolver.query(uri, COLUMNS, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    children += ChildDoc(
                        docId = c.getString(0) ?: continue,
                        name = c.getString(1) ?: "?",
                        mime = c.getString(2) ?: "",
                        size = c.getLong(3),
                        modified = c.getLong(4)
                    )
                }
            }
        }
        return children
    }
}

package com.arjun.gander

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.database.sqlite.transaction
import androidx.core.net.toUri
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Whether a node is one Gander made up or one the reader granted. */
internal enum class NodeKind(val stored: String) {
    VIRTUAL("virtual"),
    IMPORTED("imported");

    companion object {
        fun of(stored: String?) = entries.firstOrNull { it.stored == stored } ?: VIRTUAL
    }
}

/** Where a ref came from, which decides what deleting it means. */
internal enum class RefOrigin(val stored: String) {
    /** Picked through `OpenDocument`: it carries a grant of its own, and deleting it gives that back. */
    PICK("pick"),

    /** Found inside an imported tree: it lives and dies with the tree's grant. */
    TREE("tree");

    companion object {
        fun of(stored: String?) = entries.firstOrNull { it.stored == stored } ?: PICK
    }
}

/**
 * A folder in the library. [colorIndex] is a slot in the ten-colour palette rather than a
 * colour, so a theme change is a resource lookup rather than a migration.
 */
internal data class LibraryNode(
    val id: Long,
    val kind: NodeKind,
    val name: String,
    val treeUri: String?,
    val docId: String?,
    val colorIndex: Int,
    val position: Int,
    val scannedAt: Long?,
    val createdAt: Long
) {
    val imported: Boolean get() = kind == NodeKind.IMPORTED

    /** An imported folder nobody has looked inside yet, so its files are not in the index. */
    val unscanned: Boolean get() = imported && scannedAt == null
}

/** A file in the library. [size] and [mtime] are -1 when the provider would not say. */
internal data class LibraryRef(
    val id: Long,
    val uri: String,
    val name: String,
    val ext: String,
    val mime: String?,
    val size: Long,
    val mtime: Long,
    val origin: RefOrigin,
    val sourceNode: Long?,
    val addedAt: Long,
    val coverState: Int,
    val coverKey: String?
)

/** One entry of the reading history. */
internal data class OpenedRef(val uri: String, val name: String, val openedAt: Long)

/** What a refresh is doing, for the progress bar at the top of the content column. */
internal data class RefreshState(val running: Boolean, val scanned: Int, val total: Int)

/** Why a refresh was asked for. A cold start is throttled; a pull never is. */
internal enum class RefreshReason { COLD_START, PULL }

/**
 * Told when the index changes. Implemented by the home screen, which is the only thing that
 * draws from it.
 *
 * Both calls arrive on the main thread. [onLibraryChanged] carries the node ids whose
 * sections changed so a refresh can redraw the part of the grid that moved rather than all
 * of it, which is the difference between a list that fills in and one that is replaced.
 */
internal interface LibraryListener {
    fun onLibraryChanged(nodes: Set<Long>?)
    fun onRefresh(state: RefreshState)
}

/**
 * The index, and the one thing that writes to it.
 *
 * It is owned by the [GanderApp] process rather than by the activity, because a refresh is
 * a property of the library and not of whichever screen happens to be open: a rotation
 * recreates the activity, and a refresh running inside it would be thrown away with it.
 * The activity subscribes for as long as it is alive and is held weakly, so the library
 * cannot keep a dead screen on the heap.
 *
 * Reads are synchronous and safe from any background thread; WAL lets them run beside a
 * write. Writes are serialised on this instance, because there are two threads that make
 * them — the home screen's loader and the refresh thread — and a transaction that half
 * applied would leave a ref filed under a node that no longer exists.
 *
 * Nothing here touches the user's files. Deleting a node or a ref drops Gander's own record
 * and, where there is one, the grant behind it; the file on the phone is untouched, which
 * is the promise the whole model rests on.
 */
internal class Library(private val app: Context) {

    private val helper = LibraryDb(app)

    /**
     * One thread for the whole library, daemon so a refresh in flight cannot hold the
     * process open. Refreshing is a sequence of provider queries with a database write
     * between each, and running two at once would mean two writers racing over the same
     * node.
     *
     * Replaceable so a test can run a refresh on the calling thread rather than waiting for
     * one, the same shape the home screen's own loader uses.
     */
    @androidx.annotation.VisibleForTesting
    internal var io: java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "library-refresh").apply { isDaemon = true }
        }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<WeakReference<LibraryListener>>()

    /**
     * Bumped by every refresh and by every cancel. A refresh that finds the number moved
     * stops rather than finishing, which is what lets a second pull take over from a first
     * one still walking a slow folder.
     */
    private var refreshToken = 0

    @Volatile
    private var refreshState = RefreshState(false, 0, 0)

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** Every node, in the order the reader made them. */
    fun nodes(): List<LibraryNode> =
        db.rawQuery("SELECT * FROM node ORDER BY position, id", null).use { c ->
            c.map(::node)
        }

    fun importedNodes(): List<LibraryNode> = nodes().filter { it.imported }

    fun node(id: Long): LibraryNode? =
        db.rawQuery("SELECT * FROM node WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) node(c) else null
        }

    /**
     * What a section shows.
     *
     * A virtual folder shows what was filed under it, newest first, because a made-up
     * folder is a place the reader put things and the last thing put there is the one they
     * are looking for. An imported one shows what it holds, by name, because that is a
     * mirror of a real directory and reordering it would be a lie about the folder.
     */
    fun refsFor(node: LibraryNode): List<LibraryRef> = if (node.imported) {
        db.rawQuery(
            "SELECT * FROM ref WHERE source_node = ? ORDER BY name COLLATE NOCASE",
            arrayOf(node.id.toString())
        ).use { c -> c.map(::ref) }
    } else {
        db.rawQuery(
            """
            SELECT r.* FROM ref r
            JOIN membership m ON m.ref_id = r.id
            WHERE m.node_id = ?
            ORDER BY m.added_at DESC, m.ref_id DESC, r.name COLLATE NOCASE
            """.trimIndent(),
            arrayOf(node.id.toString())
        ).use { c -> c.map(::ref) }
    }

    fun ref(refId: Long): LibraryRef? =
        db.rawQuery("SELECT * FROM ref WHERE id = ?", arrayOf(refId.toString())).use { c ->
            if (c.moveToFirst()) ref(c) else null
        }

    /** The row filed under [uri], or null if the library has never heard of the file. */
    fun refFor(uri: Uri): LibraryRef? =
        db.rawQuery("SELECT * FROM ref WHERE uri = ?", arrayOf(uri.toString())).use { c ->
            if (c.moveToFirst()) ref(c) else null
        }

    /**
     * Files a cover the viewer drew of a document it had open.
     *
     * An Office document is the sort Gander renders but cannot cheaply draw a card for: it is a
     * page in the viewer's WebView, and rendering one off-screen for a card that scrolled past
     * would stall a tablet for seconds at a time. So the viewer photographs the page it has
     * already drawn and hands it here, and this is where it becomes a cover — on the thread that
     * owns the index, and only if the file is one of ours and has no cover yet.
     */
    fun fileCover(uri: Uri, drawn: Bitmap) {
        io.execute {
            val ref = refFor(uri) ?: return@execute
            if (ref.coverState == Covers.READY) return@execute
            markCover(ref.id, Covers.READY, Covers.store(app, ref, drawn))
            // The card for this file is still holding the type icon that stood in for the cover
            // it did not have. The screen showing it is not the one that asked for this — the
            // viewer is — so it is told rather than left to notice.
            changed(null)
        }
    }

    /** How many files a section holds, without reading them. */
    fun countFor(node: LibraryNode): Int = (
        if (node.imported) {
            db.rawQuery("SELECT COUNT(*) FROM ref WHERE source_node = ?", arrayOf(node.id.toString()))
        } else {
            db.rawQuery("SELECT COUNT(*) FROM membership WHERE node_id = ?", arrayOf(node.id.toString()))
        }
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * Files whose name contains [query], across every folder.
     *
     * Only the index is searched, which is a real limit and the UI says so: a file in an
     * imported folder nobody has opened is not in the index yet and cannot be found.
     */
    fun search(query: String, limit: Int = SEARCH_LIMIT): List<LibraryRef> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val pattern = "%" + trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return db.rawQuery(
            "SELECT * FROM ref WHERE name LIKE ? ESCAPE '\\' ORDER BY name COLLATE NOCASE LIMIT $limit",
            arrayOf(pattern)
        ).use { c -> c.map(::ref) }
    }

    /**
     * The reading history, newest first.
     *
     * The grant check the old recents list did is gone. It only ever saw files picked
     * through `OpenDocument`, and the main way a reader opens anything on a tablet is to
     * open a folder and tap a file, which leaves no grant of its own to check for. So the
     * question asked now is the honest one — can this be opened — and an entry that cannot
     * be is hidden rather than deleted, so a grant that comes back brings the entry back
     * with it.
     */
    fun recents(limit: Int = MAX_OPENED): List<OpenedRef> =
        db.rawQuery(
            // rowid breaks a tie: two files opened inside the same millisecond would otherwise
            // come back in whatever order the query planner felt like.
            "SELECT * FROM opened ORDER BY opened_at DESC, rowid DESC LIMIT $limit", null
        ).use { c ->
            c.map { OpenedRef(it.getString(0), it.getString(1), it.getLong(2)) }
                .filter { readable(it.uri) }
        }

    /** Whether anything has ever been opened or filed, which is what tells a first run apart. */
    fun isEmpty(): Boolean =
        db.rawQuery("SELECT (SELECT COUNT(*) FROM node) + (SELECT COUNT(*) FROM opened)", null)
            .use { c -> c.moveToFirst() && c.getInt(0) == 0 }

    /** Whether [uri] can actually be opened right now. See [recents]. */
    private fun readable(uri: String): Boolean = runCatching {
        app.contentResolver.openFileDescriptor(uri.toUri(), "r")?.use { }
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Makes an empty folder. Its colour comes from its own id, so renaming it never recolours it. */
    @Synchronized
    fun createVirtualFolder(name: String): Long {
        val now = System.currentTimeMillis()
        val position = nextPosition()
        val values = ContentValues().apply {
            put("kind", NodeKind.VIRTUAL.stored)
            put("name", name)
            put("color", 0)
            put("position", position)
            put("created_at", now)
        }
        val id = db.insert("node", null, values)
        if (id > 0) {
            db.update(
                "node", ContentValues().apply { put("color", folderColorIndex(id.toString())) },
                "id = ?", arrayOf(id.toString())
            )
        }
        changed(null)
        return id
    }

    /** Renames a folder. An imported one's real directory is not touched; this is a display name. */
    @Synchronized
    fun renameNode(id: Long, name: String) {
        db.update("node", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(id.toString()))
        changed(setOf(id))
    }

    /**
     * Takes a granted tree into the library, without looking inside it.
     *
     * Importing is finished the moment the grant is recorded; the files come later, on the
     * first expansion or the next refresh. Flattening a large tree inside the picker's
     * callback would make the reader wait for it with nothing on screen.
     */
    @Synchronized
    fun importTree(uri: Uri): Long {
        val key = uri.toString()
        nodes().firstOrNull { it.treeUri == key }?.let { return it.id }

        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val name = treeLabel(uri, docId) ?: app.getString(R.string.imported_folder_fallback)
        val id = db.insert("node", null, ContentValues().apply {
            put("kind", NodeKind.IMPORTED.stored)
            put("name", name)
            put("tree_uri", key)
            put("doc_id", docId)
            put("color", folderColorIndex(key))
            put("position", nextPosition())
            put("created_at", System.currentTimeMillis())
        })
        changed(null)
        return id
    }

    /**
     * Takes picked files into the library, each into a folder named after the real one it
     * came from.
     *
     * A picked file does not scatter: files from one directory are filed together under
     * that directory's own name, and a second import from the same place lands in the same
     * folder. When the source cannot be worked out — a cloud provider, an app's private
     * directory — everything goes to one fallback folder instead of one folder per file.
     */
    @Synchronized
    fun importPicked(uris: List<Uri>): List<Long> {
        val added = mutableListOf<Long>()
        for (uri in uris) {
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val meta = queryMeta(uri) ?: continue
            val folder = sourceFolderName(uri) ?: app.getString(R.string.imported_files)
            val nodeId = nodes().firstOrNull { it.name == folder && !it.imported }?.id
                ?: createVirtualFolder(folder)
            val refId = upsertRef(
                uri = uri.toString(),
                name = meta.name,
                mime = meta.mime,
                size = meta.size,
                mtime = meta.mtime,
                origin = RefOrigin.PICK,
                sourceNode = null
            ) ?: continue
            db.insertWithOnConflict(
                "membership", null,
                ContentValues().apply {
                    put("node_id", nodeId)
                    put("ref_id", refId)
                    put("added_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
            added += refId
        }
        changed(null)
        return added
    }

    /**
     * Files a ref under another virtual folder.
     *
     * Membership is many-to-many, so this adds a place rather than moving one: a file from
     * an imported folder can sit in three made-up folders at once and still be in the
     * folder it came from.
     */
    @Synchronized
    fun moveRef(refId: Long, nodeId: Long) {
        db.insertWithOnConflict(
            "membership", null,
            ContentValues().apply {
                put("node_id", nodeId)
                put("ref_id", refId)
                put("added_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        changed(setOf(nodeId))
    }

    /**
     * Takes a ref out of the library.
     *
     * A picked file's grant goes with it, because nothing else opens a file by it. A tree
     * file has no grant of its own, so there is nothing to give back; it comes back on the
     * next scan of the tree it lives in, which is the honest answer to removing a file
     * Gander never owned.
     */
    @Synchronized
    fun removeRef(refId: Long) {
        val target = ref(refId) ?: return
        val nodes = nodesTouchedBy(refId)
        db.delete("membership", "ref_id = ?", arrayOf(refId.toString()))
        db.delete("ref", "id = ?", arrayOf(refId.toString()))
        if (target.origin == RefOrigin.PICK) release(target.uri)
        changed(nodes)
    }

    /**
     * Deletes a folder.
     *
     * What that means depends on what it is. A virtual folder is a list of memberships and
     * nothing else: the memberships go, and a picked file left in no folder at all goes
     * with them, grant and all, because an unfiled pick would be an invisible grant. A file
     * that came out of an imported tree stays in that tree, which is where it came from.
     *
     * An imported folder is a grant: deleting it gives the grant back and drops everything
     * that was found through it.
     */
    @Synchronized
    fun deleteNode(id: Long) {
        val target = node(id) ?: return
        if (target.imported) {
            val found = db.rawQuery("SELECT * FROM ref WHERE source_node = ?", arrayOf(id.toString()))
                .use { c -> c.map(::ref) }
            found.forEach { member ->
                db.delete("membership", "ref_id = ?", arrayOf(member.id.toString()))
                db.delete("ref", "id = ?", arrayOf(member.id.toString()))
                if (member.origin == RefOrigin.PICK) release(member.uri)
            }
            target.treeUri?.let { releaseTree(it) }
        } else {
            val members = refsFor(target)
            db.delete("membership", "node_id = ?", arrayOf(id.toString()))
            members.filter { it.origin == RefOrigin.PICK && membershipsOf(it.id) == 0 }
                .forEach { orphan ->
                    db.delete("ref", "id = ?", arrayOf(orphan.id.toString()))
                    release(orphan.uri)
                }
        }
        db.delete("node", "id = ?", arrayOf(id.toString()))
        changed(null)
    }

    /** Records that a file was opened. The history is capped; the oldest falls off the end. */
    @Synchronized
    fun recordOpened(uri: Uri, name: String) {
        db.insertWithOnConflict(
            "opened", null,
            ContentValues().apply {
                put("uri", uri.toString())
                put("name", name)
                put("opened_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
        db.execSQL(
            "DELETE FROM opened WHERE uri NOT IN " +
                "(SELECT uri FROM opened ORDER BY opened_at DESC, rowid DESC LIMIT $MAX_OPENED)"
        )
        changed(null)
    }

    /**
     * Records an open from a screen that is not already on a background thread.
     *
     * The viewer learns a file was opened while it is putting that file on screen, which is the
     * main thread, and a database write there is a frame the reader does not get. Nothing waits
     * for this: the history is a convenience, and the file is open either way.
     */
    fun recordOpenedAsync(uri: Uri, name: String) {
        io.execute { recordOpened(uri, name) }
    }

    /** Drops one entry from the reading history. The file itself, and any ref, is untouched. */
    @Synchronized
    fun forgetOpened(uri: String) {
        db.delete("opened", "uri = ?", arrayOf(uri))
        changed(null)
    }

    /** Keeps what a cover did, so a failure is not tried again on every scroll. */
    @Synchronized
    fun markCover(refId: Long, state: Int, key: String?) {
        db.update(
            "ref",
            ContentValues().apply {
                put("cover_state", state)
                put("cover_key", key)
            },
            "id = ?", arrayOf(refId.toString())
        )
    }

    // ------------------------------------------------------------------
    // Refreshing
    // ------------------------------------------------------------------

    fun addListener(listener: LibraryListener) {
        listeners += WeakReference(listener)
    }

    fun removeListener(listener: LibraryListener) {
        listeners.removeAll { it.get() === listener || it.get() == null }
    }

    /**
     * Reads an imported tree into the index, if it has never been read.
     *
     * Returns whether the tree held more files than the ceiling allows, which the caller
     * says out loud rather than leaving the reader to wonder where the rest went.
     */
    @Synchronized
    fun ensureScanned(nodeId: Long): Boolean {
        val target = node(nodeId) ?: return false
        if (!target.unscanned) return false
        return scanInto(target).truncated
    }

    /**
     * Rescans every imported folder.
     *
     * The index is a snapshot, and this is the only thing that moves it. There is no
     * content observer behind it on purpose: the platform's change notifications for
     * documents are per-directory, tied to an open cursor, and optional per provider, so
     * covering a flattened tree would mean holding a cursor open on every directory in it.
     * A cold start and a pull are the two moments the reader has asked for fresh data.
     *
     * A cold start is skipped if one happened less than [COLD_START_QUIET_MS] ago, because
     * opening and closing the app ten times is not ten reasons to walk a photo library.
     * The stamp lives in the database, so it survives the process that wrote it.
     */
    fun refresh(reason: RefreshReason) {
        if (reason == RefreshReason.COLD_START && refreshedRecently()) return
        val token = ++refreshToken
        publish(RefreshState(running = true, scanned = 0, total = 0))

        io.execute {
            val targets = importedNodes()
            publish(RefreshState(running = true, scanned = 0, total = targets.size))
            var done = 0
            for (target in targets) {
                if (token != refreshToken) return@execute
                // Re-read: another writer may have deleted it between the listing and here.
                val fresh = node(target.id) ?: continue
                scanInto(fresh)
                done++
                publish(RefreshState(running = true, scanned = done, total = targets.size))
                changed(setOf(fresh.id))
            }
            if (token == refreshToken) stampRefresh()
            publish(RefreshState(running = false, scanned = done, total = targets.size))
        }
    }

    /** Stops a refresh in flight. The next pull starts a new one. */
    fun cancelRefresh() {
        refreshToken++
        publish(RefreshState(running = false, scanned = 0, total = 0))
    }

    fun currentRefreshState(): RefreshState = refreshState

    /**
     * Walks one tree and writes what changed.
     *
     * One transaction for the whole folder, because three thousand rows written one
     * transaction each is three thousand fsyncs. Within it only rows whose size or
     * modification time moved are written: the comparison is against the file's own last
     * record, so it cannot hide a change, and it is what keeps a rescan of an untouched
     * photo library down to a read.
     */
    @Synchronized
    private fun scanInto(folder: LibraryNode): LibraryScan.Result {
        val treeUri = folder.treeUri?.toUri() ?: return LibraryScan.Result(emptyList(), false)
        val result = LibraryScan.scan(app.contentResolver, treeUri)

        val existing = db.rawQuery(
            "SELECT * FROM ref WHERE source_node = ?", arrayOf(folder.id.toString())
        ).use { c -> c.map(::ref).associateBy { it.uri } }

        val seen = mutableSetOf<String>()
        db.transaction {
            result.files.forEach { found ->
                seen += found.uri
                val before = existing[found.uri]
                if (before == null) {
                    val id = insertRef(
                        uri = found.uri, name = found.name, ext = found.ext, mime = found.mime,
                        size = found.size, mtime = found.mtime,
                        origin = RefOrigin.TREE, sourceNode = folder.id
                    )
                    if (id <= 0) {
                        // The URI is already in the library under another name — the same file
                        // picked by hand and then found in a tree. The pick's row wins, because
                        // that is the one with a grant of its own.
                        db.update(
                            "ref",
                            ContentValues().apply {
                                put("size", found.size)
                                put("mtime", found.mtime)
                                put("mime", found.mime)
                            },
                            "uri = ?", arrayOf(found.uri)
                        )
                    }
                } else if (before.size != found.size || before.mtime != found.mtime) {
                    db.update(
                        "ref",
                        ContentValues().apply {
                            put("name", found.name)
                            put("size", found.size)
                            put("mtime", found.mtime)
                            put("mime", found.mime)
                            // The file behind the URI changed, so whatever cover was drawn of
                            // it is of something that is no longer there.
                            put("cover_state", Covers.NONE)
                            putNull("cover_key")
                        },
                        "id = ?", arrayOf(before.id.toString())
                    )
                }
            }

            // Gone from the tree, gone from the index. Its memberships go with it, and a
            // picked file that is also filed somewhere keeps its row: only tree rows are
            // pruned here, because only they were enumerated.
            existing.values.filter { it.uri !in seen && it.origin == RefOrigin.TREE }
                .forEach { stale ->
                    db.delete("membership", "ref_id = ?", arrayOf(stale.id.toString()))
                    db.delete("ref", "id = ?", arrayOf(stale.id.toString()))
                }

            db.update(
                "node",
                ContentValues().apply { put("scanned_at", System.currentTimeMillis()) },
                "id = ?", arrayOf(folder.id.toString())
            )
        }
        return result
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    private fun node(c: Cursor) = LibraryNode(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        kind = NodeKind.of(c.getString(c.getColumnIndexOrThrow("kind"))),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        treeUri = c.string("tree_uri"),
        docId = c.string("doc_id"),
        colorIndex = c.getInt(c.getColumnIndexOrThrow("color")),
        position = c.getInt(c.getColumnIndexOrThrow("position")),
        scannedAt = c.long("scanned_at"),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
    )

    private fun ref(c: Cursor) = LibraryRef(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        uri = c.getString(c.getColumnIndexOrThrow("uri")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        ext = c.getString(c.getColumnIndexOrThrow("ext")),
        mime = c.string("mime"),
        size = c.getLong(c.getColumnIndexOrThrow("size")),
        mtime = c.getLong(c.getColumnIndexOrThrow("mtime")),
        origin = RefOrigin.of(c.getString(c.getColumnIndexOrThrow("origin"))),
        sourceNode = c.long("source_node"),
        addedAt = c.getLong(c.getColumnIndexOrThrow("added_at")),
        coverState = c.getInt(c.getColumnIndexOrThrow("cover_state")),
        coverKey = c.string("cover_key")
    )

    private fun Cursor.string(column: String): String? {
        val at = getColumnIndex(column)
        return if (at < 0 || isNull(at)) null else getString(at)
    }

    private fun Cursor.long(column: String): Long? {
        val at = getColumnIndex(column)
        return if (at < 0 || isNull(at)) null else getLong(at)
    }

    private fun <T> Cursor.map(one: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(count)
        while (moveToNext()) out += one(this)
        return out
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun nextPosition(): Int =
        db.rawQuery("SELECT IFNULL(MAX(position), -1) + 1 FROM node", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun membershipsOf(refId: Long): Int =
        db.rawQuery("SELECT COUNT(*) FROM membership WHERE ref_id = ?", arrayOf(refId.toString()))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun nodesTouchedBy(refId: Long): Set<Long> =
        db.rawQuery("SELECT node_id FROM membership WHERE ref_id = ?", arrayOf(refId.toString()))
            .use { c -> c.map { it.getLong(0) }.toSet() }

    private fun insertRef(
        uri: String, name: String, ext: String, mime: String?, size: Long, mtime: Long,
        origin: RefOrigin, sourceNode: Long?
    ): Long = db.insertWithOnConflict(
        "ref", null,
        ContentValues().apply {
            put("uri", uri)
            put("name", name)
            put("ext", ext)
            put("mime", mime)
            put("size", size)
            put("mtime", mtime)
            put("origin", origin.stored)
            if (sourceNode == null) putNull("source_node") else put("source_node", sourceNode)
            put("added_at", System.currentTimeMillis())
            put("cover_state", Covers.NONE)
        },
        SQLiteDatabase.CONFLICT_IGNORE
    )

    /**
     * Writes a ref, or brings an existing one up to date. Returns its id, or null when the
     * provider would not describe the file at all.
     */
    private fun upsertRef(
        uri: String, name: String, mime: String?, size: Long, mtime: Long,
        origin: RefOrigin, sourceNode: Long?
    ): Long? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val id = insertRef(uri, name, ext, mime, size, mtime, origin, sourceNode)
        if (id > 0) return id
        // Already there: a re-import of the same file. Keep the row and its grant, take the
        // provider's fresher description of it.
        val existing = db.rawQuery("SELECT * FROM ref WHERE uri = ?", arrayOf(uri))
            .use { c -> if (c.moveToFirst()) ref(c) else null } ?: return null
        db.update(
            "ref",
            ContentValues().apply {
                put("name", name)
                put("ext", ext)
                put("mime", mime)
                put("size", size)
                put("mtime", mtime)
            },
            "id = ?", arrayOf(existing.id.toString())
        )
        return existing.id
    }

    /** A picked file's own description, as the provider gives it. */
    private class Meta(val name: String, val mime: String?, val size: Long, val mtime: Long)

    private fun queryMeta(uri: Uri): Meta? = runCatching {
        app.contentResolver.query(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.string(OpenableColumns.DISPLAY_NAME)
                ?: uri.lastPathSegment ?: return@use null
            Meta(
                name = name,
                mime = runCatching { app.contentResolver.getType(uri) }.getOrNull(),
                size = c.long(OpenableColumns.SIZE) ?: -1L,
                mtime = c.long(DocumentsContract.Document.COLUMN_LAST_MODIFIED) ?: -1L
            )
        }
    }.getOrNull()

    /**
     * The name of the real folder a picked file came from, so files from one directory are
     * filed together under its name. Null when the provider does not expose a real location,
     * which is every cloud provider and most app-private ones.
     */
    private fun sourceFolderName(uri: Uri): String? {
        @Suppress("DEPRECATION")
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath
        val parent = parentDocUri(uri, primaryRoot) { mediaUri ->
            runCatching {
                app.contentResolver.query(mediaUri, arrayOf("_data"), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        } ?: return null
        return runCatching {
            app.contentResolver.query(
                parent, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun treeLabel(uri: Uri, docId: String?): String? {
        val id = docId ?: return null
        return runCatching {
            app.contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(uri, id),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun release(uri: String) {
        runCatching {
            app.contentResolver.releasePersistableUriPermission(
                uri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun releaseTree(treeUri: String) = release(treeUri)

    private fun refreshedRecently(): Boolean {
        val at = db.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(META_LAST_REFRESH))
            .use { c -> if (c.moveToFirst()) c.getString(0)?.toLongOrNull() ?: 0L else 0L }
        return System.currentTimeMillis() - at < COLD_START_QUIET_MS
    }

    private fun stampRefresh() {
        db.insertWithOnConflict(
            "meta", null,
            ContentValues().apply {
                put("k", META_LAST_REFRESH)
                put("v", System.currentTimeMillis().toString())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun publish(state: RefreshState) {
        refreshState = state
        main.post {
            listeners.removeAll { it.get() == null }
            listeners.mapNotNull { it.get() }.forEach { it.onRefresh(state) }
        }
    }

    private fun changed(nodes: Set<Long>?) {
        main.post {
            listeners.removeAll { it.get() == null }
            listeners.mapNotNull { it.get() }.forEach { it.onLibraryChanged(nodes) }
        }
    }

    /** Closes the database and stops the refresh thread. The app process lives as long as it does. */
    fun close() {
        io.shutdownNow()
        helper.close()
    }

    internal companion object {
        /** How many entries the sidebar's reading history keeps. See §7.6. */
        const val MAX_OPENED = 10

        /** How many hits a search will draw. More than this is not a list, it is a haystack. */
        const val SEARCH_LIMIT = 300

        /** A cold start within this long of the last one does not rescan. */
        const val COLD_START_QUIET_MS = 60_000L

        private const val META_LAST_REFRESH = "last_refresh_at"
    }
}

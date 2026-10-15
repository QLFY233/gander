package com.arjun.gander

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/**
 * The library index: five tables the home screen's two columns are both views of.
 *
 * `node` is a folder, and a folder here is not a directory. It is either a
 * *virtual* folder Gander made up, which owns nothing but its own name and
 * colour, or an *imported* one, which is a tree the reader granted through
 * `OpenDocumentTree`. Neither can hold the other: the tree is flat, because the
 * content column flattens an imported tree's whole depth into refs anyway.
 *
 * `ref` is a file — a URI and what the provider said about it. It is the only
 * thing that can be opened, and it is what "all files" means: Gander has no
 * storage permission and cannot see a device's files, only the ones somebody
 * handed it. `membership` is the many-to-many between a virtual folder and a
 * ref, because a file from an imported folder can be filed under several made-up
 * ones while still belonging to the folder it came from.
 *
 * `opened` is the reading history behind the sidebar's recent list. It is
 * deliberately not a `ref`: a file that was opened once and never filed is still
 * worth offering again, and the file it points at may not be in the library at
 * all.
 *
 * The framework's own SQLite rather than Room, which would be a new dependency
 * and a new licence to account for to buy nothing: there are five tables, one
 * version, and no migration yet.
 */
internal class LibraryDb(private val app: Context) :
    SQLiteOpenHelper(app, NAME, null, VERSION) {

    init {
        // The refresh thread writes while the home screen's own loader thread reads,
        // and a rollback journal makes those two wait for each other. This is the one
        // place in the app where two threads touch the same database.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE node (
              id         INTEGER PRIMARY KEY,
              kind       TEXT    NOT NULL,
              name       TEXT    NOT NULL,
              tree_uri   TEXT,
              doc_id     TEXT,
              color      INTEGER NOT NULL,
              position   INTEGER NOT NULL,
              scanned_at INTEGER,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE ref (
              id          INTEGER PRIMARY KEY,
              uri         TEXT    NOT NULL UNIQUE,
              name        TEXT    NOT NULL,
              ext         TEXT    NOT NULL,
              mime        TEXT,
              size        INTEGER NOT NULL DEFAULT -1,
              mtime       INTEGER NOT NULL DEFAULT -1,
              origin      TEXT    NOT NULL,
              source_node INTEGER,
              added_at    INTEGER NOT NULL,
              cover_state INTEGER NOT NULL DEFAULT 0,
              cover_key   TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX ref_source ON ref(source_node)")
        db.execSQL("CREATE INDEX ref_name ON ref(name COLLATE NOCASE)")
        db.execSQL(
            """
            CREATE TABLE membership (
              node_id  INTEGER NOT NULL,
              ref_id   INTEGER NOT NULL,
              added_at INTEGER NOT NULL,
              PRIMARY KEY (node_id, ref_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE opened (
              uri       TEXT PRIMARY KEY,
              name      TEXT NOT NULL,
              opened_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")

        adoptRecents(db)
    }

    /**
     * v1 is the first version of this database, so there is nothing to upgrade from yet.
     * The hook exists because [VERSION] is not going to stay at 1: the index is the part of
     * this app most likely to grow a column, and a reader who updates should not have to
     * import their folders again.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /**
     * Carries the old recents list into `opened`, once, on the first run of a build that has
     * a library.
     *
     * They go to `opened` and not to `ref`: those entries were "opened", which is what the
     * recent list is, not "filed", which is what a ref is. The old JSON store is left where
     * it is rather than cleared, so a reader who rolls back to the previous build still has
     * their list; nothing reads it after this.
     */
    private fun adoptRecents(db: SQLiteDatabase) {
        val raw = runCatching {
            app.getSharedPreferences(RECENTS_PREFS, Context.MODE_PRIVATE)
                .getString(RECENTS_KEY, "[]")!!
        }.getOrNull() ?: return
        val items = runCatching { JSONArray(raw) }.getOrNull() ?: return
        if (items.length() == 0) return

        db.beginTransaction()
        try {
            for (i in 0 until items.length()) {
                val item = runCatching { items.getJSONObject(i) }.getOrNull() ?: continue
                val uri = item.optString("uri", "")
                if (uri.isEmpty()) continue
                db.insertWithOnConflict(
                    "opened", null,
                    android.content.ContentValues().apply {
                        put("uri", uri)
                        put("name", item.optString("name", uri))
                        put("opened_at", item.optLong("time", System.currentTimeMillis()))
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    internal companion object {
        const val NAME = "library.db"

        /** Bumped when a table changes, and handled in [onUpgrade]. */
        const val VERSION = 1

        /** Where the pre-library builds kept the reading history. Read once, never written. */
        const val RECENTS_PREFS = "recents"
        const val RECENTS_KEY = "items"
    }
}

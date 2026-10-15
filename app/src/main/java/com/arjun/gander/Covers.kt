package com.arjun.gander

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import androidx.core.net.toUri
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The cover on a card in the content column.
 *
 * This is [Thumbs] with three differences, and each of them is why it is a second object
 * rather than a flag on the first one.
 *
 * It lives in `filesDir/covers`, not `cacheDir`. A thumbnail in the cache is a thumbnail the
 * system may delete at any moment, and the moment it does, every card on screen goes back to
 * decoding a PDF's first page or a photo's pixels — which is the cost this cache exists to
 * avoid. `filesDir` is not touched by the system, so the size is capped here instead: past
 * [MAX_BYTES] or [MAX_FILES] the oldest covers go.
 *
 * The key carries the file's size and modification time. The thumbnail key did not, so a
 * replaced file kept showing the old cover for good. With them in the key, a rewritten file
 * is simply a miss, and the stale file falls off the end of the cache on its own.
 *
 * It is drawn at [LONG_EDGE], because it is shown at the width of a card rather than the
 * 48dp of a list row.
 *
 * A cover that cannot be drawn is a state and not a retry. Encrypted PDFs and videos with no
 * seekable first frame fail every time, and failing again on every scroll is worse than the
 * type icon that stands in for them.
 */
internal object Covers {

    /** Not tried yet. */
    const val NONE = 0

    /** Drawn, and on disk under [LibraryRef.coverKey]. */
    const val READY = 1

    /** Tried and failed. Not tried again until the file's size or time changes. */
    const val FAILED = 2

    /**
     * Nothing to show and nothing to draw: audio, zips, and the Office formats. A Word
     * document reports this until the reader has opened it once, since the viewer is the only
     * place one is ever rendered and its cover is a photograph of that.
     */
    const val UNSUPPORTED = 3

    /** Long edge of a stored cover, in pixels. A 3:4 card shows it at about 190dp on a tablet. */
    private const val LONG_EDGE = 512

    private const val MAX_BYTES = 200L * 1024 * 1024
    private const val MAX_FILES = 5000

    /** How long one cover may take before it is given up on. */
    private const val TIMEOUT_MS = 2_000L

    /** Covers in memory, for the cards that scroll back into view. */
    private val mem = LruCache<String, Bitmap>(24)

    /** Orchestration: one task per card that scrolls in. */
    private val workers = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "covers").apply { isDaemon = true }
    }

    /**
     * The actual drawing, on a thread of its own.
     *
     * Separate from [workers] so that a decode which never returns — a PDF on a slow card, a
     * video with no first frame — costs its own two seconds rather than occupying one of the
     * two workers for good.
     */
    private val renderer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cover-render").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * What a cover of [uri] is filed under.
     *
     * Size and modification time are in it on purpose: the file behind a URI can be replaced,
     * and a cover of what used to be there is worse than no cover at all. -1 for either means
     * the provider would not say, in which case the key is as stable as it can be.
     */
    fun key(uri: String, size: Long, mtime: Long): String =
        md5("$uri|$size|$mtime")

    fun key(ref: LibraryRef): String = key(ref.uri, ref.size, ref.mtime)

    /** Which kinds have a cover worth drawing. See §5.2 of the design. */
    fun supported(kind: FileKind, ext: String): Boolean = when (kind) {
        FileKind.IMAGE, FileKind.IMAGE_WEB, FileKind.PDF -> true
        FileKind.PLAYER -> !FileKind.isAudioExt(ext)
        else -> false
    }

    /**
     * Whether a cover for this kind can exist at all: one drawn here from the file, or one the
     * viewer filed after the reader opened it. A Word document is the second sort, and is the
     * only one — the viewer is the only place one is ever rendered.
     */
    fun covered(kind: FileKind, ext: String): Boolean =
        supported(kind, ext) || kind == FileKind.DOCX

    /**
     * Draws the cover of [ref] into [into], hiding [badge] when it arrives.
     *
     * [onState] is told what happened, on the main thread, so the caller can keep that answer
     * in the index and not ask again. It is called with [UNSUPPORTED] and [FAILED] too, and
     * it is called whether or not the view is still showing this card: a cover that was drawn
     * while the reader scrolled on is still worth keeping.
     */
    fun load(
        context: Context,
        ref: LibraryRef,
        into: ImageView,
        badge: View,
        onState: (Int) -> Unit = {}
    ) {
        val kind = FileKind.detect(ref.ext, ref.mime)
        val key = key(ref)
        into.tag = key
        mem.get(key)?.let {
            show(into, badge, it)
            onState(READY)
            return
        }

        // Two ways a cover comes about: drawn here from the file, or filed by the viewer, which
        // is the only place a Word document is ever rendered. See §5.4 of the design.
        if (!covered(kind, ref.ext)) {
            onState(UNSUPPORTED)
            return
        }
        val drawable = supported(kind, ref.ext)

        val appCtx = context.applicationContext
        workers.execute {
            val state = if (drawable) drawNow(appCtx, ref) else filedCover(appCtx, ref)
            main.post {
                if (state == Covers.READY) {
                    mem.get(key)?.let { if (into.tag == key) show(into, badge, it) }
                }
                onState(state)
            }
        }
    }

    /**
     * [drawNow] for a kind that cannot be drawn here: the cover the viewer left behind, if it
     * has been opened. Nothing has to be rendered, so this is a disk read and no more.
     */
    private fun filedCover(ctx: Context, ref: LibraryRef): Int {
        val key = key(ref)
        val bmp = fromDisk(ctx, key) ?: return UNSUPPORTED
        mem.put(key, bmp)
        return READY
    }

    /**
     * Files a cover that was drawn somewhere else, and says what it is filed under.
     *
     * The viewer is the only caller. A Word document is rendered by a page in a WebView, and
     * drawing one again off-screen for a card that scrolled past would stall a tablet for
     * seconds at a time, so the viewer photographs the page it has already put on screen and
     * hands it here. From this point it is a cover like any other: scaled to [LONG_EDGE],
     * written under the same key as every other kind, and kept in memory for the card that is
     * about to ask for it.
     */
    fun store(context: Context, ref: LibraryRef, drawn: Bitmap): String {
        val key = key(ref)
        val stored = scaleToLongEdge(drawn)
        toDisk(context, key, stored)
        prune(context)
        mem.put(key, stored)
        if (stored !== drawn) drawn.recycle()
        return key
    }

    /** Down to [LONG_EDGE]; anything already that small is left alone. */
    private fun scaleToLongEdge(bmp: Bitmap): Bitmap {
        val longEdge = maxOf(bmp.width, bmp.height)
        if (longEdge <= LONG_EDGE) return bmp
        val scale = LONG_EDGE.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    /**
     * Draws [ref]'s cover on the calling thread and says what came of it.
     *
     * [load] is this plus a worker thread and a view; separating them leaves the part worth
     * testing reachable without waiting on a thread the test does not own. Returns [READY],
     * [FAILED] or [UNSUPPORTED].
     */
    internal fun drawNow(ctx: Context, ref: LibraryRef): Int {
        val kind = FileKind.detect(ref.ext, ref.mime)
        if (!supported(kind, ref.ext)) return UNSUPPORTED
        val key = key(ref)
        fromDisk(ctx, key)?.let {
            mem.put(key, it)
            return READY
        }
        val bmp = draw(ctx, ref.uri.toUri(), kind, ref.ext) ?: return FAILED
        toDisk(ctx, key, bmp)
        prune(ctx)
        mem.put(key, bmp)
        return READY
    }

    /** Drops the cover of [ref], in memory and on disk. */
    fun evict(context: Context, ref: LibraryRef) {
        val key = key(ref)
        mem.remove(key)
        diskFile(context, key).delete()
    }

    /** Drops the in-memory covers. Process-wide state leaks between unit tests otherwise. */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTests() {
        mem.evictAll()
    }

    private fun draw(ctx: Context, uri: Uri, kind: FileKind, ext: String): Bitmap? {
        val asked = renderer.submit(Callable { Thumbs.render(ctx, uri, kind, ext, LONG_EDGE) })
        return runCatching { asked.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun show(into: ImageView, badge: View, bmp: Bitmap) {
        into.setImageBitmap(bmp)
        into.visibility = View.VISIBLE
        badge.visibility = View.GONE
    }

    private fun diskDir(ctx: Context): File = File(ctx.filesDir, "covers").apply { mkdirs() }

    private fun diskFile(ctx: Context, key: String): File = File(diskDir(ctx), "$key.png")

    private fun fromDisk(ctx: Context, key: String): Bitmap? {
        val f = diskFile(ctx, key)
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    private fun toDisk(ctx: Context, key: String, bmp: Bitmap) {
        runCatching {
            diskFile(ctx, key).outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 90, it)
            }
        }
    }

    /**
     * Keeps the cover directory inside its two ceilings, oldest first.
     *
     * The system will not do this for us here the way it would in the cache directory, so
     * something has to. It runs after a write rather than on a timer: the directory only
     * grows when a cover is added, so that is the only moment there is anything to do.
     *
     * The ceilings are parameters so a test can reach them without writing 200 MB.
     */
    internal fun prune(ctx: Context, maxFiles: Int = MAX_FILES, maxBytes: Long = MAX_BYTES) {
        runCatching {
            val files = diskDir(ctx).listFiles()?.filter { it.isFile } ?: return
            var bytes = files.sumOf { it.length() }
            if (files.size <= maxFiles && bytes <= maxBytes) return
            val oldestFirst = files.sortedBy { it.lastModified() }
            var count = files.size
            for (f in oldestFirst) {
                if (count <= maxFiles && bytes <= maxBytes) break
                val size = f.length()
                if (f.delete()) {
                    count--
                    bytes -= size
                    mem.remove(f.nameWithoutExtension)
                }
            }
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

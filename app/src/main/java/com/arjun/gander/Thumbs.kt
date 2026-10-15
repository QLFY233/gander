package com.arjun.gander

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Small preview thumbnails for images, videos and PDFs.
 * Memory LRU + PNG disk cache under cacheDir/thumbs, generated off the
 * main thread. Kinds without a cheap visual preview simply keep their badge.
 *
 * A file inside a zip gets the memory cache and never the disk. Nothing in a zip is written
 * to the phone, which is what Gander says about opening one, and a thumbnail on disk is a
 * copy of a photo, only smaller.
 */
object Thumbs {

    private const val SIZE = 192

    private val mem = LruCache<String, Bitmap>(48)
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun supported(kind: FileKind, ext: String): Boolean = when (kind) {
        FileKind.IMAGE, FileKind.IMAGE_WEB, FileKind.PDF -> true
        FileKind.PLAYER -> !FileKind.isAudioExt(ext)
        else -> false
    }

    /**
     * Which files inside a zip get one. A photo, however it is packed, since decoding one from
     * the start is what decoding a photo is. A video only when it is stored as it is and not
     * encrypted, which makes it a window onto the archive the retriever can seek in; through
     * the pipe, finding one frame would mean inflating the video up to it. Never a PDF, which
     * PdfRenderer will only take as a whole file, and a window onto a zip is not one.
     */
    internal fun supportedInArchive(kind: FileKind, ext: String, at: EntryLocation): Boolean = when (kind) {
        FileKind.IMAGE, FileKind.IMAGE_WEB -> true
        FileKind.PLAYER -> !FileKind.isAudioExt(ext) && at.method == ZipReader.METHOD_STORED && at.lock == Lock.NONE
        else -> false
    }

    /** Shows the thumbnail in [into] and hides [badge] once ready. */
    fun load(context: Context, uri: Uri, ext: String, into: ImageView, badge: View) {
        val key = md5(uri.toString())
        into.tag = key
        mem.get(key)?.let {
            into.setImageBitmap(it)
            into.visibility = View.VISIBLE
            badge.visibility = View.GONE
            return
        }
        val appCtx = context.applicationContext
        val kind = FileKind.detect(ext, null)
        val onDisk = !ArchiveProvider.isEntry(appCtx, uri)
        executor.execute {
            val bmp = (if (onDisk) fromDisk(appCtx, key) else null)
                ?: generate(appCtx, uri, kind, ext, SIZE)?.also { if (onDisk) toDisk(appCtx, key, it) }
                ?: return@execute
            mem.put(key, bmp)
            main.post {
                if (into.tag == key) {
                    into.setImageBitmap(bmp)
                    into.visibility = View.VISIBLE
                    badge.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Drops the in-memory cache.
     *
     * This object is process-wide and its LruCache outlives any one screen,
     * which is what makes it useful and also what makes it leak between unit
     * tests. Nothing in the app calls this.
     */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTests() {
        mem.evictAll()
    }

    fun evict(context: Context, uriString: String) {
        val key = md5(uriString)
        mem.remove(key)
        diskFile(context, key).delete()
    }

    fun exifRotation(resolver: ContentResolver, uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_TRANSPOSE -> 90
                ExifInterface.ORIENTATION_ROTATE_180,
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSVERSE -> 270
                else -> 0
            }
        } ?: 0
    }.getOrDefault(0)

    private fun generate(ctx: Context, uri: Uri, kind: FileKind, ext: String, size: Int): Bitmap? =
        runCatching {
            when {
                kind == FileKind.PDF -> pdfThumb(ctx, uri, size)
                kind == FileKind.PLAYER -> videoThumb(ctx, uri, size)
                else -> imageThumb(ctx, uri, size)
            }
        }.getOrNull()

    /**
     * Draws a preview of [uri] at [size] on its long edge, for a caller that keeps the result
     * somewhere of its own.
     *
     * The card covers in [Covers] draw at a much larger size than a list row does and cache
     * under a different key in a different directory, so they cannot use [load]; they can use
     * the drawing, which is the part that knows how to read a photo, a video's first frame and
     * a PDF's first page. Null for anything that cannot be drawn, which is the answer this has
     * always given.
     */
    internal fun render(ctx: Context, uri: Uri, kind: FileKind, ext: String, size: Int): Bitmap? =
        generate(ctx, uri, kind, ext, size)

    private fun imageThumb(ctx: Context, uri: Uri, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val rotation = exifRotation(ctx.contentResolver, uri)
        if (rotation == 0) return bmp
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    private fun videoThumb(ctx: Context, uri: Uri, size: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ctx, uri)
            val frame = retriever.frameAtTime ?: return null
            val scale = size.toFloat() / maxOf(frame.width, frame.height)
            if (scale >= 1f) frame
            else Bitmap.createScaledBitmap(
                frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun pdfThumb(ctx: Context, uri: Uri, size: Int): Bitmap? {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
        pfd.use {
            PdfRenderer(it).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val height = (size.toFloat() * page.height / page.width).toInt()
                        .coerceIn(1, size * 2)
                    val bmp = Bitmap.createBitmap(size, height, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        }
    }

    private fun diskDir(ctx: Context): File =
        File(ctx.cacheDir, "thumbs").apply { mkdirs() }

    private fun diskFile(ctx: Context, key: String): File =
        File(diskDir(ctx), "$key.png")

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

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

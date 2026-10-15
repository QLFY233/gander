package com.arjun.gander

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The card covers.
 *
 * Three of these pin the reasons [Covers] exists as a second cache beside `Thumbs`: the
 * directory it uses is one the system will not clear, the key carries the file's own size
 * and time so a replaced file cannot keep showing its predecessor's cover, and a kind with
 * no cheap preview says so instead of being tried on every scroll.
 */
@RunWith(AndroidJUnit4::class)
class CoversTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Covers.resetForTests()
    }

    private fun ref(
        uri: String = "content://test.fixtures/a.pdf",
        name: String = "a.pdf",
        size: Long = 100,
        mtime: Long = 200
    ) = LibraryRef(
        id = 1, uri = uri, name = name, ext = name.substringAfterLast('.'),
        mime = null, size = size, mtime = mtime, origin = RefOrigin.PICK,
        sourceNode = null, addedAt = 0, coverState = Covers.NONE, coverKey = null
    )

    // ---------------------------------------------------------------
    // The key
    // ---------------------------------------------------------------

    @Test
    fun theKeyCarriesTheSizeAndTheTime() {
        val a = Covers.key(ref(size = 1, mtime = 2))
        val b = Covers.key(ref(size = 1, mtime = 3))
        val c = Covers.key(ref(size = 9, mtime = 2))
        assertThat(a).isNotEqualTo(b)
        assertThat(a).isNotEqualTo(c)
    }

    @Test
    fun theSameFileAlwaysHasTheSameKey() {
        assertThat(Covers.key(ref())).isEqualTo(Covers.key(ref()))
    }

    @Test
    fun aDifferentFileHasADifferentKey() {
        assertThat(Covers.key(ref(uri = "content://test.fixtures/a.pdf")))
            .isNotEqualTo(Covers.key(ref(uri = "content://test.fixtures/b.pdf")))
    }

    // ---------------------------------------------------------------
    // What can have one
    // ---------------------------------------------------------------

    @Test
    fun aPhotoAPdfAndAVideoAllHaveCovers() {
        assertThat(Covers.supported(FileKind.IMAGE, "jpg")).isTrue()
        assertThat(Covers.supported(FileKind.PDF, "pdf")).isTrue()
        assertThat(Covers.supported(FileKind.PLAYER, "mp4")).isTrue()
    }

    /**
     * Audio, Office documents and zips have none. The Office two are the interesting pair:
     * they could be rendered, but only by running the viewer's own JavaScript, which costs
     * half a second and tens of megabytes per card and is why §5.4 of the design leaves them
     * to a screenshot taken while the document is open.
     */
    @Test
    fun audioOfficeAndArchivesHaveNone() {
        assertThat(Covers.supported(FileKind.PLAYER, "mp3")).isFalse()
        assertThat(Covers.supported(FileKind.DOCX, "docx")).isFalse()
        assertThat(Covers.supported(FileKind.XLSX, "xlsx")).isFalse()
        assertThat(Covers.supported(FileKind.PPTX, "pptx")).isFalse()
        assertThat(Covers.supported(FileKind.ARCHIVE, "zip")).isFalse()
        // None of them can be drawn here, and only one of them can end up with a cover anyway:
        // the viewer photographs a Word document's page when the reader opens it.
        assertThat(Covers.covered(FileKind.DOCX, "docx")).isTrue()
        assertThat(Covers.covered(FileKind.XLSX, "xlsx")).isFalse()
        assertThat(Covers.covered(FileKind.PPTX, "pptx")).isFalse()
        assertThat(Covers.covered(FileKind.ARCHIVE, "zip")).isFalse()
        assertThat(Covers.covered(FileKind.PLAYER, "mp3")).isFalse()
        assertThat(Covers.covered(FileKind.PDF, "pdf")).isTrue()
    }

    @Test
    fun aKindWithNoCoverSaysSoWithoutDrawingAnything() {
        val states = mutableListOf<Int>()
        Covers.load(
            context, ref(name = "song.mp3"),
            ImageView(context), View(context)
        ) { states += it }
        assertThat(states).containsExactly(Covers.UNSUPPORTED)
    }

    // ---------------------------------------------------------------
    // Word documents, whose cover is a photograph of the viewer's page
    // ---------------------------------------------------------------

    /**
     * A docx has no cover until the reader has opened it, and a card that asks before that is
     * told so rather than left waiting. Nothing is drawn and nothing is written, so the next
     * scroll asks the same question and gets the same answer for the same price.
     */
    @Test
    fun aWordDocumentHasNoCoverUntilTheViewerFilesOne() {
        val states = mutableListOf<Int>()
        val into = loadAndWait(ref(name = "report.docx"), states)

        assertThat(states).containsExactly(Covers.UNSUPPORTED)
        assertThat(into.drawable).isNull()
        assertThat(File(context.filesDir, "covers").listFiles().orEmpty()).isEmpty()
    }

    /** And shows it afterwards, at the size every other cover is kept at. */
    @Test
    fun aWordDocumentShowsTheCoverTheViewerFiled() {
        val ref = ref(name = "report.docx")
        Covers.store(context, ref, Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888))

        val states = mutableListOf<Int>()
        val into = loadAndWait(ref, states)

        assertThat(states).containsExactly(Covers.READY)
        // The long edge of every cover, with the shape it was drawn in kept.
        val shown = (into.drawable as BitmapDrawable).bitmap
        assertThat(maxOf(shown.width, shown.height)).isEqualTo(512)
        assertThat(shown.width).isEqualTo(384)
        assertThat(File(context.filesDir, "covers/${Covers.key(ref)}.png").exists()).isTrue()
    }

    /** A page photographed smaller than the ceiling is kept as it is rather than stretched. */
    @Test
    fun aSmallWordCoverIsNotScaled() {
        val ref = ref(name = "report.docx")
        Covers.store(context, ref, Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888))

        val into = loadAndWait(ref, mutableListOf())

        assertThat((into.drawable as BitmapDrawable).bitmap.width).isEqualTo(120)
    }

    /** The file behind the URI was replaced, so what was photographed is not what is there. */
    @Test
    fun aReplacedWordDocumentHasNoCover() {
        val was = ref(name = "report.docx", size = 10, mtime = 100)
        Covers.store(context, was, Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888))
        val now = ref(name = "report.docx", size = 20, mtime = 100)

        val states = mutableListOf<Int>()
        loadAndWait(now, states)

        assertThat(states).containsExactly(Covers.UNSUPPORTED)
    }

    /**
     * Binds a card the way the grid does, and waits for the answer.
     *
     * Real time, not SystemClock: Robolectric's clock stands still unless a test moves it, so a
     * deadline on that one never arrives and a cover that never comes hangs the run rather than
     * failing it. Covers are drawn and read on a worker thread, which is what the wait is for.
     */
    private fun loadAndWait(ref: LibraryRef, states: MutableList<Int>): ImageView {
        val into = ImageView(context)
        Covers.load(context, ref, into, View(context)) { states += it }
        val deadline = System.nanoTime() + 10_000_000_000
        while (states.isEmpty()) {
            check(System.nanoTime() < deadline) { "no cover state for ${ref.name}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        return into
    }

    // ---------------------------------------------------------------
    // Where it is kept
    // ---------------------------------------------------------------

    /**
     * A cover is kept where the system will not clear it.
     *
     * `cacheDir` would be the obvious place and is the wrong one: the system empties it
     * whenever it wants the space, and the moment it does, every card on screen goes back to
     * decoding a document's first page — which is the whole cost this cache exists to avoid.
     */
    @Test
    fun aPhotoGetsACoverUnderFilesDir() {
        FixtureProvider.install()
        val ref = ref(uri = FixtureProvider.uriFor("tiny.png").toString(), name = "tiny.png")

        assertThat(Covers.drawNow(context, ref)).isEqualTo(Covers.READY)

        val covers = File(context.filesDir, "covers")
        assertThat(covers.listFiles().orEmpty().map { it.name })
            .containsExactly("${Covers.key(ref)}.png")
    }

    /** The second time is a read of the file rather than a second decode. */
    @Test
    fun aCoverAlreadyDrawnIsNotDrawnAgain() {
        FixtureProvider.install()
        val ref = ref(uri = FixtureProvider.uriFor("tiny.png").toString(), name = "tiny.png")
        Covers.drawNow(context, ref)
        val drawn = File(context.filesDir, "covers/${Covers.key(ref)}.png")
        val at = drawn.lastModified()

        drawn.setLastModified(1_000)
        assertThat(Covers.drawNow(context, ref)).isEqualTo(Covers.READY)
        assertThat(drawn.lastModified()).isEqualTo(1_000)
        assertThat(at).isNotEqualTo(0L)
    }

    /** A file that cannot be read is a state, not a crash, and not a retry. */
    @Test
    fun aFileThatCannotBeDrawnIsFailed() {
        FixtureProvider.install()
        val ref = ref(uri = "content://${FixtureProvider.AUTHORITY}/gone.png", name = "gone.png")
        assertThat(Covers.drawNow(context, ref)).isEqualTo(Covers.FAILED)
        assertThat(File(context.filesDir, "covers").listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun pruningKeepsTheNewestAndDropsTheOldest() {
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val oldest = File(dir, "oldest.png").apply { writeBytes(ByteArray(8)) }
        val middle = File(dir, "middle.png").apply { writeBytes(ByteArray(8)) }
        val newest = File(dir, "newest.png").apply { writeBytes(ByteArray(8)) }
        oldest.setLastModified(1_000)
        middle.setLastModified(2_000)
        newest.setLastModified(3_000)

        Covers.prune(context, maxFiles = 2)

        assertThat(oldest.exists()).isFalse()
        assertThat(middle.exists()).isTrue()
        assertThat(newest.exists()).isTrue()
    }

    @Test
    fun pruningLeavesADirectoryInsideItsCeilingsAlone() {
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val only = File(dir, "only.png").apply { writeBytes(ByteArray(8)) }
        Covers.prune(context, maxFiles = 2)
        assertThat(only.exists()).isTrue()
    }

    @Test
    fun pruningDropsFilesWhenTheByteCeilingIsReached() {
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val old = File(dir, "old.png").apply { writeBytes(ByteArray(100)) }
        val fresh = File(dir, "fresh.png").apply { writeBytes(ByteArray(100)) }
        old.setLastModified(1_000)
        fresh.setLastModified(2_000)

        Covers.prune(context, maxFiles = 100, maxBytes = 150)

        assertThat(old.exists()).isFalse()
        assertThat(fresh.exists()).isTrue()
    }
}

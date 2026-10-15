package com.arjun.gander

import android.content.Context
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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

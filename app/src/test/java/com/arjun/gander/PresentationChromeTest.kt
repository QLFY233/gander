package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.children
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Presentation mode: one page at a time, whole, for a PDF being shown to somebody rather than
 * read. The app's own parts go for the length of it, the phone is turned the way the page is, and
 * the screen is kept on.
 *
 * The WebView is Robolectric's, so nothing is drawn and nothing swipes: what a slide does is
 * asserted in tests/viewer/test_pdf_present.py against the real page. What is here is the app
 * around it, which is where the chrome and the orientation live.
 */
@RunWith(AndroidJUnit4::class)
class PresentationChromeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Thumbs.resetForTests()
    }

    private fun open(fixture: String): ActivityController<ViewerActivity> {
        val uri = FixtureProvider.uriFor(fixture)
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri))
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent)
        controller.get().documentLoader = Executor { it.run() }
        return controller.setup().also { settle() }
    }

    private fun pdf(): ViewerActivity = open("six-pages.pdf").get()

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private val ViewerActivity.toolbar get() = findViewById<MaterialToolbar>(R.id.toolbar)
    private val ViewerActivity.presentItem get() = toolbar.menu.findItem(R.id.action_present)
    private val ViewerActivity.web
        get() = findViewById<FrameLayout>(R.id.container).children
            .filterIsInstance<WebView>().single()
    private val ViewerActivity.pagePill get() = findViewById<View>(R.id.pageIndicator)

    /** The strip holding the title bar over the document, which is what goes away. */
    private val ViewerActivity.bars get() = toolbar.parent as View

    /** The item's own click listener, which is what a tap on the menu runs. */
    private fun ViewerActivity.pressPresent() =
        toolbar.menu.performIdentifierAction(R.id.action_present, 0)

    private fun keepsScreenOn(viewer: ViewerActivity): Boolean =
        (viewer.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    private fun lightStatusIcons(viewer: ViewerActivity): Boolean =
        WindowCompat.getInsetsController(viewer.window, viewer.window.decorView)
            .isAppearanceLightStatusBars

    /** The page's channel, opened as the page finishes loading. */
    private fun ViewerActivity.openChannel() =
        web.webViewClient.onPageFinished(web, shadowOf(web).lastLoadedUrl)

    /** Everything the app has said down the channel so far. */
    private fun ViewerActivity.said(): List<String> =
        shadowOf(web).createdPorts.single()[0].outgoingMessages

    // ---------------------------------------------------------------
    // The chrome itself
    // ---------------------------------------------------------------

    @Test
    fun enteringPutsTheAppAwayAndHoldsTheScreenOn() {
        val viewer = pdf()
        val seen = mutableListOf<Boolean>()
        val chrome = PresentationChrome(viewer) { seen += it }

        chrome.enter()

        assertThat(seen).containsExactly(true)
        assertThat(chrome.up).isTrue()
        assertThat(keepsScreenOn(viewer)).isTrue()
        // Pale over the black a slide is shown against, whatever the app's own theme asked for
        assertThat(lightStatusIcons(viewer)).isFalse()
    }

    @Test
    fun leavingPutsAllOfItBack() {
        val viewer = pdf()
        val wasLight = lightStatusIcons(viewer)
        val seen = mutableListOf<Boolean>()
        val chrome = PresentationChrome(viewer) { seen += it }

        chrome.enter()
        chrome.exit()

        assertThat(seen).containsExactly(true, false).inOrder()
        assertThat(chrome.up).isFalse()
        assertThat(keepsScreenOn(viewer)).isFalse()
        assertThat(lightStatusIcons(viewer)).isEqualTo(wasLight)
    }

    @Test
    fun enteringTwicePutsItAwayOnce() {
        val seen = mutableListOf<Boolean>()
        val chrome = PresentationChrome(pdf()) { seen += it }

        chrome.enter()
        chrome.enter()

        assertThat(seen).containsExactly(true)
    }

    @Test
    fun theOrientationFollowsThePage() {
        val viewer = pdf()
        val chrome = PresentationChrome(viewer) {}
        chrome.enter()

        // A slide, and the phone is free to be held either way up
        chrome.hold(PresentationChrome.orientation(wide = true))
        assertThat(viewer.requestedOrientation)
            .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

        // A printed page, which landscape would only shrink
        chrome.hold(PresentationChrome.orientation(wide = false))
        assertThat(viewer.requestedOrientation)
            .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)

        chrome.exit()
        assertThat(viewer.requestedOrientation)
            .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    // ---------------------------------------------------------------
    // Where it is offered
    // ---------------------------------------------------------------

    @Test
    fun aPdfOffersThePresentation() {
        assertThat(pdf().presentItem.isVisible).isTrue()
    }

    @Test
    fun aWordDocumentDoesNot() {
        assertThat(open("report.docx").get().presentItem.isVisible).isFalse()
    }

    /**
     * A slide is turned by a swipe and a screen reader has that gesture for itself, so the mode
     * would be one a reader could enter and not get out of: nothing is drawn on a slide to tap.
     */
    @Test
    fun underAScreenReaderThereIsNoPresentationToAskFor() {
        shadowOf(context.getSystemService(AccessibilityManager::class.java))
            .setTouchExplorationEnabled(true)

        assertThat(pdf().presentItem.isVisible).isFalse()
    }

    // ---------------------------------------------------------------
    // Asking for it, and what the page answers
    // ---------------------------------------------------------------

    @Test
    fun theMenuAsksThePageAndBackTakesItBack() {
        val viewer = pdf()
        viewer.openChannel()

        viewer.pressPresent()
        assertThat(viewer.said()).contains(PortCommand.play(true))
        assertThat(viewer.presentItem.isChecked).isTrue()

        viewer.onBackPressedDispatcher.onBackPressed()
        assertThat(viewer.said().last()).isEqualTo(PortCommand.play(false))
        assertThat(viewer.presentItem.isChecked).isFalse()
    }

    /**
     * Nothing is put away until the page says it has a document to present, because the page is
     * the side that knows. A menu tapped in the first moments after opening would otherwise take
     * the app's bar away over a document that never came up.
     */
    @Test
    fun askingForItDoesNotPutAnythingAwayOnItsOwn() {
        val viewer = pdf()
        viewer.openChannel()

        viewer.pressPresent()

        assertThat(viewer.bars.visibility).isEqualTo(View.VISIBLE)
        assertThat(keepsScreenOn(viewer)).isFalse()
        assertThat(viewer.requestedOrientation)
            .isNotEqualTo(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
    }

    @Test
    fun whatThePageSaysTakesTheBarsAwayAndTurnsThePhone() {
        val viewer = pdf()
        viewer.openChannel()
        viewer.onPresenting(on = true, wide = true)

        assertThat(viewer.bars.visibility).isEqualTo(View.GONE)
        assertThat(keepsScreenOn(viewer)).isTrue()
        assertThat(viewer.requestedOrientation)
            .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

        // And a page of the other shape turns it back, since a document may mix them
        viewer.onPresenting(on = true, wide = false)
        assertThat(viewer.requestedOrientation)
            .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)

        viewer.onPresenting(on = false, wide = false)

        assertThat(viewer.bars.visibility).isEqualTo(View.VISIBLE)
        assertThat(keepsScreenOn(viewer)).isFalse()
        assertThat(viewer.requestedOrientation)
            .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    /**
     * The bar stays away for the length of the presentation, whatever asks for it back.
     *
     * Every other way this app has of bringing the title bar back is the window's focus - a menu
     * opened from it, a box opened from that, and the app being left for another one - and a
     * presentation outlives all of them. Left to answer a message and come back to, the bar came
     * back with the document's own scrolling long gone: nothing had scrolled it there and nothing
     * was going to scroll it away again.
     */
    @Test
    fun theBarsStayAwayWhileASlideIsUp() {
        val viewer = pdf()
        viewer.openChannel()
        viewer.onPresenting(on = true, wide = true)
        assertThat(viewer.bars.visibility).isEqualTo(View.GONE)

        // Out of the app and back in, which is a loss of focus and a gain of it
        viewer.onWindowFocusChanged(false)
        viewer.onWindowFocusChanged(true)
        settle()
        assertThat(viewer.bars.visibility).isEqualTo(View.GONE)

        // And the document back at the top of itself, which brings the bar back at any other time
        viewer.web.scrollTo(0, 300)
        settle()
        viewer.web.scrollTo(0, 0)
        settle()
        assertThat(viewer.bars.visibility).isEqualTo(View.GONE)

        viewer.onPresenting(on = false, wide = false)
        assertThat(viewer.bars.visibility).isEqualTo(View.VISIBLE)
    }

    /**
     * The page reports which slide it is on every time one is turned, and the pill is what that
     * report normally puts up. Over a slide there is nothing drawn at all.
     */
    @Test
    fun thePageReadoutStaysOutOfTheWayOfASlide() {
        val viewer = pdf()
        viewer.pageTotal = 6
        viewer.web.scrollTo(0, 300)
        settle()
        assertThat(viewer.pagePill.visibility).isEqualTo(View.VISIBLE)

        viewer.onPresenting(on = true, wide = true)
        assertThat(viewer.pagePill.visibility).isEqualTo(View.GONE)

        viewer.web.scrollTo(0, 600)
        settle()
        assertThat(viewer.pagePill.visibility).isEqualTo(View.GONE)
    }

    /** The card that stands in for a document whose renderer has gone has nothing to present. */
    @Test
    fun thePresentationGoesWithTheDocumentWhenTheRendererDies() {
        val viewer = pdf()
        viewer.openChannel()
        viewer.onPresenting(on = true, wide = true)

        viewer.web.webViewClient.onRenderProcessGone(viewer.web, object : RenderProcessGoneDetail() {
            override fun didCrash() = true
            override fun rendererPriorityAtExit() = 0
        })
        settle()

        assertThat(viewer.presentItem.isVisible).isFalse()
        assertThat(viewer.bars.visibility).isEqualTo(View.VISIBLE)
        assertThat(keepsScreenOn(viewer)).isFalse()
        assertThat(viewer.requestedOrientation)
            .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }
}

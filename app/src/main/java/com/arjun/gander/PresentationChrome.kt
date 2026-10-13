package com.arjun.gander

import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * What the app does around a PDF being presented: the phone's bars and the app's own out of the
 * way, the screen held on, and the phone held the way the slide is.
 *
 * A slide is shown, not read, and everything this app puts over a document is in the way of that.
 * The title bar floats over the top of the page, the page readout appears on every turn of a page
 * and the scroll thumb follows a scroll there is none of, so all three go for the length of it.
 * The bars behind the clock and the gesture handle go too, which is the one thing here a reader
 * might not expect: a presentation on a phone is the closest this app comes to a slide show, and
 * every other viewer of one takes the whole screen.
 *
 * The bars come back for a swipe in from the edge and go again by themselves, so nothing is lost
 * by hiding them. They are hidden rather than made transparent, which is what lets the document
 * grow into the room they were using: the viewer keeps its content clear of them with padding
 * taken from the insets, and an inset for a bar that is not there is nothing.
 *
 * The orientation is the page's own. A slide is landscape and a printed page is not, and the
 * document is free to mix them, so the page is asked again on every turn of a page rather than
 * once on the way in: see PortMessage.Presenting. Which of the two landscape ways round the phone
 * is held is the phone's own business - SENSOR_LANDSCAPE follows it, so a reader who turns the
 * phone over gets the same slide the other way up, which is what a video in full screen does here.
 * See VideoChrome, which this is deliberately the same shape as.
 *
 * A slide is also the one thing in this app somebody reads without touching the screen, so the
 * screen is kept on while it is up. Nothing else needs it: a page is read by scrolling, and a
 * video keeps the screen on through its player.
 */
internal class PresentationChrome(
    private val activity: AppCompatActivity,
    /** The app's own parts over the document, put away while a slide is up. */
    private val away: (Boolean) -> Unit,
) {
    private val window = activity.window
    private val bars = WindowCompat.getInsetsController(window, window.decorView)

    // As enter() found them, for exit()
    private val lightStatusIcons = bars.isAppearanceLightStatusBars
    private val lightNavigationIcons = bars.isAppearanceLightNavigationBars
    private val barsBehaviour = bars.systemBarsBehavior

    /** Whether the presentation is up, which is what every other part of this asks. */
    var up = false
        private set

    /** The orientation the phone is being held at, or null while the phone is holding it. */
    var held: Int? = null
        private set

    fun enter() {
        if (up) return
        up = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Hidden, they come back for a swipe in from the edge and go again by themselves, and a
        // swipe across the slide still reaches the page
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Pale over a slide, and over the black it is shown against. The app's own theme may have
        // asked for dark icons, which are a smudge on a black letterbox
        bars.isAppearanceLightStatusBars = false
        bars.isAppearanceLightNavigationBars = false
        bars.hide(WindowInsetsCompat.Type.systemBars())
        away(true)
    }

    fun exit() {
        if (!up) return
        up = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bars.show(WindowInsetsCompat.Type.systemBars())
        bars.isAppearanceLightStatusBars = lightStatusIcons
        bars.isAppearanceLightNavigationBars = lightNavigationIcons
        bars.systemBarsBehavior = barsBehaviour
        hold(null)
        away(false)
    }

    /**
     * The window's focus, which is when the phone's bars have to be asked for again.
     *
     * The request that hid them survives being left on most devices and most of the time, and not
     * on all of them: the clock and the gesture handle come back over a slide that is otherwise the
     * whole screen, and stay there until something asks them to go. Asked here rather than in
     * onResume, which a dialog opened over the app arrives as too, and where asking would take the
     * bars away from under it.
     */
    fun focusChanged(hasFocus: Boolean) {
        if (!up || !hasFocus) return
        bars.hide(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * Hold the phone at [orientation], or give it back to the phone for null.
     *
     * Called with the same answer on every turn of a page, since the page reports its shape each
     * time and a document may mix them. Setting the same orientation again is not free - it is a
     * call into the window manager - so it is skipped when nothing has changed.
     */
    fun hold(orientation: Int?) {
        if (orientation == held) return
        held = orientation
        activity.requestedOrientation = orientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    companion object {
        /**
         * The orientation a page of this shape is presented at.
         *
         * SENSOR_ rather than the plain constants, for both: the phone is free to be held either
         * way up, which for a landscape slide is the difference between the charging port being
         * on the left and on the right. The viewer has configChanges for orientation, so this
         * never rebuilds the activity and the document is never read again; the page is told the
         * screen changed and fits the slide to it again. See playPlace() in pdf.mjs.
         */
        fun orientation(wide: Boolean): Int =
            if (wide) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }
}

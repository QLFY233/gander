"""
The presentation mode in pdf.html: one page at a time, whole, for a document being shown
to somebody rather than read.

Reached over the message channel with "s1" and "s0" and answered with "present <on>
<wide>", which is what tells the app whether there is anything to present and which way
round to hold the phone. Everything else here is a swipe.

The geometry is asserted against the box the slide was fitted to rather than against
numbers written down, so the same tests hold at any viewport the suite is run at, and the
page's own shape is read off the document rather than off the fixture's name.
"""

import threading

import pytest

from helpers import (
    after_frames, held_slide, play_boxes, play_centre, play_colours, play_slides,
    play_state, play_visible, set_page_scale, slot_states, swipe_within, touch_gesture,
    wait_for_play_away, wait_for_pdf, wait_for_slide,
)

SLIDES = "slides-16x9.pdf"

# The corner of a slide's canvas, which is paper in the fixture and so white by day and
# black by night. Waiting on the pixel rather than on a flag is what makes the night test
# unambiguous: the bitmap is turned over after the render lands, and a flag that says
# "drawn" is set either side of that.
CORNER_IS_DARK = """() => {
  const c = document.getElementById('vw-play-canvas');
  if (!c || !c.width) return false;
  const d = c.getContext('2d').getImageData(2, 2, 1, 1).data;
  return d[0] < 40 && d[1] < 40 && d[2] < 40;
}"""


def open_slides(viewer, page, **params):
    viewer("pdf.html", SLIDES, **params)
    wait_for_pdf(page, pages=3)
    return page


def present(port, page, on=True):
    """Asks for the presentation, and waits for the page to say it is up."""
    port.play(on)
    if on:
        wait_for_slide(page)
    else:
        wait_for_play_away(page)
    return page


def ratio(page):
    """Page 1's width over its height, which is what the slide is fitted to."""
    return float(page.evaluate(
        "() => getComputedStyle(document.getElementById('pages'))"
        ".getPropertyValue('--pg-ratio')"))


# The page the reader is looking at, counting from one, read the way reportPage() reads
# it: the box the middle of the visible area falls in.
UNDER_THE_MIDDLE = """() => {
  const vv = visualViewport, mid = vv.pageTop + vv.height / 2;
  const pgs = [...document.querySelectorAll('#pages .pg')];
  return pgs.findIndex(pg => {
    const r = pg.getBoundingClientRect();
    return r.top + scrollY <= mid && r.bottom + scrollY > mid;
  }) + 1;
}"""


def page_under_the_middle(page):
    """Which page the reader is looking at, counting from one, as reportPage() reads it."""
    return page.evaluate(UNDER_THE_MIDDLE)


# ---------------------------------------------------------------------------
# Getting in and out
# ---------------------------------------------------------------------------

def test_the_layer_is_not_there_until_it_is_asked_for(viewer, page):
    open_slides(viewer, page)
    assert not play_visible(page)
    assert play_state(page)["on"] is False


def test_asking_for_it_puts_the_first_page_on_screen(viewer, page, port):
    open_slides(viewer, page)
    present(port(), page)
    assert play_visible(page)
    assert play_state(page)["at"] == 1


def test_it_says_so_over_the_channel_so_the_app_can_hold_the_phone(viewer, page, port):
    open_slides(viewer, page)
    p = port()
    present(p, page)
    # 16:9 is wider than it is tall, which is the landscape the app is asked to hold
    assert p.wait_for(r"present 1 1") == "present 1 1"
    assert p.pages()[-1] == "page 1 3"


def test_a_page_taller_than_it_is_wide_asks_for_upright(viewer, page, port):
    """The other half of the decision: an A4 document is shown as it is printed."""
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page, pages=6)
    p = port()
    present(p, page)
    assert p.wait_for(r"present 1 0") == "present 1 0"


def test_leaving_puts_the_layer_away(viewer, page, port):
    open_slides(viewer, page)
    p = port()
    present(p, page)
    present(p, page, on=False)
    assert not play_visible(page)
    assert p.wait_for(r"present 0 0") == "present 0 0"


def test_a_request_before_the_document_is_open_is_honoured(viewer, page, port):
    """
    The menu is offered as soon as the file is, and a tap can land before the renderer
    has read a byte. The request is kept and honoured once there is something to show
    rather than dropped, because by then the app has put its own bar away.
    """
    held = threading.Event()

    def wait_for_the_test(route):
        held.wait(timeout=20)
        route.continue_()

    page.route("**/doc/**", wait_for_the_test)
    viewer("pdf.html", SLIDES, wait=False)
    p = port()
    p.play(True)
    # Nothing to present yet, so nothing may have been said
    assert p.presents() == []
    held.set()
    assert p.wait_for(r"present 1 1") == "present 1 1"
    wait_for_slide(page)


# ---------------------------------------------------------------------------
# The slide, on the screen
# ---------------------------------------------------------------------------

def test_the_slide_is_whole_and_in_the_middle(viewer, page, port):
    """
    The whole point of the mode, and the thing the document's own layout cannot do: a
    16:9 slide fitted to the width of an upright phone is a strip across the top of it.
    """
    open_slides(viewer, page)
    present(port(), page)
    box = play_boxes(page)
    layer, slide = box["layer"], box["slide"]

    assert slide["w"] / slide["h"] == pytest.approx(ratio(page), rel=0.01)
    # Met the screen on one axis and inside it on the other
    assert min(layer["w"] - slide["w"], layer["h"] - slide["h"]) == pytest.approx(0, abs=1)
    assert slide["w"] <= layer["w"] + 1 and slide["h"] <= layer["h"] + 1
    # And centred in what is left over, both ways
    assert slide["x"] + slide["w"] / 2 == pytest.approx(layer["x"] + layer["w"] / 2, abs=1)
    assert slide["y"] + slide["h"] / 2 == pytest.approx(layer["y"] + layer["h"] / 2, abs=1)


def test_the_slide_is_drawn_at_the_resolution_of_the_screen(viewer, page, port):
    """
    baseScale() stops at 2 canvas px per CSS px, which is a phone's portrait resolution
    and not its landscape one. The presentation uses the uncapped liveScale() instead,
    because it holds one canvas rather than a band of them and can afford it.
    """
    open_slides(viewer, page)
    present(port(), page)
    box = play_boxes(page)
    assert box["canvas"]["w"] >= box["slide"]["w"] - 1
    assert box["canvas"]["h"] >= box["slide"]["h"] - 1
    assert box["canvas"]["w"] / box["canvas"]["h"] == pytest.approx(
        box["slide"]["w"] / box["slide"]["h"], rel=0.01)


def test_the_layer_covers_what_the_reader_can_see(viewer, page, port):
    open_slides(viewer, page)
    present(port(), page)
    assert page.evaluate(
        "() => getComputedStyle(document.getElementById('vw-play')).backgroundColor"
    ) == "rgb(0, 0, 0)"
    assert page.evaluate(
        "() => getComputedStyle(document.getElementById('vw-play')).touchAction"
    ) == "none"


def test_it_follows_the_reader_into_a_pinch(viewer, page, port):
    """
    The layer is placed against the visual viewport rather than the layout one, because
    this page lays out at 980 CSS px and is then scaled to fit. A reader who has zoomed
    into a table and then asks for the presentation must be given the screen they can
    see, not one corner of the slide.
    """
    from helpers import set_page_scale

    open_slides(viewer, page)
    present(port(), page)
    before = play_boxes(page)

    scale = set_page_scale(page, 2)
    assert scale == pytest.approx(2, abs=0.01)
    page.wait_for_function(
        "() => document.getElementById('vw-play').getBoundingClientRect().width < 700",
        timeout=10000,
    )
    wait_for_slide(page)
    after = play_boxes(page)

    assert after["layer"]["w"] == pytest.approx(before["layer"]["w"] / scale, rel=0.02)
    assert after["layer"]["h"] == pytest.approx(before["layer"]["h"] / scale, rel=0.02)
    # The same slide at the same number of device pixels, whatever the pinch
    assert after["canvas"]["w"] == pytest.approx(before["canvas"]["w"], rel=0.02)


def test_a_turn_of_the_phone_fits_the_slide_again(viewer, page, port):
    """
    The phone turned is the box changing shape, and every slide that is ready was fitted to
    the box as it was.

    Waited on by the canvas on screen being a different one, because the page number and
    the fit the slide was drawn for are both unchanged by a turn of the phone: what is
    being waited for is a slide drawn for the new box, and the old one is up until there is
    one - stretched into the new box rather than taken away, which is a soft slide for the
    length of a render instead of a blank one.
    """
    open_slides(viewer, page)
    present(port(), page)
    assert play_boxes(page)["layer"]["h"] == pytest.approx(1600, abs=2)
    page.evaluate(
        "() => { document.getElementById('vw-play-canvas').dataset.vw = 'before'; }")

    page.set_viewport_size({"width": 1400, "height": 800})
    page.wait_for_function(
        "() => { const c = document.getElementById('vw-play-canvas');"
        "return !!c && c.dataset.vw !== 'before'; }", timeout=10000)
    after = play_boxes(page)

    assert after["layer"]["w"] == pytest.approx(1400, abs=2)
    assert after["layer"]["h"] == pytest.approx(800, abs=2)
    # Whole, centred, and drawn at the new box's resolution: fitted again rather than left
    # at the size the box it was drawn for gave it.
    assert min(after["layer"]["w"] - after["slide"]["w"],
               after["layer"]["h"] - after["slide"]["h"]) == pytest.approx(0, abs=1)
    assert after["slide"]["w"] / after["slide"]["h"] == pytest.approx(ratio(page), rel=0.01)
    assert after["canvas"]["w"] >= after["slide"]["w"] - 1


# ---------------------------------------------------------------------------
# Turning the page
# ---------------------------------------------------------------------------

def swipe(page, dx, dy=0):
    """A finger dragged across the middle of the screen by [dx], [dy] and lifted."""
    x, y = play_centre(page)
    touch_gesture(page, [[(x - dx / 2, y - dy / 2), (x + dx / 2, y + dy / 2)]])
    return page


def test_a_swipe_to_the_left_turns_to_the_next_page(viewer, page, port):
    open_slides(viewer, page)
    p = port()
    present(p, page)
    swipe(page, -300)
    wait_for_slide(page, 2)
    assert play_state(page)["at"] == 2
    assert p.wait_for(r"page 2 3") == "page 2 3"


def test_a_swipe_to_the_right_turns_back(viewer, page, port):
    open_slides(viewer, page)
    p = port()
    present(p, page)
    swipe(page, -300)
    wait_for_slide(page, 2)
    swipe(page, 300)
    wait_for_slide(page, 1)
    assert play_state(page)["at"] == 1


def test_the_last_page_has_nothing_after_it(viewer, page, port):
    open_slides(viewer, page)
    present(port(), page)
    swipe(page, -300)
    wait_for_slide(page, 2)
    swipe(page, -300)
    wait_for_slide(page, 3)
    swipe(page, -300)
    after_frames(page)
    assert play_state(page)["at"] == 3


def test_the_first_page_has_nothing_before_it(viewer, page, port):
    open_slides(viewer, page)
    present(port(), page)
    swipe(page, 300)
    after_frames(page)
    assert play_state(page)["at"] == 1


def test_a_mostly_vertical_drag_leaves_the_page_alone(viewer, page, port):
    """Somebody trying to scroll a slide that has nothing to scroll."""
    open_slides(viewer, page)
    present(port(), page)
    swipe(page, -60, 500)
    after_frames(page)
    assert play_state(page)["at"] == 1


def test_a_short_drag_leaves_the_page_alone(viewer, page, port):
    """A hand shifting its grip is not a page turn, and the threshold is well above one."""
    open_slides(viewer, page)
    present(port(), page)
    swipe(page, -35)
    after_frames(page)
    assert play_state(page)["at"] == 1


def test_a_second_finger_cancels_the_swipe(viewer, page, port):
    """
    Two fingers is a pinch, which this mode has nothing for. A browser delivers each
    finger's events separately, so without the guard the swipe would be read off
    whichever of the two lifted first.
    """
    open_slides(viewer, page)
    present(port(), page)
    x, y = play_centre(page)
    touch_gesture(page, [
        [(x + 150, y), (x - 150, y)],
        [(x + 100, y), (x - 100, y)],
    ])
    after_frames(page)
    assert play_state(page)["at"] == 1


# ---------------------------------------------------------------------------
# Drawing ahead, so that a turn is a swap rather than a wait
# ---------------------------------------------------------------------------

def wait_for_neighbour(page, n, timeout=20000):
    """Waits until page [n] is drawn and waiting, off the screen or on it."""
    page.wait_for_function(
        "(n) => document.getElementById('vw-play')._play.slides.some(s => s.n === n)",
        arg=n, timeout=timeout,
    )
    return page


def test_the_neighbour_is_drawn_before_it_is_asked_for(viewer, page, port):
    """
    The page after this one is drawn while the reader is still looking at this one.

    This is the whole of what makes a turn instant. A render takes about a fifth of a
    second on a phone, so a presentation that draws the page it has been asked for draws
    it while the reader waits; one that draws the page after it has nothing to wait for.
    """
    open_slides(viewer, page)
    present(port(), page)
    wait_for_neighbour(page, 2)
    assert 2 in play_slides(page)


def test_a_turn_towards_a_ready_slide_is_a_swap(viewer, page, port):
    """
    A turn to a page whose slide is drawn lands in the same turn as the swipe.

    Read inside the page rather than after a round trip, because that is the claim: there
    is no wait to measure from outside. The canvas that was on screen is off it afterwards
    and still has its own page on it, which is the difference between a slide being swapped
    out and a slide being drawn over - and drawing over it is what a reader sees as the
    page going white and then assembling itself.
    """
    open_slides(viewer, page)
    present(port(), page)
    wait_for_neighbour(page, 2)
    state = swipe_within(page, -240)[0]
    assert state["at"] == 2
    assert state["drawn"] == 2, "the slide was not on screen by the time the swipe returned"
    assert state["view"] == 2
    held = state["held"]
    assert not held["connected"], "the slide that was on screen is still on screen"
    assert held["w"] > 300 and held["h"] > 300
    assert held["corner"] != [0, 0, 0], "the slide that was swapped out was emptied"


def test_a_page_that_is_not_ready_leaves_the_slide_that_is_up(viewer, page, port):
    """
    A turn to a page with no slide drawn yet changes nothing until there is one.

    pdf.js fills a page white and then runs the display list over it, so a turn drawn into
    the canvas that is on screen goes: the slide disappears, a white rectangle arrives, and
    the page assembles itself across it. Here the swipe is read in the same turn it is made
    in and the slide the reader was looking at is still up, whole, until its replacement is
    finished. A swipe that does nothing for a fifth of a second and then turns the page is
    worth more than one that turns it into a white rectangle.

    The swipe being asked about is the last of three, because a page one away may already
    have been drawn and the page after that never has: nothing is drawn further ahead than
    the page after the one on screen. All three are made in the same turn, because a render
    that lands between two round trips out to Python answers for the wrong swipe.
    """
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page, pages=6)
    present(port(), page)
    wait_for_neighbour(page, 2)
    last = swipe_within(page, -240, swipes=3)[-1]
    assert last["at"] == 4
    assert last["rendering"] == 4
    assert last["drawn"] == last["view"], "the page recorded is not the one on screen"
    assert last["drawn"] < last["at"], "a slide landed, so nothing was waited for"
    assert last["held"]["connected"], "the slide was taken off the screen to wait"
    assert last["held"]["w"] > 300
    assert last["held"]["corner"] != [0, 0, 0], "the slide on screen was emptied"
    wait_for_slide(page, 4)
    assert held_slide(page)["connected"] is False


def test_turning_back_comes_back_to_the_slide_that_was_left(viewer, page, port):
    """
    The page just left is kept, so a turn back to it is the same swap as a turn forward.

    Named before the turn away and looked for again after the turn back: presenting a
    document is a thing done in both directions, and re-drawing the page the reader has
    just been shown would be a fifth of a second spent showing them something already
    drawn.
    """
    open_slides(viewer, page)
    present(port(), page)
    page.evaluate(
        "() => { document.getElementById('vw-play-canvas').dataset.vw = 'first'; }")
    wait_for_neighbour(page, 2)
    assert swipe_within(page, -240)[0]["drawn"] == 2
    assert 1 in play_slides(page)
    state = swipe_within(page, 240)[0]
    assert state["at"] == 1
    assert state["drawn"] == 1
    assert page.evaluate(
        "() => document.getElementById('vw-play-canvas').dataset.vw") == "first"


def test_the_presentation_holds_three_slides_and_no_more(viewer, page, port):
    """
    A slide is a canvas and a canvas is a screenful of pixels, so the presentation keeps
    three: the page on screen and the two either side of it.

    A document flicked through is then a canvas allocated and a canvas freed on every turn
    rather than a document's worth of them waiting for a collection. Only one of the three
    is in the layer as well; the rest are canvases being held, not elements being stacked
    up behind the slide on screen.
    """
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page, pages=6)
    present(port(), page)
    for n in (2, 3, 4, 5):
        swipe(page, -300)
        wait_for_slide(page, n)
        held = play_slides(page)
        assert len(held) <= 3, f"{len(held)} slides at page {n}"
        assert set(held) <= {n - 1, n, n + 1}
        assert page.evaluate(
            "() => document.querySelectorAll('#vw-play canvas').length") == 1


# ---------------------------------------------------------------------------
# What the document underneath is doing
# ---------------------------------------------------------------------------

def test_the_document_is_given_up_while_the_presentation_is_up(viewer, page, port):
    """
    A presentation is one canvas, not a canvas and the band of pages the reader was
    scrolling through. On a long document that is the difference between about 10 MB and
    about 80, and it is why nothing is drawn behind the slide.
    """
    open_slides(viewer, page)
    present(port(), page)
    states = page.wait_for_function(
        "() => [...document.querySelectorAll('#pages .pg')]"
        ".every(pg => !pg._slot || pg._slot.state === 'blank')",
        timeout=10000,
    )
    assert slot_states(page) == ["blank"] * 3, slot_states(page)


def test_the_document_comes_back_when_the_presentation_ends(viewer, page, port):
    open_slides(viewer, page)
    p = port()
    present(p, page)
    present(p, page, on=False)
    wait_for_pdf(page, pages=3)
    assert "drawn" in slot_states(page)


def test_leaving_puts_the_document_back_on_the_page_it_reached(viewer, page, port):
    """
    A presentation that ended on page 2 and came back on page 1 would have thrown away
    where the reader got to, and the page saved to reopen at is read off the scroll
    position rather than out of the presentation.
    """
    open_slides(viewer, page)
    p = port()
    present(p, page)
    swipe(page, -300)
    wait_for_slide(page, 2)
    present(p, page, on=False)
    wait_for_pdf(page, pages=3)
    # The document came back to the slide that was on screen rather than to the top
    page.wait_for_function(f"() => ({UNDER_THE_MIDDLE})() === 2", timeout=10000)
    assert page_under_the_middle(page) == 2


# ---------------------------------------------------------------------------
# Night mode, which the slide is drawn through as well
# ---------------------------------------------------------------------------

def test_night_mode_turns_the_slide_over_too(viewer, page, port):
    """
    The filter is applied to the finished bitmap, and the presentation's bitmap is a
    bitmap like any other. Left out, a reader who turned the pages over to read in the
    dark gets a white slide in the face the moment they present one.
    """
    open_slides(viewer, page)
    p = port()
    present(p, page)
    day = play_colours(page)
    assert day and max(day, key=day.get) == "255,255,255"

    p.night_mode(True)
    page.wait_for_function(CORNER_IS_DARK, timeout=10000)
    wait_for_slide(page)
    night = play_colours(page)
    assert night and max(night, key=night.get) == "0,0,0"

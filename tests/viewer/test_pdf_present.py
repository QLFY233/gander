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
    after_frames, play_boxes, play_centre, play_colours, play_state, play_visible,
    set_page_scale, slot_states, touch_gesture, wait_for_play_away, wait_for_pdf,
    wait_for_slide,
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

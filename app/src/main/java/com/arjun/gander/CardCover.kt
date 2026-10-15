package com.arjun.gander

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * The cover area of a card: a frame whose height is its width turned on its side.
 *
 * The card is as wide as the column it sits in, and the column count follows the screen — two
 * on a phone, five on a tablet held sideways — so nothing here can be a fixed height. A
 * drawable's own aspect ratio is no help either: a cover is a photo, a PDF's first page or
 * nothing at all, and the area has to hold its shape before any of them arrives.
 *
 * Four thirds of the width, which is the 3:4 the design draws, and the shape a portrait page
 * or a phone photograph fills without much letterboxing. The cover itself is cropped into it
 * rather than fitted, so what varies is how much of the picture shows, never how big the card
 * is: a grid of cards that changed height with their contents would not be a grid.
 */
internal class CardCover @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            // Nothing has said how wide this will be, which happens while a RecyclerView is
            // still measuring. Taking the spec it was handed leaves the frame free to be any
            // size rather than zero.
            heightMeasureSpec
        } else {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.makeMeasureSpec((width * TALL / WIDE).roundToInt(), MeasureSpec.EXACTLY)
        }
        super.onMeasure(widthMeasureSpec, height)
    }

    private companion object {
        const val WIDE = 3f
        const val TALL = 4f
    }
}

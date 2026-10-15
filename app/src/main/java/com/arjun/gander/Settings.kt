package com.arjun.gander

import android.content.Context
import androidx.core.content.edit

/**
 * The reader's own preferences, as opposed to the library's reading history, which is a
 * record of what they opened.
 *
 * Gander has never had a settings screen and does not want one; settings here persist
 * across opens because reading preferences and view modes are not things you want to
 * re-select each time.
 *
 * Not backed up: `allowBackup="false"` in the manifest covers this file along with the
 * recents list, for the reason given there.
 */
object Settings {

    private const val PREFS = "viewer"
    private const val KEY_NIGHT = "night"
    private const val KEY_SORT_ORDER = "folder_sort_order"
    private const val KEY_SIDEBAR_OPEN = "sidebar_open"
    private const val KEY_SECTIONS_EXPANDED = "sections_expanded"
    private const val KEY_SECTIONS_COLLAPSED = "sections_collapsed"

    enum class SortOrder(val labelRes: Int) {
        NAME_AZ(R.string.sort_name_az),
        NAME_ZA(R.string.sort_name_za),
        NEWEST(R.string.sort_newest),
        OLDEST(R.string.sort_oldest),
        LARGEST(R.string.sort_largest),
        SMALLEST(R.string.sort_smallest);
    }

    /** Whether PDF pages are drawn turned over. See issue #19 and `pdf.html`. */
    fun night(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NIGHT, false)

    fun setNight(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_NIGHT, on) }
    }

    fun sortOrder(context: Context): SortOrder {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SORT_ORDER, SortOrder.NAME_AZ.name)
        return runCatching { SortOrder.valueOf(name ?: SortOrder.NAME_AZ.name) }
            .getOrDefault(SortOrder.NAME_AZ)
    }

    fun setSortOrder(context: Context, order: SortOrder) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_SORT_ORDER, order.name) }
    }

    /**
     * Whether the sidebar is showing.
     *
     * The fallback is a resource rather than a constant because the answer is a property of
     * the screen: a tablet held sideways has room for the sidebar beside the content and
     * opens with it out, and everything else — a phone in either orientation, a tablet held
     * upright — floats it over the content and opens with it away. Once the reader has moved
     * it themselves, their answer stands and the configuration stops deciding.
     */
    fun sidebarOpen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(
            KEY_SIDEBAR_OPEN,
            context.resources.getBoolean(R.bool.sidebar_open_by_default)
        )

    fun setSidebarOpen(context: Context, open: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_SIDEBAR_OPEN, open) }
    }

    /**
     * Whether the section named [key] is folded shut, with [default] for one nobody has
     * touched yet.
     *
     * Two sets rather than one, because the default is not the same for every section and
     * "absent" has to keep meaning "nobody has said": the content column opens with its
     * first folder showing and the rest folded, and the sidebar's two lists both open. One
     * set of collapsed keys could not tell a section the reader opened from one they never
     * touched, so the second time it was drawn it would fold itself again.
     */
    fun sectionCollapsed(context: Context, key: String, default: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val expanded = prefs.getStringSet(KEY_SECTIONS_EXPANDED, emptySet()) ?: emptySet()
        val collapsed = prefs.getStringSet(KEY_SECTIONS_COLLAPSED, emptySet()) ?: emptySet()
        return when {
            key in expanded -> false
            key in collapsed -> true
            else -> default
        }
    }

    fun setSectionCollapsed(context: Context, key: String, collapsed: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // getStringSet hands back a set the framework owns and asks not to be written to.
        val expanded = (prefs.getStringSet(KEY_SECTIONS_EXPANDED, emptySet()) ?: emptySet())
            .toMutableSet()
        val folded = (prefs.getStringSet(KEY_SECTIONS_COLLAPSED, emptySet()) ?: emptySet())
            .toMutableSet()
        if (collapsed) {
            expanded -= key
            folded += key
        } else {
            folded -= key
            expanded += key
        }
        prefs.edit {
            putStringSet(KEY_SECTIONS_EXPANDED, expanded)
            putStringSet(KEY_SECTIONS_COLLAPSED, folded)
        }
    }
}

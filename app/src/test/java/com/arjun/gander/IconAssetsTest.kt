package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * The generated icons, checked against the SVGs they were converted from.
 *
 * Both halves are in the repository on purpose — the SVG so an icon can be redrawn, the vector
 * so the app can draw it — which means the two can drift, and they did once: a conversion run
 * wrote fifteen vectors with the right viewport and no `<path>` in them at all.
 *
 * A vector with no path is not a blank icon, it is a crash. `VectorDrawable` throws
 * "no path defined" when it is loaded, and `MaterialButton` throws it while a layout is being
 * inflated, so the first screen that uses one does not open. That is a failure no unit test
 * about the library or the index can see, and it costs a full CI round trip to find.
 *
 * Nothing here inflates anything: it reads the two directories and compares them.
 */
class IconAssetsTest {

    private companion object {
        val DRAWABLES = File("../app/src/main/res/drawable")
        val SOURCES = File("../docs/file-icons")

        /** Every vector here, and the SVG each one was converted from. */
        val PAIRS = listOf(
            "ic_kind_pdf", "ic_kind_docx", "ic_kind_txt", "ic_kind_md", "ic_kind_generic",
            "ic_kind_xlsx", "ic_kind_ppt", "ic_kind_image", "ic_kind_video", "ic_kind_audio",
            "ic_kind_archive", "ic_kind_3d", "ic_kind_folder",
            "ic_folder_back", "ic_folder_front",
            "ic_import", "ic_add", "ic_settings", "ic_panel"
        )
    }

    @Test
    fun everyVectorDrawsSomethingAndKeepsItsSource() {
        PAIRS.forEach { name ->
            val vector = File(DRAWABLES, "$name.xml")
            assertWithMessage("$name.xml").that(vector.exists()).isTrue()
            val text = vector.readText()

            // One grid for the whole family, which is what lets a card's icon and a sidebar
            // row's be the same picture at the same weight.
            assertWithMessage("$name.xml viewport").that(text)
                .contains("android:viewportWidth=\"64\"")
            assertWithMessage("$name.xml size").that(text)
                .contains("android:width=\"24dp\"")

            val paths = Regex("""android:pathData="([^"]*)"""").findAll(text).toList()
            assertWithMessage("$name.xml has no paths in it").that(paths).isNotEmpty()
            paths.forEach { path ->
                assertWithMessage("$name.xml has an empty pathData")
                    .that(path.groupValues[1].trim()).isNotEmpty()
            }

            val source = File(SOURCES, "$name.svg")
            assertWithMessage("docs/file-icons/$name.svg").that(source.exists()).isTrue()
            assertWithMessage("docs/file-icons/$name.svg viewBox").that(source.readText())
                .contains("viewBox=\"0 0 64 64\"")
        }
    }

    /**
     * The ten folder slots are a layer-list of the two halves, tinted separately.
     *
     * Which is the whole mechanism behind a two-tone folder: one drawing, ten pairs of
     * colours, and nothing per slot but the two tints.
     */
    @Test
    fun everyFolderSlotStacksTheTwoHalves() {
        (0 until FOLDER_COLOR_COUNT).forEach { slot ->
            val text = File(DRAWABLES, "folder_$slot.xml").readText()
            listOf(
                "@drawable/ic_folder_back",
                "@drawable/ic_folder_front",
                "@color/folder_${slot}_shade\"",
                "@color/folder_$slot\""
            ).forEach { expected ->
                assertWithMessage("folder_$slot.xml should name $expected")
                    .that(text).contains(expected)
            }
        }
    }

    /** Every one of the ten colours the slots point at is defined, in both themes. */
    @Test
    fun everyFolderColourIsDefined() {
        val day = File("../app/src/main/res/values/colors.xml").readText()
        val night = File("../app/src/main/res/values-night/colors.xml").readText()
        (0 until FOLDER_COLOR_COUNT).forEach { slot ->
            assertWithMessage("values/colors.xml folder_$slot").that(day)
                .contains("name=\"folder_$slot\"")
            assertWithMessage("values/colors.xml folder_${slot}_shade").that(day)
                .contains("name=\"folder_${slot}_shade\"")
            // Only the shade has a night twin: the ten fronts clear 3:1 against both grounds.
            assertWithMessage("values-night/colors.xml folder_${slot}_shade").that(night)
                .contains("name=\"folder_${slot}_shade\"")
            assertWithMessage("values-night/colors.xml should not override folder_$slot")
                .that(night).doesNotContain("name=\"folder_$slot\"")
        }
    }
}

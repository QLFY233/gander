# File type icons

The source SVGs behind `app/src/main/res/drawable/ic_kind_*.xml`, the two halves of the folder
icon, and the four UI icons the home screen added.

## Why these are drawn here

They are original drawings by this project, released under **CC0-1.0**: a public-domain
dedication, so no attribution is owed and nothing has to be added to `licences.md`. The rule
they exist to satisfy is that no vendor's file-type artwork may be used: Microsoft Office's
icons are trademarks over proprietary assets, which is neither a permissive licence nor
something `AGENTS.md` §12 would allow into the APK.

There is one family, drawn on one 64×64 grid with a 4-wide stroke, round joins and round
caps, and one colour throughout. Everything is tinted where it is used — a card's icon takes
its badge colour, a sidebar row's takes the same one — which is what lets one picture serve
two columns.

| File | What it is |
|---|---|
| `ic_kind_pdf` | A page with the corner turned down and a bookmark ribbon |
| `ic_kind_docx` | The same page, with three lines of text |
| `ic_kind_txt` | The page, two lines, and a caret after the second |
| `ic_kind_md` | The page, with Markdown's own mark: a bar and a down arrow |
| `ic_kind_xlsx` | A table, ruled both ways |
| `ic_kind_ppt` | A screen on a stand, with a bar chart on it |
| `ic_kind_image` | A frame, a sun, and two peaks |
| `ic_kind_video` | A rounded frame with a play triangle |
| `ic_kind_audio` | A quaver: a beam and two note heads |
| `ic_kind_archive` | A box with a lid and a zip |
| `ic_kind_3d` | A cube in three faces |
| `ic_kind_generic` | The page alone — the shape for a file nothing else claims |
| `ic_kind_folder` | A folder, flat and single-colour, for a sidebar row |
| `ic_folder_back` | The tab and body behind a folder |
| `ic_folder_front` | The flap in front of it |
| `ic_import` | A tray with an arrow coming down into it |
| `ic_add` | A plus |
| `ic_settings` | A gear |
| `ic_panel` | A panel with its left column filled |

`ic_search`, `ic_down`, `ic_up`, `ic_back`, `ic_close`, `ic_delete` and `ic_share` already
existed and are not repeated here.

## SVG → vector drawable

Mechanical, one line per rule:

| SVG | VectorDrawable |
|---|---|
| `width`/`height` | `android:width`/`android:height="24dp"` |
| `viewBox="0 0 64 64"` | `android:viewportWidth`/`android:viewportHeight="64"` |
| `fill="#000000" stroke="none"` | `android:fillColor="#FF000000"` |
| `fill="none" stroke="#000000" stroke-width="4"` | `android:fillColor="#00000000"` + `android:strokeColor="#FF000000"` + `android:strokeWidth="4"` |
| `stroke-linejoin="round" stroke-linecap="round"` | `android:strokeLineJoin`/`android:strokeLineCap="round"` |
| `fill-rule="evenodd"` | `android:fillType="evenOdd"` |
| `#RRGGBB` | `#FFRRGGBB` |
| `<title>` / `role` / `aria-label` | dropped; a vector has nowhere to put them |

No file here needs a `<clipPath>`. `ic_kind_image`'s peaks are drawn to end inside the frame
rather than clipped to it, which is one command fewer and renders identically at 24dp.

Nothing sets `android:tint` in the `ic_kind_*` or `ic_folder_*` drawables: those are tinted at
the point of use (`HomeAdapter.setIcon`, `SidebarAdapter`). `ic_import`, `ic_add`,
`ic_settings` and `ic_panel` do set it, because they are menu and button icons that are never
tinted by a caller.

**Known:** a 4-wide stroke on a 64 grid is 1.5dp at 24dp, which is thinner than the 2dp
Material's own icons use. It is deliberate here — the family is drawn on one grid and a
heavier stroke closes the counters in `ic_kind_archive` and `ic_settings` — but it is the
first thing to change if these are ever drawn at 16dp.

## The folder colours

`ic_folder_back` and `ic_folder_front` are tinted separately and stacked, which is how a
folder gets two tones out of one source. `app/src/main/res/drawable/folder_N.xml` is that
pair for each of the ten slots:

```xml
<layer-list>
    <item android:drawable="@drawable/ic_folder_back" android:tint="@color/folder_N_shade" />
    <item android:drawable="@drawable/ic_folder_front" android:tint="@color/folder_N" />
</layer-list>
```

The ten fronts and their light-theme shades are in `values/colors.xml`; the ten night-theme
shades are in `values-night/colors.xml`. The fronts are written once because each clears 3:1
— WCAG's threshold for a non-text graphic — against both the light ground `#FEF9F0` and the
night ground `#17130A`. The shades have to be overridden, and are lightened rather than
darkened at night: a darker tab on a dark ground stops being a shape.

| # | Name | Front | Shade, light | Shade, night |
|---|---|---|---|---|
| 0 | docx blue | `#2B6CB0` | `#225489` | `#84AAD1` |
| 1 | xlsx green | `#2F855A` | `#256846` | `#86B89F` |
| 2 | ppt orange | `#C05621` | `#96431A` | `#DA9D7E` |
| 3 | pdf red | `#C53030` | `#9A2525` | `#DD8787` |
| 4 | md teal | `#4C8DA8` | `#3B6E83` | `#97BDCD` |
| 5 | video purple | `#805AD5` | `#6446A6` | `#B59FE7` |
| 6 | image cyan | `#319795` | `#267674` | `#88C3C2` |
| 7 | audio magenta | `#D53F8C` | `#A6316D` | `#E790BC` |
| 8 | archive gold | `#B7791F` | `#8F5E18` | `#D5B17D` |
| 9 | slate | `#546E7A` | `#42565F` | `#9CABB2` |

Which slot a folder gets is `Listing.folderColorIndex`, a hash of its tree URI or its database
id, so a folder keeps its colour across a rename.

**Known gap:** the ten have nearly the same lightness, so they are told apart by hue alone —
and to a reader with red-green colour blindness, the red, green and orange slots are the
ones that collapse into each other. The folder's name is always beside its colour, so nothing
becomes unreachable; giving the slots distinct shapes is what would close it, and is not done.

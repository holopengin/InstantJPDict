# Main-screen redesign — `design/main-ui-deepseek`

Three designers were asked to redesign `MainActivity`. This branch is the
**deepseek-v4.1-flash** take: two genuinely distinct designs, each built and
shipped as its own APK from its own commit.

Both designs keep every entry point and every line of behaviour from the old
screen. What changed is the surface: grouping, hierarchy, theming, spacing and
which affordance is pinned.

---

## Design 1 — "Harbour" (Material 3, card stack, camera FAB)

*APK: `~/apk-dl/IJPD-ui-deepseek-1-<sha>.apk` — commit `<sha>`*

### Concept

A Material 3 home surface: one scrolling column of low-elevation tonal cards
under a lifted app bar, grouped by what the user is trying to do — "Get started",
"Dictionaries", "Settings". The camera, which is the reason the app exists, is
not a row in that column at all: it is an extended FAB pinned to the bottom-right,
a sibling of the scroll view, so it never scrolls away and never competes with the
list for attention. Material You recolours the whole screen from the wallpaper on
API 31+; the hand-picked harbour-azure palette is the deliberate fallback below
that.

### What changed vs the old screen

| Old | New |
| --- | --- |
| Flat vertical column of eight identical full-width buttons | Three tonal cards grouped by intent, with one primary action per card |
| Title + bare status line | App bar with title/subtitle, and a status **banner** that hides itself entirely when there is nothing to say |
| Long accessibility-settings paragraph in the open | One filled primary button + an "Access was denied?" disclosure that reveals the paragraph only when needed |
| Camera as a plain button **below** the list | Extended FAB "Scan with camera", pinned, always reachable |
| Debug tuning revealed inline in the middle of the button column | Debug tuning nested inside the Settings card as a bordered panel, split into "Overlay behaviour" and "PP-OCR parameters" |
| `SeekBar` rows with hand-tinted backgrounds | Material 3 `Slider`s with value tooltips, snapping to each row's step on touch-up |
| `CheckBox`es | `MaterialSwitch` row items (≥56 dp targets, label as content description) |
| `Theme.MaterialComponents.DayNight` | `Theme.Material3.DayNight` + `DynamicColors` via `App` |

### Notable decisions

- **Material 3 as the base theme.** `Theme.InstantJPDict` moves to
  `Theme.Material3.DayNight.NoActionBar`; a full light/night role palette lives in
  `values/colors.xml` / `values-night/colors.xml`, and `App`
  (`DynamicColors.applyToActivitiesIfAvailable`) overlays Material You on API 31+.
  The palette is a real fallback, not a placeholder.
- **The camera is the FAB, not a row.** Pinning it in `CoordinatorLayout` outside
  the `NestedScrollView` is the direct replacement for the old pinned bottom
  button, with better prominence and a larger tap target.
- **Sliders run continuous (`stepSize = 0`).** The tuning rows' ranges are not
  exactly divisible by their steps in float (`0.2..1.0 step 0.1`, `0.3..1.0 step
  0.05`), and Material's `Slider` throws on a stepped configuration that does not
  divide cleanly. The snap-to-step happens on touch-up and Apply instead — same
  stored values, same behaviour, no layout-time crash risk.
- **The status banner is invisible when empty**, so the first screen has no
  reserved dead strip.
- **The tuning panel keeps the old code paths exactly**: same
  `SharedPreferences` keys, same `DebugTuning.rows`/`features` iteration, same
  `recreate()` after a reset, same "Copy inference log" clipboard payload.
- Edge-to-edge is explicit: `WindowCompat.setDecorFitsSystemWindows(false)` plus
  one `systemBars()` inset pass over the app bar (top), the column (sides +
  bottom) and the FAB (bottom), so the bar icons never sit on content.

### How to reach it

It is the default and only screen — launch the app. Material You follows the
device wallpaper on Android 12+; otherwise the Harbour palette shows, day and
night.

### What a reviewer should look at

- First-run hierarchy: is "Enable accessibility service" obviously the thing to
  do, and is the camera obviously the thing to reach for?
- The FAB's clearance over the last card while scrolling, and over the gesture /
  3-button navigation bar.
- Light **and** dark mode (the palette and both bar-icon polarities).
- The Debug settings switch: does the nested panel read as subordinate to
  Settings rather than as a peer of the camera?
- TalkBack: switch rows, the disclosure, and the status banner.

---

## Design 2 — "Instrument" (dashboard grid, camera hero, bottom-sheet settings)

*APK: `~/apk-dl/IJPD-ui-deepseek-2-<sha>.apk` — commit `<sha>`*

_(to be filled in by the second commit)_

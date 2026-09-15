# #85 keystone warp: four-corner quad fit — host-bench findings

**Outcome: do not ship a component-boundary quad fit for keystone Lines.** On the
#85 host bench every fitter tried is unstable across lines and, at keystone
strength ≥ 0.35, loses to the shipped #53 minimum-area-rectangle path. An oracle
experiment shows the blocker is structural, not the estimator: even a *perfect*
fit of the DB component's own boundary does not beat the rect at 0.30–0.35, and
at 0.40 it recovers only a fraction of the ideal-warp headroom. The measurements
below are the evidence; nothing in this note is shipped.

## Bench and reproduction

The bench is the #85 host probe set (real Aozora lines, synthetic keystone; the
shipped det + int8 rec harnesses):

- `/tmp/opencode/rot53/rot53_probe.py` — render/variants, det pre/post port,
  `fit_quad`/`unclip_quad`/warp/rec helpers.
- `/tmp/opencode/rot53/keystone_probe.py` — the measured headroom experiment
  quoted in the issue (AABB vs #53 rect vs ground-truth ideal quad).
- `/tmp/opencode/rot53/keystone_fit_probe.py` — the four fitter prototypes and
  their CER runs (`--fitter hull|chains|wmodel|spans`).
- `/tmp/opencode/rot53/oracle_probe.py` — the oracle experiment below.

Run with `uv run python <script>` from the uv project root. Keystone `m` is the
top-edge inset as a fraction of page width; weighted CER = Σ(edit ops × len) /
Σ len over the 12 lines.

Shipped-path baseline (reproduces the issue table exactly):

| keystone | AABB | #53 rect | ideal quad |
|---|---|---|---|
| 0.30 | 0.0411 | 0.0411 | 0.0000 |
| 0.40 | 0.4726 | 0.6233 | 0.0137 |
| 0.48 | 1.0000 | 1.0000 | 1.0000 |

## What was tried

All fitters take the same boundary points `detectRotated` already builds (the
outer corners of every boundary pixel, in source space), the #53 rect fit as
initialisation/fallback, and apply DB unclip + warp-with-target-from-longer-edges
before recognition.

1. **Hull corners (`hull`).** Convex hull → Douglas–Peucker (ε ≈ 2% of the short
   side) → the 4 simplified vertices maximising inscribed-quad area → refine by
   TLS lines through the raw hull chains between them → intersect. Cyclic
   rotation/direction chosen to best match the rect corners.
2. **Boundary chains (`chains`).** Per row of the rect frame, the min/max local x
   (left/right chains); per column the min/max local y (top/bottom chains);
   robust lines, intersected.
3. **Width model (`wmodel`).** As (2) but the side lines come from robust
   regressions of `xl(v)`, `xr(v)` themselves (every boundary pixel votes).
4. **Spans (`spans`).** Only the top/bottom edge length difference is trusted:
   the u-extents of the boundary in the top and bottom 25% bands, centre and
   half-width extrapolated to the frame's v=0/h edges → a symmetric trapezoid.

Shared fallback gate: a fit engages only when its corners move at least 2.5 px
from the rect fit's; otherwise the probe keeps the exact #53 (rect) crop. The
gate is what makes `wmodel` a no-op at 0.35 — not a principled fallback, just a
tolerance that is already too coarse to separate keystone from cap noise.
Fitter parameters: 1 px bins and 1.5 px outlier trim (`chains`), 3 px trim and
8–92% side bands (`wmodel`), 25% end bands (`spans`), DP ε = max(1 px, 2% of the
short side) (`hull`).

## Results (weighted CER, 12 lines)

| keystone | AABB | #53 rect | hull | chains | wmodel | spans | ideal |
|---|---|---|---|---|---|---|---|
| 0.30 | 0.0411 | 0.0411 | **0.0205** | 0.1370 | 0.0342 | 0.0274 | 0.0000 |
| 0.35 | 0.1096 | 0.1027 | 0.3699 | 0.1712 | 0.1027 | 0.1164 | 0.0000 |
| 0.40 | 0.4726 | 0.6233 | 0.7055 | 0.6644 | 0.6233 | 0.6781 | 0.0137 |

At 0.30 three fitters beat the rect on aggregate (hull 0.0205, spans 0.0274,
wmodel 0.0342 vs 0.0411) — but the oracle below shows the component cannot
actually carry a keystone correction there, so those wins are crop/aspect
luck, not recovery. At the acceptance threshold (0.35) the hull fit collapses
to 0.3699 and no fitter beats the rect. That swing is the fitting noise that
must not ship.

Per-line instability is the point, not the aggregate. Examples:

- `hull`, m=0.35: line "が、背むしはふり返りもしない" 0.000 → 0.643; line
  "二つともこの通り入れ眼ですよ" 0.071 → 1.000; 2/12 wins, 10/12 losses.
- `spans`, m=0.40: the top/bottom span difference has the *wrong sign* on some
  lines (l4: top span 441.8 < bottom span 590.3, although the keystone compresses
  the top edge), so the correction pushes the crop further from upright.
- `wmodel`, m=0.35: the fallback rejects all 12 fits (corner displacement below
  tolerance despite the keystone), i.e. the fit contributes nothing at all.

## Oracle: the component's own extent is the wall

`oracle_probe.py` inverts the ground-truth keystone homography, maps the largest
det component's pixels back into the upright page, takes their local bounding
box, and maps that box forward again. The result is the best quad *any* fit of
this component's boundary could produce if estimation were perfect. Warping with
it (unclip + longer-edge target) gives:

| keystone | #53 rect | oracle (perfect component fit) | ideal (full ink quad) |
|---|---|---|---|
| 0.30 | 0.0411 | 0.0479 (0/12 wins) | 0.0000 |
| 0.35 | 0.1027 | 0.1027 (3/12 wins) | 0.0000 |
| 0.40 | 0.6233 | 0.5685 (8/12 wins) | 0.0137 |

At 0.30–0.35 the component's own extent simply does not carry the headroom: the
ideal warp's gain comes from the *full text extent* (the compressed line ends,
which the DB mask under-covers), not from the shape of the blob the detector
returns. At 0.40 the component does become genuinely trapezoidal (8/12 oracle
wins) but fragments as well — e.g. l0 splits into 6128 px + 607 px — so no
single-component warp can even contain the whole Line, and the oracle stays
0.57 against ideal 0.01.

## Why the boundary is a bad quad

- The DB 0.3 component of a keystoned line is an inward-shrunk, glyph-shaped
  strip, not the image of the Line's bounding rectangle. On l5/m=0.30 the true
  left edge moves 20.6 px across the component's 16-row extent (slope 1.33 x/y);
  the component's own per-row leftmost x moves 10.8 px in steps — the ink shape
  of the first glyph, not the edge.
- Corner estimates are set by which glyphs have extreme ink. Across the 12 lines
  at m=0.30, hull-fitted side slopes ranged 0.1–2.2 against true 1.09–1.82, and
  the two sides were often wildly asymmetric (l0/m=0.35: 2.2 vs 0.2).
- The robust top/bottom span signal is also content-dependent: the "top edge" is
  the width at whichever rows have tall glyphs, so its length changes with the
  characters at the ends, not only with the keystone.
- A rounded cap has no resolvable side line: TLS on a cap chain returns the cap's
  chord direction (~vertical), no matter where the true side edge is.

## If keystone recognition is picked up again

- Prefer a **document/page-level unwarp in front of detection** (the issue's
  open question 5). It fits a page-scale homography where the signal is strong,
  instead of estimating a projective correction from a 15–35 px-tall strip.
- Or make the detector emit a genuinely quadrilateral region (PaddleOCR
  `box_type="poly"` uses the contour, not a min-area rect) — but on this bench
  the component contour itself is the weak link, so this needs a det-side change,
  not a postprocess fit.
- A **multi-component group fit** (merge the fragments first, then fit one quad)
  is the only route that could approach the ideal at m≈0.40, and it needs its own
  merge rules and QA; it is not a four-corner fit problem.
- Recognition-side robustness (augment/curriculum on keystoned crops) targets the
  same CER without any of the fit's geometry risk.

## What is NOT a blocker

The recognition model reads 0.30-keystone lines fine through an AABB (0.041), so
none of this implies retraining; the measured ideal headroom is real. It is the
estimate-from-the-component step that cannot reach it on this bench.

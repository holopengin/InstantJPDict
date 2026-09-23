# Char placement: findings and a proposed algorithm

Research branch `research/char-placement`.  Scope: replace
`OcrEngine.computeCharBoxes` (plus `legacyCells` / `snapCells` /
`resolveInkCollisions` / `uniformCells`) with **one** algorithm that turns CTC
timesteps, per-timestep confidences and the crop pixels into per-character
boxes that stay on the glyphs for horizontal, vertical and rotated lines, on
screenshots and camera photos.

Everything below is reproducible from this branch:

```
# synthetic set (HarfBuzz + FreeType, real JP corpus, simulated CTC)
tools/char_placement$ python synthesize.py --out /tmp/opencode/charplace_synth --preset all --n 30
tools/char_placement$ python eval.py --data /tmp/opencode/charplace_synth --only clean
# real-inference spot check (dump produced by the scratch Rust harness, below)
tools/char_placement$ python real_eval.py /tmp/opencode/real_lines.jsonl
# JVM: the Kotlin port pinned to the Python reference
$ ./gradlew :app:testDebugUnitTest --tests "*CharPlacement*"
```

## TL;DR

* The shipped chain places each character at its CTC column
  (`(charCols + 0.5) · stride`) and then repairs with pixel snapping, pairwise
  ink-collision nudges and a median-pitch em.  The fundamental limit is that
  a CTC column can only locate a glyph to **±0.5 timesteps**, and a timestep
  is 0.33em (tight crops) to 0.7em (tall photo crops) wide.  Measured on real
  inference against an ink proxy, **50.7% of characters sit more than 0.25
  timesteps and 19.9% more than 0.5 timesteps from the glyph's ink centre**;
  the error is unbiased, so only image evidence can remove it.  A second,
  smaller error: the shipped map ignores **run length** — for the 7% of
  characters whose argmax run spans ≥2 timesteps the box is
  `(runLength-1)/2` timesteps left of the activation centre.
* The proposed algorithm uses the **whole activation run** (from the
  per-timestep top-K that is already cached in `rawAlternatives`), fits a
  **JP advance-class layout template** to the run centres, then refines each
  character with a **robust ink measurement** (mid-quartile of the
  reading-axis ink profile inside a template-bounded window, retried around
  the CTC run when a window is contaminated — punctuation picking up a
  neighbour's stroke, or a bimodal window whose mid-quartile straddles a real
  ink valley (a dakuten crossing the Voronoi bound)) and
  ends with a **final boundary pass**: adjacent boxes share a boundary placed
  in the empty ink space between the glyphs — and an overlapping pair first
  translates apart into that empty space; only the residue is split.  The template averages the
  quantization noise over the whole line; ink refinement then takes out what is
  left, including for the punctuation and small kana the shipped snapping
  refuses to touch; the boundary pass stops the midpoint cap from chopping
  strokes.
* Synthetic evaluation, 190 clean lines / 3556 characters (horizontal,
  vertical, rotated, degraded, ruby, tracking, long; real corpus):
  mean centre error **4.07px → 1.50px** (0.131em → 0.048em), p90 **9.60px →
  3.00px**, >0.5em errors **2.0% → 0.1%**, tap-at-centre hit **97.5% →
  99.4%**, jittered-tap hit **94.4% → 98.7%**, and the share of a glyph's own
  ink interval its box covers **0.919 → 0.967**.  Every preset improves.
* Real inference spot check (290 lines / 3075 characters from the vendored
  recognition fixtures): where the two algorithms disagree by ≥2px on an
  unambiguous ink component, the proposed centre is closer **1184 times vs
  848** (37% ties).
* No new Android runtime dependencies; the Kotlin implementation is pure
  functions over `IntArray` pixels, `FloatArray` columns and a
  `List<List<Step>>` derived from `rawAlternatives`.

---

## 1. What the shipped algorithm does, and where it loses

`computeCharBoxes(text, charCols, seqLenTotal, crop…)` is a four-stage
pipeline per line (horizontal path; the vertical path adds punctuation
surgery):

| stage | what it does | failure mode |
|---|---|---|
| `legacyCells` | centre each char at `(t+0.5)*cropLen/seqLen`, width = the crop's short side | `t` is the run **start**; correct only for single-timestep runs, otherwise `(runLength-1)/2` timesteps left of the activation centre |
| `snapCells` (#49) | moves centres to nearest ink-profile peak, vetoed by five gates (mass floor, argmax/centroid agreement, 3px min-move, Voronoi clamp, 0.4-pitch leash); skips punctuation and small kana entirely | every veto silently restores the un-snapped centre; punctuation and small kana *never* get snapped |
| `resolveInkCollisions` (#49) | nudges centres apart using `Paint.getTextBounds` half-widths of the **platform default typeface** | the on-device font is unknown; measured on the wrong font, the nudge chain moves centres that did not need moving |
| `uniformCells` (#49) | rewrites widths to `advance_class × em`, `em` = median width-normalised centre pitch | uses the already-noisy centres to size boxes; the vertical path then expands/shrinks punctuation by ad-hoc rules |

### Where the error actually comes from (measured)

The decoder is not to blame for the balance of the error; the *resolution* is.
`ctcDecode` walks timesteps; a character owns an integer run of timesteps, and
the app maps it to the centre of that run's pixel coverage, `(t+0.5)·stride`.
On the vendored fixtures (290 lines, 2598 characters with usable ink
evidence), comparing that mapped centre with the glyph's own ink mid-quartile:

| quantity (real inference) | mean | median | \|err\| > 0.25 t | \|err\| > 0.5 t |
|---|---|---|---|---|
| shipped map `(charCols + 0.5)·stride` | +0.065 t | +0.065 t | **50.7%** | **19.9%** |
| run-centre map `(runMid + 0.5)·stride` | +0.095 t | — | — | — |

Both are unbiased, and the two maps agree on average — because **93% of
characters have a single-timestep run**, where `charCols` *is* the run start
and `(t+0.5)` is already the activation centre.  The error is the
quantization: the glyph's true centre can fall anywhere inside its timestep,
so the shipped centre is off by a uniform ±0.5 timesteps.  One timestep is
`crop_height/3` px with the default squish (`crop_height/6` when the 32-step
floor keeps a short line unsquished), i.e. **0.33em on a tightly cropped
screenshot line and 0.5-0.7em on a tall photo line box**.  Half a timestep is
a sixth to a third of a character.

The residual run-length error is real but small in count: for the 7% of runs
that span ≥2 timesteps, `charCols` is 0.5-1.5 timesteps left of the run
centre (measured -0.94 t for 2-timestep runs, -1.60 t for 3-timestep runs),
so the shipped box is `(runLength-1)/2` t left of the activation centre.

Snapping repairs the quantization error for the characters it is allowed to
touch, but its gates (contaminated peak, min-move, punctuation, small kana)
leave the rest at the raw column.  Synthetic clean-set means tell the same
story from the other side: legacy 0.190em, shipped (snap+uniform) 0.131em,
proposed 0.053em.

### Quantified failure taxonomy

Categories observed in the synthetic set and in the real fixtures, with the
mechanism that produces them:

1. **Timestep quantization / coarse stride.**  The column pins a glyph only
   to ±0.5 timesteps; on real lines 50.7% of centres are more than 0.25
   timesteps off, and any vetoed snap or skipped class keeps that error.  The
   synthetic `long`/`rotated` presets (stride 0.5-0.7em) show it as
   0.14-0.18em mean error for the shipped chain; the `rotated` preset p90 is
   0.48em.  A 2-3 timestep run adds `(runLength-1)/2` t of one-sided error.
2. **Punctuation and small kana.**  Excluded from snapping
   (`isSnapSkipped`) and then given the same cell-shaped box as everything
   else; the vertical path shrinks/grows them asymmetrically.  Measured
   punctuation error: shipped 0.15-0.41em, proposed 0.09em.
3. **Ink-snap vetoes and contamination.**  Profile peaks merge neighbours
   (the documented し/失 case), so the agreement veto restores the legacy
   centre; conversely a peak that is a neighbour's stroke moves the centre
   onto the neighbour.
4. **Neighbour-driven collision nudges.**  `resolveInkCollisions` can push a
   chain of centres; the move is only bounded by each pair.
5. **Recognition errors (CTC substitutions/insertions/deletions).**  The
   shipped chain does not use confidence at all, so a low-confidence
   character drags its box by the same amount as a certain one.  With
   injected CTC errors the synthetic `errors` preset mean error is 0.107em
   (shipped) vs 0.063em (proposed).
6. **Ruby stacks / neighbour strokes.**  A fixed central 60% ink band
   contains furigana ink for wide crops; ruby presets show 0.18em (shipped)
   vs 0.05em (proposed, which picks the dominant cross-band first).

## 2. The proposed algorithm

Name it **CAP** — *CTC-Anchored Placement*.  One pure function, one pass over
the line, no per-class zoo of repairs.  Inputs: decoded `text`, `charCols`,
`seqLenTotal`, crop size/orientation, `pixels` (optional), and the
per-timestep top-K (`rawAlternatives`, already cached on `PPOcrResult` /
`LineResult`).

### Step 0 — activations from the real decode (not the first column)

Replay the decoder's greedy walk over the top-K: blank resets the repeat
state, a repeated argmax extends the current run, a new argmax opens the next
one.  Each decoded character `i` gets

```
run_i    = [start_i, end_i]            (inclusive timesteps)
centre_i = ((start_i + end_i)/2 + 0.5) * L / seqLenTotal
conf_i   = mean top-1 logit over the run
margin_i = mean (top-1 - top-2) logit over the run
```

The run centre is unbiased against an ink proxy (measured +0.10 timesteps over
2598 real characters); the `+0.5` is the app's cell convention and is correct.
`margin_i` is the confidence signal used later; raw logits have no absolute
scale, their margin does.

If no top-K is available (legacy callers, cache re-decode), fall back to
one-timestep runs at `charCols` — the algorithm then degenerates gracefully to
a better version of `legacyCells`.

### Step 1 — advance-class layout template

JP fonts lay characters on the em grid.  With

```
u_i    = 1.0 for fullwidth (kanji, kana, fullwidth punctuation/digits)
         0.5 for ASCII and halfwidth katakana       (OcrEngine.isHalfWidth)
P_i    = Σ_{j<i} u_j
```

fit, over characters whose ink is centred in the em box
(`optical_class == CENTER`; punctuation, small kana and marks excluded),

```
centre_i ≈ X0 + em · (P_i + u_i/2) + ls · i
```

by **Huber-reweighted least squares** (3 passes, weights start from `conf_i`).
The system is solved in column-standardised space — on a uniform-class line
the `ls` column is collinear with the advance column, so letter-spacing is
fitted on the residuals afterwards with a prior ridge (`|ls| ≤ 0.1em`) and
kept only if mixed advance classes make it identifiable and it is larger than
`0.02 · em`.  `em` is clamped to `[0.6, 1.7] × median width-normalised run
pitch` and loosely to `[0.15, 3] × cross` (a crop can be much taller than one
em, so the crop thickness is only a backstop).

The pseudo-pitch used for clamping is the weighted median of
`(centre_{i+1} - centre_i) / ((u_i + u_{i+1})/2)` — the same normalisation
`estimateEm` uses, but on run centres instead of repaired box centres.

Why a template: it gives every character an absolute position that does not
accumulate drift, and it gives the ink step a window that does not depend on
the previous character's measurement.

### Step 2 — anchor selection per character

```
anchor_i = template_i   if |centre_i^ctc - template_i| ≤ max(0.4·em, 1.2·stride)
           centre_i^ctc otherwise
```

The tolerance is deliberately generous relative to the ±0.5-stride
quantization bound: the template is preferred whenever quantization could
explain the disagreement, and the CTC column only wins when the template is
off by more than a full timestep.  This matters when the linear model breaks:
halfwidth runs cut by an integer grid, or crops whose stride is comparable to
the em (tall photo lines).  There the run centre is the better anchor and ink
measurement repairs the remaining ±stride/2.

### Step 3 — ink refinement

1. Build the reading-axis ink profile over the **dominant cross band** (the
   ink run containing the cross-axis mass median, widened to ≥35% of the
   cross axis).  This dodges ruby/neighbouring-line ink before it can
   contaminate anything.
2. For each character, take the Voronoi window
   `[mid(anchor_{i-1}, anchor_i), mid(anchor_i, anchor_{i+1})]`, clipped to
   `anchor_i ± max(0.6·em, 0.4·stride)` and to the character's own advance
   cell ± 0.6em.
3. Measure the **mid-quartile** of the profile inside the window
   (`(q25+q75)/2` of ink mass).  Quartiles, not a centroid: a neighbour's
   stroke leaking through the window moves a centroid, not the quartiles.
   Skip the window if its mass is below `max(6, 0.12 × median window mass)`,
   or if the IQR spread exceeds `0.8em` (a smeared/ambiguous window).
4. Move the centre by the clipped pull `clamp(ink - anchor, ±0.45em)`,
   halved when `margin_i < 1.0` logit (the recogniser is unsure the run is
   even this character).  Repeat the window/pull pass once with the updated
   centres.

Punctuation and small kana use the same measurement without any special
boxes: their ink is genuinely off-centre in the cell, the mid-quartile lands
on it, and the renderer (which draws each glyph with its ink centre at the
box centre) consequently draws them where the source has them.

### Step 4 — the final pass: boundaries, not midpoints

The old width rule capped each box at the midpoint between centres, which
guarantees non-overlap but cuts ink: on the clean synthetic set **95.7% of
boxes were midpoint-capped and 45% of them failed to cover their glyph's own
ink interval** (mean coverage 0.947).  The cap also cannot tell a wide glyph
from a narrow one, so a kanji beside a comma lost exactly as much as the comma.

The final pass replaces the midpoint cap with a **shared boundary per adjacent
pair**, chosen in the empty ink space between the glyphs:

1. **Required room per glyph.**  The advance cell (`0.5 · u_i · em`) is the
   floor; the profile is measured for an ink span (5% trimmed mass quantile
   span) inside the character's Voronoi window, but only when the centre sits
   on its own ink (`profile[centre] > floor`).  An offset-ink glyph whose
   centre is a gap (punctuation) or a contaminated pull keeps the advance cell:
   its "extent" would be the neighbour's stroke.  The result is capped at
   `1.3 · advance half`.
2. **Translate first.**  When a pair's rooms do not fit
   (`need_i + need_{i+1} > gap`) it is pushed apart by its deficit into the
   *positive slack of its flanking pairs* — the empty space beside them — with
   slack recomputed after every move, so a move can shrink a neighbour pair's
   interval but never drive it negative.  Each glyph's move is capped at
   `translate_max_em` (0.04em — the measured peak, below); whatever deficit
   remains falls through to the split.
3. **Boundary.**  With `req_i = max(need_i, h_old_i)` (`h_old` = the old
   midpoint cap), if `centre_i + req_i ≤ centre_{i+1} - req_{i+1}` both glyphs
   fit and the boundary may sit anywhere in that interval: it goes to the
   **emptiest point** (midpoint of the longest run under the profile floor,
   else the mass minimum).
4. **Split.**  When they do not fit (tight tracking, merged strokes, a wide
   glyph beside a narrow one), the contested span is split at its emptiest
   point, with each box kept at least `splitFloorFrac` of `h_old` (1.0 by
   default — see below).

```
req_i  = max(need_i, h_old_i)
b_i    = emptiest(prof, centre_i + req_i, centre_{i+1} - req_{i+1})   if feasible
       = emptiest(prof, centre_i + floor_i, centre_{i+1} - floor_{i+1})  else
half_i = min(need_i, b_i - centre_i, centre_i - b_{i-1})
box_i  = [centre_i - half_i, centre_i + half_i]       (clamped to [0, L])
```

Because the boundary never crosses `centre_i + h_old_i` or
`centre_{i+1} - h_old_{i+1}`, **every box is at least as wide as the old cap
allowed** (the pass is monotone: 1511 clean characters improve their ink
coverage, 0 regress), and no box crosses its neighbour's centre, so the
first-rect-wins hit test keeps working.  Cross axis = the whole crop, exactly
as the renderer and hit-tester expect.  Vertical lines are the same on the y
axis; rotated lines are unchanged (they are computed in the upright local
frame and mapped through the quad).

**Measured effect, the full stack (clean set, 3556 characters).**
Midpoint-cap lineage (no final pass): coverage 0.946, cell IoU 0.556, width
error -0.088em.  Adding the boundary pass: coverage 0.952, cell IoU 0.561,
width error -0.068em.  Adding translation: **coverage 0.967, cell IoU 0.570,
width error -0.037em, tap 99.4%, mean centre error 1.50px** (vs 1.65px
without).  On all 210 cases: 1.73px → 1.57px, coverage 0.949 → 0.965.

**Translation is where the win is.**  Diagnosis first: 51.9% of pairs fail
the requirement test `need_i + need_{i+1} > gap`, but only **17 pairs have
source inks that truly overlap** — the collision is our own policy (both
boxes at the advance cell) against centre pitches jittered below the em, and
with `splitFloorFrac = 1.0` the split's floor interval collapsed to ~0.02·d,
so for half the pairs the pass degenerated back into the midpoint split it
was meant to replace.  53% of those pairs have flank room ≥ their deficit —
exactly the case this step exploits.

With translation (clean set): centre error **1.61 → 1.46px** (p90
3.50 → 3.00), tap **99.2% → 99.5%**, jitter **98.3% → 98.8%**, coverage
**0.955 → 0.970**, cell IoU **0.562 → 0.571**, width error
**-0.066 → -0.036em**.  All 210 cases: **1.71 → 1.55px** (p90 3.50 → 3.09),
coverage 0.952 → 0.967 — and **every preset improves** on error and cover
(rotated 1.99 → 1.79, degraded 1.94 → 1.70, errors 2.28 → 2.13).  Costs:
ink IoU 0.765 → 0.762, the CTC-error subset's tap 0.980 → 0.978.

Why *small* moves win: centres the ink refinement pulled inward by crowding
are debiased by the outward translation, so at the cap the mean error drops
*below* the no-translate baseline; past ~0.10em the moves overshoot and error
climbs back above it.  Sweep (clean, mean px): no translate 1.61, cap 0.03 →
1.45, **0.04 → 1.46**, 0.08 → 1.55, 0.15 → 1.68, 0.35 → 1.71.  0.04em is the
knee: every fit metric at its plateau, error still improving.

Allowing the split to go below the old cap (`splitFloorFrac < 1`) buys ink IoU
(0.778 at 0.70) but loses tap quality (98.8% → 98.8%/97.8% jitter) and cell
IoU, so the default keeps the monotone floor.

**End-anchored sweep (measured, not the default).**  The alternative
proposal: anchor ONE end of the line and walk to the other
(`sweep_place`, `sweep_reverse`), pushing each glyph forward through the
sweep into usable space, centring it in the gap when the whole box cannot
fit, letting whitespace shrink to yield first (it has no ink and the
renderer never draws it), with every split deferred to the boundary pass —
plus a pull-back to the measured centres afterwards.  The tiling is the
best of any variant: cell IoU **0.608** (the shipped chain's 0.605,
translate's 0.571), width error **-0.013em**, tap **0.996**.  But
one-sided anchoring costs alignment: the *full* deficit lands on the swept
glyph (translate splits it between two, and its outward direction happens
to debias the ink refinement's inward pull), pushes cascade until the boxes
touch, and the pull-back recovers nothing at convergence because the space
genuinely does not exist.  Clean set: centre error **2.37px** (start→end
direction: 1.88) vs translate's 1.46, cover 0.959 vs 0.970, ink IoU 0.732
vs 0.762.  Direction only trades one error for another, so `sweep_reverse`
stays for a device comparison of the tiling look; the default remains
translate.  Reproduce as `proposed_sweep` in eval.py; not ported to Kotlin
while it is not the default.

**Contaminated windows (punctuation and cross-boundary strokes).**  The
boundary pass only moves box edges; the measured *centre* failures come from
windows whose mid-quartile straddles a neighbour's ink.  Two gates feed one
retry — the CTC run centre in a 0.5em window, accepted when it measures a
compact blob within `0.6em` of the anchor; a rejected retry keeps the primary
measurement rather than dropping the pull:

* **spread gate** — a smeared window (`spread > 0.8em`), for
  punctuation/small kana whose small ink lets a neighbour dominate it (a `、`
  was measured pulled 0.44em onto the following kanji; corner-punctuation
  error 0.098em → 0.086em, >0.3em 8.0% → 5.0% on the clean set).
* **bimodal gate** (`bimodal_retry`) — `CENTER`-class windows split by a real
  ink valley (`>= 0.20em` long, off-side mass `>= 0.12` of the window): the
  mid-quartile lands *between* the blobs.  The device case it was found with:
  **の sat 9.5px too high** because で's dakuten crosses the Voronoi bound into
  の's window; with the retry the same device line measures **-1.5px**.  Gate
  sweep (clean): valley 0.10/0.15em misses the device case (its valley is
  0.26em), 0.25em costs clean accuracy, 0.20em/0.12 holds comfortable margins
  on both sides — cost **1.46 → 1.50px**, tap 0.995 → 0.994, coverage 0.970 →
  0.967.

**Anchor tolerance (measured, not shipped).**  A dropped character skews the
template grid mid-line, and where `|ctc - template|` stays under
`max(0.4em, 1.2*stride)` the skewed template beats the quantised-but-correct
CTC (the の case: 21px < 30px).  Tightening to stride-only tolerance fixes
such cases and *helps* the CTC-error subset (2.45 → 2.35px) but costs the
clean set (1.46 → 1.53px), because on clean lines the averaged template
genuinely beats the quantised CTC.  Residual-step and fit-RMS detectors were
both tested and neither separates skewed lines from clean ones — a per-line
template-validity signal is the open problem.

**What the pass cannot fix.**  On the real fixtures the ink-run arbitration
(which measures *centres*) is unchanged by the pass (2 vs 3 of 3530 components
differ by ≥1px), because the pass only moves box edges, and the dominant real
error is centre placement.

### Parameter sensitivity

Perturbing each parameter by ±25% (anchor tolerance in em/stride, window in
em/stride, pull) around the defaults moves the clean-set mean between 0.052em
and 0.056em with tap between 98.7% and 99.2%, so the choice is not
knife-edge.  The parameters also have physical readings: the
anchor tolerance is just past the ±0.5-stride quantization bound, the window
is roughly the glyph's half-width, the pull is bounded below the distance at
which a box could land on a neighbour.  The residual width bias (boxes ~8%
narrower than the em cell, mostly halfwidth Latin) is the one number worth
revisiting if a consumer ever needs cell-exact tiling.

## 3. Evaluation

### Synthetic set

`tools/char_placement/` generates line crops with HarfBuzz shaping +
FreeType rasterisation from the bundled Noto Sans/Serif JP fonts and the
committed text sample (Jitendex examples + Aozora Bunko, see
`corpus/PROVENANCE.md`).  Per character the generator stores the **advance
(em cell) box** and the **ink box**, and records a documented CTC simulation:
per-glyph activation runs at the app's exact geometry
(`targetW = round(L*48/cross)`, squish 0.5, 32-step floor, stride 8), a
per-timestep top-K logit stream with a blank, and injected
substitution/insertion/deletion failures for the `errors` presets.  The
simulated stream is decoded with the app's own rules, so both algorithms
receive byte-identical `charCols`/`text` to what the device would pass, and
the proposed one additionally gets the top-K.

Cases: horizontal/vertical, 20-60px em, sans/serif, tracking -0.08..+0.5em,
rotations ±1..6°, blur/noise/JPEG/uneven light/downscale/dark, ruby stacks,
long lines, CTC error injection.  210 cases, 190 of them decode exactly
("clean"); the remaining 20 are CTC failures kept as first-class cases.

Metrics per character (decoded→true mapping from the generator): reading-axis
centre error, IoU against the cross-extended ink box, axis-IoU against the
advance cell, tap-at-centre hit (does the box contain the true ink centre),
and tap-jitter hit (4 samples, σ=0.12em, simulating a finger tap and the
first-rect-wins hit test).  These metrics — exact formulas, eligibility, and
the corpus quality gates they feed — are specified normatively in
`docs/char-placement-conformance.md` (the PC handoff).

**Clean set, 190 lines / 3556 characters** (`current` = shipped chain with
snap+uniform; `current_nosnap` = uniform only; `legacy` = neither):

| algorithm | mean err | median | p90 | mean (em) | >0.5em | tap centre | tap jitter | cover | ink IoU | cell IoU |
|---|---|---|---|---|---|---|---|---|---|---|
| legacy | 6.09px | 5.07px | 12.20px | 0.190em | 2.9% | 98.3% | 82.0% | 0.957 | 0.486 | 0.568 |
| current_nosnap | 5.78px | 4.96px | 11.66px | 0.181em | 1.5% | 97.7% | 93.8% | 0.880 | 0.612 | 0.641 |
| **current** | 4.07px | 2.62px | 9.60px | 0.131em | 2.0% | 97.5% | 94.4% | 0.919 | 0.661 | 0.605 |
| proposed (midpoint cap) | 1.65px | 1.00px | 3.50px | 0.053em | 0.1% | 98.9% | 98.0% | 0.946 | 0.768 | 0.556 |
| proposed (final pass, no translation) | 1.65px | 1.00px | 3.50px | 0.053em | 0.1% | 99.0% | 98.1% | 0.952 | 0.763 | 0.561 |
| **proposed (+ translation)** | **1.50px** | **1.00px** | **3.00px** | **0.048em** | **0.1%** | **99.4%** | **98.7%** | **0.967** | 0.760 | **0.570** |

`cover` = mean share of a glyph's own ink interval (reading axis) the box
covers; it is the metric the final pass moves, and it moves monotonically
(1511 characters improve, 0 regress).  `tap jitter` uses four σ=0.12em samples
per character with a fixed CRC seed; run-to-run differences before that seed
was pinned were ~0.4pp, the same size as the pass's jitter gain, so treat the
tap-centre and cover columns as the load-bearing ones.


Per preset (mean error em, shipped → proposed): core 0.105 → 0.049,
degraded 0.102 → 0.058, errors 0.092 → 0.042, long 0.143 → 0.057, rotated
0.179 → 0.059, ruby 0.180 → 0.050, spacing 0.090 → 0.041.  The proposed
algorithm's >0.5em miss rate is 0.0% in every clean preset; the shipped
chain's worst is 4.8% (rotated).

Including the 20 CTC-error cases (all 210 lines, 3814 characters) the gap
widens on the tail (shipped `errors` preset 0.107em / 1.8% miss vs proposed
0.063em / 1.0%), because the confidence margin halves the ink pull exactly
where the recogniser is unsure.

### Real-inference spot check

The vendored recognition fixtures (PC-hosted conformance corpus) were run
through the shared ncnn core with the scratch Rust harness committed under
`tools/char_placement/real_dump/` (linked against the pinned ncnn fork's
prebuilt `libncnn.a`; build notes in that directory).  The dump carries, per
recognized line, the pinned geometry (the shipped algorithm's PC output), the
crop box and the raw per-timestep top-K.

290 axis-aligned lines / 3075 characters (25 rotated lines skipped — the
quad warp is not implemented in the Python harness):

* **Port validation**: the Python `current_char_boxes` reproduces the pinned
  output to **0.30px mean |Δcentre|**, axis-IoU 0.961 — the baseline in this
  document is the real thing, not a straw man.
* Proposed vs pinned: 2.56px mean |Δcentre| (they are different algorithms;
  the interesting question is which is right).
* Independent arbitration: 3350 ink runs whose centre is contained by exactly
  one box of each algorithm.  Proposed is closer to the ink run **1184
  (35%)**, shipped **848 (25%)**, tie 1318 (39%).  Restricting to the 1481
  runs where the two algorithms disagree by ≥2px: proposed **779 (53%)**,
  shipped **633 (43%)**, 69 ties.  (The ink-run centroid is a proxy, not
  ground truth — the proposed algorithm optimises a related quantity — but
  the margin survives that caveat, and a visual check of the worst
  disagreements shows proposed boundaries tracking glyph gaps better than the
  shipped ones.)
* Per fixture, disagreements (proposed/current): tategaki fonts 43/6,
  newspaper+ruby 148/62, phone photo 123/157, ruby test 66/49, phone UI
  lines 69/47, 97/64, 68/82, 87/59, 78/107.  Clean font pages and
  ruby-heavy pages favour the proposed algorithm most; two dense UI
  screenshots (7 and 9) favour the shipped chain.  Those two are the
  honest weak spot: synthetic-only tuning has not seen their exact ink
  statistics, and their crops are UI chrome as much as text.
* **Translate A/B (`--ab-translate`)**: on 3544 clean components the real
  ink-run proxy mildly prefers *no* translation — split 420 vs translate 244,
  2880 ties (81%).  That proxy measures exactly the centredness (≤0.04em)
  translation deliberately spends, and cannot see the coverage/tap/width
  gains; ground truth (the synthetic set) decides the default.  Recheck if
  device taps ever feel off-centre.
* **Real alignment measurement**: the shipped map `(charCols + 0.5)·stride` is
  unbiased against an ink proxy (mean +0.065 timesteps over 2598 characters)
  but spread out by quantization (50.7% of characters beyond ±0.25
  timesteps, 19.9% beyond ±0.5).  This validated the app's `+0.5` cell
  convention and exposed a half-timestep bias in the first version of the
  synthetic CTC simulation (fixed; the simulator now places a run's pixel
  coverage centre on the glyph's ink centre).

## 4. Font metrics: what is inferable and what must stay heuristic

On device the rendering font is unknown (the app draws with the platform
default).  The algorithm only needs metrics that are stable across JP fonts:

* **Advance class** (1em vs 0.5em) is a hard typographic convention: all
  fullwidth CJK/kana/punctuation are 1em, ASCII and halfwidth katakana 0.5em.
  This is inferable from the character, no font needed.
* **Absolute em and letter-spacing** are inferred per line from the run
  centres by the robust fit — no font needed, and it survives proportional
  Latin because the fit weights centered-class characters and the residual
  `ls` absorbs uniform tracking.
* **Centred-vs-offset ink within the em box** (the optical class: punctuation,
  small kana, marks) is also a font-independent convention, and correct
  placement does not require the offset value: the crop's own ink is
  measured.  The class only decides *whether* to trust the template for the
  initial anchor; both classes are refined from ink.
* **What is not inferable**: the exact glyph ink shape of the app's own font
  (used by the renderer's fit-scaling and by the shipped
  `resolveInkCollisions`).  CAP removes that dependency for placement; the
  renderer keeps its own measured bounds, which is correct because it draws
  its own glyphs.
* **Ink thresholds** (dark-on-light vs light-on-dark) are detected from the
  crop border as the shipped code already does.  The dominant cross band is
  data-driven (mass median ± widening), not a fixed fraction.

## 5. Limitations and what remains unvalidated

* **Simulation, not the net, for the synthetic set.**  The CTC simulator
  reproduces the app's geometry and decode rules and is calibrated against
  the real dump (run lengths, logit margins, alignment), but the real head's
  activation shape is approximated.  The real-data spot check exists exactly
  because of this; it is small (290 lines, 9 fixtures) and has no per-glyph
  ground truth, so it can only arbitrate disagreements via ink runs.
* **Rotated (quad) lines on the real path** are skipped in the Python spot
  check (25 lines); the synthetic `rotated` preset covers small in-plane
  tilts, where boxes are computed in the unrotated frame as on device.
* **Long-line stitch path.**  The app splits lines with `targetW > 2000` into
  chunks and stitches; char boxes then come from chunk-local columns mapped
  through the stitch.  The algorithm is per-line and would need the stitched
  column space to be fed in; this is unvalidated.
* **Tap-target semantics are approximated**: the unit tests and the synthetic
  harness model the first-rect-wins hit test and a σ=0.12em tap jitter, not
  real fingers.
* **Two dense UI fixtures favour the shipped chain** (see above).  Before
  flipping a default, those should be inspected on device.
* Box **widths are ~8% narrower** than the em cell, mostly for halfwidth
  Latin; the cell-IoU is slightly lower than the shipped chain's as a result.
  If a consumer ever needs exact tiling (e.g. a highlight rectangle for the
  whole line), do not derive it from char boxes; that is already the case.
* **The final pass moves edges, not centres.**  On the real fixtures the
  ink-run arbitration (a centre metric) is unchanged by the pass (2 vs 3 of
  3530 components differ by ≥1px between with-pass and without).  Its measured
  value there is coverage/cell fit, not centre placement.
* **Measured extents are contaminated by construction** when the ink pull is:
  a window that captured a neighbour's stroke also measures that stroke as the
  glyph's extent.  The pass therefore trusts an extent only when the centre
  sits on ink (profile above the floor) and clips it to the Voronoi window and
  to `1.3 · advance half`; the earlier, ungated version over-estimated 61% of
  extents and produced 1436 phantom split pairs (vs 1104 real ones).
* **Offset-ink punctuation in dense text** was the worst single centre failure
  (a `、` pulled 0.44em onto the next kanji, coverage 0.06).  The spread-gated
  CTC retry fixes the measured cases (corner-punctuation mean 0.098em →
  0.086em, >0.3em 8.0% → 5.0%) but never fires on the real fixtures, so it is
  a synthetic-tuned safety net, not a real-data win.
* **The renderer's box-fit shrink now floors at 0.85** (`BOX_FIT_SCALE_FLOOR`
  in LineOverlayView): a genuinely jammed source pair (boxes floored at 0.49
  of a 0.77em pitch) rendered ~30% small ("まで renders small").  The floor
  halves that size gap at the cost of a few px of drawn-ink overlap in the
  worst pair; the clean fix for those pairs is full-width placement (the
  sweep), which trades position accuracy for size instead.
* **Translation spends centredness for fit by design**, and the real proxy
  mildly prefers the split (above).  The default rests on synthetic ground
  truth; a contrary device report is the trigger to revisit the cap.
* **Split asymmetry is available but off**: `splitFloorFrac < 1` lets the
  emptier side win the contested span (ink IoU 0.765 → 0.778 at 0.70) at the
  cost of tap quality (tap 99.2% → 98.8%, jitter 98.2% → 97.8%) and cell IoU,
  so the default keeps every box at least as wide as the old cap.

## 6. Adoption plan (Android, no new dependencies)

1. **Land the pure algorithm** — `app/src/main/java/.../CharPlacement.kt` on
   this branch is the port, already pinned by
   `app/src/test/.../CharPlacementTest.kt` against Python-generated fixtures
   (`app/src/test/resources/char_placement/`).  No Android imports beyond
   `IntArray`.
2. **Feed it the evidence it needs.**  At the two `computeCharBoxes` call
   sites in `recognizeStreaming`, `result.rawAlternatives` is alive and
   `result.charCols` is passed today; derive `steps` once per line
   (`rawAlternatives.map { it.map { (c, s) -> Step(c, s) } }`) and pass it
   instead of `charCols` (keep `charCols` as the no-top-K fallback).  For
   re-decode (`reDecodeLineResult`) the cached `rawAlternatives` is already
   there; for the long-line stitch path, boxes are computed per chunk today
   and would need the stitched column mapping — validate separately.
3. **Replace the width/position stages, keep the cross axis.**  CAP returns
   the same `(left, top, right, bottom)` in crop coordinates, so
   `JpDictRect` mapping, quad mapping and overlay rendering are unchanged.
   Delete `snapCells` / `resolveInkCollisions` / `uniformCells` only after
   the tap-through metrics on device match the synthetic directions (the
   `TapDisambiguator`, `GapDetector` and nav-graph paths do not care how the
   boxes were produced, only that they contain the glyph centres).
4. **Keep a kill switch during rollout.**  Landed as the boolean
   `PREF_BOX_PLACEMENT_CAP` (`box_placement_cap`, ON for the device A/B) read
   in `computeCharBoxes`; the debug screen's "CTC-anchored char placement
   (experimental)" switch flips it, and turning it off restores the shipped
   chain bit-for-bit.  The long-line stitch path still needs the stitched
   column mapping — validate separately.
5. **Guard rails.**  Pure functions: input `text` shorter than the run count
   or an empty pixel array must fall back to the `charCols` path (implemented
   and tested); `cropW/H < 8` skips the ink pass.
6. **Tests to keep**: the two JVM tests here, plus a new conformance case
   only if the pipeline text/box surface changes (it does not: same inputs,
   same `LineResult` shape).

### Porting pitfall: Python slicing vs Kotlin indexing (blank-lines bug, fixed)

`_window_is_bimodal` / `_emptiest_point` compute
`b = floor(clamp(hi, 0, len(prof))) + 1` and then read `prof[a:b]` — a slice
that silently caps at the array end.  The Kotlin port walked `a until b`,
which **indexes past the profile exactly when `hi == prof.size`**: the last
glyph's Voronoi window clips to the line end (`hi0 = L`), the common case on
tight det crops.  The `IndexOutOfBoundsException` left `CharPlacement.place`
into the per-result `onEach` callback in `recognizePpocrBatch`, whose
`catch (_: Exception) {})` swallowed it without a log — **the device detected
every line and rendered about half of them blank**, `logcat` showing only
`Batch N 4 jobs → 2 lines` and no error anywhere.  Fixed by clamping `b` to
`prof.size` in both walks (value-identical to the Python slice; also fixes
`(b - a)` vs `seg.size` in the tie terms when `hi ≥ size`) and by logging the
emit callback's exception instead of dropping it.  Regression:
`CharPlacementEdgeCaseTest` (last-glyph window at the line end) throws
`ArrayIndexOutOfBoundsException` before the fix.  Rule for future ports:
Python slices and NumPy reductions are bounds-tolerant — every ported index
walk needs an explicit clamp, and no callback on the recognition path may
catch silently.

## 7. Files

| path | what |
|---|---|
| `app/src/main/java/.../CharPlacement.kt` | Kotlin port of the algorithm (research sketch, not yet wired) |
| `app/src/test/java/.../CharPlacementTest.kt` | parity vs Python + ink-containment, 6 fixture cases + line-end edge regression |
| `app/src/test/resources/char_placement/` | committed fixture (crop PNGs + raw luminance + JSON) |
| `tools/char_placement/jpfmt.py` | font-metric classes, HarfBuzz shaping, FreeType raster |
| `tools/char_placement/synthesize.py` | generator + documented CTC simulation + presets |
| `tools/char_placement/place.py` | shipped-chain port, proposed algorithm, metrics |
| `tools/char_placement/eval.py` | synthetic evaluation harness; `--boxes-from` scores an external port as the `imported` row |
| `docs/char-placement-conformance.md` | conformance spec for the PC handoff: metric formulas, parity vectors, corpus gates |
| `tools/char_placement/real_eval.py` | real-dump evaluation / ink arbitration |
| `tools/char_placement/export_kotlin_fixture.py` | fixture export for the JVM test |
| `tools/char_placement/real_dump/` | scratch Rust harness that dumps real inference evidence |
| `tools/char_placement/corpus/` | committed text sample + provenance |

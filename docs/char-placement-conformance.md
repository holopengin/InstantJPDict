# Character-positioning conformance spec

**Audience:** the PC build agent (InstantJPDictDecky, `accessibility_daemon`
conformance corpus, harness `core/src/conformance.rs`, `cargo test`).

**Purpose:** defines *conforming* for the character-box placement stage — the
metric, three conformance tiers, and paste-ready additions for the PC
`FORMAT.md` / `TOLERANCES.md`. Those two files remain the corpus's source of
truth; this file is the handoff document, deliberately **not** mirrored into
the Android conformance directory (same anti-drift rule as the corpus README:
one home for the prose).

**Background and all measurements cited here:**
`docs/char-placement-findings.md`.

---

## 1. The stage under test

### Interface

```
place(text, char_cols, seq_len_total, crop_w, crop_h, orientation, pixels, steps)
  -> boxes
```

| input | meaning |
|---|---|
| `text` | the recognized line, one char per box (vertical-punctuation normalization happens upstream — placement is pure over what it is given) |
| `char_cols` | one fractional column per character, in **timesteps** (the decode's run centres; the references apply any `+0.5` convention internally — do not pre-adjust) |
| `seq_len_total` | timesteps in the line's decode grid |
| `crop_w`, `crop_h` | crop size in pixels |
| `orientation` | `"h"` (reading axis = x) or `"v"` (reading axis = y) |
| `pixels` | luminance source, `crop_w * crop_h`, row-major. Normative computation of luminance from RGB: `reading_profile` in `place.py` (the committed `.gray` fixtures are channel-uniform, so any channel formula agrees on Tier-1 vectors) |
| `steps` | optional per-timestep top-K from the decode, shape `[seq_len_total][topk]` of `[char, score]` — the same `alternatives` convention the `gap` kind uses. When absent or shorter than `text`, the references derive runs from `char_cols` |

**Output:** one box per character, `[left, top, right, bottom]` as floats in
crop pixel coordinates. By contract the box spans the **whole cross axis**
(`0..crop_h` horizontal, `0..crop_w` vertical — the fixture `expected_boxes`
show `0.0`/`56.0` on exactly those edges). After the final pass, adjacent
boxes share exact boundary floats.

### References (normative)

* **Python, float64 — the reference.** `tools/char_placement/place.py`,
  `proposed_char_boxes(...)` with `ProposedOptions()` shipped defaults. The
  dataclass is normative; the load-bearing knobs: `translate_max_em=0.04`,
  `final_pass=True`, `translate_overlap=True`, **`sweep_place=False`** (the
  end-anchored sweep is a measured, off-by-default look option — see §7),
  `bimodal_retry=True`, `bimodal_valley_em=0.20`, `bimodal_min_frac=0.12`.
* **Kotlin mirror, float32 — Android.** `app/src/main/java/
  com/holopengin/instantjpdict/CharPlacement.kt`, pinned to the fixture
  expectations at **worst edge delta ≤ 1.5 px**
  (`CharPlacementTest.kotlinPortMatchesPythonReference`).

Conformance means: agree with the reference within the tier tolerances below.
Preferred precision is float64; the measured float32 cost is ≤1.5 px worst
edge on the vectors.

### Rules beyond the math (each one was paid for by a real failure)

1. **Profile walks clamp to the profile length.** Every window/boundary loop
   over the reading-axis ink profile uses
   `b = min(floor(clamp(hi, 0, L)) + 1, L)` with `L` the profile length —
   Python's `prof[a:b]` slice caps at the array end implicitly, an indexing
   loop with the raw `b` overruns exactly when `hi == L`, which is the common
   case: the **last glyph's Voronoi window clips to the line end**. On Android
   this threw `IndexOutOfBoundsException`, the emit callback swallowed it
   silently, and half a page of detected lines rendered blank (findings §6,
   "Porting pitfall"). No conforming implementation may ever touch `prof[L]`.
2. **No silent catches on this path.** Placement runs inside the recognition
   emit path; an exception must reach the caller (Android now logs
   `emit failed for crop N` instead of `catch (_) {}`). Losing a line quietly
   is a conformance failure even if the boxes that do come out are perfect.
3. **Guard rails** (findings §6.5): `text` shorter than the run count, or a
   missing/short pixel array, falls back to the `char_cols` path; `crop_w/H < 8`
   skips the ink pass; empty/degenerate inputs (`n == 0`,
   `seq_len_total <= 0`) return an empty list — never throw.
4. **Deterministic.** No RNG inside placement: same inputs, same boxes.
   (The only RNG in this spec is in the *metric's* jitter sampling, §2.)

---

## 2. The metric (normative formulas)

Reference implementation: `score_boxes()` in `tools/char_placement/place.py`.
Definitions below are exact, not paraphrased.

**Eligibility.** Character `i` is scored iff `decoded_to_true[i]` is not
`null`, `i < len(boxes)`, and the true ink box is non-degenerate
(`right > left`, `bottom > top`). Everything (`n`, all rates) is over the
scored characters. `axis = 1` for `"v"`, `0` for `"h"`; gold centre = midpoint
of the **true ink box**.

| metric | definition |
|---|---|
| centre err (px) | `\|box_center[axis] − ink_center[axis]\|` |
| centre err (em) | centre err px / `em_px` (divisor floored at `1e-6`) |
| miss `>0.5em` | counts 1 when centre err px > `0.5 · em_px` |
| ink IoU | 2-D IoU of the box vs the true ink box **cross-extended to the crop extent** (`cross_extent = crop_w` if `"v"` else `crop_h`), so a full-cross box is not punished for the glyph's height |
| cell IoU | **reading-axis 1-D only**: `max(0, overlap) / (max(box_hi, cell_hi) − min(box_lo, cell_lo))` against the true advance cell (a 2-D compare would just re-measure the crop) |
| width err (em) | `(box axis-extent − cell axis-extent) / em_px` |
| ink cover | `overlap(box interval, ink interval) / ink interval length` on the reading axis — 1.0 = the box contains all of the glyph's own ink |
| cross-capture | `max` over neighbours `i±1` of `overlap(box, neighbour ink) / neighbour ink length`, clamped ≥ 0 — how much of a *neighbour's* ink the box swallows |
| tap centre | hit when the box **contains** the gold centre, inclusive on all four edges |
| tap jitter | 4 samples per character: `p = gold_centre + N(0, 0.12·em_px)` independently per axis; hit when the **first** box in box order containing `p` is box `i` (first-rect-wins tap disambiguation). `rng = numpy.random.default_rng(crc32(case_id) % 100000)`, one stream per case, drawn only for eligible characters in decoded order |
| by class | em error grouped by `optical_class(true_text[d2t])` (`center`/`punct`/`small`) — report-only diagnostic |

**Aggregates.** Means over the per-character lists; p90 via
`numpy.percentile` default (linear) method; rates over `n` (jitter over its
sample count). The jitter **stream** is NumPy-PCG64-specific: a port whose RNG
differs moves the jitter rate by ~±0.4 pp — that is a known stream caveat, not
drift (the gate absorbs it). Findings treat **tap-centre and cover as the
load-bearing rate metrics**.

---

## 3. Tier 1 — parity vectors (hard gate)

Six committed fixtures, byte-shared with Android —
`app/src/test/resources/char_placement/`:

| id | orientation | crop | pins |
|---|---|---|---|
| `synth-core-00000` | h | 927×56 | long plain sans line, no tracking |
| `synth-core-00001` | v | 58×575 | tategaki column |
| `synth-degraded-00030` | h | 317×53 | degraded rendering preset |
| `synth-rotated-00060` | h | 784×78 | 2° rotated glyphs inside the axis-aligned crop |
| `synth-ruby-00150` | v | 97×361 | ruby stack, wide cross axis |
| `synth-spacing-00120` | h | 455×57 | +0.3em tracking |

Per case: run the placement stage on the recorded inputs (`.json` fields +
`.gray` pixels) and compare with `expected_boxes` — **box count exact**, every
edge within **`box_px = 1.5`** (measured mirrors: Python-vs-Python ≈ 0,
Kotlin float32 ≤ 1.5; the corpus default of 2 also holds, 1.5 is what the
Android test enforces). `.gray` is authoritative (`crop_w * crop_h` bytes,
row-major); `.png` exists for eyeballing only.

Case envelope (placeholders elided for reading — the case file carries the
fixture's arrays **verbatim**):

```json
{
  "id": "char-placement-01-synth-core-h",
  "kind": "char_placement",
  "description": "Long horizontal sans line, no tracking/rotation: pins template fit + ink refinement + shared final boundaries on a clean h line.",
  "mobile_mirror": "CharPlacementTest.kotlinPortMatchesPythonReference",
  "tolerances": { "box_px": 1.5 },
  "case": {
    "gray": "images/char_placement/synth-core-00000.gray",
    "crop_w": 927, "crop_h": 56, "orientation": "h", "seq_len_total": 50,
    "text": "少しも疑わず、静かに期待してくれている人があるのだ。",
    "char_cols": [1.0, 3.0, 4.0, 6.0, 9.0, 10.0, "…"],
    "steps": [[["　", 3.1389171367993645]], "…"],
    "gold": {
      "em_px": 36.0,
      "ink_boxes": [[11.0, 11.0, 45.0, 45.0], [54.0, 13.0, 79.0, 44.0], "…"],
      "decoded_to_true": [0, 1, 2, 3, 4, 5, 6, 7, "…"]
    },
    "expect_boxes": [[10.402113861386134, 0.0, 45.597886138613866, 56.0],
                     [45.597886138613866, 0.0, 80.7936584158416, 56.0], "…"]
  }
}
```

The `gold` block is optional for running the kind (inputs + `expect_boxes`
are enough) but recommended: with it the harness can report Tier-2-style
metrics per case.

**`mobile_mirror` candidates** (all run JVM-side today):

* `CharPlacementTest.kotlinPortMatchesPythonReference` — the parity pin.
* `CharPlacementTest.boxesContainTheirCharactersInkCentre` — every box
  contains its gold ink centre (±0.5 px slack).
* `CharPlacementEdgeCaseTest.last glyph window reaching the line end does not
  throw` — the clamp rule of §1.3.1; it fails with
  `ArrayIndexOutOfBoundsException` without it.

---

## 4. Tier 2 — corpus quality gates (hard gate)

Tier 1 proves *parity* on six lines; Tier 2 proves *quality* over the whole
synthetic corpus. Regenerate deterministically and score:

```bash
# environment: uv venv --python 3.12 + requirements.txt (see
# tools/char_placement/README.md); seed defaults to 7, per-case seed
# = 7 * 100003 + case_index
$V synthesize.py --out /tmp/charplace_synth --preset all --n 30
$V eval.py --data /tmp/charplace_synth --only clean
# -> cases=190 (clean=190) chars=3556
```

Measured rows (2026-09-23, this environment):

| metric | shipped chain (`current`) | reference CAP (`proposed`) | **gate** |
|---|---|---|---|
| centre err mean px | 4.07 | **1.50** | ≤ 1.9 |
| centre err p90 px | 9.60 | **3.00** | ≤ 4.0 |
| centre err mean em | 0.131 | **0.048** | ≤ 0.060 |
| miss >0.5em | 0.020 | **0.001** | ≤ 0.005 |
| tap centre | 0.975 | **0.994** | ≥ 0.985 |
| tap jitter | 0.944 | **0.987** | ≥ 0.975 |
| ink IoU | 0.661 | **0.760** | ≥ 0.72 |
| ink cover | 0.919 | **0.967** | ≥ 0.95 |
| cross-capture >0.25 | 0.115 | **0.014** | ≤ 0.05 |
| width err mean em | +0.030 | −0.037 | \|x\| ≤ 0.06 (sanity only) |
| cell IoU | 0.605 | 0.570 | **report-only** (see §7) |

Design of the gates:

* **Every hard gate sits between the shipped chain and the reference** — a
  faithful port passes with margin (error-style metrics carry ≥25% headroom
  over the reference; rate metrics 0.9–1.9 pp), while a port that accidentally
  ships the old snap/uniform chain, or a broken CAP, fails all nine. The gates
  are a *quality floor*, not parity — parity is Tier 1's job.
* **width error** barely separates the two algorithms (both are narrow) — it
  is a sanity net against mangled widths, nothing more.
* **cell IoU** and the median / by-class / by-preset rows are report-only
  diagnostics (§7).

**Scoring your port.** `eval.py --boxes-from` scores an external
implementation in the same table as an `imported` row:

1. Run the port over the corpus cases (inputs: the RGB image + `decoded_text`,
   `char_cols`, `seq_len_total`, `crop_w/h`, `orientation`, `steps` from
   `cases.jsonl`).
2. Write `pc_boxes.jsonl`, one `{"id": ..., "boxes": [[l, t, r, b], ...]}`
   per case — **every** case, box count = `len(char_cols)`.
3. `eval.py --data /tmp/charplace_synth --only clean --boxes-from pc_boxes.jsonl`

Partial dumps abort (`missing case …`), as do box-count mismatches — verified:
a reference dump through this path reproduces the `proposed` row exactly
(1.50 / 3.00 / 0.048 / 0.001 / 0.994 / 0.987 / 0.760 / 0.967 / 0.014 / 0.570
/ −0.037).

**If the reference itself misses its gates** on a freshly regenerated corpus,
the environment drifted (FreeType/HarfBuzz/font versions) — record it and pin
the environment; never widen the gates to pass.

---

## 5. Tier 3 — real-corpus spot check (report-only)

Real inference has no ground truth, so this tier records direction, it does
not gate. Run from `tools/char_placement/`:

```bash
# dump: real inference over the PC-hosted recognition fixtures (their inputs
# are the conformance cases themselves)
tools/char_placement/real_dump: ./target/release/rec_dump \
    <instantjpdictdecky>/accessibility_daemon/tests/conformance/cases \
    /tmp/real_lines.jsonl
$V real_eval.py /tmp/real_lines.jsonl
```

Two things to record:

* **Corpus self-validation (invariant).** The Python `current_char_boxes`
  reproduces the pinned shipped output (`recognition-*` `char_boxes`) to
  ≈**0.30 px** mean |Δcentre|, axis-IoU ≈0.96. If it does not, the dump or the
  corpus drifted — fix that before reading anything else.
* **Arbitration direction.** Ink runs contained by exactly one algorithm's box:
  expected to favour CAP over the shipped chain (measured 2026-09-23 on 290
  axis-aligned lines / 3075 chars: **35% vs 25%** of 3350 unambiguous runs;
  **53% vs 43%** on the 1481 runs where the two disagree ≥2 px). The ink-run
  centre is a *proxy* the CAP optimises toward, not ground truth — a fixture
  (dense phone-UI screenshots) where the shipped chain wins more often is a
  finding to record, never a reason to edit expectations.

---

## 6. Paste-ready corpus additions

### New `FORMAT.md` section

```markdown
### `char_placement` — recorded line inputs, per-character boxes out

"case": {
  "gray": "images/char_placement/<fixture>.gray",
  "crop_w": 927, "crop_h": 56, "orientation": "h", "seq_len_total": 50,
  "text": "…", "char_cols": [1.0, 3.0, "…"],
  "steps": [[["　", 3.14]], "…"],
  "gold": { "em_px": 36.0, "ink_boxes": [[l, t, r, b], "…"],
            "decoded_to_true": [0, 1, "…"] },
  "expect_boxes": [[l, t, r, b], "…"]
}

Inputs are recorded decoder evidence (plain floats) plus raw luminance bytes —
no inference, no rasterizer, no fonts. The runner calls the PC placement port
with exactly these values (never platform defaults), then compares: box count
exact, each edge within `box_px` (this kind overrides the default to 1.5;
the Kotlin float32 mirror measures ≤1.5, float64 ≈0). Boxes span the full
cross axis by contract. `gold` is optional for the pin and enables per-case
metric reporting.

The expectations come from the PYTHON reference (tools/char_placement),
never from the port — regenerating a case runs the reference over the
fixture inputs (snippet in docs/char-placement-conformance.md §3/§4); a
DUMP mode on the PC side would be circular for this kind and must not
bless port output. Spec, metric formulas and corpus gates:
docs/char-placement-conformance.md (handoff doc in the Android repo).
```

### New `TOLERANCES.md` rows

```markdown
| `char_placement` box edges | 1.5 px (kind override) | each of l/t/r/b within ±1.5 px of the Python reference; box count exact |

Known-acceptable platform substitution: none for `char_placement` — inputs
are recorded plain data and IEEE float ops; float32-vs-float64 placement
noise is inside the 1.5 px override (measured on the Kotlin mirror). The
tap-jitter *stream* is NumPy-PCG64-specific: ~±0.4 pp rate movement is the
RNG stream, not drift (gate 0.975 absorbs it); centre/cover are the
load-bearing rates.

Real drift for this kind: profile walks not clamped at the profile length
(§1.3.1 of the spec — the blank-lines bug), a placement exception swallowed
instead of propagated, guard-rail inputs (short text / empty pixels / crop
< 8) throwing instead of falling back, box counts changing, any Tier-2 gate
failing.
```

### Runner contract (`core/src/conformance.rs`)

* Dispatch `kind == "char_placement"`; load the `.gray` bytes; assert length
  `crop_w * crop_h`.
* Call the port with the case's recorded values — **case values, never
  platform defaults** (same rule as `detection`'s `det_thresh`).
* Compare count + edges within `box_px`; mismatch messages must carry the
  case id, char index, and both rects.
* Regeneration convention: follow the corpus's dump pattern, but per §6 the
  dump prints the **Python reference's** `expect_boxes`, not the port's.
* The parity-bug rule applies unchanged: a parity bug fix adds a case; paste
  actuals only after checking they are the correct semantics — here
  "correct" means the reference output.

---

## 7. What conforming does NOT mean

* **Do not chase cell IoU.** CAP deliberately hugs ink and spends cell
  alignment (0.570 vs the shipped chain's 0.605). The off-default
  end-anchored sweep (`proposed_sweep`) raises cell IoU to 0.607 while
  degrading centre error to 0.077em — it is a measured look option
  (`sweep_place=False` by decision), not the conformance target.
* **Do not re-tune the shipped chain to the gates.** `current` /
  `current_nosnap` / `legacy` rows exist as the fail anchor.
* **Never edit expectations to pass** (the corpus's standing rule): a red
  case or gate is a finding first — record it, then decide.

---

## 8. Files

| path | what |
|---|---|
| `tools/char_placement/place.py` | normative reference: `proposed_char_boxes`, `ProposedOptions`, `score_boxes` (the metric) |
| `tools/char_placement/eval.py` | gate harness; `--boxes-from` scores an external port as `imported` |
| `tools/char_placement/synthesize.py` | deterministic corpus generator (seed 7, per-case `7*100003+ci`) |
| `tools/char_placement/real_dump/` | Rust harness: real inference over the PC conformance cases → `real_lines.jsonl` |
| `tools/char_placement/README.md` | environment + command reference |
| `app/src/main/java/.../CharPlacement.kt` | Kotlin mirror (float32), parity-pinned ≤1.5 px |
| `app/src/test/java/.../CharPlacementTest.kt` | Tier-1 runner: parity + ink-containment |
| `app/src/test/java/.../CharPlacementEdgeTest.kt` (in `CharPlacementTest.kt`) | clamp-rule regression |
| `app/src/test/resources/char_placement/` | the six vectors: `.json` inputs + gold + `expected_boxes`, `.gray` pixels, `.png` preview |
| `docs/char-placement-findings.md` | why: measurements, ablations, porting pitfalls |

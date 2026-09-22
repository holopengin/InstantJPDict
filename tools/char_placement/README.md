# Char-placement research tooling

Python harness behind `docs/char-placement-findings.md`: a HarfBuzz/FreeType
synthetic Japanese line generator with a documented CTC simulation, a port of
the shipped placement chain, the proposed CTC-anchored algorithm and the
evaluation that compares them.

```
uv venv /tmp/opencode/charplace-venv --python 3.12
uv pip install --python /tmp/opencode/charplace-venv/bin/python -r requirements.txt
V=/tmp/opencode/charplace-venv/bin/python
```

## Files

| file | role |
|---|---|
| `jpfmt.py` | font-metric classes (advance/optical), HarfBuzz shaping, FreeType raster, per-char advance + ink boxes |
| `corpus.py` | rebuilds `corpus/ja_sample.txt` (Jitendex + Aozora Bunko, provenance in `corpus/PROVENANCE.md`) |
| `synthesize.py` | case generator: layout, raster, degradation, CTC simulation, presets |
| `place.py` | `current_char_boxes` (shipped-chain port), `proposed_char_boxes` (CAP + final boundary pass), metrics |
| `eval.py` | synthetic evaluation harness, JSON summaries |
| `real_eval.py` | real dump evaluation + ink-run arbitration |
| `export_kotlin_fixture.py` | writes the fixtures pinned by `CharPlacementTest` |
| `real_dump/` | scratch Rust harness that produces the real-inference dump |
| `corpus/` | committed text sample + provenance |

## Dataset generation

```
$V synthesize.py --out /tmp/opencode/charplace_synth --preset all --n 30 --seed 11
```

Presets: `core` (h/v, sizes, fonts, tracking), `degraded` (blur, noise, JPEG,
dark, uneven light, downscale), `rotated` (±1-6°), `errors` (CTC
substitution/insertion/deletion), `spacing`, `ruby` (stacked furigana),
`long` (18-34 chars at small sizes).  `--n` lines per preset, deterministic
per `--seed`.

Each case is a crop (also the whole image) plus ground truth:

* `ink_boxes` — the exact per-character ink bbox from the label raster
  (rotation/crop/scale applied; degradations do not move the geometric bbox),
* `cell_boxes` — the advance/em cell from HarfBuzz,
* simulated CTC: `steps` (top-K per timestep, decoded characters, blank as
  U+3000), `char_cols` (rebuilt with the app's own peak-offset rules),
  `seq_len_total`, `decoded_text`, `decoded_to_true`, `clean`.

### The CTC simulation (documented model)

Geometry follows the app exactly: `targetW = round(L·48/cross)`, clamp 4..2000,
lengthwise squish (default 0.5), the 32-timestep floor, `seqLen = ceil/8`,
`stride = L/seqLen`.  Each glyph gets an activation support whose pixel
coverage centre is its ink centre; support length ≈ ink span / stride scaled
by `run_scale` (default 0.9) plus jitter, clipped to `[1, 8]`.  Supports are
made non-overlapping (cut at midpoints) and identical adjacent characters keep
one blank timestep between them, mirroring what CTC heads are trained to emit.
Per-timestep logits are `peak · (0.4 + 0.6 · parabolic falloff)` inside a
support and a blank baseline elsewhere; `peak ~ N(9, 2.2)` clipped above the
blank, so clean presets decode exactly (the run's top-K is sorted descending).
Substitutions use a small confusion table; insertions get a weak support in a
gap; deletions drop a support.  Every knob lives in `CtcParams`.

The simulation was calibrated against the real dump: run-length distribution
(93% of real runs are 1 timestep), per-timestep logit magnitudes, top1-top2
margins, and the alignment convention (a real-data check put the app's
`(column + 0.5)·stride` map within 0.07 timesteps of an ink proxy, which is
why the simulator uses pixel-coverage centres).

Failure cases are first-class: the `errors` presets inject the CTC mistakes,
`clean` marks lines whose decode still equals the true text, and the
evaluation scores both subsets (boxes are mapped to true glyphs positionally
for substitutions, skipped for insertions).

## Evaluation

```
$V eval.py --data /tmp/opencode/charplace_synth --only clean --json /tmp/opencode/eval_clean.json
$V eval.py --data /tmp/opencode/charplace_synth --only all
```

Algorithms compared per case: `current` (ship: snap + uniform), `current_nosnap`,
`legacy`, `proposed` (final pass + translate-before-split), `proposed_nopass`
(midpoint cap, the original ablation), `proposed_notranslate` (final pass
without translation),
the ablation).  Metrics per character: reading-axis centre error (px and em),
IoU vs the cross-extended ink box, axis-IoU vs the advance cell, width error,
own-ink coverage (share of the glyph's ink interval the box covers), neighbour
capture (share of a neighbour's ink the box swallows), tap-at-centre hit, and
4-sample tap-jitter hit with the first-rect-wins order the overlay uses, plus
per-optical-class and per-orientation breakdowns.

Real inference:

```
$V real_eval.py /tmp/opencode/real_lines.jsonl --json /tmp/opencode/real_eval.json
$V real_eval.py /tmp/opencode/real_lines.jsonl --ab-pass    # pass vs midpoint cap
$V real_eval.py /tmp/opencode/real_lines.jsonl --ab-punct   # punct fallback vs not
$V real_eval.py /tmp/opencode/real_lines.jsonl --ab-translate  # translate vs split
```

See `real_dump/README.md` for producing the dump.  The harness reconstructs
`charCols` from the raw top-K with the app's greedy rules, runs both
algorithms on the same crop pixels, compares against the pinned output and
arbitrates disagreements with unambiguous ink runs.

## Kotlin fixture

```
$V export_kotlin_fixture.py --data /tmp/opencode/charplace_synth \
    --out ../../app/src/test/resources/char_placement
```

Writes crop PNGs (for humans), raw 8-bit luminance (Android unit tests have
no `java.awt`) and the expected boxes from the Python reference; the JVM test
`CharPlacementTest` pins the Kotlin port to them.

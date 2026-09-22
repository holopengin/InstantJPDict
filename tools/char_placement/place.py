"""Per-character box placement algorithms + evaluation metrics.

Two implementations share one interface:

* `current_char_boxes` — a line-by-line port of the shipped Android
  `OcrEngine.computeCharBoxes` chain (`legacyCells` → `snapCells` →
  `resolveInkCollisions` → `uniformCells`, plus the vertical punctuation
  rules).  Faithful except for `resolveInkCollisions`' font metric, which on
  device is the platform DEFAULT typeface and here is the bundled Noto Sans JP
  (documented approximation; the on-device font is unknown anyway).

* `proposed_char_boxes` — the research algorithm: CTC activation runs (from
  per-timestep top-K) → robust advance-class template fit (Huber IRLS) →
  per-char robust ink centre (mid-quartile of the reading-axis ink profile in
  a template-bounded window) → confidence-weighted fusion → advance-class box
  widths capped at neighbour midpoints.

Both are deterministic pure functions over the same inputs.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

from jpfmt import (
    FONTS,
    Shaper,
    advance_units,
    is_halfwidth,
    optical_class,
)

BLANK = "\u3000"

# ─────────────────────────────────────────────────────────────────────────────
# Small geometry helpers
# ─────────────────────────────────────────────────────────────────────────────


def peak_offset(v0: float, v1: float, v2: float) -> float:
    """Exact mirror of OcrEngine.peakOffset (sub-column peak interpolation)."""
    denom = v0 - 2.0 * v1 + v2
    if denom >= -1e-6:
        return 0.0
    if min(v1 - v0, v1 - v2) <= 1.0:
        return 0.0
    return float(np.clip(0.5 * (v0 - v2) / denom, -0.5, 0.5))


def _median(xs: list[float]) -> float:
    return float(np.median(xs)) if xs else 0.0


def _clamp(v: float, lo: float, hi: float) -> float:
    return lo if v < lo else hi if v > hi else v


def _weighted_median(xs: list[float], ws: list[float]) -> float:
    if not xs:
        return 0.0
    order = np.argsort(xs)
    xs_s = np.asarray(xs)[order]
    ws_s = np.asarray(ws)[order]
    cw = np.cumsum(ws_s)
    if cw[-1] <= 0:
        return float(np.median(xs))
    return float(xs_s[np.searchsorted(cw, cw[-1] / 2.0)])


# ─────────────────────────────────────────────────────────────────────────────
# Luminance / profile (shared by both algorithms; mirrors the Android code)
# ─────────────────────────────────────────────────────────────────────────────


def luminance_from_argb(pixels: np.ndarray) -> np.ndarray:
    """pixels: int32 ARGB (Android IntArray order) or uint8 RGB -> float lum."""
    if pixels.dtype == np.uint8 and pixels.ndim == 3:
        return pixels.astype(np.float32).mean(axis=2)
    p = pixels.astype(np.int64)
    r = (p >> 16) & 0xFF
    g = (p >> 8) & 0xFF
    b = p & 0xFF
    return ((r + g + b) / 3.0).astype(np.float32)


def _ink_mask(lum: np.ndarray) -> tuple[np.ndarray, bool]:
    border = []
    h, w = lum.shape
    border.extend(lum[0, ::7])
    border.extend(lum[h - 1, ::7])
    border.extend(lum[::7, 0])
    border.extend(lum[::7, w - 1])
    bg_light = float(np.median(border)) > 128.0
    mask = lum < 110.0 if bg_light else lum > 145.0
    return mask, bg_light


def dominant_cross_band(
    lum: np.ndarray, orientation: str, min_frac: float = 0.35
) -> tuple[float, float]:
    """Fractions [lo, hi] of the cross axis that contain the body text.

    Ruby/furigana and a neighbouring line's ascenders can sit inside a crop;
    they inflate the reading-axis profile and pull ink measurements.  The
    body is the dominant ink run on the cross axis (the one containing the
    ink-mass median), widened until it covers `min_frac` of the cross so a
    sparse row (e.g. ー-only) is not mistaken for a thin strip.
    """
    mask, _ = _ink_mask(lum)
    if orientation == "h":
        cross = mask.sum(axis=1).astype(np.float64)  # per row
    else:
        cross = mask.sum(axis=0).astype(np.float64)  # per column
    m = cross.max()
    if m <= 0:
        return (0.0, 1.0)
    thr = max(1.0, 0.15 * m)
    runs: list[tuple[int, int]] = []
    start = None
    for i, v in enumerate(cross):
        if v >= thr and start is None:
            start = i
        elif v < thr and start is not None:
            runs.append((start, i - 1))
            start = None
    if start is not None:
        runs.append((start, len(cross) - 1))
    if not runs:
        return (0.0, 1.0)
    total = cross.sum()
    cum = np.cumsum(cross)
    median_pos = int(np.searchsorted(cum, total / 2.0))
    ci = next((k for k, r in enumerate(runs) if r[0] <= median_pos <= r[1]), 0)
    lo_i, hi_i = ci, ci  # inclusive run-index range merged into the band
    n = len(cross)
    need = min_frac * n
    while (runs[hi_i][1] - runs[lo_i][0] + 1) < need:
        left = runs[lo_i - 1] if lo_i > 0 else None
        right = runs[hi_i + 1] if hi_i < len(runs) - 1 else None
        if left is None and right is None:
            break
        gap_l = (runs[lo_i][0] - left[1]) if left else 10**9
        gap_r = (right[0] - runs[hi_i][1]) if right else 10**9
        if gap_l <= gap_r and left is not None:
            lo_i -= 1
        elif right is not None:
            hi_i += 1
        else:
            break
    lo, hi = runs[lo_i][0], runs[hi_i][1]
    return (max(0.0, lo / n), min(1.0, (hi + 1) / n))


def ink_profile(
    lum: np.ndarray,
    orientation: str,
    band: float | tuple[float, float] = 0.2,
    smooth: int = 2,
) -> tuple[np.ndarray, float]:
    """Reading-axis ink projection + polarity detection.

    Returns (profile in the same units as the Android code — ink pixel count
    per column, smoothed with a box blur) and the background luminance.
    """
    h, w = lum.shape
    mask, bg_light = _ink_mask(lum)
    if isinstance(band, tuple):
        lo_f, hi_f = band
    else:
        lo_f, hi_f = band, 1.0 - band
    if orientation == "v":
        x0, x1 = int(w * lo_f), max(int(w * hi_f), int(w * lo_f) + 1)
        prof = mask[:, x0:x1].sum(axis=1).astype(np.float32)
    else:
        y0, y1 = int(h * lo_f), max(int(h * hi_f), int(h * lo_f) + 1)
        prof = mask[y0:y1, :].sum(axis=0).astype(np.float32)
    if smooth > 0:
        k = np.ones(2 * smooth + 1, dtype=np.float32) / (2 * smooth + 1)
        prof = np.convolve(prof, k, mode="same")
    return prof, bg_light


# ─────────────────────────────────────────────────────────────────────────────
# Current algorithm (Android port)
# ─────────────────────────────────────────────────────────────────────────────


def _is_snap_skipped(ch: str) -> bool:
    if ch in "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ":
        return True
    return ch in "、。．，,．「」『』（）〔〕［］｛｝〈〉《》【】〘〙〚〛'\"\"‘’“”()[]{}-+*/<>＜＞＝…‥︙︰：；"


def _legacy_cells(cols, seq_len: int, L: float, cross: float) -> list[tuple[float, float]]:
    avg = L / seq_len
    half = cross / 2.0
    cells = []
    for t in cols:
        c = (t + 0.5) * avg
        cells.append((max(c - half, 0.0), min(c + half, L)))
    return sorted(cells, key=lambda b: b[0])


@dataclass
class _Peak:
    argmax: float
    centroid: float
    mass: float


def _snap_cells(cells, text, pixels, vertical, L):
    """Port of OcrEngine.snapCells (BOX_LAYOUT_MODE == 1)."""
    lum = luminance_from_argb(pixels)
    h, w = lum.shape
    prof, _ = ink_profile(lum, "v" if vertical else "h", band=0.2, smooth=2)
    prof_len = prof.shape[0]

    peaks: list[_Peak] = []
    p = 1
    while p < prof_len - 1:
        if prof[p] > prof[p - 1] and prof[p] >= prof[p + 1]:
            l = p
            while l > 0 and prof[l - 1] >= prof[l] * 0.5:
                l -= 1
            r = p
            while r < prof_len - 1 and prof[r + 1] >= prof[r] * 0.5:
                r += 1
            m2 = 0.0
            mo = 0.0
            am = -1.0
            ap = p
            for q in range(l, r + 1):
                m2 += prof[q]
                mo += prof[q] * q
                if prof[q] > am:
                    am = prof[q]
                    ap = q
            if m2 > 0:
                peaks.append(_Peak(float(ap), mo / m2, m2))
            p = r + 1
        else:
            p += 1
    med_mass = _median([pk.mass for pk in peaks])

    out = []
    for i, (a, b) in enumerate(cells):
        if i >= len(text) or _is_snap_skipped(text[i]):
            out.append((a, b))
            continue
        c = (a + b) / 2.0
        pitch = max(b - a, 4.0)
        scale = prof_len / max(L, 1.0)
        cp = _clamp(c * scale, 0.0, prof_len - 1.0)
        best = None
        best_d = 0.5 * pitch * scale + 1.0
        for pk in peaks:
            if pk.mass < max(6.0, 0.35 * med_mass):
                continue
            d = abs(pk.centroid - cp)
            if d < best_d:
                best_d = d
                best = pk
        if best is None:
            out.append((a, b))
            continue
        if abs(best.centroid - best.argmax) > 0.3 * pitch * scale:
            out.append((a, b))
            continue
        if abs(best.centroid / scale - c) < 3.0:
            out.append((a, b))
            continue
        nc = best.centroid / scale
        lo = (out[-1][0] + out[-1][1]) / 2.0 if i > 0 and len(out) > i - 1 else 0.0
        # Voronoi between the *legacy* centres, as in the Kotlin code.
        centers = [(x + y) / 2.0 for (x, y) in cells]
        lo_b = (centers[i - 1] + c) / 2.0 if i > 0 else 0.0
        hi_b = (centers[i + 1] + c) / 2.0 if i < len(centers) - 1 else L
        nc = _clamp(nc, lo_b, hi_b)
        nc = _clamp(nc, c - 0.4 * pitch, c + 0.4 * pitch)
        length = b - a
        s0 = _clamp(nc - length / 2.0, 0.0, max(L - length, 0.0))
        out.append((s0, min(s0 + length, L)))
    return out


class _InkMetrics:
    """Android Paint.getTextBounds(\"x\").width()/2 approximation.

    Android measures the *platform DEFAULT* typeface; the device font is
    unknown, so the Python baseline uses the bundled Noto Sans JP at the same
    size.  This substitution is documented in the findings doc.  Instances
    are cached per (quantised) size — the font parse dominates otherwise.
    """

    def __init__(self, size_px: float):
        self.shaper = Shaper(FONTS["sans"], max(size_px, 1.0))
        self._half_cache: dict[str, float] = {}

    def ink_half_width(self, ch: str) -> float:
        cached = self._half_cache.get(ch)
        if cached is not None:
            return cached
        s = self.shaper
        placements = s.shape(ch, "h")
        half = 0.0
        if placements:
            arr, bl, bt = s._glyph_bitmap(placements[0].gid)
            if arr.size:
                half = arr.shape[1] / 2.0
        self._half_cache[ch] = half
        return half


_INK_METRIC_CACHE: dict[int, _InkMetrics] = {}


def ink_metrics(size_px: float) -> _InkMetrics:
    key = int(round(size_px * 2.0))
    m = _INK_METRIC_CACHE.get(key)
    if m is None:
        m = _InkMetrics(key / 2.0)
        _INK_METRIC_CACHE[key] = m
    return m


def _resolve_ink_collisions(cells, text, metrics: _InkMetrics):
    n = len(cells)
    if n < 2:
        return list(cells)
    centers = [(a + b) / 2.0 for (a, b) in cells]
    ink_half = [
        metrics.ink_half_width(text[i]) if i < len(text) else 0.0 for i in range(n)
    ]
    for ci in range(n - 1):
        ink_r = centers[ci] + ink_half[ci]
        ink_l = centers[ci + 1] - ink_half[ci + 1]
        if ink_r <= ink_l:
            continue
        shift = (ink_r - ink_l) / 2.0
        centers[ci] -= shift
        centers[ci + 1] += shift
    out = []
    for i in range(n):
        half = (cells[i][1] - cells[i][0]) / 2.0
        out.append((centers[i] - half, centers[i] + half))
    return out


def estimate_em(text: str, centers: list[float]) -> float:
    """Port of OcrEngine.estimateEm."""
    if len(text) != len(centers) or len(centers) < 2:
        return 0.0
    norm = []
    for i in range(len(centers) - 1):
        gap = centers[i + 1] - centers[i]
        if gap <= 0:
            continue
        units = (advance_units(text[i]) + advance_units(text[i + 1])) / 2.0
        norm.append(gap / units)
    return _median(norm)


def _uniform_cells(cells, text, L):
    if len(cells) < 2 or len(text) != len(cells):
        return list(cells)
    centers = [(a + b) / 2.0 for (a, b) in cells]
    em = estimate_em(text, centers)
    if em <= 0:
        return list(cells)
    out = []
    for i, c in enumerate(centers):
        w = (0.5 if is_halfwidth(text[i]) else 1.0) * em
        half = w / 2.0
        out.append((max(c - half, 0.0), min(c + half, L)))
    return out


def current_char_boxes(
    text: str,
    char_cols,
    seq_len_total: int,
    crop_w: int,
    crop_h: int,
    orientation: str,
    pixels=None,
    box_layout_mode: int = 1,
    box_uniform_size: bool = True,
) -> list[tuple[float, float, float, float]]:
    """Port of OcrEngine.computeCharBoxes; returns (x0, y0, x1, y1) in crop px."""
    n = len(char_cols)
    if n == 0 or seq_len_total <= 0:
        return []
    vertical = orientation == "v"
    if not vertical:
        L = float(crop_w)
        cross = max(float(crop_h), 3.0)
        base = _legacy_cells(char_cols, seq_len_total, L, cross)
        cells = (
            _snap_cells(base, text, pixels, False, L)
            if box_layout_mode == 1 and pixels is not None
            else base
        )
        resolved = _resolve_ink_collisions(cells, text, ink_metrics(crop_h * 0.90))
        sized = _uniform_cells(resolved, text, L) if box_uniform_size else resolved
        return [(x0, 0.0, x1, float(crop_h)) for (x0, x1) in sized]
    else:
        L = float(crop_h)
        cross = max(float(crop_w), 3.0)
        base = _legacy_cells(char_cols, seq_len_total, L, cross)
        cells = (
            _snap_cells(base, text, pixels, True, L)
            if box_layout_mode == 1 and pixels is not None
            else base
        )
        is_cp = [ch in "。.．、,，)）〕》」』】〙〗〟’”］" for ch in text]
        is_op = [ch in "(（〔《「『【〘〖〝‘“［" for ch in text]
        resolved = list(cells)
        for ci in range(n - 1):
            if resolved[ci][1] <= resolved[ci + 1][0]:
                continue
            if is_cp[ci]:
                resolved[ci] = (resolved[ci][0], resolved[ci + 1][0])
            elif is_op[ci + 1]:
                resolved[ci + 1] = (resolved[ci][1], resolved[ci + 1][1])
            elif is_cp[ci + 1]:
                resolved[ci + 1] = (resolved[ci][1], resolved[ci + 1][1])
            elif is_op[ci]:
                resolved[ci] = (resolved[ci][0], resolved[ci + 1][0])
            else:
                h = (resolved[ci][1] - resolved[ci + 1][0]) / 2.0
                resolved[ci] = (resolved[ci][0], resolved[ci][1] - h)
                resolved[ci + 1] = (resolved[ci + 1][0] + h, resolved[ci + 1][1])
        sized = _uniform_cells(resolved, text, L) if box_uniform_size else list(resolved)
        np_heights = [
            sized[i][1] - sized[i][0] for i in range(n) if not is_cp[i] and not is_op[i]
        ]
        avg_np_h = float(np.mean(np_heights)) if np_heights else float(crop_w)
        for ci in range(n):
            yt, yb = sized[ci]
            if is_cp[ci]:
                nx = next(
                    (sized[j][0] for j in range(ci + 1, n) if not is_cp[j] and not is_op[j]),
                    float("inf"),
                )
                sized[ci] = (yt, max(yb, min(yt + avg_np_h, nx)))
            elif is_op[ci]:
                pl = next(
                    (sized[j][1] for j in range(ci - 1, -1, -1) if not is_cp[j] and not is_op[j]),
                    float("-inf"),
                )
                sized[ci] = (min(yt, max(pl, yb - avg_np_h)), yb)
        return [(0.0, yt, float(crop_w), yb) for (yt, yb) in sized]


# ─────────────────────────────────────────────────────────────────────────────
# Proposed algorithm
# ─────────────────────────────────────────────────────────────────────────────


@dataclass
class CtcChar:
    """One decoded character's CTC support."""

    char: str
    run_start: int  # first timestep of the argmax run
    run_end: int  # last timestep (inclusive)
    conf: float  # mean top-1 score over the run (raw logit units)
    margin: float  # mean (top1 - top2) over the run


def runs_from_steps(
    text: str, steps: list[list[tuple[str, float]]]
) -> list[CtcChar]:
    """Mirror the app's greedy CTC walk to attach a run to each text char.

    `steps[t]` is that timestep's top-K (char, score), descending.  Blank is
    BLANK; the walk skips blanks, collapses repeats — exactly the decoder's
    rules, so the runs come out in the same order as the decoded characters.

    The emitted `text` can differ from the raw walk (the app rewrites kana
    size, folds vertical punctuation, recovers gaps), so the runs are aligned
    to `text` by character and any text position without a run is
    interpolated between its neighbours.  A count mismatch beyond that falls
    back to one-timestep runs at `charCols` (the caller's safety net).
    """
    runs: list[list[int]] = []
    prev: str | None = None
    for t, alts in enumerate(steps):
        if not alts:
            continue
        top, _ = alts[0]
        if top == BLANK:
            prev = None
            continue
        if prev == top:
            if runs:
                runs[-1][1] = t
            continue
        runs.append([t, t])
        prev = top
    if not runs or len(runs) != len(text):
        return []

    out: list[CtcChar] = []
    for i, (s, e) in enumerate(runs):
        scores = [steps[t][0][1] for t in range(s, e + 1) if steps[t]]
        margins = [
            steps[t][0][1] - (steps[t][1][1] if len(steps[t]) > 1 else 0.0)
            for t in range(s, e + 1)
            if steps[t]
        ]
        out.append(
            CtcChar(
                char=text[i],
                run_start=s,
                run_end=e,
                conf=float(np.mean(scores)) if scores else 0.0,
                margin=float(np.mean(margins)) if margins else 0.0,
            )
        )
    return out


@dataclass
class ProposedOptions:
    # Fusion
    ink_max_pull_em: float = 0.45  # |ink - anchor| clamp, in em
    window_em: float = 0.6  # window half-width around the anchor, in em
    window_stride: float = 0.4  # ... and in timestep strides
    # Anchor selection: the template is used when it agrees with the CTC
    # column within these bounds; the CTC column otherwise.  1.2 strides is
    # just past the +/- 0.5-stride quantization bound, so the CTC anchor only
    # wins when the template is genuinely off.
    anchor_tol_em: float = 0.4
    anchor_tol_stride: float = 1.2
    # Punctuation / small kana: their ink is small, so a mid-quartile window
    # that reaches the neighbour's stroke lands between the two inks (measured:
    # a `、` pulled 0.44em onto the following kanji).  A window whose spread
    # exceeds the gate is retried around the CTC run centre -- it sits on the
    # glyph -- and the retry is taken only when it is a compact blob close to
    # the anchor; otherwise the primary measurement is kept.
    punct_spread_fallback: bool = True
    punct_fallback_window_em: float = 0.5
    punct_fallback_max_em: float = 0.6
    # Template fit
    huber_em: float = 0.35  # residual scale for IRLS weights
    ridge_ls: float = 1e-3
    # Profile
    profile_band: float = 0.2
    profile_smooth: int = 2
    # Reliability gates
    min_mass_frac: float = 0.12  # vs median window ink mass
    max_spread_em: float = 0.80  # mid-quartile spread / em
    conf_floor: float = 1.0  # raw-logit margin below which ink trust halves
    # Second pass
    refine_passes: int = 1
    # Resolve overlapping requirements by translating apart before splitting:
    # a pair whose required half-widths do not fit between their centres is
    # pushed into its neighbours' positive slack (the empty space flanking the
    # pair) first, and only whatever deficit remains is split.  Slack is
    # consumed, never driven negative, so a translation can never make a
    # NEIGHBOURING pair infeasible.  Measured on the clean set: 51.9% of pairs
    # are infeasible on requirements (but only 17 pairs have source inks that
    # truly overlap) and 53% of those have enough flank room to fully resolve.
    translate_overlap: bool = True
    # Cap on one glyph's move, in em.  Measured peak on the clean set: small
    # outward moves *debias* centres the ink refinement pulled inward by the
    # crowding (err 1.61 -> 1.46px) while resolving the fit (cover 0.955 ->
    # 0.970, tap 0.992 -> 0.995); larger caps keep buying cover but overshoot
    # the debiasing and push the centre error back above the no-translate
    # baseline past ~0.10em.
    translate_max_em: float = 0.04
    translate_passes: int = 2
    # Only translate a pair when the split would actually cut one of its
    # glyphs' ink (ink half-extent > what the split's floor allows).  Keeps
    # the centre-error cost to the pairs that need the room.
    translate_gate_cut: bool = False
    # Line-wide placement sweep: anchor ONE end of the line and walk toward the
    # other, resolving every overlap as it appears.  A glyph that would collide
    # with its already-placed successor is pushed FORWARD through the sweep
    # (i.e. toward the start for a reverse sweep) into usable space; when the
    # gap cannot hold its whole box, the glyph is centred in the gap.  Splits
    # stay the boundary pass's last resort.  Whitespace is soft — it shrinks to
    # yield room rather than displacing a real glyph (no ink to lose, and the
    # renderer never draws it); a gap left by a missed character is ordinary
    # slack the sweep consumes.
    # Measured, NOT the default: the sweep's one-sided pushes land the full
    # deficit on the swept glyph (the symmetric translate splits it between
    # two), so tiling wins (cell IoU 0.608, width error -0.013em, tap 0.996)
    # but centre error costs 1.46 -> 2.37px and cover 0.970 -> 0.959.  See
    # the findings doc; eval.py reproduces it as `proposed_sweep`.
    sweep_place: bool = False
    sweep_reverse: bool = True  # True = end-to-start (the line's end anchors)
    sweep_passes: int = 1
    # After the sweep, slide every glyph back toward its measured centre as
    # far as the placed neighbours allow: the sweep's contact-tight pushes
    # accumulate drift toward the unanchored end, and the pull-back keeps the
    # tiling while walking the boxes back onto their ink.
    sweep_pull_back: bool = True
    # Final pass: boundaries, not midpoints.  Each pair of neighbours gets a
    # shared boundary chosen in the empty ink space between the glyphs (or, when
    # the measured inks cannot both fit, split at the emptiest point of the
    # contested span).  Boxes grow from the advance cell to cover their own
    # measured ink when the boundary allows, so the midpoint cap no longer cuts
    # strokes -- see docs/char-placement-findings.md.
    final_pass: bool = True
    extent_pad_px: float = 1.0  # slack added to the measured ink half-extent
    extent_floor: float = 0.5  # profile mass floor for the ink run
    extent_window_em: float = 0.15  # slack past the advance cell when measuring
    # Growth beyond the advance cell: only when the centre sits on its own ink
    # and only within the character's own Voronoi window, capped proportionally
    # so a halfwidth box cannot swell into a fullwidth cell.
    extent_grow_frac: float = 0.3
    # When a pair cannot both have their measured room, the split still leaves
    # each box this share of its old half-width (1.0 = fully monotone; lower
    # lets the emptier side win the contested span).
    split_floor_frac: float = 1.0
    min_half_px: float = 2.0


def _robust_template_fit(
    centers: list[float],
    units: list[float],
    classes: list[str],
    confs: list[float],
    cross: float,
    opts: ProposedOptions,
) -> tuple[float, float, float]:
    """Fit center_i = X0 + em*(P_i + u_i/2) + ls*i for 'center' chars.

    Returns (em, X0, ls) where X0 is the first cell's origin.  The `ls`
    column is collinear with the advance column on uniform-class lines, so the
    solve runs in column-standardised space with a prior ridge on `ls`
    (|ls| ~ 0.1 cross); it only survives when mixed advance classes make it
    identifiable.  Three Huber-reweighted passes keep outliers (a misaligned
    CTC peak, a merged run) from dragging the template.
    """
    n = len(centers)
    idx = [i for i in range(n) if classes[i] == "center"]
    if len(idx) < 2:
        idx = list(range(n))
    if len(idx) < 2:
        return float(cross), (centers[0] if centers else 0.0), 0.0

    # Pitch scale from normalised adjacent gaps: quantization makes single
    # gaps unreliable (a 1-timestep gap can be half an em), but the median
    # over the line is within ~15% — good enough as a clamp reference.
    pitches: list[float] = []
    weights: list[float] = []
    for i in range(n - 1):
        gap = centers[i + 1] - centers[i]
        units_pair = 0.5 * (units[i] + units[i + 1])
        if gap > 0 and units_pair > 0:
            pitches.append(gap / units_pair)
            weights.append(max(0.2, 0.5 * (confs[i] + confs[i + 1]) / 8.0))
    pitch = _weighted_median(pitches, weights) if pitches else 0.0
    if pitch <= 0:
        span = centers[-1] - centers[0] if n > 1 else 0.0
        total_units = sum(units[1:]) + 0.5 * (units[0] + units[-1]) if n > 1 else 1.0
        pitch = span / total_units if span > 0 else float(cross)

    # Well-conditioned 2-parameter fit: centre_i = X0 + em*(P_i + u_i/2).
    # `ls` is deliberately *not* in this system: on uniform-class lines it is
    # collinear with the advance column and any ridge on it biases em.
    rows = np.array(
        [[sum(units[:i]) + 0.5 * units[i], 1.0] for i in idx], dtype=np.float64
    )
    ys = np.array([centers[i] for i in idx], dtype=np.float64)
    scale = np.sqrt((rows**2).mean(axis=0))
    scale[scale <= 0] = 1.0
    a_std = rows / scale

    em, x0 = float(pitch), float(ys[0])
    for it in range(3):
        if it == 0:
            w = np.array([min(1.0, max(0.15, confs[i] / 4.0)) for i in idx])
        else:
            r = ys - (a_std @ np.array([em * scale[0], x0]))
            s = opts.huber_em * pitch
            w = np.clip(s / np.maximum(np.abs(r), 1e-6), 0.05, 1.0)
        ata = (a_std * w[:, None]).T @ a_std
        atb = (a_std * w[:, None]).T @ ys
        try:
            sol = np.linalg.solve(ata, atb)
        except np.linalg.LinAlgError:
            return float(pitch), float(ys[0]), 0.0
        em = float(sol[0] / scale[0])
        x0 = float(sol[1])
        if not np.isfinite(em) or em <= 0:
            return float(pitch), float(ys[0]), 0.0
    # Sanity clamps: the line's own pitch is the scale reference (a crop can
    # be much taller than one em); the cross extent is only a loose backstop.
    em = _clamp(em, 0.6 * pitch, 1.7 * pitch)
    em = _clamp(em, 0.15 * cross, 3.0 * cross)

    # Letter-spacing only survives when mixed advance classes make the index
    # column non-collinear.  Fit it on the residuals with a prior ridge and
    # keep it only if it clearly improves the layout.
    ls = 0.0
    if len(set(units[i] for i in idx)) > 1 and len(idx) >= 4:
        pred = a_std @ np.array([em * scale[0], x0])
        resid = ys - pred
        xs = np.array([float(i) for i in idx])
        xc = xs - xs.mean()
        wsum = max(1e-6, float(w.sum()))
        slope = float((w * resid * xc).sum() / max(1e-9, float((w * xc * xc).sum())))
        # Prior: |ls| <= 0.1*pitch -> ridge shrinks small slopes to zero.
        ridge = (0.35 * pitch / (0.1 * pitch)) ** 2
        slope = slope * float((w * xc * xc).sum()) / (
            float((w * xc * xc).sum()) + ridge
        )
        if abs(slope) > 0.02 * pitch:
            ls = slope
    return em, x0, ls


def _mid_quartile(prof: np.ndarray, lo: float, hi: float) -> tuple[float, float, float]:
    """Mass, mid-quartile centre and IQR spread of a profile slice."""
    a = int(np.floor(_clamp(lo, 0, len(prof))))
    b = int(np.ceil(_clamp(hi, 0, len(prof))))
    if b <= a:
        return 0.0, 0.5 * (lo + hi), 0.0
    seg = prof[a:b].astype(np.float64)
    mass = float(seg.sum())
    if mass <= 0:
        return 0.0, 0.5 * (lo + hi), 0.0
    cum = np.cumsum(seg)
    q1 = float(np.searchsorted(cum, mass * 0.25)) + a
    q3 = float(np.searchsorted(cum, mass * 0.75)) + a
    return mass, 0.5 * (q1 + q3), q3 - q1


def _ink_run(
    prof: np.ndarray, centre: float, lo_lim: float, hi_lim: float, floor: float
) -> tuple[float, float]:
    """Trimmed mass span of `prof` around `centre`, clipped to the limits.

    Used by the final pass to estimate how far a glyph's own ink extends around
    its measured centre.  A quantile span, not a floor walk: the profile is
    smoothed, so a narrow inter-glyph gap can stay above any low floor and a
    contiguous run would walk straight into the neighbour's strokes (measured:
    that over-estimated 61% of extents and produced phantom split pairs).  The
    5% trim drops anti-aliasing tails; the window limits keep a neighbour's
    stroke from inflating the estimate.
    """
    a = int(np.floor(_clamp(lo_lim, 0, len(prof))))
    b = int(np.ceil(_clamp(hi_lim, 0, len(prof))))
    if b - a <= 0:
        return centre, centre
    seg = prof[a:b].astype(np.float64)
    mass = float(seg.sum())
    if mass <= 0:
        return centre, centre
    cum = np.cumsum(seg)
    lo = float(np.searchsorted(cum, mass * 0.05)) + a
    hi = float(np.searchsorted(cum, mass * 0.95)) + a
    if hi < lo:
        lo = hi
    return lo, hi


def _emptiest_point(prof: np.ndarray, lo: float, hi: float, floor: float) -> float:
    """Midpoint of the emptiest run inside [lo, hi], else the mass minimum.

    The boundary between two glyphs belongs where there is no ink: prefer the
    middle of the longest run under the floor, and fall back to the lightest
    point (ties broken toward the interval centre) when the span is inked.
    Only whole pixels inside [lo, hi] are candidates, so the returned point
    never leaves the interval -- callers rely on it for their no-regression
    floor.
    """
    a = int(np.ceil(_clamp(lo, 0, len(prof))))
    b = int(np.floor(_clamp(hi, 0, len(prof)))) + 1
    if b <= a:
        return 0.5 * (lo + hi)
    seg = prof[a:b].astype(np.float64)
    thr = max(floor, 0.10 * float(seg.max()))
    best_len = best_end = cur = 0
    for k, v in enumerate(seg):
        cur = cur + 1 if v <= thr else 0
        if cur > best_len:
            best_len, best_end = cur, k
    if best_len >= 1:
        return float(a + best_end - (best_len - 1) / 2.0)
    cost = seg + 1e-3 * np.abs(np.arange(seg.size) - seg.size / 2.0)
    return float(a + int(np.argmin(cost)))


def _translate_apart(
    centers: list[float],
    need: list[float],
    ink_half: list[float],
    desired: list[float],
    em: float,
    opts,
) -> None:
    """Push overlapping pairs apart into their neighbours' slack, in place.

    For a pair whose required half-widths do not fit between the centres
    (`need[i] + need[i+1] > d`), translating the two glyphs outward by the
    deficit hands both their full room, so the boundary pass can place the
    divider in genuinely empty space instead of splitting the contested span.

    Only *positive* slack of the flanking pairs may be consumed (capped at
    `translate_max_em`), so a move can shrink a neighbour pair's interval but
    never make it infeasible; whatever deficit remains after the move is left
    for the split path.  Slack is recomputed after every move, so chains
    resolve left-to-right honestly.
    """
    n = len(centers)
    if n < 2 or not opts.translate_overlap:
        return
    cap = opts.translate_max_em * em

    def slacks() -> list[float]:
        out = []
        for i in range(n - 1):
            out.append((centers[i + 1] - centers[i]) - need[i] - need[i + 1])
        return out

    def would_split_cut(i: int) -> bool:
        # Would the split path's floor leave ink outside this glyph's box?
        for j in (i, i + 1):
            if j >= n:
                continue
            h = min(desired[j], 0.49 * (centers[j] - centers[j - 1])) if j > 0 else desired[j]
            if j < n - 1:
                h = min(h, 0.49 * (centers[j + 1] - centers[j]))
            if ink_half[j] > h:
                return True
        return False

    for _ in range(max(1, opts.translate_passes)):
        slack = slacks()
        moved = False
        for i in range(n - 1):
            d = centers[i + 1] - centers[i]
            deficit = need[i] + need[i + 1] - d
            if deficit <= 0:
                continue
            if opts.translate_gate_cut and not would_split_cut(i):
                continue
            room_l = min(max(slack[i - 1], 0.0), cap) if i > 0 else 0.0
            room_r = min(max(slack[i + 1], 0.0), cap) if i + 1 < n - 1 else 0.0
            move = min(deficit, room_l + room_r)
            if move <= 0:
                continue
            total = room_l + room_r
            dl = move * (room_l / total)
            centers[i] -= dl
            centers[i + 1] += move - dl
            slack = slacks()
            moved = True
        if not moved:
            break


def _sweep_place(
    centers: list[float],
    need: list[float],
    inkless: list[bool],
    L: float,
    opts,
) -> None:
    """One end-to-start (or start-to-end) placement sweep, in place.

    Walk the line from the anchored end toward the other end, maintaining the
    invariant "every processed glyph clears its placed successor":

    - fits at the measured centre?  keep it;
    - the current glyph is whitespace?  shrink its need to the room (spaces
      yield first — they have no ink and are never drawn);
    - the placed successor is whitespace?  take the room from ITS need and
      retry;
    - otherwise push the glyph forward through the sweep (toward the
      unprocessed end) until its box clears the successor — unless the space
      between the measured predecessor's box and the successor cannot hold
      the whole box, in which case the glyph is CENTRED in that gap.  A
      centred box may overhang both sides; the boundary pass clips it, and
      the predecessor still to be processed re-clamps itself against it.
    """
    n = len(centers)
    if n == 0 or not opts.sweep_place:
        return
    reverse = opts.sweep_reverse
    order = range(n - 1, -1, -1) if reverse else range(n)

    def limit_of(j: int) -> float:
        return (centers[j] - need[j]) if reverse else (centers[j] + need[j])

    def floor_of(i: int) -> float:
        k = i - 1 if reverse else i + 1
        if 0 <= k < n:
            return (centers[k] + need[k]) if reverse else (centers[k] - need[k])
        return 0.0 if reverse else L

    for _ in range(max(1, opts.sweep_passes)):
        for i in order:
            j = i + 1 if reverse else i - 1
            has_limit = 0 <= j < n
            limit = limit_of(j) if has_limit else (float("inf") if reverse else float("-inf"))
            c, nd = centers[i], need[i]

            def fits() -> bool:
                return (c + nd <= limit) if reverse else (c - nd >= limit)

            if fits():
                continue

            if inkless[i]:
                room = (limit - c) if reverse else (c - limit)
                if room >= 1.0:
                    # The space shrinks to exactly the room: no glyph moves.
                    need[i] = room
                    continue

            if has_limit and inkless[j]:
                # The placed neighbour is whitespace: take the room from it.
                room_over = (c + nd) - limit if reverse else limit - (c - nd)
                need[j] = max(1.0, need[j] - room_over)
                limit = limit_of(j)
                if fits():
                    continue

            # Push forward through the sweep; centre in the gap if the whole
            # box cannot fit between the measured predecessor and the limit.
            pushed = (limit - nd) if reverse else (limit + nd)
            floor_edge = floor_of(i)
            whole_fits = (
                (pushed - nd >= floor_edge) if reverse else (pushed + nd <= floor_edge)
            )
            if has_limit and whole_fits:
                centers[i] = pushed
                continue
            lo = max(0.0, floor_edge) if reverse else limit
            hi = limit if reverse else min(L, floor_edge)
            centers[i] = 0.5 * (lo + hi) if hi > lo else pushed


def _sweep_pull_back(
    centers: list[float],
    need: list[float],
    measured: list[float],
    L: float,
    opts,
) -> None:
    """Walk glyphs back toward their measured centres without creating overlap.

    After the sweep every glyph sits in a gap bounded by its neighbours'
    boxes; sliding it back toward `measured[i]` (clamped to that gap) undoes
    the sweep's accumulated push drift while keeping the tiling.  A glyph
    boxed into a gap narrower than its own need (the sweep's centred-in-space
    case) has nowhere to slide; it is left for the boundary pass.
    """
    if not (opts.sweep_place and opts.sweep_pull_back):
        return
    n = len(centers)
    for _ in range(max(2, n)):
        changed = False
        for i in range(n):
            lo = (centers[i - 1] + need[i - 1]) if i > 0 else need[i]
            hi = (centers[i + 1] - need[i + 1]) if i + 1 < n else L - need[i]
            a = lo + need[i]
            b = hi - need[i]
            if a > b:
                continue
            target = min(max(measured[i], a), b)
            if target != centers[i]:
                centers[i] = target
                changed = True
        if not changed:
            break


def proposed_char_boxes(
    text: str,
    char_cols,
    seq_len_total: int,
    crop_w: int,
    crop_h: int,
    orientation: str,
    pixels=None,
    steps: list[list[tuple[str, float]]] | None = None,
    opts: ProposedOptions | None = None,
    debug: dict | None = None,
) -> list[tuple[float, float, float, float]]:
    """The proposed single algorithm.  Returns (x0, y0, x1, y1) in crop px."""
    opts = opts or ProposedOptions()
    n = len(text)
    if n == 0 or seq_len_total <= 0:
        return []
    vertical = orientation == "v"
    L = float(crop_h if vertical else crop_w)
    cross = float(crop_w if vertical else crop_h)
    px_per_t = L / float(seq_len_total)

    # 1. CTC activations -----------------------------------------------------
    if steps is not None and len(steps) > 0:
        chars = runs_from_steps(text, steps)
    else:
        chars = []
    if len(chars) != n:
        # Fallback: single-timestep runs straight from charCols.
        chars = [
            CtcChar(text[i], int(round(char_cols[i])), int(round(char_cols[i])), 1.0, 1.0)
            for i in range(n)
        ]
    ctc_centers = [
        (c.run_start + c.run_end) / 2.0 * px_per_t + 0.5 * px_per_t for c in chars
    ]
    units = [advance_units(ch) for ch in text]
    classes = [optical_class(ch) for ch in text]
    confs = [c.conf for c in chars]

    # 2. Robust layout template ---------------------------------------------
    em, x0, ls = _robust_template_fit(ctc_centers, units, classes, confs, cross, opts)
    prefixes = np.cumsum([0.0] + units[:-1])
    # Cell origins from the fitted (em, X0, ls); the fit's X0 is the first
    # cell's origin because the half-advance was in the design row.
    origins = [x0 + em * float(prefixes[i]) + ls * i for i in range(n)]
    tmpl_centers = [origins[i] + 0.5 * units[i] * em for i in range(n)]

    # Anchor per char: the template when it agrees with the CTC column within
    # what timestep quantization can explain, the CTC column otherwise.  The
    # linear advance model breaks when the timestep grid is coarse relative
    # to the glyphs (tall crops: stride can exceed 0.5 em) or when mixed
    # halfwidth text is cut by an integer grid — there the run centres are
    # the better anchor and ink measurement fixes the remaining +/- stride/2.
    stride = px_per_t
    anchors = []
    for i in range(n):
        tol = max(opts.anchor_tol_em * em, opts.anchor_tol_stride * stride)
        anchors.append(
            tmpl_centers[i]
            if abs(ctc_centers[i] - tmpl_centers[i]) <= tol
            else ctc_centers[i]
        )

    # 3. Ink evidence --------------------------------------------------------
    prof = None
    if pixels is not None:
        lum = luminance_from_argb(pixels)
        if lum.shape[0] == crop_h and lum.shape[1] == crop_w:
            band = dominant_cross_band(lum, "v" if vertical else "h")
            prof, _ = ink_profile(
                lum,
                "v" if vertical else "h",
                band=band,
                smooth=opts.profile_smooth,
            )

    centers = list(anchors)
    if debug is not None:
        debug["ctc_centers"] = list(ctc_centers)
        debug["tmpl_centers"] = list(tmpl_centers)
        debug["anchors"] = list(anchors)
        debug["ink_pulls"] = []
    if prof is not None:
        win_center = max(opts.window_em * em, opts.window_stride * stride)
        masses = []
        for i in range(n):
            lo = 0.0 if i == 0 else 0.5 * (centers[i - 1] + centers[i])
            hi = L if i == n - 1 else 0.5 * (centers[i] + centers[i + 1])
            # Clip the Voronoi window to the character's own cell.
            lo = max(lo, origins[i] - opts.window_em * em)
            hi = min(hi, origins[i] + units[i] * em + opts.window_em * em)
            if hi <= lo:
                lo, hi = origins[i], origins[i] + units[i] * em
            m, _, _ = _mid_quartile(prof, lo, hi)
            masses.append(m)
        med_mass = _median([m for m in masses if m > 0]) or 1.0

        for _pass in range(opts.refine_passes + 1):
            for i in range(n):
                win = win_center
                lo0 = 0.0 if i == 0 else 0.5 * (centers[i - 1] + centers[i])
                hi0 = L if i == n - 1 else 0.5 * (centers[i] + centers[i + 1])
                lo = max(lo0, anchors[i] - win)
                hi = min(hi0, anchors[i] + win)
                if hi <= lo:
                    continue
                # Robust ink centre: mid-quartile of the profile inside the
                # window.  (A sliding em-width mass-maximising box was tried
                # and lost: the objective is flat for fullwidth glyphs, so
                # ties both drift and get captured by a dense neighbour.)
                mass, ink_c, spread = _mid_quartile(prof, lo, hi)
                if mass < max(6.0, opts.min_mass_frac * med_mass):
                    continue
                if spread > opts.max_spread_em * em and classes[i] != "center":
                    # A smeared/ambiguous window: punctuation and small kana are
                    # small enough that a neighbour's stroke can dominate the
                    # wide window (measured: a `、` pulled 0.44em onto the
                    # following kanji).  Retry around the CTC run centre -- it
                    # sits on the glyph -- and take the retry only when it is a
                    # compact blob near the anchor; otherwise keep the primary
                    # measurement rather than dropping the pull.
                    if opts.punct_spread_fallback:
                        win_fb = max(
                            opts.punct_fallback_window_em * em,
                            opts.window_stride * stride,
                        )
                        lo2 = max(lo0, ctc_centers[i] - win_fb)
                        hi2 = min(hi0, ctc_centers[i] + win_fb)
                        if hi2 > lo2:
                            mass2, ink_c2, spread2 = _mid_quartile(prof, lo2, hi2)
                            if (
                                mass2 >= max(6.0, opts.min_mass_frac * med_mass)
                                and spread2 <= opts.max_spread_em * em
                                and abs(ink_c2 - anchors[i])
                                <= opts.punct_fallback_max_em * em
                            ):
                                mass, ink_c, spread = mass2, ink_c2, spread2
                elif spread > opts.max_spread_em * em:
                    continue
                pull = _clamp(
                    ink_c - centers[i],
                    -opts.ink_max_pull_em * em,
                    opts.ink_max_pull_em * em,
                )
                w = 0.5 if chars[i].margin < opts.conf_floor else 1.0
                if debug is not None:
                    debug["ink_pulls"].append(
                        dict(pass_=_pass, i=i, lo=lo, hi=hi, mass=mass,
                             ink_c=ink_c, spread=spread, pull=pull, w=w)
                    )
                centers[i] = centers[i] + w * pull

    # 4. Boundaries and boxes ------------------------------------------------
    # Measured ink extents decide how much room each glyph wants; a shared
    # boundary per adjacent pair then goes into the empty ink space between the
    # two glyphs.  When both extents fit between the centres, both boxes get
    # their room; when they do not, the span is split at its emptiest point,
    # with each box kept tappable.  Extents are trusted only when the centre
    # sits on its own ink: an offset-ink glyph (punctuation whose centre is a
    # gap) or a contaminated ink pull falls back to the advance cell, because
    # its "extent" would otherwise be the neighbour's stroke.
    desired = [0.5 * units[i] * em for i in range(n)]
    need = list(desired)
    # Raw measured ink half-extents (unclamped, unpadded): the gate needs to
    # know what the split would actually cut, not what the box aspires to.
    ink_half = list(need)
    boundaries: list[float] = []
    if not opts.final_pass:
        # Ablation: the previous behaviour -- one midpoint boundary per pair,
        # boxes capped at 0.49 of the neighbour distance.
        boundaries = [0.5 * (centers[i] + centers[i + 1]) for i in range(n - 1)]
    if opts.final_pass and prof is not None:
        for i in range(n):
            ci = int(np.clip(round(centers[i]), 0, len(prof) - 1))
            if prof[ci] <= opts.extent_floor:
                continue
            pad = 0.5 * units[i] * em + opts.extent_window_em * em
            lo_lim = centers[i] - pad
            hi_lim = centers[i] + pad
            if i > 0:
                lo_lim = max(lo_lim, 0.5 * (centers[i] + centers[i - 1]))
            if i < n - 1:
                hi_lim = min(hi_lim, 0.5 * (centers[i] + centers[i + 1]))
            span_lo, span_hi = _ink_run(prof, centers[i], lo_lim, hi_lim,
                                        opts.extent_floor)
            ink_half[i] = max(centers[i] - span_lo, span_hi - centers[i])
            half_need = (
                max(centers[i] - span_lo, span_hi - centers[i]) + opts.extent_pad_px
            )
            need[i] = _clamp(
                half_need, desired[i], desired[i] * (1.0 + opts.extent_grow_frac)
            )

    if opts.final_pass:
        # Translate first: an overlapping pair pushes apart into its
        # neighbours' positive slack, so the boundary pass below gives both
        # glyphs their full room; only the residue (if any) is split.
        inkless = [text[i].isspace() for i in range(n)]
        measured = list(centers)
        _sweep_place(centers, need, inkless, L, opts)
        _sweep_pull_back(centers, need, measured, L, opts)
        _translate_apart(centers, need, ink_half, desired, em, opts)
        # h_old: what the midpoint cap allowed.  Every boundary is constrained
        # so no box ends up narrower than before (the pass is monotone).
        h_old = list(desired)
        for i in range(n):
            if i > 0:
                d = centers[i] - centers[i - 1]
                if d > 0:
                    h_old[i] = min(h_old[i], 0.49 * d)
            if i < n - 1:
                d = centers[i + 1] - centers[i]
                if d > 0:
                    h_old[i] = min(h_old[i], 0.49 * d)
        req = [max(need[i], h_old[i]) for i in range(n)]
        for i in range(n - 1):
            d = centers[i + 1] - centers[i]
            mid = 0.5 * (centers[i] + centers[i + 1])
            if d <= 0 or prof is None:
                boundaries.append(mid)
                continue
            lo = centers[i] + req[i]
            hi = centers[i + 1] - req[i + 1]
            if hi >= lo:
                # Both glyphs fit: the boundary can sit anywhere between their
                # measured inks -- put it in the emptiest spot.
                boundaries.append(_emptiest_point(prof, lo, hi, opts.extent_floor))
                continue
            # They do not fit (tight tracking, merged strokes, or a wide glyph
            # beside a narrow one): split at the emptiest point, keeping each
            # box at least split_floor_frac of its old half-width.
            lo = centers[i] + max(opts.split_floor_frac * h_old[i], opts.min_half_px)
            hi = centers[i + 1] - max(
                opts.split_floor_frac * h_old[i + 1], opts.min_half_px
            )
            boundaries.append(
                _emptiest_point(prof, lo, hi, opts.extent_floor) if hi > lo else mid
            )

    boxes = []
    for i in range(n):
        half = need[i]
        if not opts.final_pass:
            if i > 0:
                d = centers[i] - centers[i - 1]
                if d > 0:
                    half = min(half, 0.49 * d)
            if i < n - 1:
                d = centers[i + 1] - centers[i]
                if d > 0:
                    half = min(half, 0.49 * d)
        else:
            if i > 0:
                half = min(half, centers[i] - boundaries[i - 1])
            if i < n - 1:
                half = min(half, boundaries[i] - centers[i])
        half = max(half, 1.0)
        a = _clamp(centers[i] - half, 0.0, L)
        b = _clamp(centers[i] + half, 0.0, L)
        if b - a < 1.0:
            b = min(L, a + 1.0)
            a = max(0.0, b - 1.0)
        boxes.append((a, b))
    if debug is not None:
        debug.update(
            em=em, x0=x0, ls=ls, centers=list(centers), desired=list(desired),
            need=list(need), boundaries=list(boundaries), units=list(units),
            origins=list(origins),
        )
    if vertical:
        return [(0.0, a, cross, b) for (a, b) in boxes]
    return [(a, 0.0, b, cross) for (a, b) in boxes]


# ─────────────────────────────────────────────────────────────────────────────
# Metrics
# ─────────────────────────────────────────────────────────────────────────────


def iou(a: tuple[float, float, float, float], b: tuple[float, float, float, float]) -> float:
    ix0 = max(a[0], b[0])
    iy0 = max(a[1], b[1])
    ix1 = min(a[2], b[2])
    iy1 = min(a[3], b[3])
    iw = max(0.0, ix1 - ix0)
    ih = max(0.0, iy1 - iy0)
    inter = iw * ih
    area_a = max(0.0, a[2] - a[0]) * max(0.0, a[3] - a[1])
    area_b = max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])
    union = area_a + area_b - inter
    return inter / union if union > 0 else 0.0


def contains(box: tuple[float, float, float, float], p: tuple[float, float]) -> bool:
    return box[0] <= p[0] <= box[2] and box[1] <= p[1] <= box[3]


@dataclass
class CaseScore:
    n: int = 0
    center_err: list[float] = field(default_factory=list)  # px along reading axis
    center_err_em: list[float] = field(default_factory=list)
    ink_iou: list[float] = field(default_factory=list)
    cell_iou: list[float] = field(default_factory=list)
    width_err_em: list[float] = field(default_factory=list)
    tap_center_hit: int = 0
    tap_jitter_hit: int = 0
    tap_jitter_total: int = 0
    miss_half_em: int = 0  # chars with center error > 0.5 em
    # reading-axis fit: own ink covered by the box (1.0 = all of it) and the
    # largest share of a neighbour's ink the box swallows
    ink_cover: list[float] = field(default_factory=list)
    cross_capture: list[float] = field(default_factory=list)
    # per optical class (center / punct / small) center errors in em
    by_class: dict = field(default_factory=lambda: {"center": [], "punct": [], "small": []})

    def merge(self, other: "CaseScore") -> None:
        self.n += other.n
        self.center_err += other.center_err
        self.center_err_em += other.center_err_em
        self.ink_iou += other.ink_iou
        self.cell_iou += other.cell_iou
        self.width_err_em += other.width_err_em
        self.tap_center_hit += other.tap_center_hit
        self.tap_jitter_hit += other.tap_jitter_hit
        self.tap_jitter_total += other.tap_jitter_total
        self.miss_half_em += other.miss_half_em
        self.ink_cover += other.ink_cover
        self.cross_capture += other.cross_capture
        for k in self.by_class:
            self.by_class[k] += other.by_class[k]

    def summary(self) -> dict:
        def pct(xs, q):
            return float(np.percentile(xs, q)) if xs else 0.0

        n = max(self.n, 1)
        return {
            "n": self.n,
            "center_err_mean_px": float(np.mean(self.center_err)) if self.center_err else 0.0,
            "center_err_median_px": pct(self.center_err, 50),
            "center_err_p90_px": pct(self.center_err, 90),
            "center_err_mean_em": float(np.mean(self.center_err_em)) if self.center_err_em else 0.0,
            "center_err_p90_em": pct(self.center_err_em, 90),
            "miss_rate_over_half_em": self.miss_half_em / n,
            "ink_iou_mean": float(np.mean(self.ink_iou)) if self.ink_iou else 0.0,
            "cell_iou_mean": float(np.mean(self.cell_iou)) if self.cell_iou else 0.0,
            "width_err_mean_em": float(np.mean(self.width_err_em)) if self.width_err_em else 0.0,
            "tap_center_hit_rate": self.tap_center_hit / n,
            "tap_jitter_hit_rate": self.tap_jitter_hit / max(self.tap_jitter_total, 1),
            "ink_cover_mean": float(np.mean(self.ink_cover)) if self.ink_cover else 0.0,
            "ink_cover_lt90_rate": sum(1 for c in self.ink_cover if c < 0.90)
            / max(len(self.ink_cover), 1),
            "cross_capture_mean": float(np.mean(self.cross_capture))
            if self.cross_capture
            else 0.0,
            "cross_capture_gt25_rate": sum(1 for c in self.cross_capture if c > 0.25)
            / max(len(self.cross_capture), 1),
            "center_err_mean_em_punct": float(np.mean(self.by_class["punct"]))
            if self.by_class["punct"]
            else 0.0,
            "center_err_mean_em_small": float(np.mean(self.by_class["small"]))
            if self.by_class["small"]
            else 0.0,
        }


def score_boxes(
    boxes: list[tuple[float, float, float, float]],
    true_ink_boxes: list[tuple[float, float, float, float]],
    true_cell_boxes: list[tuple[float, float, float, float]],
    em: float,
    orientation: str,
    decoded_to_true: list[int | None],
    true_text: str = "",
    jitter: float = 0.12,
    seed: int = 0,
    cross_extent: float | None = None,
) -> CaseScore:
    """Compare one line's placed boxes against ground truth."""
    sc = CaseScore()
    if not boxes:
        return sc
    rng = np.random.default_rng(seed)
    vertical = orientation == "v"
    for i, d2t in enumerate(decoded_to_true):
        if d2t is None or i >= len(boxes):
            continue
        ink = true_ink_boxes[d2t]
        cell = true_cell_boxes[d2t]
        if ink[2] <= ink[0] or ink[3] <= ink[1]:
            continue
        box = boxes[i]
        true_center = ((ink[0] + ink[2]) / 2.0, (ink[1] + ink[3]) / 2.0)
        box_center = ((box[0] + box[2]) / 2.0, (box[1] + box[3]) / 2.0)
        axis = 1 if vertical else 0
        err = abs(box_center[axis] - true_center[axis])
        sc.center_err.append(err)
        sc.center_err_em.append(err / max(em, 1e-6))
        sc.miss_half_em += 1 if err > 0.5 * em else 0
        # Ink IoU: extend the true ink box across the cross axis to the crop
        # extent so a full-cross box is not punished for the glyph's height.
        if cross_extent is not None:
            ink_cmp = (
                (0.0, ink[1], cross_extent, ink[3])
                if vertical
                else (ink[0], 0.0, ink[2], cross_extent)
            )
        else:
            ink_cmp = ink
        sc.ink_iou.append(iou(box, ink_cmp))
        # Cell IoU on the reading axis only: the box's cross axis is the whole
        # crop by contract, so a 2-D comparison would just re-measure the crop.
        if vertical:
            b0, b1, c0, c1 = box[1], box[3], cell[1], cell[3]
        else:
            b0, b1, c0, c1 = box[0], box[2], cell[0], cell[2]
        inter = max(0.0, min(b1, c1) - max(b0, c0))
        union = max(b1, c1) - min(b0, c0)
        sc.cell_iou.append(inter / union if union > 0 else 0.0)
        box_width = (box[2] - box[0]) if not vertical else (box[3] - box[1])
        cell_width = (cell[2] - cell[0]) if not vertical else (cell[3] - cell[1])
        sc.width_err_em.append((box_width - cell_width) / max(em, 1e-6))
        # Reading-axis fit: how much of the glyph's own ink the box covers and
        # how much of a neighbour's ink it swallows (final-pass A/B metrics).
        i0, i1 = (ink[1], ink[3]) if vertical else (ink[0], ink[2])
        b0, b1 = (box[1], box[3]) if vertical else (box[0], box[2])
        sc.ink_cover.append(max(0.0, min(b1, i1) - max(b0, i0)) / max(i1 - i0, 1e-6))
        capture = 0.0
        for j in (i - 1, i + 1):
            if 0 <= j < len(boxes) and 0 <= j < len(decoded_to_true):
                t2 = decoded_to_true[j]
                if t2 is None or t2 >= len(true_ink_boxes):
                    continue
                nink = true_ink_boxes[t2]
                n0, n1 = (nink[1], nink[3]) if vertical else (nink[0], nink[2])
                if n1 - n0 <= 0:
                    continue
                capture = max(capture, (min(b1, n1) - max(b0, n0)) / (n1 - n0))
        sc.cross_capture.append(max(capture, 0.0))
        if d2t < len(true_text):
            cls = optical_class(true_text[d2t])
            sc.by_class[cls].append(err / max(em, 1e-6))
        sc.n += 1
        if contains(box, true_center):
            sc.tap_center_hit += 1
        for _ in range(4):
            jx = rng.normal(0.0, jitter * em)
            jy = rng.normal(0.0, jitter * em)
            p = (true_center[0] + jx, true_center[1] + jy)
            hit = None
            for bi, b in enumerate(boxes):
                if contains(b, p):
                    hit = bi
                    break
            sc.tap_jitter_total += 1
            if hit == i:
                sc.tap_jitter_hit += 1
    return sc

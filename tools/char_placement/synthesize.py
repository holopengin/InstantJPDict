#!/usr/bin/env python3
"""HarfBuzz/FreeType synthetic Japanese line generator with CTC simulation.

Each case is one line crop plus everything the placement algorithms need:

* the crop PNG (axis-aligned; the whole image is the crop),
* per-character ground truth: advance (em cell) boxes and ink boxes,
* a simulated CTC timestep stream consistent with the app's geometry
  (`targetW = round(L*48/cross)`, squish 0.5, 32-step floor, stride 8) and a
  documented score model, decoded with the app's own greedy rules so
  `charCols` matches what the device would hand to `computeCharBoxes`.

Failure cases are first-class: CTC substitution/insertion/deletion rates,
degradations (blur/noise/JPEG/uneven light/downscale), rotation, tracking and
ruby stacks are all parameters, and each case records the error it contains.

Usage:
    python synthesize.py --out /tmp/opencode/synth --preset all --seed 7
"""

from __future__ import annotations

import argparse
import json
import math
import random
from dataclasses import asdict, dataclass, field
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

from jpfmt import FONTS, Shaper, render_layout, load_sentences
from place import peak_offset

BLANK = "\u3000"
EPS = 1e-9


# ─────────────────────────────────────────────────────────────────────────────
# Parameters
# ─────────────────────────────────────────────────────────────────────────────


@dataclass
class CtcParams:
    """Documented CTC timestep/score simulation.

    The app's rec geometry: crop → height 48 resample → lengthwise squish
    (default 0.5) → pad to mult-of-8 → stride-8 head.  A glyph whose ink spans
    `span` crop px therefore covers `span / stride` timesteps, where
    `stride = L / seq_len`.  Scores are raw logits (the app stores the same
    values in `rawAlternatives`); `margin` (top1 - top2) is the confidence
    signal that is robust to the head's arbitrary logit scale.
    """

    run_scale: float = 0.90  # activation run length = span/stride * run_scale
    run_jitter: float = 0.5  # +/- timesteps, uniform
    min_run: int = 1
    max_run: int = 8
    peak_jitter_t: float = 0.22  # char alignment noise, timesteps (sigma)
    peak_bias_t: float = 0.0
    peak_logit_mu: float = 9.0
    peak_logit_sigma: float = 2.2
    blank_logit: float = 3.0
    blank_sigma: float = 0.25
    p_sub: float = 0.0  # CTC substitution
    p_ins: float = 0.0  # CTC insertion
    p_del: float = 0.0  # CTC deletion


@dataclass
class SynthParams:
    font: str = "sans"
    size_px: float = 36.0
    orientation: str = "h"
    tracking_em: float = 0.0
    angle_deg: float = 0.0
    box_pad_em: float = 0.30
    blur: float = 0.0
    noise: float = 0.0
    jpeg_quality: int = 0  # 0 = off
    dark: bool = False
    gradient: float = 0.0  # 0..1 bg falloff across the reading axis
    scale: float = 1.0
    squish: float = 0.5
    ctc: CtcParams = field(default_factory=CtcParams)

    def tag(self) -> str:
        parts = [self.font, self.orientation, f"s{int(self.size_px)}"]
        if self.tracking_em:
            parts.append(f"tr{self.tracking_em:.2f}")
        if self.angle_deg:
            parts.append(f"ang{self.angle_deg:.0f}")
        if self.blur:
            parts.append(f"blur{self.blur:.1f}")
        if self.noise:
            parts.append(f"nz{self.noise:.0f}")
        if self.jpeg_quality:
            parts.append(f"jpg{self.jpeg_quality}")
        if self.dark:
            parts.append("dark")
        if self.gradient:
            parts.append(f"gr{self.gradient:.1f}")
        if self.scale != 1.0:
            parts.append(f"sc{self.scale:.2f}")
        c = self.ctc
        if c.p_sub or c.p_ins or c.p_del:
            parts.append(f"err{c.p_sub:.2f}/{c.p_ins:.2f}/{c.p_del:.2f}")
        return "_".join(parts)


# ─────────────────────────────────────────────────────────────────────────────
# Geometry helpers
# ─────────────────────────────────────────────────────────────────────────────


def squish_target(target_w: int, factor: float) -> int:
    return max(1, int(round(target_w * factor)))


def ctc_geometry(L: float, cross: float, squish: float) -> tuple[int, float]:
    """(seq_len_total, stride_px) exactly as the app computes them."""
    target_w = int(round(L * 48.0 / max(cross, 1.0)))
    target_w = max(4, min(2000, target_w))
    sq = squish_target(target_w, squish)
    if sq / 8 < 32:
        sq = target_w
    seq_len = max(1, math.ceil(sq / 8))
    return seq_len, L / seq_len


def _affine_rotate(w: int, h: int, angle_deg: float):
    """Return (out_w, out_h, forward(p)->p', pillow_inverse_data)."""
    th = math.radians(angle_deg)
    c, s = math.cos(th), math.sin(th)
    corners = [(0, 0), (w, 0), (w, h), (0, h)]
    pts = [(c * x - s * y, s * x + c * y) for x, y in corners]
    minx = min(p[0] for p in pts)
    miny = min(p[1] for p in pts)
    maxx = max(p[0] for p in pts)
    maxy = max(p[1] for p in pts)
    out_w = int(math.ceil(maxx - minx))
    out_h = int(math.ceil(maxy - miny))
    tx, ty = -minx, -miny

    def forward(p):
        return (c * p[0] - s * p[1] + tx, s * p[0] + c * p[1] + ty)

    # PIL AFFINE samples src at (a*x + b*y + c, d*x + e*y + f); that is
    # R^T (dst - t) = R^T dst - R^T t.
    data = (
        c,
        s,
        -(c * tx + s * ty),
        -s,
        c,
        s * tx - c * ty,
    )
    return out_w, out_h, forward, data


# ─────────────────────────────────────────────────────────────────────────────
# Rendering
# ─────────────────────────────────────────────────────────────────────────────


@dataclass
class RenderedLine:
    image: np.ndarray  # uint8 RGB (H, W, 3)
    labels: np.ndarray  # uint8 (H, W); 0 = bg, i+1 = char i
    ink_boxes: list[tuple[float, float, float, float]]
    cell_boxes: list[tuple[float, float, float, float]]
    em: float
    n_chars: int
    text: str = ""  # main (non-ruby) body text


def render_line(
    text: str,
    params: SynthParams,
    ruby: tuple[str, float] | None = None,
) -> RenderedLine:
    """Render one line (optionally with a ruby stack) into a crop.

    `ruby` = (text, size_ratio) draws a second, smaller line: horizontal →
    above the body; vertical → right of the body.  Ruby chars get label
    indices after the body chars; `main_count` tells the caller how many are
    body chars.
    """
    shaper = Shaper(FONTS[params.font], params.size_px)
    layout = shaper.layout_line(
        text, params.orientation, tracking_px=params.tracking_em * shaper.em
    )
    ink, labels, ink_boxes = render_layout(layout, shaper)
    cell_boxes = list(layout.advance_boxes)
    em = shaper.em
    main_count = len(text)

    if ruby is not None:
        rtext, rratio = ruby
        rshaper = Shaper(FONTS[params.font], params.size_px * rratio)
        rlayout = rshaper.layout_line(rtext, params.orientation)
        rink, rlabels, rink_boxes = render_layout(rlayout, rshaper)
        gap = int(round(0.15 * em))
        if params.orientation == "h":
            h = max(ink.shape[0], rink.shape[0] + gap + ink.shape[0])
            w = max(ink.shape[1], rink.shape[1])
            big_ink = np.zeros((h, w), np.float32)
            big_lab = np.zeros((h, w), np.uint8)
            big_ink[: rink.shape[0], : rink.shape[1]] = rink
            rmask = rlabels > 0
            big_lab[: rink.shape[0], : rink.shape[1]][rmask] = rlabels[rmask] + main_count
            oy = h - ink.shape[0]
            big_ink[oy:, : ink.shape[1]] = np.maximum(
                big_ink[oy:, : ink.shape[1]], ink
            )
            mask = labels > 0
            sub = big_lab[oy:, : ink.shape[1]]
            sub[mask] = labels[mask]
            dy = float(oy)
            dx = 0.0
        else:
            w = max(ink.shape[1], rink.shape[1] + gap + ink.shape[1])
            h = max(ink.shape[0], rink.shape[0])
            big_ink = np.zeros((h, w), np.float32)
            big_lab = np.zeros((h, w), np.uint8)
            big_ink[: rink.shape[0], : rink.shape[1]] = rink
            rmask = rlabels > 0
            big_lab[: rink.shape[0], : rink.shape[1]][rmask] = rlabels[rmask] + main_count
            ox = w - ink.shape[1]
            big_ink[: ink.shape[0], ox:] = np.maximum(big_ink[: ink.shape[0], ox:], ink)
            mask = labels > 0
            sub = big_lab[: ink.shape[0], ox:]
            sub[mask] = labels[mask]
            dy = 0.0
            dx = float(ox)
        ink, labels = big_ink, big_lab
        ink_boxes = ink_boxes + [
            (b[0] + dx, b[1] + dy, b[2] + dx, b[3] + dy) for b in rink_boxes
        ]
        cell_boxes = cell_boxes + [
            (b[0] + dx, b[1] + dy, b[2] + dx, b[3] + dy) for b in rlayout.advance_boxes
        ]

    arr = (np.clip(ink, 0.0, 1.0) * 255.0).astype(np.uint8)
    mask_img = Image.fromarray(arr, "L")
    lab_img = Image.fromarray(labels, "L")

    forward = lambda p: p  # noqa: E731
    if params.angle_deg:
        w, h = mask_img.size
        ow, oh, forward, data = _affine_rotate(w, h, params.angle_deg)
        mask_img = mask_img.transform((ow, oh), Image.AFFINE, data, resample=Image.BILINEAR)
        lab_img = lab_img.transform((ow, oh), Image.AFFINE, data, resample=Image.NEAREST)

    # Tight crop to the label extent + padding.
    ys, xs = np.nonzero(np.asarray(lab_img) > 0)
    pad = int(round(params.box_pad_em * em))
    x0 = max(0, int(xs.min()) - pad)
    y0 = max(0, int(ys.min()) - pad)
    x1 = min(lab_img.size[0], int(xs.max()) + 1 + pad)
    y1 = min(lab_img.size[1], int(ys.max()) + 1 + pad)
    mask_img = mask_img.crop((x0, y0, x1, y1))
    lab_img = lab_img.crop((x0, y0, x1, y1))
    prev_forward = forward
    forward = lambda p: (  # noqa: E731
        prev_forward(p)[0] - x0,
        prev_forward(p)[1] - y0,
    )

    if params.scale != 1.0:
        w, h = mask_img.size
        nw, nh = max(4, int(round(w * params.scale))), max(4, int(round(h * params.scale)))
        mask_img = mask_img.resize((nw, nh), Image.BILINEAR)
        lab_img = lab_img.resize((nw, nh), Image.NEAREST)

    # Ground truth in final coordinates: ink from labels, cells through the map.
    lab = np.asarray(lab_img)
    s = params.scale
    final_ink: list[tuple[float, float, float, float]] = []
    n_total = main_count + (len(ruby[0]) if ruby else 0)
    for i in range(n_total):
        yy, xx = np.nonzero(lab == min(i + 1, 255))
        if len(xx) == 0:
            final_ink.append((0.0, 0.0, 0.0, 0.0))
        else:
            final_ink.append(
                (float(xx.min()), float(yy.min()), float(xx.max() + 1), float(yy.max() + 1))
            )
    final_cells = []
    for (a, b, c2, d) in cell_boxes:
        pts = [forward((a, b)), forward((c2, b)), forward((c2, d)), forward((a, d))]
        pts = [(p[0] * s, p[1] * s) for p in pts]
        final_cells.append(
            (
                min(p[0] for p in pts),
                min(p[1] for p in pts),
                max(p[0] for p in pts),
                max(p[1] for p in pts),
            )
        )

    # Compose RGB.
    m = np.asarray(mask_img).astype(np.float32) / 255.0
    bg_v = 26.0 if params.dark else 245.0
    fg_v = 232.0 if params.dark else 22.0
    h, w = m.shape
    bg = np.full((h, w), bg_v, np.float32)
    if params.gradient:
        axis = np.linspace(1.0 - params.gradient, 1.0, w if params.orientation == "h" else h)
        ramp = axis[None, :] if params.orientation == "h" else axis[:, None]
        bg = bg * ramp
    gray = bg * (1.0 - m) + fg_v * m
    img = Image.fromarray(np.clip(gray, 0, 255).astype(np.uint8), "L").convert("RGB")
    if params.blur:
        img = img.filter(ImageFilter.GaussianBlur(params.blur))
    if params.noise:
        arr2 = np.asarray(img).astype(np.float32)
        rng = np.random.default_rng(int(params.noise * 1000) + h * 31 + w)
        arr2 += rng.normal(0.0, params.noise, arr2.shape)
        img = Image.fromarray(np.clip(arr2, 0, 255).astype(np.uint8), "RGB")
    if params.jpeg_quality:
        import io

        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=params.jpeg_quality)
        buf.seek(0)
        img = Image.open(buf).convert("RGB")

    return RenderedLine(
        image=np.asarray(img).copy(),
        labels=lab,
        ink_boxes=final_ink,
        cell_boxes=final_cells,
        em=em * s,
        n_chars=main_count,
        text=text,
    )


# ─────────────────────────────────────────────────────────────────────────────
# CTC simulation
# ─────────────────────────────────────────────────────────────────────────────

# Confusion sets for simulated substitutions (visual/metric siblings that a
# CTC head realistically swaps); the placement lesson is the *class* change
# (fullwidth ↔ halfwidth, centred ↔ corner-placed).
CONFUSIONS = {
    "ロ": "口", "口": "ロ", "ー": "一", "一": "ー", "、": "．", "。": "．",
    "ん": "ソ", "ソ": "ン", "カ": "力", "力": "カ", "へ": "ヘ", "ヘ": "へ",
    "り": "リ", "リ": "り", "エ": "工", "工": "エ", "タ": "夕", "夕": "タ",
    "O": "0", "0": "O", "l": "1", "1": "l", "rn": "m",
}
INSERT_CHARS = "のれをにたはでとがし"


@dataclass
class Unit:
    char: str
    true_index: int | None
    t_center: float
    run: int
    peak: float


def simulate_ctc(
    line: RenderedLine,
    params: SynthParams,
    seed: int,
) -> dict:
    """Build the timestep stream + the app-rule `charCols` for one line."""
    rng = random.Random(seed)
    n = line.n_chars
    vertical = params.orientation == "v"
    h, w = line.image.shape[:2]
    L = float(h if vertical else w)
    cross = float(w if vertical else h)
    seq_len, stride = ctc_geometry(L, cross, params.squish)

    # Per-char reading-axis ink centre/span.
    centers = []
    spans = []
    for i in range(n):
        b = line.ink_boxes[i]
        if b[2] <= b[0] or b[3] <= b[1]:
            # No ink (space): the CTC class still fires over the advance cell.
            b = line.cell_boxes[i]
        if vertical:
            centers.append((b[1] + b[3]) / 2.0)
            spans.append(max(2.0, b[3] - b[1]))
        else:
            centers.append((b[0] + b[2]) / 2.0)
            spans.append(max(2.0, b[2] - b[0]))

    def vert_fold(ch: str) -> str:
        # Mirror of JapaneseUtil.verticalPunctuationChar: the app normalises
        # text and raw alternatives *before* char boxes, so the simulation
        # must fold here too.
        return {"?": "？", "…": "︙", "‥": "︰"}.get(ch, ch)

    c = params.ctc
    units: list[Unit] = []
    for i in range(n):
        if rng.random() < c.p_del:
            continue
        ch = vert_fold(line.text[i]) if vertical else line.text[i]
        units.append(
            Unit(
                char=ch,
                true_index=i,
                t_center=centers[i] / stride + c.peak_bias_t + rng.gauss(0.0, c.peak_jitter_t),
                run=int(
                    np.clip(
                        round(spans[i] / stride * c.run_scale + rng.uniform(-c.run_jitter, c.run_jitter)),
                        c.min_run,
                        c.max_run,
                    )
                ),
                # Clean presets must decode exactly: keep every peak clearly
                # above the simulated blank.  Weak/ambiguous characters are
                # the injected-error presets' job, not noise here.
                peak=float(
                    np.clip(
                        rng.gauss(c.peak_logit_mu, c.peak_logit_sigma),
                        c.blank_logit + 1.5,
                        20.0,
                    )
                ),
            )
        )
    # Insertions land in a random gap (before the first / between / after).
    n_ins = sum(1 for _ in range(n) if rng.random() < c.p_ins)
    for _ in range(n_ins):
        pos = rng.randint(0, len(units))
        t_ref = units[pos].t_center - 1.0 if pos < len(units) else (
            units[-1].t_center + 1.0 if units else seq_len / 2.0
        )
        units.append(
            Unit(
                char=rng.choice(INSERT_CHARS),
                true_index=None,
                t_center=t_ref + rng.uniform(-0.4, 0.4),
                run=rng.randint(c.min_run, max(c.min_run, 3)),
                peak=float(np.clip(rng.gauss(c.peak_logit_mu - 2.0, 1.5), 1.0, 12.0)),
            )
        )
    # Substitutions keep the run but change the emitted char.
    for u in units:
        if u.true_index is not None and rng.random() < c.p_sub:
            src = u.char
            u.char = CONFUSIONS.get(src, "錯" if not src.isascii() else "X")
    units.sort(key=lambda u: u.t_center)

    # CTC activations live *inside their integer support*: outside it the
    # blank wins, so a char's argmax run equals its support.  Supports never
    # overlap (the head emits one dominant class per timestep) and identical
    # adjacent chars keep one blank timestep between them, or greedy CTC
    # would collapse them into one character.
    # The app maps timestep t to the pixel cell [t*stride, (t+1)*stride) and a
    # run [a, b] to the *centre* of that coverage, ((a+b)/2 + 0.5)*stride.
    # Real evidence agrees with that convention (measured bias over the
    # vendored fixtures: -0.09 timesteps), so the support is placed with its
    # coverage centre at the glyph's ink centre.
    supports: list[tuple[int, int]] = []
    for u in units:
        ilo = int(round(u.t_center - u.run / 2.0))
        supports.append((max(0, min(seq_len - 1, ilo)), max(0, min(seq_len - 1, ilo + u.run - 1))))
    for i in range(1, len(units)):
        p_lo, p_hi = supports[i - 1]
        c_lo, c_hi = supports[i]
        same = units[i - 1].char == units[i].char
        if c_lo > p_hi + (1 if same else 0):
            continue
        if c_lo > p_hi:
            # Adjacent same-char supports: insert the blank timestep.
            c_lo = p_hi + 2
        else:
            cut = (p_hi + c_lo) // 2
            p_hi = max(p_lo, cut)
            c_lo = cut + 1 + (1 if same else 0)
        c_hi = max(c_hi, c_lo + units[i].run - 1)
        supports[i - 1] = (p_lo, p_hi)
        supports[i] = (min(c_lo, seq_len - 1), min(c_hi, seq_len - 1))

    blank = np.full(seq_len, c.blank_logit, np.float64)
    blank += np.array([rng.gauss(0.0, c.blank_sigma) for _ in range(seq_len)])
    scores = {i: np.full(seq_len, -1e9) for i in range(len(units))}
    for i, u in enumerate(units):
        lo, hi = supports[i]
        if hi < lo:
            continue
        half = max(0.5, (hi - lo + 1) / 2.0)
        center = 0.5 * (lo + hi)
        for t in range(lo, hi + 1):
            x = (t - center) / (half + 0.5)
            fall = max(0.0, 1.0 - x * x)
            scores[i][t] = u.peak * (0.40 + 0.60 * fall)

    topk = 4
    steps: list[list[tuple[str, float]]] = []
    for t in range(seq_len):
        cands = [(u.char, scores[i][t]) for i, u in enumerate(units) if scores[i][t] > -1e8]
        cands.sort(key=lambda cs: -cs[1])
        cands = cands[:topk]
        allc = [(BLANK, float(blank[t]))] + cands
        allc.sort(key=lambda cs: -cs[1])
        steps.append(allc[: topk + 1])

    # Decode with the app's exact greedy rules (mirrors ctcDecodeTopK).
    def wval(t: int, ch: str) -> float | None:
        if t < 0 or t >= seq_len:
            return None
        for cch, cs in steps[t]:
            if cch == ch:
                return cs
        return None

    text_out = []
    char_cols: list[float] = []
    prev: str | None = None
    for t, alts in enumerate(steps):
        top, score = alts[0]
        if top == BLANK:
            prev = None
            continue
        if top == prev:
            continue
        w0 = wval(t - 1, top)
        w2 = wval(t + 1, top)
        frac = t + peak_offset(w0, score, w2) if (w0 is not None and w2 is not None) else float(t)
        text_out.append(top)
        char_cols.append(frac)
        prev = top
    decoded_text = "".join(text_out)

    # Map decoded positions back to true char indices by greedy unit order.
    # A unit that lost its run (blank/other unit won) is a CTC deletion and
    # contributes no decoded char; a decoded char with no unit is an insertion.
    decoded_to_true: list[int | None] = []
    di = 0
    for u in units:
        if di >= len(decoded_text):
            break
        if decoded_text[di] == u.char:
            decoded_to_true.append(u.true_index)
            di += 1
        # else: unit was dropped by CTC (deletion); skip it.
    while len(decoded_to_true) < len(decoded_text):
        decoded_to_true.append(None)

    return {
        "seq_len_total": seq_len,
        "stride_px": stride,
        "char_cols": char_cols,
        "steps": [[list(pair) for pair in alts] for alts in steps],
        "decoded_text": decoded_text,
        "decoded_to_true": decoded_to_true,
        "units": [
            {"char": u.char, "true_index": u.true_index, "t_center": u.t_center, "run": u.run}
            for u in units
        ],
    }


# ─────────────────────────────────────────────────────────────────────────────
# Dataset assembly
# ─────────────────────────────────────────────────────────────────────────────


PRESETS: dict[str, list[SynthParams]] = {
    "core": [
        SynthParams(),
        SynthParams(orientation="v"),
        SynthParams(size_px=52, orientation="h"),
        SynthParams(size_px=28, orientation="h"),
        SynthParams(font="serif", orientation="h"),
        SynthParams(font="serif", orientation="v"),
        SynthParams(tracking_em=0.15),
        SynthParams(tracking_em=-0.05),
    ],
    "degraded": [
        SynthParams(blur=1.0),
        SynthParams(blur=2.0, noise=6.0),
        SynthParams(noise=10.0),
        SynthParams(jpeg_quality=45),
        SynthParams(jpeg_quality=30, blur=0.8),
        SynthParams(dark=True),
        SynthParams(dark=True, gradient=0.25),
        SynthParams(gradient=0.3),
        SynthParams(scale=0.7),
        SynthParams(scale=0.55, blur=0.8),
        SynthParams(size_px=60, scale=0.6, jpeg_quality=50),
    ],
    "rotated": [
        SynthParams(angle_deg=2.0),
        SynthParams(angle_deg=-3.0),
        SynthParams(angle_deg=5.0, blur=0.6),
        SynthParams(angle_deg=-6.0, orientation="v"),
        SynthParams(angle_deg=1.5, size_px=28),
        SynthParams(angle_deg=-2.5, noise=6.0, jpeg_quality=50),
    ],
    "errors": [
        SynthParams(ctc=CtcParams(p_sub=0.06)),
        SynthParams(ctc=CtcParams(p_ins=0.04)),
        SynthParams(ctc=CtcParams(p_del=0.05)),
        SynthParams(ctc=CtcParams(p_sub=0.05, p_ins=0.03, p_del=0.03)),
        SynthParams(orientation="v", ctc=CtcParams(p_sub=0.05, p_del=0.04)),
    ],
    "spacing": [
        SynthParams(tracking_em=0.3),
        SynthParams(tracking_em=0.5, size_px=30),
        SynthParams(size_px=44, tracking_em=-0.08),
        SynthParams(tracking_em=0.2, orientation="v"),
    ],
    "ruby": [
        SynthParams(orientation="v"),
        SynthParams(),
        SynthParams(font="serif", orientation="h", size_px=30),
    ],
    "long": [
        SynthParams(size_px=24),
        SynthParams(size_px=20, blur=0.5),
        SynthParams(size_px=22, orientation="v"),
    ],
}


@dataclass
class Case:
    id: str
    kind: str
    preset: str
    params: dict
    image: str
    orientation: str
    crop_w: int
    crop_h: int
    em_px: float
    main_count: int
    true_text: str
    app_text: str  # true text after the app's vertical-punctuation fold
    clean: bool  # decoded_text == app_text (no simulated CTC errors)
    decoded_text: str
    seq_len_total: int
    char_cols: list[float]
    steps: list
    decoded_to_true: list
    ink_boxes: list
    cell_boxes: list
    units: list
    notes: str = ""


def build_case(
    case_id: str,
    preset: str,
    text: str,
    params: SynthParams,
    out_dir: Path,
    seed: int,
    ruby: tuple[str, float] | None = None,
) -> Case:
    line = render_line(text, params, ruby=ruby)
    h, w = line.image.shape[:2]
    sim = simulate_ctc(line, params, seed)
    img_name = f"{case_id}.png"
    Image.fromarray(line.image).save(out_dir / img_name)
    fold = {"?": "？", "…": "︙", "‥": "︰"}
    app_text = (
        "".join(fold.get(ch, ch) for ch in text)
        if params.orientation == "v"
        else text
    )
    return Case(
        id=case_id,
        kind="synthetic-line",
        preset=preset,
        params=asdict(params),
        image=img_name,
        orientation=params.orientation,
        crop_w=w,
        crop_h=h,
        em_px=line.em,
        main_count=line.n_chars,
        true_text=text,
        app_text=app_text,
        clean=sim["decoded_text"] == app_text,
        decoded_text=sim["decoded_text"],
        seq_len_total=sim["seq_len_total"],
        char_cols=sim["char_cols"],
        steps=sim["steps"],
        decoded_to_true=sim["decoded_to_true"],
        ink_boxes=line.ink_boxes,
        cell_boxes=line.cell_boxes,
        units=sim["units"],
        notes=(
            f"font={params.font} size={params.size_px} angle={params.angle_deg} "
            f"tracking={params.tracking_em}em stride={sim['stride_px']:.2f}px"
        ),
    )


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=Path("/tmp/opencode/charplace_synth"))
    ap.add_argument("--preset", default="core")
    ap.add_argument("--n", type=int, default=80, help="cases (lines) per preset")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--corpus", type=Path, default=None)
    ap.add_argument("--long-min", type=int, default=18)
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    sentences = load_sentences(args.corpus)
    rng = random.Random(args.seed)

    presets = list(PRESETS) if args.preset == "all" else [args.preset]
    index_path = args.out / "cases.jsonl"
    records: list[dict] = []
    ci = 0
    for preset in presets:
        variants = PRESETS[preset]
        for k in range(args.n):
            params = variants[k % len(variants)]
            if preset == "long":
                text = "".join(rng.sample(sentences, 3))[: max(args.long_min, 34)]
            else:
                pool = sentences if preset != "spacing" else [s for s in sentences if len(s) >= 10]
                text = rng.choice(pool) if pool else "あいうえお"
                if len(text) > 34:
                    cut = rng.randint(10, 34)
                    text = text[:cut]
            ruby = None
            if preset == "ruby":
                base = rng.choice([s for s in sentences if len(s) >= 6])
                text = base[: min(len(base), 16)]
                ruby = (text[:4], 0.55)
            case_id = f"synth-{preset}-{ci:05d}"
            seed = args.seed * 100003 + ci
            case = build_case(case_id, preset, text, params, args.out, seed, ruby=ruby)
            records.append(asdict(case))
            ci += 1
    with (args.out / "cases.jsonl").open("w", encoding="utf-8") as fh:
        for rec in records:
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
    print(f"wrote {len(records)} cases -> {index_path}")


if __name__ == "__main__":
    main()

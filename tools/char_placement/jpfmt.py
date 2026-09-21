"""Shared Japanese font-metric knowledge, HarfBuzz shaping and FreeType raster.

This module is the single place where "what a JP font knows" lives: advance
classes, punctuation/small-kana optical classes, shaping (uharfbuzz) and glyph
rasterisation (freetype-py) plus per-glyph ink geometry.  It is used by the
synthetic generator (`synthesize.py`) and, for the metric classes, by the
placement algorithms (`place.py`).

Everything here is deterministic given the same font file and inputs.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import freetype
import numpy as np
import uharfbuzz as hb
from fontTools.ttLib import TTFont

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
FONT_DIR = REPO / "app/src/main/assets/fonts"
FONTS = {
    "sans": FONT_DIR / "NotoSansJP-Regular.ttf",
    "serif": FONT_DIR / "NotoSerifJP-Regular.ttf",
}

# ── Generic JP font-metric classes ──────────────────────────────────────────
def is_halfwidth(ch: str) -> bool:
    """Advance class split: ASCII + halfwidth katakana are 0.5 em."""
    cp = ord(ch)
    return cp <= 0x7E or 0xFF61 <= cp <= 0xFFDC


def advance_units(ch: str) -> float:
    return 0.5 if is_halfwidth(ch) else 1.0


# Characters whose ink is NOT centred in their advance box along the reading
# axis.  The layout fit must exclude them; per-character ink measurement
# places them instead.
PUNCT_CLOSING = "、。，．,.)）〕》」』】〙〗〟’”］"
PUNCT_OPENING = "(（〔《「『【〘〖〝‘“［"
PUNCT_MISC = "：；！？…‥︙︰・ー～「」『』（）[]{}〈〉《》【】"
SMALL_KANA = "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ"
OPTICAL_MARKS = "゛゜ゝゞヽヾ々〆〇"
PUNCT_ALL = PUNCT_CLOSING + PUNCT_OPENING + PUNCT_MISC


def optical_class(ch: str) -> str:
    """'center' | 'small' | 'punct'.

    Only 'center' characters may anchor the layout fit (their ink centre sits
    at the advance-box centre to within a few percent of an em in real JP
    fonts).  The others are placed from measured ink.
    """
    if ch in SMALL_KANA:
        return "small"
    if ch in PUNCT_ALL or ch in OPTICAL_MARKS or ch.isspace():
        return "punct"
    return "center"


# ── Shaping / rasterisation ─────────────────────────────────────────────────


@dataclass
class GlyphPlacement:
    char_idx: int  # index into the source string (cluster)
    gid: int
    # pen origin in layout pixels; for horizontal lines y is the baseline
    # (y down), for vertical lines (x, y) is the top-centre origin of the
    # character cell and y grows downward with the text flow.
    x: float
    y: float
    advance: float  # reading-axis advance in px (x for horizontal, y for vertical)


@dataclass
class LineLayout:
    text: str
    orientation: str  # 'h' | 'v'
    size_px: float
    em: float
    positions: list[GlyphPlacement] = field(default_factory=list)
    # advance box (x0, y0, x1, y1) per source char, reading order
    advance_boxes: list[tuple[float, float, float, float]] = field(default_factory=list)
    layout_bbox: tuple[float, float, float, float] = (0, 0, 0, 0)


_FONT_CACHE: dict[Path, tuple[bytes, TTFont, list]] = {}


def _load_font(path: Path) -> tuple[bytes, TTFont, list]:
    """Parse-once cache: font bytes, TTFont and glyph order per file."""
    key = Path(path)
    cached = _FONT_CACHE.get(key)
    if cached is None:
        raw = key.read_bytes()
        tt = TTFont(str(key), lazy=True)
        cached = (raw, tt, tt.getGlyphOrder())
        _FONT_CACHE[key] = cached
    return cached


class Shaper:
    """HarfBuzz shaping + FreeType rasterisation at one pixel size."""

    def __init__(self, font_path: Path, size_px: float):
        self.path = Path(font_path)
        self.size_px = float(size_px)
        self._hb_data, self._tt, self._glyph_order = _load_font(self.path)
        self.hb_face = hb.Face(self._hb_data)
        self.hb_font = hb.Font(self.hb_face)
        self.upem = self.hb_face.upem
        self.ft = freetype.Face(str(self.path))
        self.ft.set_char_size(int(round(self.size_px * 64)))
        self.em = self.advance_to_px(self.upem)

    def advance_to_px(self, units: float) -> float:
        return units / self.upem * self.size_px

    def shape(self, text: str, orientation: str) -> list[GlyphPlacement]:
        """Shape one line; glyph origins are in layout pixels.

        Horizontal: origin = (x, baseline), y down.  Vertical: HarfBuzz TTB
        (with the font's `vert` forms) gives per-glyph vertical origins on
        the column's centre line (x = 0 is the centre line), y down.
        """
        buf = hb.Buffer()
        buf.add_str(text)
        buf.guess_segment_properties()
        buf.direction = "ttb" if orientation == "v" else "ltr"
        buf.language = "ja"
        hb.shape(self.hb_font, buf)

        out: list[GlyphPlacement] = []
        pen_x = 0.0
        pen_y = 0.0
        for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
            ax = self.advance_to_px(pos.x_advance)
            ay = self.advance_to_px(pos.y_advance)
            ox = self.advance_to_px(pos.x_offset)
            oy = self.advance_to_px(pos.y_offset)
            if orientation == "v":
                # HB vertical: +y up; screen layout here is +y down.
                out.append(
                    GlyphPlacement(
                        char_idx=info.cluster,
                        gid=info.codepoint,
                        x=pen_x + ox,
                        y=pen_y - oy,
                        advance=-ay,
                    )
                )
                pen_y += -ay
            else:
                out.append(
                    GlyphPlacement(
                        char_idx=info.cluster,
                        gid=info.codepoint,
                        x=pen_x + ox,
                        y=pen_y - oy,
                        advance=ax,
                    )
                )
                pen_x += ax
        return out

    def layout_line(
        self,
        text: str,
        orientation: str,
        tracking_px: float = 0.0,
        baseline_ratio: float = 0.80,
    ) -> LineLayout:
        """Compose shaped glyphs into per-char advance boxes + draw positions.

        `tracking_px` is extra letter-spacing added after every advance
        (screenshots with CSS letter-spacing / kashira-style layout).
        """
        placements = self.shape(text, orientation)
        em = self.em
        layout = LineLayout(
            text=text, orientation=orientation, size_px=self.size_px, em=em
        )

        # Per-char origin/advance by walking the monotone cluster order.
        n = len(text)
        origins = [0.0] * n
        advances = [0.0] * n
        has_glyph = [False] * n
        pen = 0.0
        prev = -1
        prev_origin = 0.0
        for p in placements:
            ci = min(p.char_idx, n - 1)
            if ci != prev:
                if prev >= 0:
                    advances[prev] = pen - prev_origin
                prev = ci
                prev_origin = pen
                origins[ci] = pen
            has_glyph[ci] = True
            pen += p.advance if orientation == "h" else max(p.advance, 0.0)
        if prev >= 0:
            advances[prev] = pen - prev_origin
        # Chars HarfBuzz dropped (should not happen for JP) tail off.
        running = pen
        for i in range(n):
            if not has_glyph[i]:
                origins[i] = running
                advances[i] = em if orientation == "v" else 0.0
                running += advances[i]

        shifts = [i * tracking_px for i in range(n)]
        for p in placements:
            ci = min(p.char_idx, n - 1)
            if orientation == "h":
                layout.positions.append(
                    GlyphPlacement(
                        ci, p.gid, p.x + shifts[ci], em * baseline_ratio + p.y, p.advance
                    )
                )
            else:
                layout.positions.append(
                    GlyphPlacement(ci, p.gid, em * 0.5 + p.x, p.y + shifts[ci], p.advance)
                )
        for i in range(n):
            if orientation == "h":
                layout.advance_boxes.append(
                    (origins[i] + shifts[i], 0.0, origins[i] + advances[i] + shifts[i], em)
                )
            else:
                layout.advance_boxes.append(
                    (0.0, origins[i] + shifts[i], em, origins[i] + advances[i] + shifts[i])
                )
        if orientation == "h":
            layout.layout_bbox = (0.0, 0.0, running + shifts[-1] if n else 0.0, em)
        else:
            layout.layout_bbox = (0.0, 0.0, em, running + shifts[-1] if n else 0.0)
        return layout

    # -- rasterisation ----------------------------------------------------

    def _glyph_bitmap(self, gid: int) -> tuple[np.ndarray, int, int]:
        self.ft.load_glyph(gid, freetype.FT_LOAD_RENDER | freetype.FT_LOAD_NO_HINTING)
        bmp = self.ft.glyph.bitmap
        if bmp.rows == 0 or bmp.width == 0:
            return np.zeros((0, 0), dtype=np.uint8), 0, 0
        raw = bmp.buffer
        if not isinstance(raw, (bytes, bytearray)):
            raw = bytes(raw)
        arr = np.frombuffer(raw, dtype=np.uint8).reshape(bmp.rows, bmp.pitch)
        return arr[:, : bmp.width].copy(), self.ft.glyph.bitmap_left, self.ft.glyph.bitmap_top


def render_layout(
    layout: LineLayout,
    shaper: Shaper,
    margin_em: float = 0.35,
) -> tuple[np.ndarray, np.ndarray, list[tuple[float, float, float, float]]]:
    """Rasterise a layout into (ink alpha, labels, per-char ink bboxes).

    Layout coordinates are canvas coordinates (y down), so the caller's
    advance boxes and the returned ink boxes share one frame.  Labels store
    char_idx + 1; 0 is background.
    """
    em = layout.em
    glyphs = []
    x0 = y0 = 1e18
    x1 = y1 = -1e18
    for p in layout.positions:
        arr, bl, bt = shaper._glyph_bitmap(p.gid)
        if arr.size == 0:
            continue
        gx = p.x + bl
        gy = p.y - bt
        glyphs.append((p.char_idx, arr, gx, gy))
        x0 = min(x0, gx)
        y0 = min(y0, gy)
        x1 = max(x1, gx + arr.shape[1])
        y1 = max(y1, gy + arr.shape[0])
    if not glyphs:
        raise ValueError("empty layout")

    pad = margin_em * em
    ox = x0 - pad
    oy = y0 - pad
    w = int(np.ceil(x1 - x0 + 2 * pad))
    h = int(np.ceil(y1 - y0 + 2 * pad))
    ink = np.zeros((h, w), dtype=np.float32)
    labels = np.zeros((h, w), dtype=np.uint8)
    for ci, arr, gx, gy in glyphs:
        x = int(round(gx - ox))
        y = int(round(gy - oy))
        hgt = min(arr.shape[0], h - y)
        wid = min(arr.shape[1], w - x)
        if hgt <= 0 or wid <= 0:
            continue
        sub = ink[y : y + hgt, x : x + wid]
        np.maximum(sub, arr[:hgt, :wid].astype(np.float32) / 255.0, out=sub)
        lab = labels[y : y + hgt, x : x + wid]
        mask = arr[:hgt, :wid] > 0
        lab[mask] = min(ci + 1, 255)

    ink_boxes: list[tuple[float, float, float, float]] = []
    for ci in range(len(layout.advance_boxes)):
        ys, xs = np.nonzero(labels == min(ci + 1, 255))
        if len(xs) == 0:
            ink_boxes.append((0.0, 0.0, 0.0, 0.0))
        else:
            ink_boxes.append(
                (
                    float(xs.min()) + ox,
                    float(ys.min()) + oy,
                    float(xs.max() + 1) + ox,
                    float(ys.max() + 1) + oy,
                )
            )
    return ink, labels, ink_boxes


def advance_boxes_shifted(
    layout: LineLayout, dx: float, dy: float
) -> list[tuple[float, float, float, float]]:
    return [(a + dx, b + dy, c + dx, d + dy) for (a, b, c, d) in layout.advance_boxes]


# ── Corpus helpers ──────────────────────────────────────────────────────────


def load_sentences(path: Path | None = None) -> list[str]:
    """Read the committed sample corpus (one sentence per line, # comments)."""
    path = path or (HERE / "corpus/ja_sample.txt")
    out = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        s = raw.strip()
        if not s or s.startswith("#"):
            continue
        out.append(s)
    return out

#!/usr/bin/env python3
"""Real-inference spot check for the placement algorithms.

Consumes the dump produced by the scratch Rust harness
(`/tmp/opencode/rec_dump`, not committed): one JSON line per recognized line
with the pinned geometry (the shipped algorithm's PC output), the raw
per-timestep top-K and the line's detection box, on the vendored recognition
fixtures (PC-hosted conformance corpus).

For every axis-aligned line it:

* reconstructs `charCols` from the raw top-K with the app's own greedy rules
  (mirror of `re_decode_raw_alternatives` / `ctcDecodeTopK`),
* runs the Python current-algorithm port and the proposed algorithm on the
  same crop pixels,
* scores both against the pinned boxes (mean |centre delta| on the reading
  axis, axis-IoU) and against independent ink evidence: connected components
  of the crop's ink whose centroid lies inside exactly one box are used to
  judge which box centre is closer to the glyph's own ink.

Usage: python real_eval.py /tmp/opencode/real_lines.jsonl --images PC_CORPUS_DIR
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image

from place import (
    current_char_boxes,
    ink_profile,
    luminance_from_argb,
    peak_offset,
    proposed_char_boxes,
    ProposedOptions,
)

BLANK = "\u3000"


def reconstruct_char_cols(text: str, steps: list) -> tuple[list[float], str]:
    """Mirror of the app's greedy walk (ctcDecodeTopK + re_decode)."""
    cols: list[float] = []
    out: list[str] = []
    prev: str | None = None

    def wval(t: int, ch: str):
        if t < 0 or t >= len(steps):
            return None
        for cch, cs in steps[t]:
            if cch == ch:
                return cs
        return None

    for t, alts in enumerate(steps):
        if not alts:
            continue
        top, score = alts[0]
        if top == BLANK:
            prev = None
            continue
        if top == prev:
            continue
        w0, w2 = wval(t - 1, top), wval(t + 1, top)
        frac = t + (peak_offset(w0, score, w2) if (w0 is not None and w2 is not None) else 0.0)
        out.append(top)
        cols.append(frac)
        prev = top
    return cols, "".join(out)


def axis_metrics(
    boxes: list[tuple[float, float, float, float]],
    ref: list[tuple[float, float, float, float]],
    vertical: bool,
) -> tuple[float, float, int]:
    """Mean |centre delta| (px), axis-IoU, compared count vs `ref` boxes."""
    if len(boxes) != len(ref):
        return float("nan"), float("nan"), 0
    ax = 1 if vertical else 0
    deltas = []
    ious = []
    for b, r in zip(boxes, ref):
        bc = (b[ax] + b[ax + 2]) / 2.0
        rc = (r[ax] + r[ax + 2]) / 2.0
        deltas.append(abs(bc - rc))
        inter = max(0.0, min(b[ax + 2], r[ax + 2]) - max(b[ax], r[ax]))
        union = max(b[ax + 2], r[ax + 2]) - min(b[ax], r[ax])
        ious.append(inter / union if union > 0 else 0.0)
    return float(np.mean(deltas)), float(np.mean(ious)), len(ref)


def ink_axis_centres(
    rgb: np.ndarray, vertical: bool, band: tuple[float, float] | None = None
) -> list[tuple[float, float, float]]:
    """Independent ink evidence: per-column (or row) ink runs -> (centre, cross, mass).

    A simple 8-connected-free run splitter on the reading-axis projection
    gives one entry per separated glyph cluster, which is enough to judge a
    box centre that lands in a gap or on a neighbour.
    """
    lum = luminance_from_argb(rgb)
    prof, _ = ink_profile(lum, "v" if vertical else "h", band=band or 0.2, smooth=1)
    peak = prof.max()
    if peak <= 0:
        return []
    thr = max(1.0, 0.12 * peak)
    runs = []
    start = None
    for i, v in enumerate(prof):
        if v >= thr and start is None:
            start = i
        elif v < thr and start is not None:
            runs.append((start, i - 1))
            start = None
    if start is not None:
        runs.append((start, len(prof) - 1))
    out = []
    for (a, b) in runs:
        if b - a < 1:
            continue
        mass = float(prof[a : b + 1].sum())
        if mass < 8:
            continue
        w = prof[a : b + 1]
        centre = float((np.arange(a, b + 1) * w).sum() / w.sum())
        out.append((centre, 0.0, mass))
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("dump", type=Path)
    ap.add_argument(
        "--images",
        type=Path,
        default=Path(
            "/home/holopengin/repos/InstantJPDictDecky/accessibility_daemon/tests/conformance"
        ),
    )
    ap.add_argument("--json", type=Path, default=None)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--dump-info", action="store_true")
    args = ap.parse_args()

    rows = [json.loads(l) for l in args.dump.read_text(encoding="utf-8").splitlines()]
    stats = {
        "lines": 0,
        "skipped_rotated": 0,
        "skipped_geometry": 0,
        "text_mismatch": 0,
        "cur_vs_pinned_delta": [],
        "prop_vs_pinned_delta": [],
        "cur_vs_pinned_iou": [],
        "prop_vs_pinned_iou": [],
        "prop_closer_ink": 0,
        "cur_closer_ink": 0,
        "tie_ink": 0,
        "chars": 0,
        "prop_confident_wins": 0,
    }
    detail = []

    for row in rows:
        if args.limit and stats["lines"] >= args.limit:
            break
        if row["quad"] is not None:
            stats["skipped_rotated"] += 1
            continue
        line = row.get("line")
        if not line or not line.get("text"):
            continue
        bbox = row["bbox"]
        x, y, w, h = [int(round(v)) for v in bbox]
        # Case name -> image path: the case JSON carries the image path.
        case_path = args.images / f"cases/{row['case']}.json"
        img_rel = json.loads(case_path.read_text())["case"]["image"]
        full = Image.open(args.images / img_rel).convert("RGB")
        x = max(0, min(x, full.width - 1))
        y = max(0, min(y, full.height - 1))
        w = max(1, min(w, full.width - x))
        h = max(1, min(h, full.height - y))
        crop = full.crop((x, y, x + w, y + h))
        rgb = np.asarray(crop)
        if rgb.shape[0] < 4 or rgb.shape[1] < 4:
            stats["skipped_geometry"] += 1
            continue
        text = line["text"]
        steps = line["raw_alternatives"]
        vertical = bool(line["vertical"])
        char_cols, decoded = reconstruct_char_cols(text, steps)
        if decoded != text or len(char_cols) != len(text):
            stats["text_mismatch"] += 1
            continue
        # The dump stores (x, y, w, h); the algorithms work in (l, t, r, b).
        pinned = [
            (float(b[0]), float(b[1]), float(b[0]) + float(b[2]), float(b[1]) + float(b[3]))
            for b in line["char_boxes"]
        ]
        if len(pinned) != len(text):
            stats["skipped_geometry"] += 1
            continue

        # Pinned boxes are image coords; move them into the crop frame the two
        # algorithms work in.
        pinned_local = [(b[0] - x, b[1] - y, b[2] - x, b[3] - y) for b in pinned]

        pixels = rgb.astype(np.uint8)
        cur = current_char_boxes(
            text=text,
            char_cols=np.array(char_cols, dtype=np.float32),
            seq_len_total=len(steps),
            crop_w=rgb.shape[1],
            crop_h=rgb.shape[0],
            orientation="v" if vertical else "h",
            pixels=pixels,
        )
        prop = proposed_char_boxes(
            text=text,
            char_cols=np.array(char_cols, dtype=np.float32),
            seq_len_total=len(steps),
            crop_w=rgb.shape[1],
            crop_h=rgb.shape[0],
            orientation="v" if vertical else "h",
            pixels=pixels,
            steps=[[(c, s) for c, s in alts] for alts in steps],
            opts=ProposedOptions(),
        )
        ax = 1 if vertical else 0
        cur_d, cur_iou, n = axis_metrics(cur, pinned_local, vertical)
        prop_d, prop_iou, _ = axis_metrics(prop, pinned_local, vertical)
        if n == 0:
            stats["skipped_geometry"] += 1
            continue
        stats["chars"] += n
        stats["cur_vs_pinned_delta"].append(cur_d)
        stats["prop_vs_pinned_delta"].append(prop_d)
        stats["cur_vs_pinned_iou"].append(cur_iou)
        stats["prop_vs_pinned_iou"].append(prop_iou)
        stats["lines"] += 1

        # Independent check where the two algorithms disagree: the box whose
        # centre is closer to a clean ink run's centroid wins that char.
        comps = ink_axis_centres(rgb, vertical)
        cur_pts = [(b[ax] + b[ax + 2]) / 2.0 for b in cur]
        prop_pts = [(b[ax] + b[ax + 2]) / 2.0 for b in prop]
        # crop-local comparison: components are crop-local too.
        case_stat = stats.setdefault("by_case", {}).setdefault(
            row["case"], {"lines": 0, "components": 0, "prop": 0, "cur": 0, "tie": 0, "dis": 0, "dis_prop": 0, "dis_cur": 0}
        )
        case_stat["lines"] += 1
        for ci, cval in enumerate(comps):
            ccentre = cval[0]
            # Only judge when exactly one current box and one proposed box
            # contain the component centre (unambiguous ownership).
            cur_owner = [i for i, b in enumerate(cur) if b[ax] <= ccentre <= b[ax + 2]]
            prop_owner = [i for i, b in enumerate(prop) if b[ax] <= ccentre <= b[ax + 2]]
            if len(cur_owner) != 1 or len(prop_owner) != 1 or cur_owner[0] != prop_owner[0]:
                continue
            i = cur_owner[0]
            if i >= len(text):
                continue
            cur_err = abs(cur_pts[i] - ccentre)
            prop_err = abs(prop_pts[i] - ccentre)
            case_stat["components"] += 1
            disagree = abs(cur_pts[i] - prop_pts[i]) >= 2.0
            if disagree:
                case_stat["dis"] += 1
            if abs(cur_err - prop_err) < 1.0:
                stats["tie_ink"] += 1
                case_stat["tie"] += 1
                if disagree:
                    case_stat.setdefault("dis_tie", 0)
                    case_stat["dis_tie"] += 1
            elif prop_err < cur_err:
                stats["prop_closer_ink"] += 1
                case_stat["prop"] += 1
                if disagree:
                    case_stat["dis_prop"] += 1
            else:
                stats["cur_closer_ink"] += 1
                case_stat["cur"] += 1
                if disagree:
                    case_stat["dis_cur"] += 1
        detail.append(
            {
                "case": row["case"],
                "i": row["i"],
                "text": text,
                "vertical": vertical,
                "crop": [int(x), int(y), int(w), int(h)],
                "cur_delta": cur_d,
                "prop_delta": prop_d,
                "cur_iou": cur_iou,
                "prop_iou": prop_iou,
            }
        )

    def mean(xs):
        return float(np.mean(xs)) if xs else 0.0

    print(f"lines scored: {stats['lines']}  chars: {stats['chars']}")
    print(f"skipped (rotated/quad): {stats['skipped_rotated']}  geometry: {stats['skipped_geometry']}"
          f"  text mismatch: {stats['text_mismatch']}")
    print(f"current  vs pinned: mean |dcentre| = {mean(stats['cur_vs_pinned_delta']):.2f}px"
          f"  axis-IoU = {mean(stats['cur_vs_pinned_iou']):.3f}")
    print(f"proposed vs pinned: mean |dcentre| = {mean(stats['prop_vs_pinned_delta']):.2f}px"
          f"  axis-IoU = {mean(stats['prop_vs_pinned_iou']):.3f}")
    tot = stats["prop_closer_ink"] + stats["cur_closer_ink"] + stats["tie_ink"]
    if tot:
        print(f"ink-run arbitration on {tot} clean components:"
              f" proposed closer {stats['prop_closer_ink']} ({stats['prop_closer_ink']/tot:.0%}),"
              f" current closer {stats['cur_closer_ink']} ({stats['cur_closer_ink']/tot:.0%}),"
              f" tie {stats['tie_ink']}")
        for case, cs in sorted(stats.get("by_case", {}).items()):
            if not cs.get("dis"):
                continue
            dis = cs["dis"]
            print(
                f"  {case:<34} lines={cs['lines']:<3} components={cs['components']:<5}"
                f" disagreements={dis:<4} proposed {cs['dis_prop']:<4} current {cs['dis_cur']:<4}"
                f" tie {cs.get('dis_tie', 0)}"
            )
    if args.json:
        args.json.write_text(json.dumps({"stats": stats, "detail": detail}, indent=1))
        print(f"wrote {args.json}")


if __name__ == "__main__":
    main()

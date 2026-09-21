#!/usr/bin/env python3
"""Evaluation harness: proposed vs current char placement on the synthetic set.

Usage:
    python eval.py --data /tmp/opencode/charplace_synth
    python eval.py --data DIR --only clean --json out.json
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image

from place import (
    CaseScore,
    ProposedOptions,
    current_char_boxes,
    proposed_char_boxes,
    score_boxes,
)

ALGOS = ("current", "current_nosnap", "legacy", "proposed")


def run_algo(
    algo: str,
    case: dict,
    pixels: np.ndarray,
) -> list[tuple[float, float, float, float]]:
    text = case["decoded_text"]
    common = dict(
        text=text,
        char_cols=case["char_cols"],
        seq_len_total=case["seq_len_total"],
        crop_w=case["crop_w"],
        crop_h=case["crop_h"],
        orientation=case["orientation"],
        pixels=pixels,
    )
    if algo == "current":
        return current_char_boxes(**common, box_layout_mode=1, box_uniform_size=True)
    if algo == "current_nosnap":
        return current_char_boxes(**common, box_layout_mode=0, box_uniform_size=True)
    if algo == "legacy":
        return current_char_boxes(**common, box_layout_mode=0, box_uniform_size=False)
    if algo == "proposed":
        return proposed_char_boxes(**common, steps=case["steps"], opts=ProposedOptions())
    raise ValueError(algo)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True, nargs="+")
    ap.add_argument("--only", choices=["all", "clean", "errors"], default="all")
    ap.add_argument("--json", type=Path, default=None)
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    scores: dict[str, CaseScore] = {a: CaseScore() for a in ALGOS}
    scores_orient: dict[tuple[str, str], CaseScore] = {}
    scores_preset: dict[tuple[str, str], CaseScore] = {}
    counts = {"cases": 0, "clean": 0, "lines": 0, "chars": 0}
    case_rows = []

    for data_dir in args.data:
        index = data_dir / "cases.jsonl"
        if not index.exists():
            continue
        for line in index.read_text(encoding="utf-8").splitlines():
            case = json.loads(line)
            if args.only == "clean" and not case["clean"]:
                continue
            if args.only == "errors" and case["clean"]:
                continue
            img = np.asarray(Image.open(data_dir / case["image"]).convert("RGB"))
            true_text = case["app_text"]
            counts["cases"] += 1
            counts["clean"] += 1 if case["clean"] else 0
            counts["lines"] += 1
            n_used = 0
            row = {"id": case["id"], "preset": case["preset"], "clean": case["clean"]}
            for algo in ALGOS:
                boxes = run_algo(algo, case, img)
                sc = score_boxes(
                    boxes,
                    case["ink_boxes"],
                    case["cell_boxes"],
                    em=case["em_px"],
                    orientation=case["orientation"],
                    decoded_to_true=case["decoded_to_true"],
                    true_text=true_text,
                    cross_extent=float(case["crop_w"] if case["orientation"] == "v" else case["crop_h"]),
                    seed=hash(case["id"]) % 100000,
                )
                scores[algo].merge(sc)
                key = (case["orientation"], algo)
                scores_orient.setdefault(key, CaseScore()).merge(sc)
                pk = (case["preset"], algo)
                scores_preset.setdefault(pk, CaseScore()).merge(sc)
                if algo == "current":
                    n_used = sc.n
                if algo == "proposed" and sc.n:
                    row["cur_err_em"] = scores["current"].summary()["center_err_mean_em"]
                    row["prop_err_em"] = sc.summary()["center_err_mean_em"]
            counts["chars"] += n_used
            case_rows.append(row)
            if args.limit and counts["cases"] >= args.limit:
                break

    print(f"cases={counts['cases']} (clean={counts['clean']}) chars={counts['chars']}")
    header = (
        f"{'algo':<14}{'err mean':>9}{'err med':>9}{'err p90':>9}"
        f"{'err em':>8}{'miss':>7}{'tap':>7}{'jitter':>8}"
        f"{'inkIoU':>8}{'cellIoU':>9}{'wErr em':>9}"
    )
    print(header)
    for algo in ALGOS:
        s = scores[algo].summary()
        print(
            f"{algo:<14}{s['center_err_mean_px']:>9.2f}{s['center_err_median_px']:>9.2f}"
            f"{s['center_err_p90_px']:>9.2f}{s['center_err_mean_em']:>8.3f}"
            f"{s['miss_rate_over_half_em']:>7.3f}{s['tap_center_hit_rate']:>7.3f}"
            f"{s['tap_jitter_hit_rate']:>8.3f}{s['ink_iou_mean']:>8.3f}"
            f"{s['cell_iou_mean']:>9.3f}{s['width_err_mean_em']:>9.3f}"
        )
    print("\nby orientation:")
    for orient in ("h", "v"):
        for algo in ALGOS:
            s = scores_orient.get((orient, algo))
            if not s or s.n == 0:
                continue
            sm = s.summary()
            print(
                f"  {orient} {algo:<14} n={sm['n']:<6} err={sm['center_err_mean_px']:.2f}px"
                f" ({sm['center_err_mean_em']:.3f}em) p90={sm['center_err_p90_em']:.3f}em"
                f" miss={sm['miss_rate_over_half_em']:.3f} tap={sm['tap_center_hit_rate']:.3f}"
                f" punct={sm['center_err_mean_em_punct']:.3f} small={sm['center_err_mean_em_small']:.3f}"
            )

    print("\nby preset (err em / miss / tap, current vs proposed):")
    for preset in sorted({k[0] for k in scores_preset}):
        line = f"  {preset:<10}"
        for algo in ("current", "proposed"):
            s = scores_preset.get((preset, algo))
            if not s or s.n == 0:
                line += f"  {algo}: -"
                continue
            sm = s.summary()
            line += (
                f"  {algo}: {sm['center_err_mean_em']:.3f}em"
                f" miss={sm['miss_rate_over_half_em']:.3f}"
                f" tap={sm['tap_center_hit_rate']:.3f}"
            )
        print(line)

    if args.json:
        out = {
            "counts": counts,
            "summary": {a: scores[a].summary() for a in ALGOS},
            "by_preset": {
                f"{p}/{a}": s.summary() for (p, a), s in sorted(scores_preset.items())
            },
            "cases": case_rows,
        }
        args.json.write_text(json.dumps(out, ensure_ascii=False, indent=1))
        print(f"\nwrote {args.json}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Export a small synthetic fixture for the Kotlin sketch's JVM test.

Picks deterministic cases from a generated dataset and writes, per case:

* `<id>.png`  — the line crop (also the `pixels` input),
* `<id>.json` — decoded text, charCols, seqLenTotal, per-timestep top-3,
  true ink cells and the Python reference's proposed boxes.

Usage:
    python export_kotlin_fixture.py --data /tmp/opencode/charplace_synth \
        --out ../../app/src/test/resources/char_placement
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image

from place import ProposedOptions, proposed_char_boxes


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--ids", default=None, help="comma-separated case ids (default: auto-pick)")
    ap.add_argument("--topk", type=int, default=3)
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    cases = [
        json.loads(line)
        for line in (args.data / "cases.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    by_id = {c["id"]: c for c in cases}

    if args.ids:
        chosen = [by_id[i] for i in args.ids.split(",")]
    else:
        # One of each interesting shape: clean horizontal, clean vertical,
        # rotated, degraded (dark), ruby stack, tracking.
        def first(pred):
            for c in cases:
                if pred(c):
                    return c
            return None

        picks = [
            first(lambda c: c["preset"] == "core" and c["orientation"] == "h" and c["clean"]),
            first(lambda c: c["preset"] == "core" and c["orientation"] == "v" and c["clean"]),
            first(lambda c: c["preset"] == "rotated" and c["clean"]),
            first(lambda c: c["preset"] == "degraded" and c["clean"]),
            first(lambda c: c["preset"] == "ruby" and c["clean"]),
            first(lambda c: c["preset"] == "spacing" and c["clean"]),
        ]
        chosen = [c for c in picks if c]

    written = []
    for case in chosen:
        img = Image.open(args.data / case["image"]).convert("RGB")
        rgb = np.asarray(img)
        boxes = proposed_char_boxes(
            text=case["decoded_text"],
            char_cols=np.array(case["char_cols"], dtype=np.float32),
            seq_len_total=case["seq_len_total"],
            crop_w=case["crop_w"],
            crop_h=case["crop_h"],
            orientation=case["orientation"],
            pixels=rgb,
            steps=[[(c, s) for c, s in alts] for alts in case["steps"]],
            opts=ProposedOptions(),
        )
        name = case["id"]
        img.save(args.out / f"{name}.png")
        # Android unit tests have no java.awt, so the pixels the Kotlin sketch
        # consumes are committed as raw 8-bit luminance (crop_w * crop_h).
        # The generator renders grayscale, so this is byte-identical to the
        # RGB input the Python reference used.
        gray = np.asarray(img.convert("L"), dtype=np.uint8)
        (args.out / f"{name}.gray").write_bytes(gray.tobytes())
        payload = {
            "id": name,
            "note": case.get("notes", ""),
            "orientation": case["orientation"],
            "text": case["decoded_text"],
            "char_cols": case["char_cols"],
            "seq_len_total": case["seq_len_total"],
            "crop_w": case["crop_w"],
            "crop_h": case["crop_h"],
            "em_px": case["em_px"],
            "steps": [[[c, s] for c, s in alts[: args.topk]] for alts in case["steps"]],
            "ink_boxes": case["ink_boxes"],
            "decoded_to_true": case["decoded_to_true"],
            "expected_boxes": [[float(v) for v in b] for b in boxes],
        }
        (args.out / f"{name}.json").write_text(
            json.dumps(payload, ensure_ascii=False), encoding="utf-8"
        )
        written.append(name)
    print(f"wrote {len(written)} cases -> {args.out}")
    for n in written:
        print(f"  {n}")


if __name__ == "__main__":
    main()

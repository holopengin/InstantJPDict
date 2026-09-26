#!/usr/bin/env python3
"""Calibration npy for ncnn2table, det (DB) at the *shipped* 896x896.

`tools/gen_det_calib_npy.py` is the production generator but it needs numpy and
PIL, and this host has neither; it is also pinned to 960x960, the size det ran
at before the letterbox work. This is the same preprocessing with no
dependencies:

    scale = 896 / max(w, h); bilinear resize to (rw, rh);
    centred letterbox on gray 128; float32 ImageNet norm; NCHW (3, 896, 896)

Output is .npy v1.0, float32, C order — the format `ncnn2table type=1` reads.
Images: the committed `tools/onnx_quantization/calibration_data/detect/` PNGs
plus any extra PNGs given with --extra (the androidTest benchmark pages, which
are the closest thing to the app's real input). JPEG/PPM sources are skipped
with a note: this decoder is PNG-only, and a calibration set of pages plus the
committed PNGs is enough to place the activation ranges.

    gen_det_calib_npy.py --out /tmp/opencode/det_calib
"""
import argparse
import glob
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mkraw import read_png  # noqa: E402

MODEL = 896
MEAN = (0.485, 0.456, 0.406)
STD = (0.229, 0.224, 0.225)


def write_npy(path, w, h, c, floats):
    # .npy v1.0: magic, header, then raw little-endian float32 C order
    header = "{'descr': '<f4', 'fortran_order': False, 'shape': (%d, %d, %d), }" % (c, h, w)
    # pad so that the data starts at a 64-byte boundary
    pre = len(b"\x93NUMPY") + 2 + 2
    pad = 64 - ((pre + len(header) + 1) % 64)
    header = header + " " * pad + "\n"
    out = b"\x93NUMPY" + struct.pack("<BB", 1, 0) + struct.pack("<H", len(header)) + header.encode("latin1")
    out += struct.pack("<%df" % len(floats), *floats)
    with open(path, "wb") as fp:
        fp.write(out)


def letterbox(w, h, px):
    scale = MODEL / float(max(w, h))
    rw = max(int(round(w * scale)), 32)
    rh = max(int(round(h * scale)), 32)
    if rw > MODEL:
        rw = MODEL
    if rh > MODEL:
        rh = MODEL
    ox = (MODEL - rw) // 2
    oy = (MODEL - rh) // 2
    out = [0.0] * (3 * MODEL * MODEL)
    for c in range(3):
        mean, std = MEAN[c], STD[c]
        # gray 128 padding, before normalisation
        padv = (128 / 255.0 - mean) / std
        base = c * MODEL * MODEL
        for i in range(MODEL * MODEL):
            out[base + i] = padv
        for y in range(rh):
            sy = (y + 0.5) / scale - 0.5
            y0 = int(sy // 1)
            fy = sy - y0
            ya = max(0, min(y0, h - 1))
            yb = min(y0 + 1, h - 1)
            for x in range(rw):
                sx = (x + 0.5) / scale - 0.5
                x0 = int(sx // 1)
                fx = sx - x0
                xa = max(0, min(x0, w - 1))
                xb = min(x0 + 1, w - 1)
                w00 = (1 - fx) * (1 - fy)
                w01 = (1 - fx) * fy
                w10 = fx * (1 - fy)
                w11 = fx * fy
                ia = (ya * w + xa) * 3
                ib = (ya * w + xb) * 3
                ja = (yb * w + xa) * 3
                jb = (yb * w + xb) * 3
                v = w00 * px[ia] + w10 * px[ib] + w01 * px[ja] + w11 * px[jb]
                v = int(v + 0.5)
                if v < 0:
                    v = 0
                elif v > 255:
                    v = 255
                out[base + (y + oy) * MODEL + (x + ox)] = (v / 255.0 - mean) / std
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="/tmp/opencode/det_calib")
    ap.add_argument("--calib_dir", default="tools/onnx_quantization/calibration_data/detect")
    ap.add_argument("--extra", action="append", default=[],
                    help="extra PNG glob (repeatable)")
    ap.add_argument("--max", type=int, default=12)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    cands = []
    for g in a.extra + [os.path.join(a.calib_dir, "*.png")]:
        cands += sorted(glob.glob(g))
    seen = set()
    cands = [c for c in cands if not (c in seen or seen.add(c))]

    lines = []
    ok = 0
    for path in cands:
        if ok >= a.max:
            break
        try:
            w, h, rgb = read_png(path)
            if w < 32 or h < 32:
                print("skip %s: %dx%d too small" % (path, w, h))
                continue
            floats = letterbox(w, h, rgb)
            npy = os.path.join(a.out, "calib_%03d.npy" % ok)
            write_npy(npy, MODEL, MODEL, 3, floats)
            lines.append(npy)
            print("  %s (%dx%d) -> %s" % (path, w, h, os.path.basename(npy)))
            ok += 1
        except SystemExit as e:
            print("skip %s: %s" % (path, e))
    flist = os.path.join(a.out, "filelist.txt")
    with open(flist, "w") as fp:
        fp.write("\n".join(lines) + "\n")
    print("%d npys -> %s (list %s)" % (ok, a.out, flist))
    print("ncnn2table shape=[%d,%d,3] type=1" % (MODEL, MODEL))
    if ok == 0:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

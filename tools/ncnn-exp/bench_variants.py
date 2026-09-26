#!/usr/bin/env python3
"""Bench several *model variants* of the same net, interleaved.

Same discipline as sweep.py (one process per run, rotating order, minimum of the
per-iteration minima) but the axis is the model file rather than an Option, so
there is no `--set` to thread through. Every variant is diffed against
`--ref-dir`'s output tensor: bitwise, max-abs, and for det the DB mask IoU at
the app's 0.3 threshold.

    bench_variants.py --ref-dir <assets> --dir name=path --dir name=path ...
"""
import argparse
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "build", "bench")


def kvs(line):
    f = line.rstrip("\n").split("\t")
    return {f[i]: f[i + 1] for i in range(0, len(f) - 1, 2)}


def run(args, extra, ref=None):
    cmd = [BENCH] + args + extra
    if ref:
        cmd += ["--ref", ref]
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        return None, (p.stderr.strip().splitlines() or ["exit %d" % p.returncode])[-1]
    lines = [l for l in p.stdout.splitlines() if l.startswith("model\t")]
    return (kvs(lines[0]) if lines else None), None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ref-dir", required=True)
    ap.add_argument("--dir", action="append", required=True, help="name=path")
    ap.add_argument("--model", default="det")
    ap.add_argument("--width", type=int, default=896)
    ap.add_argument("--input")
    ap.add_argument("--crop")
    ap.add_argument("--iters", type=int, default=6)
    ap.add_argument("--repeats", type=int, default=5)
    ap.add_argument("--tmp", default="/tmp/opencode/variants")
    ap.add_argument("--threads", type=int, default=2)
    a = ap.parse_args()

    os.makedirs(a.tmp, exist_ok=True)
    variants = [tuple(d.split("=", 1)) for d in a.dir]
    variants.insert(0, ("fp16 baseline (shipped)", a.ref_dir))

    common = ["--model", a.model, "--iters", str(a.iters), "--warmup", "2",
              "--set", "threads=%d" % a.threads]
    if a.model == "rec":
        common += ["--width", str(a.width), "--fill", "--vocab",
                   os.path.join(a.ref_dir, "vocab.json"), "--remap",
                   os.path.join(a.ref_dir, "rec_remap.txt")]
        if a.crop:
            common += ["--crop", a.crop]
    else:
        common += ["--width", str(a.width)]
    if a.input:
        common += ["--input", a.input]

    refbin = os.path.join(a.tmp, "ref.bin")
    r, err = run(common + ["--dir", a.ref_dir, "--dump", refbin], [])
    if r is None:
        print("baseline failed: %s" % err, file=sys.stderr)
        return 1
    base = float(r["min_ms"])
    print("baseline min %.1f ms  ck %s" % (base, r["ck"]))

    best = {n: 1e9 for n, _ in variants}
    meta = {}
    for rep in range(a.repeats):
        order = list(range(len(variants)))
        order = order[rep % len(order):] + order[: rep % len(order)]
        for i in order:
            name, path = variants[i]
            res, err = run(common + ["--dir", path], ["--ref", refbin] if i > 0 else [])
            if res is None:
                meta[name] = {"error": err}
                continue
            best[name] = min(best[name], float(res["min_ms"]))
            meta[name] = res

    print()
    hdr = "%-26s %9s %8s %6s %10s %9s %8s" % ("variant", "min_ms", "vs_base", "bit",
                                              "maxabs", "iou03", "argmaxΔ")
    print(hdr)
    for name, _ in variants:
        m = meta.get(name, {})
        if "error" in m:
            print("%-26s ERROR %s" % (name, m["error"]))
            continue
        iou = float(m.get("iou03", -1))
        print("%-26s %9.1f %7.1f%% %6s %10.3g %9.5f %8s" % (
            name, best[name], 100.0 * (best[name] - base) / base, m.get("bitident"),
            float(m.get("maxabs", -1)), iou, m.get("argmax_diff")))
        if a.model == "rec":
            print("    text: %r" % m.get("text"))
    return 0


if __name__ == "__main__":
    sys.exit(main())

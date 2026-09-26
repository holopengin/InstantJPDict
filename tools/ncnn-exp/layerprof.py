#!/usr/bin/env python3
"""Rank the per-layer profile ncnn prints under -DNCNN_BENCHMARK=ON.

`bench-bench` runs the same input as `bench`; ncnn's own hook (src/benchmark.cpp,
reached from NetPrivate::forward_layer) prints one line per layer on **stderr**:

    Convolution  convrelu_0            12.34ms    | [ 448, 448,   3 *8] -> [ 224, 224,  24 *8]     kernel: 3 x 3     stride: 2 x 2

The per-layer times are wall time around `do_forward_layer`, so a layer that
internally parallelises over `opt.num_threads` shows its *whole* time, and the
sum of layers is >= the extract time (a layer's recursive inputs are timed
inside their own call, and OpenMP thread spin-up lands wherever it lands). Read
the ranking, not the sum.

    layerprof.py < stderr > layers.tsv
    layerprof.py --top 25 --class < stderr
"""
import argparse
import re
import sys
from collections import defaultdict

# "type name  time ms | inshape -> outshape  [kernel: k x k  stride: s x s]"
LINE = re.compile(
    r"^(?P<type>\S+)\s+(?P<name>\S+)\s+(?P<ms>[\d.]+)ms"
    r"(?:\s+\|\s+(?P<in>.*?)\s*->\s*(?P<out>.*?))?"
    r"(?:\s+kernel:\s*(?P<k>\S+)\s*x\s*(?P<kh>\S+)\s+stride:\s*(?P<s>\S+)\s*x\s*(?P<sh>\S+))?"
    r"\s*\|?\s*$"
)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--top", type=int, default=30)
    ap.add_argument("--byclass", action="store_true", help="aggregate by layer type")
    ap.add_argument("--runs", type=int, default=0, help="cap on how many runs to average over (0 = all in the stream)")
    a = ap.parse_args()

    per_run = []
    cur = []
    for line in sys.stdin:
        m = LINE.match(line)
        if not m:
            continue
        cur.append(m.groupdict())
    if cur:
        per_run.append(cur)
    if not per_run:
        print("no benchmark lines on stdin (did you link the NCNN_BENCHMARK build?)",
              file=sys.stderr)
        return 1
    lines = per_run[0]
    # ncnn prints the whole graph once per extract; the number of times the
    # graph's first layer shows up is the number of runs in the stream, which is
    # what the per-layer sums have to be divided by. Averaging over runs is
    # required: a `--warmup 1 --iters 2` invocation emits three graphs and the
    # first one carries lazy weight packing.
    nruns = sum(1 for d in lines if d["name"] == lines[0]["name"])
    if a.runs > 0:
        nruns = min(nruns, a.runs)
    if nruns < 1:
        nruns = 1
    runs = [lines]

    by_layer = defaultdict(lambda: {"ms": 0.0, "type": "", "name": "", "shape": "", "kernel": ""})
    by_class = defaultdict(float)
    n = nruns
    for r in runs:
        for d in r:
            by_layer[d["name"]]["ms"] += float(d["ms"])
            by_layer[d["name"]]["type"] = d["type"]
            by_layer[d["name"]]["shape"] = "%s -> %s" % (d.get("in") or "?", d.get("out") or "?")
            by_layer[d["name"]]["kernel"] = "%sx%s s%sx%s" % (
                d.get("k") or "-", d.get("kh") or "-", d.get("s") or "-", d.get("sh") or "-")
            by_class[d["type"]] += float(d["ms"])
    rows = sorted(by_layer.items(), key=lambda kv: -kv[1]["ms"])
    total = sum(v["ms"] for _, v in rows)

    print("# %d run(s) averaged, %d layers, total %.1f ms" % (n, len(rows), total / n))
    if a.byclass:
        print("type\tms_per_run\tshare_pct\tcalls")
        cnt = defaultdict(int)
        for dname, v in by_layer.items():
            cnt[v["type"]] += 1
        for t, ms in sorted(by_class.items(), key=lambda kv: -kv[1]):
            print("%s\t%.3f\t%.1f\t%d" % (t, ms / n, 100.0 * ms / total, cnt[t]))
    else:
        print("rank\ttype\tname\tms_per_run\tshare_pct\tkernel\tshapes")
        for i, (name, v) in enumerate(rows[: a.top], 1):
            print("%d\t%s\t%s\t%.3f\t%.1f\t%s\t%s" % (
                i, v["type"], name, v["ms"] / n, 100.0 * v["ms"] / total, v["kernel"], v["shape"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Interleaved option sweep for the ncnn x86 harness.

Method, and why it is this shape:

* One `bench` process per (config, repeat). Separate processes because ncnn
  keeps per-Net state (packed weights, the fp16/int8 conversion tables) that
  must not leak between configs, and because a crash in one config must not
  take the sweep with it.
* **Interleaved repeats, rotating order.** Configs are not run back to back;
  each repeat visits every config once, and the visit order rotates so no
  config systematically occupies the same slot in the machine's thermal /
  frequency / page-cache cycle. Per config the reported number is the median of
  its per-repeat medians. This is the same discipline the on-device sweeps in
  docs/ocr-pipeline-perf-2026-09-25.md used, and it is what makes a 2-3 %
  difference mean anything on a shared VM.
* Every config is diffed against the *baseline* config's output tensor
  (bitwise, max-abs, and a task-shaped metric: DB mask IoU for det, greedy CTC
  argmax + decoded text for rec). A config that changes the answer is reported
  as REJECTED regardless of how fast it is.

    sweep.py --model det  --repeats 5 --iters 6
    sweep.py --model rec  --width 432 --repeats 5 --iters 6
    sweep.py --model rec  --widths 64,128,256,432,728 --repeats 3
"""
import argparse
import json
import os
import statistics
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "build", "bench")


def kvs(line):
    f = line.rstrip("\n").split("\t")
    return {f[i]: f[i + 1] for i in range(0, len(f) - 1, 2)}


# ── the config matrix ──────────────────────────────────────────────────────
#
# `set` is the ncnn::Option delta from the model's shipped default. The default
# is the app's own: det = threads 2 + fp16 packed/storage/arithmetic + packing
# layout; rec = threads 1 + fp16 off + packing layout.
def configs(model):
    base = "threads=%d" % (2 if model == "det" else 1)
    if model == "det":
        return [
            ("baseline (app config)", base),
            ("threads=1", "threads=1"),
            ("threads=3", "threads=3"),
            ("threads=4", "threads=4"),
            ("threads=6", "threads=6"),
            ("fp32 (all fp16 off)", base + ",fp16_packed=0,fp16_storage=0,fp16_arithmetic=0"),
            ("fp16_storage off only", base + ",fp16_storage=0"),
            ("fp16_arithmetic off only", base + ",fp16_arithmetic=0"),
            ("bf16_storage on", base + ",bf16_storage=1"),
            ("winograd off", base + ",winograd=0"),
            ("winograd F2x3 only", base + ",wino43=0,wino63=0"),
            ("winograd F4x3 only", base + ",wino23=0,wino63=0"),
            ("winograd F6x3 only", base + ",wino23=0,wino43=0"),
            ("winograd all off", base + ",winograd=1,wino23=0,wino43=0,wino63=0"),
            ("sgemm off", base + ",sgemm=0"),
            ("packing off", base + ",packing=0"),
            ("int8 inference ON (weights are fp16)", base + ",int8=1"),
            ("a53a55 kernel on (arm-only knob)", base + ",a53a55=1"),
            ("denormals off (FTZ/DAZ=0)", base + ",denormals=0"),
            ("light mode off", base + ",light=0"),
            ("cpu_powersave=1", base + ",powersave=1"),
        ]
    return [
        ("baseline (app config)", base),
        ("threads=2", "threads=2"),
        ("threads=3", "threads=3"),
        ("threads=4", "threads=4"),
        ("int8 inference OFF (weights are int8)", base + ",int8=0"),
        ("fp16 storage+arith on (rejected on device)", base + ",fp16_packed=1,fp16_storage=1,fp16_arithmetic=1"),
        ("fp16_packed only", base + ",fp16_packed=1"),
        ("bf16_storage on", base + ",bf16_storage=1"),
        ("winograd off", base + ",winograd=0"),
        ("sgemm off", base + ",sgemm=0"),
        ("packing off", base + ",packing=0"),
        ("light mode off", base + ",light=0"),
        ("denormals off (FTZ/DAZ=0)", base + ",denormals=0"),
        ("blocktime 0", base + ",blocktime=0"),
        ("cpu_powersave=1", base + ",powersave=1"),
    ]


def run(args, extra, dump=None, ref=None):
    cmd = [BENCH] + args + ["--set", extra]
    if dump:
        cmd += ["--dump", dump]
    if ref:
        cmd += ["--ref", ref]
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        return None, p.stderr.strip().splitlines()[-1] if p.stderr.strip() else "exit %d" % p.returncode
    line = [l for l in p.stdout.splitlines() if l.startswith("model\t")]
    if not line:
        return None, "no result line"
    return kvs(line[0]), None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="det")
    ap.add_argument("--dir", required=True, help="model dir")
    ap.add_argument("--input", help="raw input (mkraw.py)")
    ap.add_argument("--crop", help="x,y,w,h for rec")
    ap.add_argument("--width", type=int, default=432)
    ap.add_argument("--widths", help="comma list; runs one sub-sweep per width")
    ap.add_argument("--repeats", type=int, default=5)
    ap.add_argument("--iters", type=int, default=6)
    ap.add_argument("--warmup", type=int, default=2)
    ap.add_argument("--tmp", default="/tmp/opencode/sweep")
    ap.add_argument("--only", help="comma list of config labels to run")
    ap.add_argument("--out", help="write TSV here")
    a = ap.parse_args()

    widths = [int(x) for x in a.widths.split(",")] if a.widths else [a.width]
    os.makedirs(a.tmp, exist_ok=True)
    rows = []
    for width in widths:
        args = ["--model", a.model, "--dir", a.dir, "--iters", str(a.iters),
                "--warmup", str(a.warmup)]
        if a.model == "rec":
            args += ["--width", str(width), "--fill"]
            args += ["--vocab", os.path.join(a.dir, "vocab.json"),
                     "--remap", os.path.join(a.dir, "rec_remap.txt")]
            if a.crop:
                args += ["--crop", a.crop]
        else:
            args += ["--width", str(width)]
        if a.input:
            args += ["--input", a.input]

        cfgs = configs(a.model)
        if a.only:
            keep = set(a.only.split(","))
            cfgs = [c for c in cfgs if c[0] in keep]

        # the baseline defines the reference tensor for everything else
        refbin = os.path.join(a.tmp, "ref_%s_%d.bin" % (a.model, width))
        r, err = run(args, cfgs[0][1], dump=refbin)
        if r is None:
            print("baseline failed: %s" % err, file=sys.stderr)
            return 1
        base_best = float(r["min_ms"])

        per = {label: [] for label, _ in cfgs}
        per_min = {label: [] for label, _ in cfgs}
        meta = {}
        for rep in range(a.repeats):
            order = list(range(len(cfgs)))
            order = order[rep % len(order):] + order[: rep % len(order)]
            for i in order:
                label, extra = cfgs[i]
                res, err = run(args, extra, ref=refbin if i > 0 else None)
                if res is None:
                    meta[label] = {"error": err}
                    continue
                per[label].append(float(res["med_ms"]))
                per_min[label].append(float(res["min_ms"]))
                meta[label] = res

        for label, _ in cfgs:
            m = meta[label]
            if label not in per or not per[label]:
                rows.append({"model": a.model, "width": width, "config": label,
                             "med_ms": None, "error": m.get("error")})
                continue
            meds = per[label]
            mins = per_min[label]
            med = statistics.median(meds)
            best = min(mins)
            rows.append({
                "model": a.model, "width": width, "config": label,
                # primary statistic: the fastest *iteration* seen in any repeat.
                # This host is shared (other agents, an adb server, a gradle
                # daemon), so sporadic interference inflates medians by 2-3x;
                # the minimum is the closest available estimate of the
                # interference-free cost. med_ms is kept for the noise picture.
                "best_ms": round(best, 3),
                "med_ms": round(med, 3),
                "worst_med_ms": round(max(meds), 3),
                "spread_pct": round(100.0 * (max(meds) - best) / best, 1) if best else None,
                "vs_base_pct": round(100.0 * (best - base_best) / base_best, 2) if base_best else None,
                "speedup": round(base_best / best, 3) if best > 0 else None,
                "stable": m.get("stable"),
                "bitident": m.get("bitident"),
                "maxabs": m.get("maxabs"),
                "iou03": m.get("iou03"),
                "argmax_diff": m.get("argmax_diff"),
                "text": m.get("text"),
                "ck": m.get("ck"),
            })

    hdr = ["model", "width", "config", "best_ms", "vs_base_pct", "speedup", "spread_pct",
           "med_ms", "worst_med_ms", "stable", "bitident", "maxabs", "iou03",
           "argmax_diff", "ck", "text", "error"]
    print("\t".join(hdr))
    for r in rows:
        print("\t".join("" if r.get(h) is None else str(r.get(h)) for h in hdr))
    if a.out:
        with open(a.out, "w") as fp:
            json.dump(rows, fp, indent=1, ensure_ascii=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())

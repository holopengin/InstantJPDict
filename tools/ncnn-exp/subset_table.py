#!/usr/bin/env python3
"""Classify det's conv layers and subset an ncnn2table table by class.

`ncnn2int8` quantises exactly the layers present in the table it is given, so
*deleting rows* is the exclusion mechanism (documented in
docs/ncnn-conversion.md: "# comments are still NOT honored ... rows must be
deleted"). That makes a per-class int8 experiment a table edit rather than a
model edit:

    int8 only the 1x1 convs   -> keep rows whose param line is 1x1
    int8 only the 3x3 convs   -> keep rows whose param line is 3x3
    int8 only depthwise       -> keep rows whose param line is a ConvolutionDepthWise

The prior attempt recorded in docs/ncnn-conversion.md excluded the 8 FPN-tail
layers from a *full*-int8 build (backbone noise still broke the 0.95 IoU gate);
this script asks the other question — which layer class can be int8 at all.

    subset_table.py --param det.param --table table.txt --class 1x1 --out t_1x1.txt
    subset_table.py --param det.param --table table.txt --list
"""
import argparse
import re


def parse_param(path):
    """layer name -> (type, kw, kh, stride_w, stride_h, outch, inch)"""
    out = {}
    for line in open(path):
        f = line.split()
        if len(f) < 4 or f[0] in ("7767517",):
            continue
        if not f[0][0].isalpha():
            continue
        t, name = f[0], f[1]
        params = {}
        for kv in f[4:]:
            if "=" in kv:
                k, v = kv.split("=", 1)
                params[int(k)] = v
        def num(k, d=0):
            try:
                return int(float(params.get(k, d)))
            except ValueError:
                return d
        # 1 = kernel_w, 11 = kernel_h, 2 = dilation, 3 = stride, 0 = output channels
        kw = num(1, 1)
        kh = num(11, kw) if 11 in params else kw
        sw = num(3, 1)
        sh = num(13, sw) if 13 in params else sw
        outch = num(0, 0)
        wsz = num(6, 0)
        inch = wsz // (kw * kh * outch) if (wsz and kw and kh and outch) else 0
        out[name] = (t, kw, kh, sw, sh, outch, inch)
    return out


def klass(info):
    t, kw, kh, sw, sh, outch, inch = info
    if t == "ConvolutionDepthWise":
        return "depthwise"
    if t == "Deconvolution":
        return "deconv"
    if t in ("Convolution",):
        if kw == 1 and kh == 1:
            return "1x1"
        if (kw, kh) == (3, 3):
            return "3x3" if sw == 1 else "3x3s%d" % sw
        if (kw, kh) == (2, 2):
            return "2x2"
        return "%dx%d" % (kw, kh)
    return "other:" + t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--param", required=True)
    ap.add_argument("--table")
    ap.add_argument("--class", dest="cls", help="keep only this class (repeatable)")
    ap.add_argument("--drop", help="drop this class instead")
    ap.add_argument("--out")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()

    layers = parse_param(a.param)
    byname = {}
    for name, info in layers.items():
        byname[name] = klass(info)

    if a.list or not a.table:
        from collections import Counter
        c = Counter(byname.values())
        print("layer classes in %s:" % a.param)
        for k, v in sorted(c.items(), key=lambda kv: -kv[1]):
            print("  %-12s %d" % (k, v))
        print("\nhottest-by-class detail (conv layers only):")
        for name, info in layers.items():
            if info[0] in ("Convolution", "ConvolutionDepthWise", "Deconvolution"):
                print("  %-18s %-22s %dx%d s%d  out=%-4d in=%d" % (
                    name, byname[name], info[1], info[2], info[3], info[5], info[6]))
        return 0

    keep = set(a.cls.split(",")) if a.cls else None
    drop = set(a.drop.split(",")) if a.drop else set()
    kept = dropped = 0
    with open(a.out, "w") as out:
        for line in open(a.table):
            if not line.strip():
                continue
            name = line.split()[0]
            # table keys look like `convrelu_0_param_0` or `convdw_144_weight_1`
            base = name
            for suf in ("_param_", "_weight_"):
                if suf in base:
                    base = base.split(suf)[0]
                    break
            k = byname.get(base)
            if k is None:
                # not a conv layer (Gemm/Softmax/...) — keep whatever the tool wrote
                out.write(line)
                kept += 1
                continue
            if drop and k in drop:
                dropped += 1
                continue
            if keep and k not in keep:
                dropped += 1
                continue
            out.write(line)
            kept += 1
    print("table %s -> %s: %d rows kept, %d dropped (keep=%s drop=%s)"
          % (a.table, a.out, kept, dropped, keep, drop))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

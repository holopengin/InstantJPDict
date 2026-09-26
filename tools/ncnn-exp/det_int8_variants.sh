#!/usr/bin/env bash
# Per-class int8 quantization of det, measured end to end.
#
# The question: docs/ncnn-conversion.md records that det int8 was rejected on
# quality (box IoU 0.61 calib / 0.89 bench against a 0.95 gate) and that a
# mixed build excluding the 8 FPN-tail layers did not recover it. Both of those
# are *full*-int8-minus-a-few-layers experiments. This asks the other question:
# is any single layer CLASS worth int8-ing on its own?
#
# The mechanism is a table edit, not a model edit: ncnn2int8 quantises exactly
# the layers present in the table, so deleting rows leaves the rest fp16
# (documented in docs/ncnn-conversion.md). The models land in $OUT/models/<v>/
# and are throwaway experiment copies — the shipped det.param/det.bin are never
# touched.
#
# Usage: tools/ncnn-exp/det_int8_variants.sh [out_dir]
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
OUT=${1:-/tmp/opencode/det_int8}
NCNN_TOOLS=${NCNN_TOOLS:-/tmp/opencode/ncnn_build/host/bin}
CALIB=${CALIB:-/tmp/opencode/det_calib}
CALIB_ARGS=${CALIB_ARGS:-}
M="$REPO/app/src/main/assets/PP-OCRv6_small_ncnn"
PAGE=${PAGE:-$REPO/app/src/androidTest/assets/benchmark/ebook_tategaki.png}
DET_SIZE=896

# shellcheck source=/dev/null
source /tmp/opencode/env-x86.sh

[ -x "$NCNN_TOOLS/ncnn2table" ] || { echo "build the host tools first: tools/build_ncnn.sh --out /tmp/opencode/ncnn_build --skip-android" >&2; exit 1; }
mkdir -p "$OUT/models"

# 1. calibration npy at the shipped 896 (tools/gen_det_calib_npy.py is pinned to
#    960 and needs numpy+PIL; this one is stdlib-only and 896)
if [ ! -f "$CALIB/filelist.txt" ]; then
  python3 "$HERE/gen_det_calib_npy.py" --out "$CALIB" --max 10 \
    --extra "$REPO/app/src/androidTest/assets/benchmark/*.png" $CALIB_ARGS
fi

# 2. one KL table for the whole graph
if [ ! -f "$OUT/table_full.txt" ]; then
  "$NCNN_TOOLS/ncnn2table" "$M/det.param" "$M/det.bin" "$CALIB/filelist.txt" \
    "$OUT/table_full.txt" "shape=[$DET_SIZE,$DET_SIZE,3]" method=kl type=1
fi
# the #16 tripwire: absurd scales mean the source was already quantized
python3 - "$OUT/table_full.txt" <<'EOF'
import re, sys
bad = [(l.split()[0], m.group(0)) for l in open(sys.argv[1])
       for m in re.finditer(r"[-+]?\d[\d.]*e[-+]?\d+", l) if abs(float(m.group(0))) > 1e6]
if bad:
    print("ABSURD SCALES (double quantization?):", bad[:5]); sys.exit(1)
print("scales sane")
EOF

# 3. per-class tables + the models
for v in 1x1 3x3,3x3s2 depthwise 2x2; do
  name=$(echo "$v" | tr ',' '_')
  python3 "$HERE/subset_table.py" --param "$M/det.param" --table "$OUT/table_full.txt" \
    --class "$v" --out "$OUT/table_$name.txt"
done
python3 "$HERE/subset_table.py" --param "$M/det.param" --table "$OUT/table_full.txt" \
  --drop 1x1 --out "$OUT/table_no1x1.txt"

for v in full 1x1 3x3_3x3s2 depthwise 2x2 no1x1; do
  mkdir -p "$OUT/models/$v"
  cp "$M/det.param" "$OUT/models/$v/det.param"
  "$NCNN_TOOLS/ncnn2int8" "$M/det.param" "$M/det.bin" \
    "$OUT/models/$v/det.param" "$OUT/models/$v/det.bin" "$OUT/table_$v.txt"
  printf "%-12s bin %s bytes\n" "$v" "$(stat -c%s "$OUT/models/$v/det.bin")"
done

# 4. measure, interleaved, against the shipped fp16 model
RAW=$(mktemp -d)
python3 "$HERE/mkraw.py" "$PAGE" "$RAW/page.raw" >/dev/null
python3 "$HERE/bench_variants.py" --ref-dir "$M" --input "$RAW/page.raw" \
  --iters 5 --repeats 5 --tmp "$RAW/variants" \
  --dir "int8 all=$OUT/models/full" \
  --dir "int8 1x1 only=$OUT/models/1x1" \
  --dir "int8 3x3 only=$OUT/models/3x3_3x3s2" \
  --dir "int8 depthwise only=$OUT/models/depthwise" \
  --dir "int8 2x2 only=$OUT/models/2x2" \
  --dir "int8 all-but-1x1=$OUT/models/no1x1"
echo "results -> $RAW/variants ; models -> $OUT/models"

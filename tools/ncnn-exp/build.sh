#!/usr/bin/env bash
# Build the x86 experiment harness against a host libncnn.a.
#
# The ncnn tree itself is built by the repo's own tools/build_ncnn.sh (pinned
# fork, marker-gated). That script's host leg only produces the quantization
# tools, so the `ncnn` library target is built here from the SAME build
# directory — same source, same flags, no second build recipe.
#
#   tools/ncnn-exp/build.sh            # timing harness (stock options)
#   tools/ncnn-exp/build.sh --bench    # same, plus -DNCNN_BENCHMARK=ON
#
# --bench uses ncnn's OWN per-layer timing hook (src/benchmark.cpp, reached from
# NetPrivate::forward_layer under #if NCNN_BENCHMARK). It prints
# "type name ms | in -> out | kernel x stride" per layer on stderr, so layer
# attribution needs no patch to the pinned tree at all. It costs two
# get_current_time() calls and one dims-only Mat per layer; it does not change
# any arithmetic.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SRC=/tmp/opencode/ncnn_build/src
BUILD=/tmp/opencode/ncnn_build/build-host
BENCH=0
while [ $# -gt 0 ]; do
  case "$1" in
    --src) SRC="$2"; shift 2;;
    --build) BUILD="$2"; shift 2;;
    --bench) BENCH=1; shift;;
    *) echo "unknown arg: $1" >&2; exit 1;;
  esac
done

# shellcheck source=/dev/null
source /tmp/opencode/env-x86.sh

B="$BUILD"
OUT="$HERE/build"
mkdir -p "$OUT"

if [ "$BENCH" -eq 1 ]; then
  B="$OUT/build-bench"
  cmake -S "$SRC" -B "$B" -DCMAKE_BUILD_TYPE=Release \
    -DNCNN_VULKAN=OFF -DNCNN_BUILD_TOOLS=OFF -DNCNN_BUILD_EXAMPLES=OFF \
    -DNCNN_BUILD_TESTS=OFF -DNCNN_BUILD_BENCHMARK=OFF -DNCNN_SHARED_LIB=OFF \
    -DNCNN_BENCHMARK=ON
  cmake --build "$B" -j"$(nproc)" --target ncnn
  LIB="$B/src/libncnn.a"
  BIN="$OUT/bench-bench"
else
  if [ ! -f "$BUILD/src/libncnn.a" ]; then
    cmake -S "$SRC" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release \
      -DNCNN_VULKAN=OFF -DNCNN_BUILD_TOOLS=OFF -DNCNN_BUILD_EXAMPLES=OFF \
      -DNCNN_BUILD_TESTS=OFF -DNCNN_BUILD_BENCHMARK=OFF -DNCNN_SHARED_LIB=OFF
    cmake --build "$BUILD" -j"$(nproc)" --target ncnn
  fi
  LIB="$BUILD/src/libncnn.a"
  BIN="$OUT/bench"
fi

g++ -O2 -std=c++17 -fopenmp -Wall -Wextra -Wno-unused-parameter \
  -I"$SRC/src" -I"$B/src" "$HERE/bench.cpp" "$LIB" -o "$BIN" -lpthread

echo "harness -> $BIN (ncnn $LIB)"

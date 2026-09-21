# Real-inference dump harness (research scratch)

Produces the JSONL `real_eval.py` consumes: per recognized line of the PC
conformance corpus, the pinned geometry (the shipped algorithm's PC output),
the crop box and the raw per-timestep top-K, so the placement algorithms can
be compared on real inference evidence without a device.

It is deliberately a separate crate: it links `jpdict_core` with the `native`
feature from the PC checkout and never touches that checkout's files.

## Build (once, on a machine that has the pinned ncnn static lib)

The Android repo's worktree already depends on the PC path, so the PC checkout
must be present.  ncnn itself is not committed: build it with the PC repo's
`tools/build_ncnn_pc.sh`, or, on this machine, reuse the prebuilt archive in
`/tmp/ncnn_build`:

```
mkdir -p /tmp/opencode/ncnn-install/{lib,include/ncnn}
cp /tmp/ncnn_build/build-host/src/libncnn.a /tmp/opencode/ncnn-install/lib/
cp /tmp/ncnn_build/src/src/*.h /tmp/opencode/ncnn-install/include/ncnn/
cp /tmp/ncnn_build/build-host/src/{ncnn_export,layer_shader_type_enum,layer_type_enum,platform}.h \
   /tmp/opencode/ncnn-install/include/ncnn/
```

`Cargo.toml` hardcodes the PC checkout path (this is a one-machine research
tool); adjust it if that path moves.  `build.rs` only adds the OpenMP link
flag the prebuilt ncnn needs.

```
NCNN_PC_DIR=/tmp/opencode/ncnn-install cargo build --release
```

## Run

```
./target/release/rec_dump <pc-conformance-cases-dir> /tmp/opencode/real_lines.jsonl
# e.g. /home/holopengin/repos/InstantJPDictDecky/accessibility_daemon/tests/conformance/cases
```

Then: `python real_eval.py /tmp/opencode/real_lines.jsonl`.

Output shape (one JSON object per recognized line):

```
{"case": "recognition-01-tategaki-fonts", "i": 0,
 "bbox": [x, y, w, h], "quad": null | [cx, cy, w, h, angle, conf],
 "line": {"text": "...", "vertical": false,
          "char_boxes": [[x, y, w, h], ...],       // shipped algorithm, PC
          "raw_alternatives": [[["c", score], ...], ...]}}   // per timestep
```

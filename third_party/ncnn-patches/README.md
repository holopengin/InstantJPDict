# ncnn fork patches (all merged into holopengin/ncnn — mboxes kept as record)

The fork carries three local fixes on top of upstream, all required by our
models; `tools/build_ncnn.sh` builds the fork at `FORK_PIN` and enforces
each by marker grep (plus an `activation_ss` check on the built archive
and a `verifyNcnnBlob` Gradle preBuild guard), so a lib missing any of
them fails fast instead of mis-inferring silently:

- int8 1x1 conv on flattened 1D blobs (SE squeeze branches) — marker
  `bottom_blob_3d = bottom_blob_unbordered`; without it, pack groups are
  misread as spatial width.
- `ConvolutionDepthWise::load_model` int8 scale terms `201/202` (double
  requantize fuse via split fanout) — marker `int8_scale_term % 100`;
  without it, weight scales stay empty and load crashes in quantize_to_int8.
- Fused GELU activation type 7, tanh approx (#41) — marker `activation_ss`;
  `rec_dyn.param` carries 13 `9=7` conv layers and mis-infers without it
  (dense garbage, blank collapse — diagnosed host-side via `synth/`
  ground truth). `det.param` uses plain `GELU … 0=1` (upstream fast mode)
  and needs no lib patch.

`ncnn-int8-fixes.mbox` is the `git format-patch` of the 2 int8 commits
(applies onto upstream `Tencent/ncnn @ 6a1bf000`, PR #6960);
`gelu-fused-activation.mbox` is the GELU patch. Both are superseded by
building the fork itself, which contains all three.

Rebuild the exact tree with `tools/build_ncnn.sh` (clone fork at the pin,
verify markers, build host `ncnn2table`/`ncnn2int8` + `arm64-v8a libncnn.a`
with `NCNN_VULKAN=OFF`). Verified here: script output `src/` is byte-identical
(`diff -r`) to the fork working tree.

## Rebase status (2026-11-07)

The shipped pin is `44d6b005` (`rebase/upstream-2026-11-07`). The old pin
`b498fc0` remains tagged `ijp-b498fc07` on the fork, so it stays reachable even
if fork master is rewritten again. The rebase onto `Tencent/ncnn` master
`437f18f7` is published as branch `rebase/upstream-2026-11-07` (tag
`ijp-2026-11-07`, head
`44d6b00574df0112f66df7569a7620b437a25f71`). The branch is five commits: the
Vulkan GELU shader lands without `Squeeze_vulkan` ever being added, so the old
"drop Squeeze_vulkan" commit is gone; the final tree is unchanged.

None of the six fork commits is fully upstreamed. Two needed adaptation for
upstream's new `NCNN_VALIDATION`: the depthwise 201/202 `load_param` check and
the activation-type-7 checks in 15 fused-activation layers. The `gpu.cpp`
integer-dot-product extension gate is dropped as redundant — every shader that
calls `dotPacked*EXT` declares the extension inline.

Verified: host CPU-only build, host tools, Android arm64 build with the app's
exact flags, all three shipped models load with validation on, the unmodified
app JNI links, and `rec_dyn` blob 191/`out0` are byte-identical to `b498fc0`.
`det` float output drifts ~1.4e-7 (upstream kernel drift; det uses none of the
fork fixes).

Device-validated 2026-11-07 on a Pixel 7a (arm64): the det boxes gate, both
rec prep/parity tests, and the kana model self-check all pass. The vendored
`libncnn.a` and headers are refreshed from the same tree, and both repos pin
`44d6b005`. Full analysis: `docs/ncnn-rebase-2026-11-07.md`.

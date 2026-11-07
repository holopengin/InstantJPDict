# ncnn fork rebase — 2026-11-07

Work done entirely in `/tmp/opencode/ncnn-rebase`. No other checkout and no
remote was modified. Nothing was pushed.

## TL;DR

* **Old pin:** `b498fc0786102a68af498ecf36c05b0b3b1fc074` (6 commits on
  `3b7bdba7fc8aea8fd46779533eee027df77c639d`).
* **Upstream head:** `437f18f7b5fc85902cb22d15dad312e8672856e7`
  (`Tencent/ncnn` master, 128 commits after the old base).
* **Rebased branch:** `rebase/upstream-2026-11-07`, head
  `44d6b00574df0112f66df7569a7620b437a25f71` (5 commits on upstream head;
  `Squeeze_vulkan` is never added, so the old "drop Squeeze_vulkan" commit is
  gone).
* **None of the six fixes is fully upstreamed.** Two needed adaptation because
  upstream added `NCNN_VALIDATION` (`5ea98309`, "layer param hardening
  (#7003)") **after** the old base, and it rejects both activation type 7 and
  int8 scale terms 201/202 at `load_param` time. The pin predates
  `NCNN_VALIDATION`, so the original fork patches never had to deal with it.
* One hunk was dropped as redundant (`gpu.cpp` integer-dot-product extension
  gate; every shader that needs it already declares it inline).
* **History simplified (2026-11-07):** the branch was rewritten to five
  commits: the Vulkan GELU shader lands without `Squeeze_vulkan`, and the
  later "drop Squeeze_vulkan" commit is removed. The final tree is unchanged
  (`a4d44278e538df8d23f3aa4fa2fd5c8e2fb08308`), so every build and numeric
  result below still applies.
* **Verification:** host and Android arm64 builds pass; all three shipped app
  models load; `rec_dyn` output is **byte-identical** to the old pin; the
  app's JNI layer compiles and links unchanged.
* **Shipped (2026-11-07):** the vendored `libncnn.a`/headers are refreshed,
  both repos pin `44d6b005`, and the Pixel 7a arm64 smoke test passes: the det
  boxes gate, both rec prep/parity tests, and the kana model self-check.

## Repositories and revisions

| Item | Value |
|---|---|
| Fork | `https://github.com/holopengin/ncnn.git` |
| Fork current master | `969a146f04be2609ac6b6836a516b6c62eb7ce52` (a previous rebase, 6 commits on upstream `6c52e59d`) |
| Pinned commit | `b498fc0786102a68af498ecf36c05b0b3b1fc074` — **orphaned**: it is still fetchable by SHA but is *not* an ancestor of fork master |
| Old upstream base | `3b7bdba7fc8aea8fd46779533eee027df77c639d` |
| New upstream base/head | `437f18f7b5fc85902cb22d15dad312e8672856e7` |
| Rebase branch | `rebase/upstream-2026-11-07` |
| Rebase head | `44d6b00574df0112f66df7569a7620b437a25f71` |

`git range-diff 3b7bdba7..b498fc07 6c52e59d..969a146f` showed the fork master
commits are patch-identical to the pinned ones, so the pinned chain was the
right thing to rebase.

Rebased commit chain:

```
44d6b00574df0112f66df7569a7620b437a25f71 vulkan Mali bring-up: gemm_int8 gx4/gy4 rename (InstantJPDict #42)
9c14ec4480d3780ab64cedacf1e81c6092ebd6af vulkan: fused-GELU type 7 shader (InstantJPDict #37)
8e6b0670d20098aa215fee642998d4d1db1fc15e fused GELU activation type 7 (tanh approx) for int8 epilogue fusion
9f0a2710140c7638c4e3e2810f9afcdfc7f05acb fix int8 1x1 conv on flattened 1D blobs (SE branches)
9b4eec9c9254cf1681e6ac060146c11d653442f4 fix ConvolutionDepthWise int8 load for scale terms 201/202
```

Tree `a4d44278e538df8d23f3aa4fa2fd5c8e2fb08308` is identical to the tree the
builds below were made from; the history simplification to five commits
changed commit metadata, not content.

## Per-commit analysis

| # | Original | Subject | Upstreamed? | Action | Conflicts |
|---|---|---|---|---|---|
| 1 | `b7fa957f` | fix ConvolutionDepthWise int8 load for scale terms 201/202 | **No** | kept + adapted for new `NCNN_VALIDATION` | none (auto-merge) |
| 2 | `27bfefad` | fix int8 1x1 conv on flattened 1D blobs (SE branches) | **No** | kept | none (auto-merge) |
| 3 | `1e22dfff` | fused GELU activation type 7 (tanh approx) | **No** | kept + adapted for new `NCNN_VALIDATION` | none |
| 4 | `9642f46b` | vulkan: fused-GELU type 7 shader + Squeeze layer | shader part **No**; Squeeze part matches upstream's *absence* | kept shader only; `Squeeze_vulkan` never added | none (auto-merge) |
| 5 | `45995de0` | vulkan Mali bring-up: int-dot ext gate + gemm gx4 rename | rename **No**; ext gate **redundant** | kept, `gpu.cpp` hunk dropped | none |
| 6 | `b498fc078` | vulkan: drop Squeeze_vulkan | end state matches upstream (upstream has no `Squeeze_vulkan`) | folded into 4 by history simplification; commit removed | none |

### 1. `b7fa957f` — depthwise int8 scale 201/202

**Not upstreamed.** Upstream master still has the exact-match branches:

```cpp
// src/layer/convolutiondepthwise.cpp @ 437f18f7
125:    if (int8_scale_term == 1 || int8_scale_term == 101)
134:    else if (int8_scale_term == 2 || int8_scale_term == 102)
```

`ncnn2int8` still adds `+100` on two independent passes (direct consumer and
split fan-out), so 201/202 is still producible:

```cpp
// tools/quantize/ncnn2int8.cpp @ 437f18f7
933:  convolution1->int8_scale_term += 100;   // Convolution/ConvolutionDepthWise -> ...
1057: convolution1->int8_scale_term += 100;   // ... -> Split -> Convolution
```

**Rebase adaptation required.** Upstream added this *new* validation in
`5ea98309` (not present at the pin):

```cpp
// src/layer/convolutiondepthwise.cpp @ 437f18f7, line 51
if (int8_scale_term != 0 && int8_scale_term != 1 && int8_scale_term != 2 &&
    int8_scale_term != 101 && int8_scale_term != 102)
    return -1;
```

With `NCNN_VALIDATION=ON` (the CMake default, and the app's
`tools/build_ncnn.sh` does not turn it off) a 201/202 model would fail in
`load_param` before the fork's `load_model` fix ever ran. The rebased commit
therefore also changes that check to the same residue test:

```cpp
// ncnn2int8 may fuse requantize more than once, producing 201/202
if (int8_scale_term != 0 && int8_scale_term % 100 != 1 && int8_scale_term % 100 != 2)
    return -1;
```

The reader fix itself (`int8_scale_term % 100 == 1/2`) is unchanged.

### 2. `27bfefad` — int8 1x1 conv on flattened 1D blobs

**Not upstreamed.** Upstream still has the `dims == 1` detour only in the
float/bf16 paths, never inside the int8 functions:

* `src/layer/convolution.cpp`: `forward()` starts at 229, checks at 234/245;
  `forward_int8()` starts at **426** and contains no `dims == 1` check.
* `src/layer/x86/convolution_x86.cpp`: checks at 526/548 (`forward()`), 1376
  (`forward_bf16s()`); `forward_int8_x86()` starts at **995** and has none.
* `src/layer/arm/convolution_arm.cpp`: checks at 329/340 (`forward()`);
  `forward_int8_arm()` starts at **1307** and has none.

So the SE-branch misread (packed 1D blob treated as spatial width) is still
present upstream. Kept verbatim; it applied cleanly to all three files.

### 3. `1e22dfff` — fused GELU activation type 7

**Not upstreamed.** Upstream master gained a *standalone* GELU layer
(`src/layer/gelu.cpp`, `gelu_x86/arm/...`, `gelu.comp`) but the fused
activation path still stops at 6:

```cpp
// src/layer/fused_activation.h @ 437f18f7
case 1: ... case 2: ... case 3: ... case 4: ... case 5: ... case 6: ...
// no case 7
```

Same for `src/layer/x86/x86_activation.h`, `arm/arm_activation.h`,
`mips`, `loongarch`, `riscv` — no `fast_gelu_*` or `activation_type == 7`.

**Rebase adaptation required.** `5ea98309` added, in 15 layers:

```cpp
if (activation_type < 0 || activation_type > 6)
    return -1;
```

That rejects the rec model's 13 × `9=7` layers. The rebased commit changes all
15 checks to `activation_type > 7` (conv/deconv 1d/2d/3d, depthwise variants,
deformableconv2d, innerproduct, requantize). All 15 use
`fused_activation.h`, so the scalar path is guaranteed; the vector helpers for
x86/arm/mips/loongarch/riscv are added by the same commit.

### 4. `9642f46b` — Vulkan fused GELU shader

* **GELU shader:** upstream `src/layer/vulkan/shader/vulkan_activation.comp`
  has only `activation_type == 1..6`; type 7 is not upstreamed. Kept.
* **Squeeze_vulkan:** upstream has no `squeeze_vulkan.{h,cpp}` at all, and the
  rebased history no longer adds them. The commit carries only the shader
  hunk; there is no add-then-remove pair. This is the simplified history
  requested on 2026-11-07, and the final tree is unchanged.

### 5. `45995de0` — Mali int-dot gate + gemm gx4 rename

**Rename not upstreamed.** Current upstream still has the same-scope name
collision in `src/layer/vulkan/shader/gemm_int8.comp`:

```
138:    const int gy4 = gy * 4;        // #else of NCNN_shader_local_memory
139:    const int gx4 = gx * 4;
...
189:    const ivec4 gx4 = gx * 4 + ivec4(0, 1, 2, 3);   // same main() scope
190:    const ivec4 gy4 = gy * 4 + ivec4(0, 1, 2, 3);
```

GLSL forbids redeclaration in the same scope, so the rename is still needed
when `NCNN_shader_local_memory` is off. Kept.

**`gpu.cpp` extension gate dropped as redundant.** The original commit added:

```cpp
if (info.support_VK_KHR_shader_integer_dot_product())
    custom_exts += "#extension GL_EXT_integer_dot_product : require\n";
```

Every shader that calls `dotPacked*EXT` already declares the extension inline.
Verified all ten such shaders at the old base `3b7bdba7` *and* at upstream
`437f18f7`:

```
convolution_packed_1x1s1d1_int8.comp: 1
convolution_packed_gemm_int8.comp: 1
convolution_packed_int8.comp: 1
convolutiondepthwise_group_packed_int8.comp: 1
convolutiondepthwise_int8.comp: 1
convolutiondepthwise_pack4_int8.comp: 1
gemm_int8.comp: 1
innerproduct_gemm_int8.comp: 1
innerproduct_int8.comp: 1
innerproduct_sum8_int8.comp: 1
```

(The inline directive came with `f6f734f4`, "vulkan int8 kernels (#6751)",
which predates the old base.) Requiring the extension for *every* shader would
also make unrelated shaders depend on a GLSL frontend feature upstream does
not assume. The rebased commit keeps only the `gemm_int8.comp` rename.

### 6. `b498fc078` — drop Squeeze_vulkan (removed from the history)

The original commit deleted the two files added by commit 4. Since commit 4
no longer adds them, this commit is not part of the rebased history. The final
tree is identical to the six-commit version.

## Marker check

The app's own gate was run against the rebased tree:

```
$ bash tools/build_ncnn.sh --out /tmp/opencode/ncnn-rebase/gate \
      --src /tmp/opencode/ncnn-rebase/fork --skip-host --skip-android
tree: 44d6b005 vulkan Mali bring-up: gemm_int8 gx4/gy4 rename (InstantJPDict #42) (override, no checkout)
marker ok: int8 1x1 on flattened 1D (SE)
marker ok: depthwise int8 scale 201/202
marker ok: fused GELU type 7 (rec 9=7)
OK
```

Direct greps:

```
src/layer/convolution.cpp:457:            bottom_blob_3d = bottom_blob_unbordered;
src/layer/convolution.cpp:466:            bottom_blob_3d = bottom_blob_unbordered.reshape(1, 1, bottom_blob_unbordered.w, opt.workspace_allocator);

src/layer/convolutiondepthwise.cpp:52:    if (int8_scale_term != 0 && int8_scale_term % 100 != 1 && int8_scale_term % 100 != 2)
src/layer/convolutiondepthwise.cpp:128:    if (int8_scale_term % 100 == 1)
src/layer/convolutiondepthwise.cpp:137:    else if (int8_scale_term % 100 == 2)

src/layer/fused_activation.h:10:static NCNN_FORCEINLINE float activation_ss(...)
src/layer/fused_activation.h:61:    case 7:
src/layer/fused_activation.h:64:        v = 0.5f * v * (1.f + tanhf(0.79788452f * (v + 0.044715f * v * v * v)));
```

The extra x86/arm int8 detours are present once each in
`convolution_x86.cpp` and `convolution_arm.cpp`; the GELU vector helpers are
present in all five arch headers; the Vulkan type-7 case is present in both
`activation_afp` and `activation_afpvec4`.

## Build results

All builds use CMake 3.22.1 from the Android SDK (`/home/holopengin/android-sdk/cmake/3.22.1`).

### Host, CPU-only (final tree)

```
cmake -B build-host -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DNCNN_VULKAN=OFF -DNCNN_BUILD_TOOLS=OFF -DNCNN_BUILD_EXAMPLES=OFF \
  -DNCNN_BUILD_TESTS=OFF -DNCNN_BUILD_BENCHMARK=OFF
cmake --build build-host -j6
```

**Success.** 799 targets, `src/libncnn.a` (27 MB), **0 errors**. 9 warnings,
all pre-existing and outside the touched code (pixelshuffle `-Wmaybe-uninitialized`,
deconvolution unused `elempack`, one libstdc++ `-Warray-bounds`).

### Host, app's tool build

```
cmake -B build-host-tools -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DNCNN_VULKAN=OFF -DNCNN_BUILD_TOOLS=ON -DNCNN_BUILD_EXAMPLES=OFF \
  -DNCNN_BUILD_TESTS=OFF -DNCNN_BUILD_BENCHMARK=OFF
cmake --build build-host-tools -j6 --target ncnn2table ncnn2int8 ncnnoptimize
```

**Success**, 0 errors. Same stb_image warning as upstream.

### Android arm64, exact app flags

```
rm -rf build-android-arm64
cmake -B build-android-arm64 -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE=/home/holopengin/android-sdk/ndk/28.2.13676358/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
  -DNCNN_VULKAN=OFF -DNCNN_BUILD_TOOLS=OFF -DNCNN_BUILD_EXAMPLES=OFF \
  -DNCNN_BUILD_TESTS=OFF -DNCNN_BUILD_BENCHMARK=OFF -DNCNN_SHARED_LIB=OFF
cmake --build build-android-arm64 -j6
```

**Success.** 250 targets, `src/libncnn.a` (72 MB), **0 errors**, 8 warnings
(pre-existing `mat_pixel_android`, arm crop/im2col/gemm_int8 helpers).

Artifact gate: `strings libncnn.a | grep -c activation_ss` → **75**, same
count as the currently vendored lib. The app's Gradle guard will pass.

### App JNI integration

Installed the Android build and compiled the **unmodified** app JNI sources
against the new headers/lib:

```
cmake --install build-android-arm64 --prefix /tmp/opencode/ncnn-rebase/android-install
aarch64-linux-android30-clang++ -std=c++17 -O2 -fPIC -shared -fopenmp \
  -I /tmp/opencode/ncnn-rebase/android-install/include -I . \
  ncnn_jni.cpp kana_size_ncnn.cpp kana_size_core.cpp ppocr_ncnn_core.cpp \
  /tmp/opencode/ncnn-rebase/android-install/lib/libncnn.a \
  -landroid -llog -ljnigraphics -o libncnn_jni-integration.so
```

**Success**, 0 errors. No app-side API changes are needed.

### Runtime: model loading

Small host program using the rebased `libncnn.a` with `NCNN_VALIDATION=ON`:

```
load_param(.../det.param)     = 0
load_model(.../det.bin)       = 0
load_param(.../rec_dyn.param) = 0
load_model(.../rec_dyn.bin)   = 0
load_param(.../nb_all.param)  = 0
load_model(.../nb_all.bin)    = 0
```

A synthetic param test also confirms the validation adaptation behaves:

```
conv activation 0                      ret=0  want=0  OK
conv activation 7                      ret=0  want=0  OK
conv activation 8 (must reject)        ret=-1 want=-1 OK
conv int8 101 + act 7                  ret=0  want=0  OK
convdw act 7                           ret=0  want=0  OK
convdw int8 201 + act 7                ret=0  want=0  OK
convdw int8 202 + act 7                ret=0  want=0  OK
convdw int8 201                        ret=0  want=0  OK
convdw int8 200 (must reject)          ret=-1 want=-1 OK
convdw int8 203 (must reject)          ret=-1 want=-1 OK
convdw act 8 (must reject)             ret=-1 want=-1 OK
```

### Runtime: numeric regression vs the old pin

`rec_dyn` (3×48×320 dummy input, 1 thread), comparing the old pin
`b498fc07` and the rebased tree, extracting both the named output `out0` and
the exact blob index the JNI uses (`191`):

```
blob 191:  n=534120  nonfinite=0  sum=-369390.044977  first=13.640371  last=7.665128
out0:      n=534120  nonfinite=0  sum=39.999823       first=0.810734   last=0.001392
cmp pin-191.bin new-191.bin -> BYTE-IDENTICAL
cmp pin-out0.bin new-out0.bin -> BYTE-IDENTICAL
```

`det` (float model, 3×640×640) is **not** byte-identical: mean |Δ| ≈ 1.4e-7,
max |Δ| ≈ 2.7e-6 on values ≤ 0.027, cosine 0.9999999995. This is upstream
float-kernel drift, not a fork patch regression (det uses none of the fork
fixes).

## Next step

1. **Published.** The rebase branch is on the fork as
   `rebase/upstream-2026-11-07`; the tag `ijp-2026-11-07` points at the same
   head. Fork master was not rewritten. The current pin `b498fc07` is tagged
   `ijp-b498fc07` so it stays reachable.
2. **Applied.** `tools/build_ncnn.sh` pins
   `44d6b00574df0112f66df7569a7620b437a25f71`, and
   `app/src/main/cpp/ncnn/{include,lib/arm64-v8a}` is refreshed from the
   verified `android-install/` tree. The Gradle `activation_ss` guard and the
   three marker gates pass.
3. **Device smoke test: passed (2026-11-07).** Pixel 7a (arm64), 4/4 tests:
   `KanaSizeNcnnDeviceTest`, `DetLetterboxParityTest#boxesMatchThroughTheEngine`,
   and both `RecPrepParityTest` methods. The kana self-check passes all ten
   published vectors; rec stays text/alternative-identical (`textDiff=0`,
   `altDiff=0`); det boxes pass through the engine.
4. **PC repo:** pinned to `44d6b005` and re-vendored from the same rebased
   tree, keeping the `ppocr` core files byte-identical per the shared-backend
   rule. Both repos now pin the same ncnn rev.
5. **Track the fork-only delta.** The branch still carries two fork-only
   behaviors that upstream rejects under `NCNN_VALIDATION`: fused activation
   type 7 and depthwise scale 201/202. If upstream later adds either, drop the
   corresponding commit instead of re-adapting it. The `45995de0` `gpu.cpp`
   hunk is intentionally gone; do not resurrect it without a shader that needs
   a global extension.

## Exact commands used

```bash
mkdir -p /tmp/opencode/ncnn-rebase
cd /tmp/opencode/ncnn-rebase
git clone --no-checkout https://github.com/holopengin/ncnn.git fork
git clone --no-checkout https://github.com/Tencent/ncnn.git upstream
cd fork
git fetch origin b498fc0786102a68af498ecf36c05b0b3b1fc074
git remote add tencent https://github.com/Tencent/ncnn.git
git fetch tencent master

# verify
git log --oneline b498fc0786102a68af498ecf36c05b0b3b1fc074
git merge-base b498fc0786102a68af498ecf36c05b0b3b1fc074 3b7bdba7fc8aea8fd46779533eee027df77c639d
git rev-list --oneline 3b7bdba7fc8aea8fd46779533eee027df77c639d..b498fc0786102a68af498ecf36c05b0b3b1fc074
git -C ../upstream rev-parse origin/master

# rebase
git checkout -b rebase/upstream-2026-11-07 tencent/master
git cherry-pick -x b7fa957f0ad8b73ecb3560a904545c38bba55716
git cherry-pick -x 27bfefad1ebeff6b96cbf14471ce28e880d04768
git cherry-pick -x 1e22dffff5bce428f85e79cd628793ed67de0331
git cherry-pick -x 9642f46b55503e4a1af8d3c08f9c1809fd3dd196
git cherry-pick -x 45995de0286dc50bcc7ab5aed97ae6c18f605f0d
#    -> amended to drop the redundant src/gpu.cpp hunk
git checkout HEAD~1 -- src/gpu.cpp
git commit --amend            # message records why the hunk was dropped
git cherry-pick -x b498fc0786102a68af498ecf36c05b0b3b1fc074

# NCNN_VALIDATION adaptations, folded into the two feature commits
#   1fc3ea55: int8_scale_term validation uses the % 100 residue test
#   301a58ca: activation_type > 6 -> > 7 in all 15 fused-activation layers
git commit -m "fixup! fix ConvolutionDepthWise int8 load for scale terms 201/202"
git commit -m "fixup! fused GELU activation type 7 (tanh approx) for int8 epilogue fusion"
GIT_SEQUENCE_EDITOR=true GIT_EDITOR=true git rebase -i --autosquash tencent/master

# host build
export PATH=/home/holopengin/android-sdk/cmake/3.22.1/bin:$PATH
cmake -B build-host -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DNCNN_VULKAN=OFF -DNCNN_BUILD_TOOLS=OFF -DNCNN_BUILD_EXAMPLES=OFF \
  -DNCNN_BUILD_TESTS=OFF -DNCNN_BUILD_BENCHMARK=OFF
cmake --build build-host -j6

# host tools
cmake -B build-host-tools -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DNCNN_VULKAN=OFF -DNCNN_BUILD_TOOLS=ON -DNCNN_BUILD_EXAMPLES=OFF \
  -DNCNN_BUILD_TESTS=OFF -DNCNN_BUILD_BENCHMARK=OFF
cmake --build build-host-tools -j6 --target ncnn2table ncnn2int8 ncnnoptimize

# android arm64, app flags
cmake -B build-android-arm64 -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE=/home/holopengin/android-sdk/ndk/28.2.13676358/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
  -DNCNN_VULKAN=OFF -DNCNN_BUILD_TOOLS=OFF -DNCNN_BUILD_EXAMPLES=OFF \
  -DNCNN_BUILD_TESTS=OFF -DNCNN_BUILD_BENCHMARK=OFF -DNCNN_SHARED_LIB=OFF
cmake --build build-android-arm64 -j6
cmake --install build-android-arm64 --prefix /tmp/opencode/ncnn-rebase/android-install

# app marker gate (read-only use of the app script)
bash tools/build_ncnn.sh --out /tmp/opencode/ncnn-rebase/gate \
  --src /tmp/opencode/ncnn-rebase/fork --skip-host --skip-android

# model load / synthetic validation / numeric comparison programs live in
#   /tmp/opencode/ncnn-rebase/validation-test/{loadmodel,runmodel,test}.cpp
```

The final branch was then rewritten to five commits (shader-only commit
without `Squeeze_vulkan`, old drop commit removed). `git diff
backup/rebase-6commits HEAD` is empty, and `HEAD^{tree}` is still
`a4d44278e538df8d23f3aa4fa2fd5c8e2fb08308`.

// Copyright 2026 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_SLICE_X86_AVX_H
#define LAYER_SLICE_X86_AVX_H

#include "slice.h"

namespace ncnn {

class Slice_x86_avx : public Slice
{
public:
    Slice_x86_avx();

    virtual int forward(const std::vector<Mat>& bottom_blobs, std::vector<Mat>& top_blobs, const Option& opt) const;

protected:
    int forward_bf16s_fp16s(const std::vector<Mat>& bottom_blobs, std::vector<Mat>& top_blobs, const Option& opt) const;
};

} // namespace ncnn

#endif // LAYER_SLICE_X86_AVX_H

// Copyright 2017 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_CLIP_X86_AVX_H
#define LAYER_CLIP_X86_AVX_H

#include "clip.h"

namespace ncnn {

class Clip_x86_avx : public Clip
{
public:
    Clip_x86_avx();

    virtual int forward_inplace(Mat& bottom_top_blob, const Option& opt) const;

protected:
#if NCNN_BF16
    int forward_inplace_bf16s(Mat& bottom_top_blob, const Option& opt) const;
#endif
};

} // namespace ncnn

#endif // LAYER_CLIP_X86_AVX_H

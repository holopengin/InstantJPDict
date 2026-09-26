// Copyright 2026 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_PIXELSHUFFLE_X86_AVX_H
#define LAYER_PIXELSHUFFLE_X86_AVX_H

#include "pixelshuffle.h"

namespace ncnn {

class PixelShuffle_x86_avx : public PixelShuffle
{
public:
    PixelShuffle_x86_avx();

    virtual int forward(const Mat& bottom_blob, Mat& top_blob, const Option& opt) const;
};

} // namespace ncnn

#endif // LAYER_PIXELSHUFFLE_X86_AVX_H

// Copyright 2021 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_REQUANTIZE_X86_AVX_H
#define LAYER_REQUANTIZE_X86_AVX_H

#include "requantize.h"

namespace ncnn {

class Requantize_x86_avx : public Requantize
{
public:
    Requantize_x86_avx();

    virtual int forward(const Mat& bottom_blob, Mat& top_blob, const Option& opt) const;
};

} // namespace ncnn

#endif // LAYER_REQUANTIZE_X86_AVX_H

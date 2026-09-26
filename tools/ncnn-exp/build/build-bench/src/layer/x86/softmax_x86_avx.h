// Copyright 2022 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_SOFTMAX_X86_AVX_H
#define LAYER_SOFTMAX_X86_AVX_H

#include "softmax.h"

namespace ncnn {

class Softmax_x86_avx : public Softmax
{
public:
    Softmax_x86_avx();

    virtual int forward_inplace(Mat& bottom_top_blob, const Option& opt) const;

protected:
#if NCNN_BF16
    int forward_inplace_bf16s(Mat& bottom_top_blob, const Option& opt) const;
#endif
};

} // namespace ncnn

#endif // LAYER_SOFTMAX_X86_AVX_H

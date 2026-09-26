// Copyright 2017 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_SIGMOID_X86_AVX512_H
#define LAYER_SIGMOID_X86_AVX512_H

#include "sigmoid.h"

namespace ncnn {

class Sigmoid_x86_avx512 : public Sigmoid
{
public:
    Sigmoid_x86_avx512();

    virtual int forward_inplace(Mat& bottom_top_blob, const Option& opt) const;

protected:
#if NCNN_BF16
    int forward_inplace_bf16s(Mat& bottom_top_blob, const Option& opt) const;
#endif
};

} // namespace ncnn

#endif // LAYER_SIGMOID_X86_AVX512_H

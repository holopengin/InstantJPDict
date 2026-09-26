// Copyright 2021 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_DEQUANTIZE_X86_AVX_H
#define LAYER_DEQUANTIZE_X86_AVX_H

#include "dequantize.h"

namespace ncnn {

class Dequantize_x86_avx : public Dequantize
{
public:
    Dequantize_x86_avx();

    virtual int forward(const Mat& bottom_blob, Mat& top_blob, const Option& opt) const;

protected:
#if NCNN_BF16
    int forward_bf16s(const Mat& bottom_blob, Mat& top_blob, const Option& opt) const;
#endif
};

} // namespace ncnn

#endif // LAYER_DEQUANTIZE_X86_AVX_H

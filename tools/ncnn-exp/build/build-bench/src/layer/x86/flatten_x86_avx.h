// Copyright 2026 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_FLATTEN_X86_AVX_H
#define LAYER_FLATTEN_X86_AVX_H

#include "flatten.h"

namespace ncnn {

class Flatten_x86_avx : public Flatten
{
public:
    Flatten_x86_avx();

    virtual int forward(const Mat& bottom_blob, Mat& top_blob, const Option& opt) const;

protected:
    int forward_bf16s_fp16s(const Mat& bottom_blob, Mat& top_blob, const Option& opt) const;
    int forward_int8(const Mat& bottom_blob, Mat& top_blob, const Option& opt) const;
};

} // namespace ncnn

#endif // LAYER_FLATTEN_X86_AVX_H

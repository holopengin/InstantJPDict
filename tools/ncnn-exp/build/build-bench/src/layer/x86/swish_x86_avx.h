// Copyright 2020 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_SWISH_X86_AVX_H
#define LAYER_SWISH_X86_AVX_H

#include "swish.h"

namespace ncnn {

class Swish_x86_avx : public Swish
{
public:
    Swish_x86_avx();

    virtual int forward_inplace(Mat& bottom_top_blob, const Option& opt) const;

protected:
#if NCNN_BF16
    int forward_inplace_bf16s(Mat& bottom_top_blob, const Option& opt) const;
#endif
};

} // namespace ncnn

#endif // LAYER_SWISH_X86_AVX_H

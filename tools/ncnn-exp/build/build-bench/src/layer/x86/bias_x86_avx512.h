// Copyright 2017 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_BIAS_X86_AVX512_H
#define LAYER_BIAS_X86_AVX512_H

#include "bias.h"

namespace ncnn {

class Bias_x86_avx512 : public Bias
{
public:
    virtual int forward_inplace(Mat& bottom_top_blob, const Option& opt) const;
};

} // namespace ncnn

#endif // LAYER_BIAS_X86_AVX512_H

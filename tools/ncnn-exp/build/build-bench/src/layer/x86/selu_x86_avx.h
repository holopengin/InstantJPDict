// Copyright 2023 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_SELU_X86_AVX_H
#define LAYER_SELU_X86_AVX_H

#include "selu.h"

namespace ncnn {

class SELU_x86_avx : public SELU
{
public:
    SELU_x86_avx();

    virtual int forward_inplace(Mat& bottom_top_blob, const Option& opt) const;

protected:
#if NCNN_BF16
    int forward_inplace_bf16s(Mat& bottom_top_blob, const Option& opt) const;
#endif
};

} // namespace ncnn

#endif // LAYER_SELU_X86_AVX_H

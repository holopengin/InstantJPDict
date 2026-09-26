// Copyright 2022 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_UNARYOP_X86_AVX_H
#define LAYER_UNARYOP_X86_AVX_H

#include "unaryop.h"

namespace ncnn {

class UnaryOp_x86_avx : public UnaryOp
{
public:
    UnaryOp_x86_avx();

    virtual int forward_inplace(Mat& bottom_top_blob, const Option& opt) const;

protected:
#if NCNN_BF16
    int forward_inplace_bf16s(Mat& bottom_top_blob, const Option& opt) const;
#endif
};

} // namespace ncnn

#endif // LAYER_UNARYOP_X86_AVX_H

// Copyright 2024 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_RMSNORM_X86_AVX_H
#define LAYER_RMSNORM_X86_AVX_H

#include "rmsnorm.h"

namespace ncnn {

class RMSNorm_x86_avx : public RMSNorm
{
public:
    RMSNorm_x86_avx();

    virtual int forward_inplace(Mat& bottom_top_blob, const Option& opt) const;

protected:
#if NCNN_BF16
    int forward_inplace_bf16s(Mat& bottom_top_blob, const Option& opt) const;
#endif
};

} // namespace ncnn

#endif // LAYER_RMSNORM_X86_AVX_H

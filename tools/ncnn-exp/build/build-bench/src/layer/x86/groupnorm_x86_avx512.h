// Copyright 2023 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_GROUPNORM_X86_AVX512_H
#define LAYER_GROUPNORM_X86_AVX512_H

#include "groupnorm.h"

namespace ncnn {

class GroupNorm_x86_avx512 : public GroupNorm
{
public:
    GroupNorm_x86_avx512();

    virtual int forward_inplace(Mat& bottom_top_blob, const Option& opt) const;

protected:
#if NCNN_BF16
    int forward_inplace_bf16s(Mat& bottom_top_blob, const Option& opt) const;
#endif
};

} // namespace ncnn

#endif // LAYER_GROUPNORM_X86_AVX512_H

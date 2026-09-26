// Copyright 2026 Futz12 <pchar.cn>
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_ERF_X86_AVX_H
#define LAYER_ERF_X86_AVX_H

#include "erf.h"

namespace ncnn {

class Erf_x86_avx : public Erf
{
public:
    Erf_x86_avx();

    virtual int forward_inplace(Mat& bottom_top_blob, const Option& opt) const;

protected:
#if NCNN_BF16
    int forward_inplace_bf16s(Mat& bottom_top_blob, const Option& opt) const;
#endif
};

} // namespace ncnn

#endif // LAYER_ERF_X86_AVX_H

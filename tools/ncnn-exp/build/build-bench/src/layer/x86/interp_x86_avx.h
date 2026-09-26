// Copyright 2022 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_INTERP_X86_AVX_H
#define LAYER_INTERP_X86_AVX_H

#include "interp.h"

namespace ncnn {

class Interp_x86_avx : public Interp
{
public:
    Interp_x86_avx();

    virtual int forward(const std::vector<Mat>& bottom_blobs, std::vector<Mat>& top_blobs, const Option& opt) const;

protected:
#if NCNN_BF16
    int forward_bf16s(const std::vector<Mat>& bottom_blobs, std::vector<Mat>& top_blobs, const Option& opt) const;
#endif
};

} // namespace ncnn

#endif // LAYER_INTERP_X86_AVX_H

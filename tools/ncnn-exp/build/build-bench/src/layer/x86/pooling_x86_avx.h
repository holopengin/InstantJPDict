// Copyright 2017 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_POOLING_X86_AVX_H
#define LAYER_POOLING_X86_AVX_H

#include "pooling.h"

namespace ncnn {

class Pooling_x86_avx : public Pooling
{
public:
    Pooling_x86_avx();

    virtual int create_pipeline(const Option& opt);
    virtual int forward(const Mat& bottom_blob, Mat& top_blob,
                        const Option& opt) const;

protected:
#if NCNN_BF16
    int forward_bf16s(const Mat& bottom_blob, Mat& top_blob, const Option& opt) const;
#endif
};

} // namespace ncnn

#endif // LAYER_POOLING_X86_AVX_H

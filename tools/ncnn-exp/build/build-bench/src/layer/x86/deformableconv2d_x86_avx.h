// Copyright 2022 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_DEFORMABLECONV2D_X86_AVX_H
#define LAYER_DEFORMABLECONV2D_X86_AVX_H

#include "deformableconv2d.h"

namespace ncnn {

class DeformableConv2D_x86_avx : public DeformableConv2D
{
public:
    DeformableConv2D_x86_avx();

    virtual int create_pipeline(const Option& opt);
    virtual int destroy_pipeline(const Option& opt);

    virtual int forward(const std::vector<Mat>& bottom_blobs, std::vector<Mat>& top_blobs, const Option& opt) const;

public:
    Layer* activation;

    Mat weight_data_tm;

    Layer* gemm;
};

} // namespace ncnn

#endif // LAYER_DEFORMABLECONV2D_X86_AVX_H

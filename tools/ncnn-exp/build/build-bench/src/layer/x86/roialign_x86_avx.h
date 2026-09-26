// Copyright 2020 Tencent
// SPDX-License-Identifier: BSD-3-Clause

#ifndef LAYER_ROIALIGN_X86_AVX_H
#define LAYER_ROIALIGN_X86_AVX_H

#include "roialign.h"

namespace ncnn {

class ROIAlign_x86_avx : public ROIAlign
{
public:
    ROIAlign_x86_avx();

    virtual int forward(const std::vector<Mat>& bottom_blobs, std::vector<Mat>& top_blobs, const Option& opt) const;
};

} // namespace ncnn

#endif // LAYER_ROIALIGN_X86_AVX_H
